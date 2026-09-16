package com.example.traductorandroid

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import java.io.IOException
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/*
 * Fuente de audio controlada por TraductorAndroid.
 *
 * La app captura continuamente el micrófono mediante AudioRecord
 * y escribe PCM en un pipe.
 *
 * SpeechRecognizer recibe el extremo de lectura del pipe mediante
 * RecognizerIntent.EXTRA_AUDIO_SOURCE.
 *
 * Resultado:
 *
 * dedo abajo
 *     -> AudioRecord permanece abierto
 *
 * pausas
 *     -> AudioRecord continúa abierto
 *
 * dedo arriba
 *     -> cerramos la escritura del pipe
 *     -> SpeechRecognizer recibe EOF
 *     -> termina la sesión
 */
class FuenteAudioReconocimientoAndroid(
    context: Context
) : AutoCloseable {

    /*
     * Conservamos el ApplicationContext en lugar
     * de la Activity para no mantener accidentalmente
     * una referencia a la interfaz.
     */
    private val contexto =
        context.applicationContext

    companion object {

        const val FRECUENCIA_MUESTREO =
            16_000

        const val NUMERO_CANALES =
            1

        const val CODIFICACION =
            AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord:
            AudioRecord? = null

    /*
     * Este extremo se entrega a SpeechRecognizer.
     */
    private var descriptorLectura:
            ParcelFileDescriptor? = null

    /*
     * Nuestra app escribe aquí el PCM capturado.
     */
    private var salidaPipe:
            ParcelFileDescriptor.AutoCloseOutputStream? = null

    private var hiloCaptura:
            Thread? = null

    @Volatile
    private var capturando =
        false

    private var tamanoBuffer =
        0

    /*
 * Copia PCM de toda la pulsación actual.
 *
 * Esta copia NO sustituye al pipe utilizado por
 * SpeechRecognizer.
 *
 * El mismo bloque de audio tendrá ahora dos destinos:
 *
 * 1. SpeechRecognizer, para el reconocimiento normal.
 * 2. memoria, para poder analizar posteriormente
 *    exactamente la misma frase en otros idiomas.
 */
    private val bloqueoAudioCapturado =
        Any()

    private val audioCapturado =
        ByteArrayOutputStream()

    @Synchronized
    @Throws(IOException::class)
    fun preparar():
            ParcelFileDescriptor {

        prepararAudioRecordComun()

        /*
         * El reconocimiento manual necesita un pipe:
         *
         * AudioRecord
         *     ↓
         * pipe
         *     ↓
         * SpeechRecognizer
         */
        val pipe =
            ParcelFileDescriptor.createPipe()

        descriptorLectura =
            pipe[0]

        salidaPipe =
            ParcelFileDescriptor
                .AutoCloseOutputStream(
                    pipe[1]
                )

        return pipe[0]
    }

    /*
     * Detectar utiliza el mismo AudioRecord,
     * pero NO alimenta todavía a SpeechRecognizer.
     *
     * Solo conservamos el PCM en memoria.
     */
    @Synchronized
    @Throws(IOException::class)
    fun prepararSoloCaptura() {

        prepararAudioRecordComun()
    }

    /*
     * Inicialización común a ambos modos.
     *
     * De esta forma no duplicamos:
     *
     * - comprobación de permiso;
     * - formato PCM;
     * - tamaño de buffer;
     * - creación de AudioRecord.
     */
    @Throws(IOException::class)
    private fun prepararAudioRecordComun() {

        val permisoMicrofonoConcedido =
            ContextCompat.checkSelfPermission(
                contexto,
                Manifest.permission.RECORD_AUDIO
            ) ==
                    PackageManager.PERMISSION_GRANTED

        if (!permisoMicrofonoConcedido) {

            throw IOException(
                "No está concedido el permiso de micrófono"
            )
        }

        if (
            audioRecord != null ||
            descriptorLectura != null ||
            salidaPipe != null
        ) {

            throw IOException(
                "La fuente de audio ya está preparada"
            )
        }

        synchronized(
            bloqueoAudioCapturado
        ) {

            audioCapturado.reset()
        }

        val minimo =
            AudioRecord.getMinBufferSize(
                FRECUENCIA_MUESTREO,
                AudioFormat.CHANNEL_IN_MONO,
                CODIFICACION
            )

        if (minimo <= 0) {

            throw IOException(
                "No fue posible obtener un buffer de audio válido"
            )
        }

        tamanoBuffer =
            maxOf(
                minimo,
                4096
            )

        val grabador =
            try {

                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    FRECUENCIA_MUESTREO,
                    AudioFormat.CHANNEL_IN_MONO,
                    CODIFICACION,
                    tamanoBuffer
                )

            } catch (excepcion: SecurityException) {

                throw IOException(
                    "Android rechazó el acceso al micrófono",
                    excepcion
                )
            }

        if (
            grabador.state !=
            AudioRecord.STATE_INITIALIZED
        ) {

            grabador.release()

            throw IOException(
                "AudioRecord no pudo inicializarse"
            )
        }

        audioRecord =
            grabador
    }

    /*
     * Comienza la captura real.
     *
     * El hilo lee PCM del micrófono y lo escribe
     * continuamente dentro del pipe.
     */
    @Synchronized
    @Throws(IOException::class)
    fun iniciarCaptura() {

        if (capturando) {
            return
        }

        val grabador =
            audioRecord
                ?: throw IOException(
                    "La fuente de audio no está preparada"
                )

        /*
         * Puede ser:
         *
         * no null -> reconocimiento manual;
         * null    -> Detectar, solo guardamos PCM.
         */
        val salida =
            salidaPipe

        try {

            grabador.startRecording()

        } catch (excepcion: Exception) {

            throw IOException(
                "No fue posible iniciar AudioRecord",
                excepcion
            )
        }

        if (
            grabador.recordingState !=
            AudioRecord.RECORDSTATE_RECORDING
        ) {

            throw IOException(
                "AudioRecord no comenzó a grabar"
            )
        }

        capturando =
            true

        val hilo =
            Thread {

                val buffer =
                    ByteArray(
                        tamanoBuffer
                    )

                try {

                    while (capturando) {

                        val bytesLeidos =
                            grabador.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING
                            )

                        if (bytesLeidos > 0) {

                            /*
                             * Reconocimiento manual:
                             *
                             * si existe pipe, enviamos PCM
                             * también a SpeechRecognizer.
                             */
                            salida?.write(
                                buffer,
                                0,
                                bytesLeidos
                            )

                            /*
                             * Ambos modos conservan una copia
                             * completa de la grabación.
                             */
                            synchronized(
                                bloqueoAudioCapturado
                            ) {

                                audioCapturado.write(
                                    buffer,
                                    0,
                                    bytesLeidos
                                )
                            }

                        } else if (bytesLeidos < 0) {

                            break
                        }
                    }

                } catch (_: Exception) {

                    /*
                     * El cierre deliberado de AudioRecord o
                     * del pipe puede desbloquear read()/write()
                     * mediante una excepción.
                     */

                } finally {

                    capturando =
                        false

                    try {

                        salida?.close()

                    } catch (_: Exception) {
                    }
                }

            }.apply {

                name =
                    "TraductorAndroid-AudioSpeech"

                isDaemon =
                    true
            }

        hiloCaptura =
            hilo

        hilo.start()
    }

    /*
 * Devuelve una COPIA de todo el PCM capturado
 * durante la pulsación actual.
 *
 * El llamador no recibe acceso directo al buffer
 * interno, por lo que no puede modificarlo.
 */
    fun obtenerAudioCapturado():
            ByteArray {

        return synchronized(
            bloqueoAudioCapturado
        ) {

            audioCapturado.toByteArray()
        }
    }

    /*
     * Finaliza la ENTRADA de audio.
     *
     * Cerrar el extremo de escritura provoca EOF
     * en el extremo utilizado por SpeechRecognizer.
     */
    @Synchronized
    fun detenerEntrada() {

        if (!capturando) {

            try {
                salidaPipe?.close()
            } catch (_: Exception) {
            }

            salidaPipe =
                null

            return
        }

        capturando =
            false

        try {

            audioRecord?.stop()

        } catch (_: Exception) {
        }

        try {

            salidaPipe?.close()

        } catch (_: Exception) {
        }

        salidaPipe =
            null
    }

    /*
     * Libera definitivamente todos los recursos.
     */
    override fun close() {

        detenerEntrada()

        val hilo =
            hiloCaptura

        if (
            hilo != null &&
            hilo !== Thread.currentThread()
        ) {

            try {

                hilo.join(
                    200L
                )

            } catch (_: InterruptedException) {

                Thread.currentThread()
                    .interrupt()
            }
        }

        hiloCaptura =
            null

        try {

            audioRecord?.release()

        } catch (_: Exception) {
        }

        audioRecord =
            null

        try {

            descriptorLectura?.close()

        } catch (_: Exception) {
        }

        descriptorLectura =
            null
    }
}