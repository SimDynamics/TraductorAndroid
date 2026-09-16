package com.example.traductorandroid

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.Locale

/*
 * Centraliza todo lo relacionado con las capacidades
 * del reconocimiento local proporcionado por Android.
 *
 * MainActivity no debería conocer los detalles de:
 *
 * - API 31
 * - API 33
 * - RecognitionSupport
 * - listas de modelos instalados
 * - listas descargables
 *
 * Esa responsabilidad pertenece a este gestor.
 */
object GestorReconocimientoAndroid {

    /*
     * Comprueba únicamente si el dispositivo dispone
     * de un SpeechRecognizer específicamente local.
     *
     * No comprueba todavía un idioma concreto.
     */
    fun reconocimientoLocalDisponible(
        context: Context
    ): Boolean {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.S
        ) {

            return false
        }

        return try {

            SpeechRecognizer
                .isOnDeviceRecognitionAvailable(
                    context
                )

        } catch (excepcion: Exception) {

            false
        }
    }

    /*
     * Consulta el estado de todos los idiomas que
     * TraductorAndroid conoce.
     *
     * El resultado es asíncrono porque Android 13+
     * consulta al servicio de reconocimiento.
     */
    fun consultarEstadosModelos(
        context: Context,
        idiomas: List<Idioma>,
        alCompletar:
            (Map<Idioma, EstadoModeloAndroid>) -> Unit
    ) {

        if (idiomas.isEmpty()) {

            alCompletar(
                emptyMap()
            )

            return
        }

        /*
         * Android 7–11.
         *
         * No existe createOnDeviceSpeechRecognizer().
         */
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.S
        ) {

            alCompletar(
                idiomas.associateWith {
                    EstadoModeloAndroid
                        .MOTOR_NO_DISPONIBLE
                }
            )

            return
        }

        if (
            !reconocimientoLocalDisponible(
                context
            )
        ) {

            alCompletar(
                idiomas.associateWith {
                    EstadoModeloAndroid
                        .MOTOR_NO_DISPONIBLE
                }
            )

            return
        }

        /*
         * Android 12 / 12L.
         *
         * Sabemos que el reconocedor local existe.
         *
         * Sin embargo RecognitionSupport apareció
         * hasta API 33, así que todavía no podemos
         * consultar oficialmente qué idiomas locales
         * están instalados o descargables.
         */
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {

            alCompletar(
                idiomas.associateWith {
                    EstadoModeloAndroid
                        .NO_COMPROBABLE
                }
            )

            return
        }

        consultarEstadosModelosApi33(
            context = context,
            idiomas = idiomas,
            alCompletar = alCompletar
        )
    }

    /*
     * Esta función solamente puede ejecutarse
     * desde Android 13 / API 33.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun consultarEstadosModelosApi33(
        context: Context,
        idiomas: List<Idioma>,
        alCompletar:
            (Map<Idioma, EstadoModeloAndroid>) -> Unit
    ) {

        val reconocedor =
            try {

                SpeechRecognizer
                    .createOnDeviceSpeechRecognizer(
                        context
                    )

            } catch (excepcion: Exception) {

                alCompletar(
                    idiomas.associateWith {
                        EstadoModeloAndroid
                            .ERROR_CONSULTA
                    }
                )

                return
            }

        /*
         * Preguntamos por las capacidades generales
         * del reconocedor local.
         *
         * RecognitionSupport devolverá las listas
         * de idiomas correspondientes.
         */
        val intentConsulta =
            Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
            }

        try {

            reconocedor.checkRecognitionSupport(
                intentConsulta,

                ContextCompat.getMainExecutor(
                    context
                ),

                object : RecognitionSupportCallback {

                    override fun onSupportResult(
                        recognitionSupport:
                        RecognitionSupport
                    ) {

                        val instalados =
                            normalizarEtiquetas(
                                recognitionSupport
                                    .installedOnDeviceLanguages
                            )

                        val descargables =
                            normalizarEtiquetas(
                                recognitionSupport
                                    .supportedOnDeviceLanguages
                            )

                        val pendientes =
                            normalizarEtiquetas(
                                recognitionSupport
                                    .pendingOnDeviceLanguages
                            )

                        val estados =
                            idiomas.associateWith { idioma ->

                                val etiqueta =
                                    normalizarEtiqueta(
                                        idioma
                                            .etiquetaReconocimientoSistema
                                    )

                                when {

                                    etiqueta in instalados -> {

                                        EstadoModeloAndroid
                                            .INSTALADO
                                    }

                                    etiqueta in pendientes -> {

                                        EstadoModeloAndroid
                                            .DESCARGA_PENDIENTE
                                    }

                                    etiqueta in descargables -> {

                                        EstadoModeloAndroid
                                            .DESCARGABLE
                                    }

                                    else -> {

                                        EstadoModeloAndroid
                                            .IDIOMA_NO_SOPORTADO
                                    }
                                }
                            }

                        reconocedor.destroy()

                        alCompletar(
                            estados
                        )
                    }

                    override fun onError(
                        error: Int
                    ) {

                        reconocedor.destroy()

                        alCompletar(
                            idiomas.associateWith {
                                EstadoModeloAndroid
                                    .ERROR_CONSULTA
                            }
                        )
                    }
                }
            )

        } catch (excepcion: Exception) {

            reconocedor.destroy()

            alCompletar(
                idiomas.associateWith {
                    EstadoModeloAndroid
                        .ERROR_CONSULTA
                }
            )
        }
    }

    /*
     * Android puede devolver etiquetas con diferencias
     * de mayúsculas/minúsculas.
     *
     * Las normalizamos para realizar comparaciones
     * técnicas seguras.
     */
    private fun normalizarEtiquetas(
        etiquetas: List<String>
    ): Set<String> {

        return etiquetas
            .map { etiqueta ->

                normalizarEtiqueta(
                    etiqueta
                )
            }
            .toSet()
    }

    private fun normalizarEtiqueta(
        etiqueta: String
    ): String {

        return Locale
            .forLanguageTag(
                etiqueta
            )
            .toLanguageTag()
            .lowercase(
                Locale.ROOT
            )
    }
}