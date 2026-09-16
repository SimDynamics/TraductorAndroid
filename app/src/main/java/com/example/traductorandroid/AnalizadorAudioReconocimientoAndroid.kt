package com.example.traductorandroid

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi

/*
 * Resultado de reconocer UNA grabación PCM
 * utilizando UN idioma concreto.
 *
 * Más adelante esta misma estructura nos permitirá
 * comparar:
 *
 * PCM -> Español
 * PCM -> Inglés
 * PCM -> Francés
 */
data class ResultadoReconocimientoAudio(
    val idioma: Idioma,
    val texto: String,
    val confianzaPromedio: Float?
)

/*
 * Reconoce una grabación PCM que YA existe.
 *
 * Esta clase:
 *
 * - NO abre el micrófono;
 * - NO utiliza AudioRecord;
 * - NO vuelve a grabar;
 * - reutiliza exactamente el PCM capturado anteriormente.
 */
class AnalizadorAudioReconocimientoAndroid(
    context: Context
) {

    private val contexto =
        context.applicationContext

    private var reconocedor:
            SpeechRecognizer? = null

    private var descriptorLectura:
            ParcelFileDescriptor? = null

    private var salidaPipe:
            ParcelFileDescriptor.AutoCloseOutputStream? = null

    private var hiloEscritura:
            Thread? = null

    private var finalizado =
        false

    private var idiomaActual:
            Idioma? = null

    /*
     * En una sesión segmentada Android puede entregar
     * varias frases independientes.
     */
    private val segmentos =
        mutableListOf<String>()

    /*
     * CONFIDENCE_SCORES es opcional.
     *
     * Conservamos solamente los valores que Android
     * realmente proporcione.
     */
    private val confianzas =
        mutableListOf<Float>()

    private var textoFinalNoSegmentado =
        ""

    private var callbackResultado:
            ((ResultadoReconocimientoAudio) -> Unit)? =
        null

    private var callbackError:
            ((Int) -> Unit)? =
        null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun reconocer(
        audioPcm: ByteArray,
        idioma: Idioma,
        alResultado: (ResultadoReconocimientoAudio) -> Unit,
        alError: (Int) -> Unit
    ) {

        /*
         * Cada objeto realizará una sola prueba a la vez.
         */
        cancelar()

        finalizado =
            false

        idiomaActual =
            idioma

        segmentos.clear()
        confianzas.clear()

        textoFinalNoSegmentado =
            ""

        callbackResultado =
            alResultado

        callbackError =
            alError

        if (audioPcm.isEmpty()) {

            completarConError(
                SpeechRecognizer.ERROR_AUDIO
            )

            return
        }

        try {

            /*
             * Pipe:
             *
             * [0] -> SpeechRecognizer lee
             * [1] -> nosotros escribimos el PCM almacenado
             */
            val pipe =
                ParcelFileDescriptor.createPipe()

            descriptorLectura =
                pipe[0]

            val salida =
                ParcelFileDescriptor
                    .AutoCloseOutputStream(
                        pipe[1]
                    )

            salidaPipe =
                salida

            val reconocedorNuevo =
                SpeechRecognizer
                    .createOnDeviceSpeechRecognizer(
                        contexto
                    )

            reconocedor =
                reconocedorNuevo

            reconocedorNuevo.setRecognitionListener(
                crearListener(
                    audioPcm = audioPcm,
                    salida = salida
                )
            )

            val intent =
                crearIntent(
                    descriptorAudio =
                        pipe[0],

                    idioma =
                        idioma
                )

            /*
             * Primero el reconocedor conoce el pipe.
             */
            reconocedorNuevo.startListening(
                intent
            )


        } catch (_: Exception) {

            completarConError(
                SpeechRecognizer.ERROR_CLIENT
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun crearIntent(
        descriptorAudio: ParcelFileDescriptor,
        idioma: Idioma
    ): Intent {

        return Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            /*
             * Ésta es la variable que cambiaremos
             * posteriormente entre ES / EN / FR.
             */
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                idioma.etiquetaReconocimientoSistema
            )

            /*
             * No necesitamos parciales porque el usuario
             * ya terminó de hablar.
             */
            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                false
            )

            putExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE,
                descriptorAudio
            )

            putExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                FuenteAudioReconocimientoAndroid
                    .FRECUENCIA_MUESTREO
            )

            putExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,
                FuenteAudioReconocimientoAndroid
                    .NUMERO_CANALES
            )

            putExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT
            )

            /*
             * El EOF del pipe define el final de la sesión.
             *
             * Las pausas contenidas dentro del PCM no cierran
             * nuestra fuente.
             */
            putExtra(
                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_AUDIO_SOURCE
            )
        }
    }

    private fun crearListener(
        audioPcm: ByteArray,
        salida: ParcelFileDescriptor.AutoCloseOutputStream
    ): RecognitionListener {

        return object : RecognitionListener {

            var audioIniciado =
                false

            override fun onReadyForSpeech(
                params: Bundle?
            ) {

                /*
                 * No entregamos PCM hasta que el propio
                 * SpeechRecognizer confirma que está listo.
                 *
                 * Esto evita perder el comienzo de la grabación.
                 */
                if (audioIniciado) {
                    return
                }

                audioIniciado =
                    true

                iniciarEscrituraAudio(
                    salida =
                        salida,

                    audioPcm =
                        audioPcm
                )
            }

            override fun onBeginningOfSpeech() {
            }

            override fun onRmsChanged(
                rmsdB: Float
            ) {
            }

            override fun onBufferReceived(
                buffer: ByteArray?
            ) {
            }

            override fun onEndOfSpeech() {
            }

            override fun onPartialResults(
                partialResults: Bundle?
            ) {
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?
            ) {
            }

            override fun onSegmentResults(
                segmentResults: Bundle
            ) {

                val texto =
                    extraerPrimeraHipotesis(
                        segmentResults
                    )

                if (texto.isNotBlank()) {

                    /*
                     * Cada callback representa un segmento
                     * ya reconocido.
                     *
                     * NO reemplazamos el anterior.
                     */
                    segmentos.add(
                        texto
                    )
                }

                extraerConfianza(
                    segmentResults
                )?.let { confianza ->

                    confianzas.add(
                        confianza
                    )
                }
            }

            override fun onEndOfSegmentedSession() {

                /*
                 * En modo segmentado éste es el cierre
                 * natural de la petición.
                 */
                completarConResultado()
            }

            override fun onResults(
                results: Bundle?
            ) {

                if (results == null) {
                    return
                }

                /*
                 * Conservamos este resultado como respaldo,
                 * pero NO cerramos aquí la petición.
                 *
                 * Nuestra petición utiliza:
                 *
                 * EXTRA_SEGMENTED_SESSION
                 *     =
                 * EXTRA_AUDIO_SOURCE
                 *
                 * Por tanto, la finalización correcta debe llegar
                 * mediante onEndOfSegmentedSession().
                 *
                 * Si cerráramos aquí al recibir el primer onResults(),
                 * podríamos descartar segmentos posteriores separados
                 * por pausas.
                 */
                textoFinalNoSegmentado =
                    extraerPrimeraHipotesis(
                        results
                    )

                extraerConfianza(
                    results
                )?.let { confianza ->

                    confianzas.add(
                        confianza
                    )
                }
            }

            override fun onError(
                error: Int
            ) {

                /*
                 * Si Android alcanzó a entregar segmentos,
                 * no destruimos texto válido simplemente
                 * porque el cierre terminara con NO_MATCH
                 * o SPEECH_TIMEOUT.
                 */
                if (
                    (
                            segmentos.isNotEmpty() ||
                                    textoFinalNoSegmentado.isNotBlank()
                            ) &&
                    (
                            error ==
                                    SpeechRecognizer.ERROR_NO_MATCH ||
                                    error ==
                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            )
                ) {

                    completarConResultado()

                    return
                }

                completarConError(
                    error
                )
            }
        }
    }

    private fun extraerPrimeraHipotesis(
        resultados: Bundle
    ): String {

        return resultados
            .getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
            )
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
    }

    private fun extraerConfianza(
        resultados: Bundle
    ): Float? {

        val valor =
            resultados
                .getFloatArray(
                    SpeechRecognizer.CONFIDENCE_SCORES
                )
                ?.firstOrNull()
                ?: return null

        /*
         * Android utiliza -1 cuando la confianza
         * no está disponible.
         */
        return if (valor >= 0.0f) {

            valor

        } else {

            null
        }
    }

    private fun iniciarEscrituraAudio(
        salida: ParcelFileDescriptor.AutoCloseOutputStream,
        audioPcm: ByteArray
    ) {

        val hilo =
            Thread {

                try {

                    var posicion =
                        0

                    /*
                     * PCM actual:
                     *
                     * 16 000 muestras/s
                     * 1 canal
                     * PCM16 = 2 bytes/muestra
                     *
                     * 32 000 bytes por segundo.
                     */
                    val bytesPorSegundo =
                        FuenteAudioReconocimientoAndroid
                            .FRECUENCIA_MUESTREO *
                                FuenteAudioReconocimientoAndroid
                                    .NUMERO_CANALES *
                                2

                    /*
                     * Cada bloque representa 20 ms de audio real.
                     */
                    val duracionAudioBloqueMs =
                        20L

                    val tamanoBloque =
                        (
                                bytesPorSegundo *
                                        duracionAudioBloqueMs /
                                        1000L
                                )
                            .toInt()
                            .coerceAtLeast(
                                1
                            )

                    /*
                     * Reproducimos 4 veces más rápido.
                     *
                     * 20 ms de audio
                     * se entregan cada 5 ms reales.
                     */
                    val factorAceleracion =
                        4L

                    val esperaEntreBloquesMs =
                        (
                                duracionAudioBloqueMs /
                                        factorAceleracion
                                )
                            .coerceAtLeast(
                                1L
                            )

                    while (
                        posicion <
                        audioPcm.size
                    ) {

                        val cantidad =
                            minOf(
                                tamanoBloque,
                                audioPcm.size -
                                        posicion
                            )

                        salida.write(
                            audioPcm,
                            posicion,
                            cantidad
                        )

                        posicion +=
                            cantidad

                        if (
                            posicion <
                            audioPcm.size
                        ) {

                            Thread.sleep(
                                esperaEntreBloquesMs
                            )
                        }
                    }

                } catch (_: InterruptedException) {

                    Thread.currentThread()
                        .interrupt()

                } catch (_: Exception) {

                    /*
                     * El reconocedor puede cerrar el pipe
                     * cuando finaliza la petición.
                     */

                } finally {

                    try {

                        salida.close()

                    } catch (_: Exception) {
                    }
                }

            }.apply {

                name =
                    "TraductorAndroid-ReplayPCM"

                isDaemon =
                    true
            }

        hiloEscritura =
            hilo

        hilo.start()
    }

    private fun completarConResultado() {

        if (finalizado) {
            return
        }

        val idioma =
            idiomaActual
                ?: run {

                    completarConError(
                        SpeechRecognizer.ERROR_CLIENT
                    )

                    return
                }

        val texto =
            if (segmentos.isNotEmpty()) {

                segmentos
                    .filter {
                        it.isNotBlank()
                    }
                    .joinToString(" ")
                    .trim()

            } else {

                textoFinalNoSegmentado
                    .trim()
            }

        if (texto.isBlank()) {

            completarConError(
                SpeechRecognizer.ERROR_NO_MATCH
            )

            return
        }

        finalizado =
            true

        val confianzaPromedio =
            if (confianzas.isNotEmpty()) {

                confianzas.average()
                    .toFloat()

            } else {

                null
            }

        val resultado =
            ResultadoReconocimientoAudio(
                idioma =
                    idioma,

                texto =
                    texto,

                confianzaPromedio =
                    confianzaPromedio
            )

        val callback =
            callbackResultado

        liberarRecursos(
            cancelarReconocedor =
                false
        )

        callback?.invoke(
            resultado
        )
    }

    private fun completarConError(
        error: Int
    ) {

        if (finalizado) {
            return
        }

        finalizado =
            true

        val callback =
            callbackError

        liberarRecursos(
            cancelarReconocedor =
                false
        )

        callback?.invoke(
            error
        )
    }

    /*
     * Se utiliza al abandonar la Activity o iniciar
     * expresamente otro análisis.
     */
    fun cancelar() {

        if (
            reconocedor == null &&
            descriptorLectura == null &&
            salidaPipe == null
        ) {

            return
        }

        finalizado =
            true

        liberarRecursos(
            cancelarReconocedor =
                true
        )
    }

    private fun liberarRecursos(
        cancelarReconocedor: Boolean
    ) {

        try {

            salidaPipe?.close()

        } catch (_: Exception) {
        }

        salidaPipe =
            null

        try {

            descriptorLectura?.close()

        } catch (_: Exception) {
        }

        descriptorLectura =
            null

        val reconocedorActual =
            reconocedor

        reconocedor =
            null

        if (reconocedorActual != null) {

            if (cancelarReconocedor) {

                try {

                    reconocedorActual.cancel()

                } catch (_: Exception) {
                }
            }

            try {

                reconocedorActual.destroy()

            } catch (_: Exception) {
            }
        }

        hiloEscritura =
            null

        idiomaActual =
            null

        callbackResultado =
            null

        callbackError =
            null
    }
}