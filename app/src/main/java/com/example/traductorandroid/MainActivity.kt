package com.example.traductorandroid

import androidx.appcompat.app.AppCompatActivity
import com.example.traductorandroid.databinding.ActivityMainBinding
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.android.StorageService
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.IOException
import org.json.JSONObject
import android.view.MotionEvent
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.content.ClipboardManager
import android.content.Context
import android.content.ClipData
import androidx.appcompat.app.AlertDialog
import java.io.File
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import android.text.Editable
import android.text.TextWatcher
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

private enum class TipoRedDescarga {

    WIFI,

    DATOS_MOVILES,

    OTRA,

    SIN_CONEXION
}

class MainActivity :
    AppCompatActivity(),
    RecognitionListener,
    TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding

    // El modelo no existe todavía cuando se crea MainActivity.
    // StorageService lo cargará de forma asíncrona y, cuando termine,
    // guardaremos aquí la referencia al objeto Model.
    private var modelo: Model? = null
    private var reconocedor: Recognizer? = null
    private var servicioVoz: SpeechService? = null
    private var escuchando = false
    /*
 * Reconocedor local proporcionado por Android.
 *
 * Es independiente del Recognizer de Vosk.
 * En este paso lo utilizaremos con el idioma origen
 * seleccionado manualmente.
 */
    private var reconocedorAndroid: SpeechRecognizer? = null

    /*
 * Audio controlado por nuestra propia aplicación.
 *
 * Solo se utilizará desde Android 13 / API 33.
 */
    private var fuenteAudioAndroid:
            FuenteAudioReconocimientoAndroid? = null

    /*
     * Permite distinguir:
     *
     * Android 12
     * -> SpeechRecognizer captura directamente el micrófono
     *
     * Android 13+
     * -> TraductorAndroid captura el micrófono y entrega PCM
     */
    private var usandoFuenteAudioAndroid =
        false

    /*
 * Al pulsar Detectar, la siguiente pulsación
 * del micrófono realizará una prueba real
 * de detección lingüística.
 */
    private var deteccionAutomaticaSolicitada =
        false

    /*
 * Conserva el PCM completo capturado por Detectar.
 *
 * En el siguiente paso lo enviaremos a los
 * reconocedores ES / EN / FR sin volver a grabar.
 */
    private var audioPendienteDeteccion:
            ByteArray? = null

    /*
 * Reconocedor utilizado para analizar PCM ya grabado.
 *
 * Es independiente de:
 *
 * - reconocedorAndroid, usado durante reconocimiento normal;
 * - AudioRecord, usado durante la captura.
 */
    private var analizadorAudioDeteccion:
            AnalizadorAudioReconocimientoAndroid? = null

    /*
 * Identificador lingüístico del TEXTO reconocido.
 *
 * No escucha audio.
 *
 * Su misión es comprobar que una transcripción
 * producida por un candidato realmente pertenece
 * al idioma que ese candidato representa.
 */
    private var identificadorIdiomaTexto:
            LanguageIdentifier? = null

    /*
 * Cuando Detectar cambia automáticamente el idioma origen,
 * el nuevo Translator y el TTS todavía necesitan prepararse.
 *
 * Conservamos aquí la transcripción ganadora hasta que
 * ese nuevo flujo esté listo.
 */
    private var textoPendienteDeteccionAutomatica:
            String? = null

    /*
 * Texto escrito o pegado mientras Detectar está activo.
 *
 * Si la detección cambia la dirección, conservamos aquí
 * el texto mientras se preparan el nuevo Translator y TTS.
 *
 * A diferencia de la entrada de voz, este texto NO debe
 * reproducirse automáticamente.
 */
    private var textoPendienteDeteccionEscrita:
            String? = null

    /*
     * Nos permite saber qué motor posee la escucha actual.
     *
     * false -> Vosk
     * true  -> Android SpeechRecognizer
     */
    private var escuchaConAndroid = false

    /*
 * true mientras el dedo permanece físicamente
 * presionando el botón del micrófono.
 *
 * Es diferente de "escuchando":
 *
 * microfonoPresionado -> estado del gesto del usuario
 * escuchando          -> estado de una sesión del reconocedor
 *
 * Android puede terminar una sesión por una pausa
 * aunque el usuario todavía mantenga el botón pulsado.
 */
    private var microfonoPresionado = false

    /*
 * Se vuelve true únicamente cuando Android entrega
 * realmente onSegmentResults().
 *
 * API 33+ permite solicitar la segmentación, pero un
 * proveedor puede ignorarla. Por eso no basta con
 * saber que la API existe.
 */
    private var segmentacionAndroidConfirmada = false

    /*
     * Evita ejecutar dos veces la traducción final si un
     * proveedor entrega más de un callback de terminación.
     */
    private var finalizacionAndroidProcesada = false

    /*
     * Utilizamos el hilo principal para iniciar una nueva sesión
     * después de que Android haya cerrado la anterior.
     */
    private val manejadorReconocimientoAndroid =
        Handler(
            Looper.getMainLooper()
        )

    /*
     * Android SpeechRecognizer utiliza su propio RecognitionListener.
     *
     * Escribimos el nombre completo de la interfaz porque
     * MainActivity ya implementa org.vosk.android.RecognitionListener.
     */
    private val listenerReconocimientoAndroid =
        object : android.speech.RecognitionListener {

            override fun onReadyForSpeech(
                params: Bundle?
            ) {

                binding.textoEstado.text =
                    textoApp(
                        R.string.estado_escuchando_android
                    )
            }

            override fun onBeginningOfSpeech() {

                // Android detectó el comienzo de la voz.
            }

            override fun onRmsChanged(
                rmsdB: Float
            ) {

                // Por ahora no mostramos un medidor de volumen.
            }

            override fun onBufferReceived(
                buffer: ByteArray?
            ) {

                // No procesamos manualmente estos datos.
            }

            override fun onEndOfSpeech() {

                /*
                 * No interpretamos este callback como el final
                 * de la pulsación.
                 *
                 * El usuario decide cuándo terminar soltando
                 * el botón del micrófono.
                 */
            }

            override fun onError(
                error: Int
            ) {

                escuchando = false

                val usabaFuenteControlada =
                    usandoFuenteAudioAndroid

                if (usabaFuenteControlada) {

                    liberarFuenteAudioAndroid()
                }

                if (finalizacionAndroidProcesada) {
                    return
                }

                /*
 * Con EXTRA_AUDIO_SOURCE no esperamos que una
 * pausa normal termine la sesión.
 *
 * Si el proveedor produce un error mientras nuestra
 * fuente sigue activa, no abrimos otra sesión Android
 * automáticamente.
 */
                if (
                    usabaFuenteControlada &&
                    microfonoPresionado
                ) {

                    androidFalloDuranteEjecucion =
                        true

                    escuchaConAndroid =
                        false

                    microfonoPresionado =
                        false

                    finalizacionAndroidProcesada =
                        true

                    binding.buttonEscuchar.isEnabled =
                        true

                    binding.buttonIntercambiarIdiomas.isEnabled =
                        true

                    binding.textoEstado.text =
                        textoApp(
                            R.string.reconocimiento_error,
                            error.toString()
                        )

                    return
                }

                /*
                 * Si la sesión terminó por una pausa o ausencia
                 * temporal de coincidencia y el usuario continúa
                 * manteniendo presionado el botón, conservamos
                 * el mecanismo antiguo como fallback.
                 *
                 * Esto cubre Android 12 y proveedores que ignoren
                 * la sesión segmentada.
                 */
                if (
                    microfonoPresionado &&
                    (
                            error == SpeechRecognizer.ERROR_NO_MATCH ||
                                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            )
                ) {

                    segmentacionAndroidConfirmada = false

                    programarNuevaEscuchaAndroid()

                    return
                }

                /*
                 * Si el usuario ya soltó pero tenemos texto
                 * reconocido de segmentos anteriores, no lo perdemos.
                 */
                if (
                    !microfonoPresionado &&
                    textoConfirmado.isNotBlank() &&
                    (
                            error == SpeechRecognizer.ERROR_NO_MATCH ||
                                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            )
                ) {

                    finalizarReconocimientoAndroidAcumulado()

                    return
                }

                escuchaConAndroid = false
                microfonoPresionado = false
                finalizacionAndroidProcesada = true

                binding.buttonEscuchar.isEnabled =
                    true

                binding.buttonIntercambiarIdiomas.isEnabled =
                    true

                if (
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {

                    binding.textoEstado.text =
                        textoApp(
                            R.string.estado_no_voz
                        )

                } else {

                    binding.textoEstado.text =
                        textoApp(
                            R.string.reconocimiento_error,
                            error.toString()
                        )
                }
            }

            override fun onResults(
                results: Bundle?
            ) {

                /*
                 * Si onSegmentResults() ya apareció,
                 * el proveedor está utilizando realmente
                 * la sesión segmentada.
                 */
                if (segmentacionAndroidConfirmada) {

                    if (!microfonoPresionado) {

                        finalizarReconocimientoAndroidAcumulado()
                    }

                    return
                }

                /*
                 * Compatibilidad con:
                 *
                 * - Android 12;
                 * - proveedores API 33+ que ignoren
                 *   EXTRA_SEGMENTED_SESSION.
                 */
                val textoNuevo =
                    results
                        ?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                if (textoNuevo.isNotBlank()) {

                    textoConfirmado =
                        listOf(
                            textoConfirmado,
                            textoNuevo
                        )
                            .filter {
                                it.isNotBlank()
                            }
                            .joinToString(" ")

                    mostrarTextoEntrada(
                        textoConfirmado
                    )
                }

                escuchando = false

                if (microfonoPresionado) {

                    programarNuevaEscuchaAndroid()

                } else {

                    finalizarReconocimientoAndroidAcumulado()
                }
            }

            override fun onPartialResults(
                partialResults: Bundle?
            ) {

                val textoParcial =
                    partialResults
                        ?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                if (textoParcial.isNotBlank()) {

                    val textoMostrado =
                        listOf(
                            textoConfirmado,
                            textoParcial
                        )
                            .filter {
                                it.isNotBlank()
                            }
                            .joinToString(" ")

                    mostrarTextoEntrada(
                        textoMostrado
                    )
                }
            }

            override fun onSegmentResults(
                segmentResults: Bundle
            ) {

                /*
                 * Este callback confirma que el proveedor
                 * realmente aceptó la sesión segmentada.
                 */
                segmentacionAndroidConfirmada = true

                if (finalizacionAndroidProcesada) {
                    return
                }

                val textoSegmento =
                    segmentResults
                        .getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                if (textoSegmento.isBlank()) {
                    return
                }

                textoConfirmado =
                    listOf(
                        textoConfirmado,
                        textoSegmento
                    )
                        .filter {
                            it.isNotBlank()
                        }
                        .joinToString(" ")

                mostrarTextoEntrada(
                    textoConfirmado
                )
            }

            override fun onEndOfSegmentedSession() {

                escuchando = false

                if (!microfonoPresionado) {

                    finalizarReconocimientoAndroidAcumulado()

                    return
                }

                /*
                 * Si el proveedor terminó inesperadamente
                 * mientras el dedo continúa presionado,
                 * conservamos el fallback antiguo.
                 */
                segmentacionAndroidConfirmada = false

                programarNuevaEscuchaAndroid()
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?
            ) {

                // Reservado para eventos específicos del proveedor.
            }
        }
    // Conserva las frases que Vosk ya consideró terminadas.
    // Los resultados parciales se muestran junto a este texto,
    // pero no lo reemplazan.
    private var textoConfirmado = ""

    /*
 * Cuando pulsamos ⇄, la traducción anterior pasa al origen.
 *
 * No podemos traducirla inmediatamente porque el nuevo
 * Translator todavía se está preparando.
 *
 * Guardamos temporalmente el texto y lo procesaremos
 * cuando el nuevo flujo esté completamente listo.
 */
    private var textoPendienteTrasIntercambio: String? = null

    /*
     * Conserva la entrada escrita o pegada mientras
     * la dirección o el Translator se están preparando.
     *
     * El texto del usuario nunca debe desaparecer
     * simplemente por cambiar un idioma.
     */
    private var textoPendienteEntradaManual:
            String? = null

    // Conserva únicamente una traducción real.
// Nunca contiene el placeholder "Traducción...".
    private var ultimaTraduccion = ""
    // Indica si el usuario está modificando manualmente
// el contenido del cuadro de origen.
    private var editandoTextoOrigen = false

    /*
 * Evita que setText() realizado por nuestra propia app
 * sea confundido con escritura manual del usuario.
 *
 * Voz / Detectar / intercambio / placeholders
 * también modifican textoEntrada mediante código.
 */
    private var actualizandoTextoOrigenProgramaticamente =
        false

    /*
     * Handler separado del reconocimiento de voz.
     *
     * Su única responsabilidad es esperar un pequeño
     * intervalo después de que el usuario deje de escribir.
     */
    private val manejadorEdicionTexto =
        Handler(
            Looper.getMainLooper()
        )

    private var tareaTraduccionEdicion:
            Runnable? = null

    /*
     * 1.5 segundos sin cambios.
     *
     * Cada nueva tecla reinicia este contador.
     */
    private val retrasoTraduccionEdicionMs =
        1500L

    /*
     * Evita traducir nuevamente exactamente el mismo
     * contenido si no ha cambiado.
     */
    private var ultimoTextoEditadoTraducido =
        ""

    /*
     * Cada nueva entrada invalida traducciones asíncronas
     * anteriores que todavía pudieran terminar después.
     */
    private var versionSolicitudTraduccion =
        0L

    // Solo permitimos una descarga de modelo simultánea por ahora.
    private var idDescargaModelo = -1L

    // Modelo asociado a la descarga activa.
    private var modeloEnDescarga: ModeloVosk? = null

    // Evita registrar dos veces el mismo BroadcastReceiver.
    private var receptorDescargaRegistrado = false

    // Impide iniciar dos instalaciones simultáneas del mismo ZIP.
    @Volatile
    private var instalacionModeloEnCurso = false

    /*
     * Conserva una descarga incluso si MainActivity se destruye.
     *
     * DownloadManager continúa trabajando fuera de nuestra Activity,
     * por lo que necesitamos recordar qué descarga nos pertenece.
     */
    private val preferenciasDescargas by lazy {

        getSharedPreferences(
            "descargas_modelos",
            Context.MODE_PRIVATE
        )
    }

    /*
 * Conserva únicamente el estado de navegación entre idiomas.
 *
 * No guarda conversaciones ni textos traducidos.
 */
    private val preferenciasIdiomas by lazy {

        getSharedPreferences(
            "estado_idiomas",
            Context.MODE_PRIVATE
        )
    }

    /*
     * Preferencias generales de red.
     *
     * El valor predeterminado es false:
     * los modelos NO pueden descargarse mediante datos móviles.
     *
     * En Opciones conectaremos posteriormente un interruptor
     * directamente con esta misma preferencia.
     */

    private val receptorDescargaModelo =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (
                    intent?.action !=
                    DownloadManager.ACTION_DOWNLOAD_COMPLETE
                ) {
                    return
                }

                val idDescarga =
                    intent.getLongExtra(
                        DownloadManager.EXTRA_DOWNLOAD_ID,
                        -1L
                    )

                /*
                 * DownloadManager puede informar sobre descargas
                 * pertenecientes a otras aplicaciones.
                 *
                 * Solo reaccionamos al ID que nosotros guardamos.
                 */
                if (idDescarga != idDescargaModelo) {
                    return
                }

                procesarDescargaFinalizada(idDescarga)
            }
        }

    // Traductor correspondiente a la dirección actualmente seleccionada.
//
// Ejemplos:
// Español → Inglés
// Inglés → Español
    private var traductorActual: Translator? = null

    private var modeloTraduccionListo = false

    // Motor de texto a voz de Android.
    private var textoAVoz: TextToSpeech? = null

    // Indica si el motor TTS está inicializado y configurado
// para hablar en el idioma de destino actual.
    private var ttsListo = false

    // true mientras TextToSpeech está pronunciando una frase.
    private var ttsReproduciendo = false

    // Dirección actual de la conversación.
//
// Estas variables cambiarán cuando el usuario pulse ⇄.
    private var idiomaOrigen = CatalogoIdiomas.ESPANOL
    private var idiomaDestino = CatalogoIdiomas.INGLES

    /*
 * Detectar ya no será solamente una acción de una frase.
 *
 * true:
 *     Detectar aparece visualmente seleccionado y cada
 *     nueva pulsación del micrófono vuelve a detectar.
 *
 * false:
 *     se utiliza manualmente idiomaOrigen.
 */
    private var modoDeteccionAutomaticaActivo =
        false

    /*
     * Historial de esta ejecución.
     *
     * En el siguiente subpaso lo persistiremos para que
     * sobreviva al cierre de la aplicación.
     *
     * El idioma situado en índice 0 es el más reciente.
     */
    private val historialIdiomasOrigen =
        mutableListOf<Idioma>()

    private val historialIdiomasDestino =
        mutableListOf<Idioma>()

    private val maximoHistorialOrigen =
        4

    private val maximoHistorialDestino =
        6

    /*
 * Estado actual de los modelos Android para cada idioma.
 *
 * El gestor lo actualizará asincrónicamente.
 *
 * Más adelante:
 *
 * - ResolverMotorReconocimiento
 * - Más
 * - Opciones
 *
 * utilizarán esta misma información.
 */
    private var estadosModelosAndroid:
            Map<Idioma, EstadoModeloAndroid> =

        CatalogoIdiomas.TODOS
            .associateWith {

                EstadoModeloAndroid
                    .SIN_CONSULTAR
            }
    /*
 * En Android 13+ la consulta de modelos mediante
 * RecognitionSupport es asíncrona.
 *
 * Mientras todavía no termine, no queremos cargar Vosk
 * precipitadamente solo porque Android aparezca
 * temporalmente como SIN_CONSULTAR.
 */
    private var consultaModelosAndroidCompletada = false

    /*
     * El prototipo utiliza selección automática del motor.
     *
     * El resolver decidirá entre Android y Vosk según
     * las capacidades del dispositivo y la disponibilidad
     * del idioma correspondiente.
     */
    private var preferenciaMotorReconocimiento =
        PreferenciaMotorReconocimiento.AUTOMATICO

    /*
 * Si Android falla realmente durante esta ejecución,
 * dejamos de insistir con él y utilizamos Vosk como
 * fallback hasta que se reconstruya/reinicie la Activity.
 *
 * Más adelante podremos sofisticar esta política.
 */
    private var androidFalloDuranteEjecucion =
        false

    // Registra una solicitud de permiso de Android.
    //
    // Cuando el usuario responda al cuadro de permiso del micrófono,
    // Android ejecutará automáticamente este bloque y nos entregará
    // true si fue autorizado o false si fue rechazado.
    private val solicitarPermisoMicrofono =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { fueConcedido ->

            if (fueConcedido) {
                prepararFlujoActual()
                // iniciaremos aquí las funciones relacionadas
                // con reconocimiento de voz.
            } else {
                binding.textoEstado.text =
                    textoApp(
                        R.string.permiso_microfono_necesario
                    )
                // mostraremos una explicación al usuario
                // indicando que sin micrófono no puede utilizar reconocimiento de voz.
            }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        /*
 * Recuperamos primero la última dirección e historial.
 *
 * Si es la primera ejecución, la función conserva:
 *
 * Español → Inglés
 */
        restaurarEstadoIdiomas()

        actualizarSelectorIdiomas()

        actualizarEstadosModelosAndroid()

        binding.textoIdiomaOrigen.setOnClickListener {

            mostrarSelectorIdioma(
                seleccionarOrigen = true
            )
        }

        binding.textoIdiomaDestino.setOnClickListener {

            mostrarSelectorIdioma(
                seleccionarOrigen = false
            )
        }

        mostrarPlaceholderTextoEntrada()
        mostrarPlaceholderTextoTraduccion()

        binding.buttonIntercambiarIdiomas.setOnClickListener {

            intercambiarIdiomas()
        }

        binding.buttonPegar.setOnClickListener {

            pegarTextoDesdePortapapeles()
        }

        binding.buttonLimpiarOrigen.setOnClickListener {

            limpiarTextoOrigen()
        }

        binding.buttonTerminarEdicion.setOnClickListener {

            /*
             * Primero procesamos inmediatamente cualquier texto
             * que todavía estuviera esperando el debounce.
             *
             * Debe ocurrir ANTES de cerrar la edición porque
             * traducirTextoEditadoActual() comprueba
             * editandoTextoOrigen.
             */
            traducirEdicionPendienteInmediatamente()

            cerrarTecladoEdicion()
        }

        /*
 * Estos controles desaparecerán definitivamente
 * del XML en el cambio visual posterior.
 *
 * Desde ahora la edición comienza tocando
 * directamente el cuadro de origen.
 */

        binding.textoEntrada.setOnClickListener {

            iniciarEdicionDirectaTextoOrigen()
        }

        binding.textoEntrada.setOnFocusChangeListener { _, tieneFoco ->

            /*
             * El foco real del EditText es la fuente definitiva
             * para saber si estamos escribiendo.
             *
             * Así no dependemos únicamente de que Android haya
             * ejecutado setOnClickListener en un orden concreto.
             */
            if (tieneFoco) {

                editandoTextoOrigen =
                    true

                activarModoVisualEdicionDirecta()

                binding.textoEntrada.isCursorVisible =
                    true
            }
        }

        binding.textoEntrada.setOnEditorActionListener { _, actionId, _ ->

            if (
                actionId ==
                android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            ) {

                /*
                 * "Done" ya no significa que haya que pulsar
                 * un botón Terminar.
                 *
                 * Traducimos inmediatamente el contenido actual
                 * y simplemente cerramos el teclado.
                 */
                traducirEdicionPendienteInmediatamente()

                cerrarTecladoEdicion()

                true

            } else {

                false
            }
        }

        binding.textoEntrada.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    texto: CharSequence?,
                    inicio: Int,
                    cantidad: Int,
                    despues: Int
                ) {
                }

                override fun onTextChanged(
                    texto: CharSequence?,
                    inicio: Int,
                    antes: Int,
                    cantidad: Int
                ) {

                    /*
                     * setText() también dispara TextWatcher.
                     *
                     * Solo queremos reaccionar a escritura real
                     * del usuario.
                     */
                    if (
                        actualizandoTextoOrigenProgramaticamente ||
                        !editandoTextoOrigen
                    ) {

                        return
                    }

                    /*
                     * Cualquier modificación invalida una
                     * traducción anterior todavía en vuelo.
                     */
                    versionSolicitudTraduccion++

                    programarTraduccionTextoEditado()
                }

                override fun afterTextChanged(
                    texto: Editable?
                ) {
                }
            }
        )

        binding.buttonCopiar.setOnClickListener {

            copiarTraduccionAlPortapapeles()
        }

        binding.buttonEscucharTraduccion.setOnClickListener {

            if (ttsReproduciendo) {

                cancelarReproduccionTts()

            } else {

                reproducirTraduccionActual()
            }
        }

        binding.buttonMas.setOnClickListener {

            mostrarGestorModelos()
        }

        binding.buttonOpciones.setOnClickListener {

            binding.textoEstado.text =
                textoApp(
                    R.string.opciones_proximo_paso
                )
        }

        binding.buttonDetectar.setOnClickListener {

            activarDeteccionAutomatica()
        }

        binding.buttonEscuchar.setOnTouchListener { vista, evento ->

            when (evento.actionMasked) {

                MotionEvent.ACTION_DOWN -> {

                    /*
 * La voz sustituirá el contenido escrito.
 *
 * Cancelamos cualquier traducción pendiente de edición
 * y retiramos teclado/cursor antes de abrir el micrófono.
 */
                    tareaTraduccionEdicion
                        ?.let { tarea ->

                            manejadorEdicionTexto
                                .removeCallbacks(
                                    tarea
                                )
                        }

                    tareaTraduccionEdicion =
                        null

                    if (editandoTextoOrigen) {

                        cerrarTecladoEdicion()
                    }

                    /*
 * Si Detectar continúa visualmente seleccionado,
 * cada nueva pulsación vuelve a utilizar detección.
 */
                    if (modoDeteccionAutomaticaActivo) {

                        deteccionAutomaticaSolicitada =
                            true
                    }

                    /*
                     * Registramos primero el estado físico del botón.
                     *
                     * Android podrá terminar internamente una frase,
                     * pero mientras esto siga siendo true iniciaremos
                     * otra sesión automáticamente.
                     */
                    microfonoPresionado = true

                    iniciarEscucha()

                    true
                }

                MotionEvent.ACTION_UP -> {

                    /*
                     * Desde este momento ya no deben iniciarse
                     * nuevas sesiones de reconocimiento.
                     */
                    microfonoPresionado = false

                    if (escuchando) {
                        detenerEscucha()
                    }

                    vista.performClick()

                    true
                }

                MotionEvent.ACTION_CANCEL -> {

                    microfonoPresionado = false

                    if (escuchando) {
                        detenerEscucha()
                    }

                    true
                }

                else -> false
            }
        }

        registrarReceptorDescargaModelo()
        recuperarDescargaPendiente()

        // Comprueba el permiso y, cuando corresponde,
        // continúa con la carga del modelo de Vosk.
        comprobarPermisoMicrofono()
    }

    override fun onInit(estado: Int) {

        if (estado != TextToSpeech.SUCCESS) {

            ttsListo = false

            binding.textoEstado.text =
                textoApp(
                    R.string.tts_inicio_error
                )

            binding.buttonEscuchar.isEnabled = false
            binding.buttonIntercambiarIdiomas.isEnabled = true

            return
        }

        /*
         * TextToSpeech terminó de inicializarse.
         *
         * Ahora seleccionamos dinámicamente la voz correspondiente
         * al idioma de destino.
         */
        configurarIdiomaTtsDestino()
    }

    private fun configurarEventosTts() {

        textoAVoz?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {

                override fun onStart(
                    utteranceId: String?
                ) {

                    ttsReproduciendo = true

                    runOnUiThread {

                        actualizarBotonAudioTraduccion()

                        binding.textoEstado.text =
                            textoApp(
                                R.string.estado_reproduciendo
                            )
                    }
                }

                override fun onDone(
                    utteranceId: String?
                ) {

                    ttsReproduciendo = false

                    runOnUiThread {

                        actualizarBotonAudioTraduccion()

                        binding.textoEstado.text =
                            textoApp(
                                R.string.estado_listo
                            )

                        binding.buttonEscuchar.isEnabled = true
                        binding.buttonIntercambiarIdiomas.isEnabled = true
                        binding.buttonDetectar.isEnabled =
                            true
                    }
                }

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean
                ) {

                    ttsReproduciendo = false

                    runOnUiThread {

                        actualizarBotonAudioTraduccion()

                        binding.textoEstado.text =
                            textoApp(
                                R.string.estado_listo
                            )

                        binding.buttonEscuchar.isEnabled = true
                        binding.buttonIntercambiarIdiomas.isEnabled = true
                        binding.buttonDetectar.isEnabled =
                            true
                    }
                }

                override fun onError(
                    utteranceId: String?
                ) {

                    ttsReproduciendo = false

                    runOnUiThread {

                        actualizarBotonAudioTraduccion()

                        binding.textoEstado.text =
                            textoApp(
                                R.string.error_reproducir_traduccion
                            )

                        binding.buttonEscuchar.isEnabled = true
                        binding.buttonIntercambiarIdiomas.isEnabled = true
                        binding.buttonDetectar.isEnabled =
                            true
                    }
                }
            }
        )
    }

    private fun pegarTextoDesdePortapapeles() {

        /*
         * Solo aceptamos una nueva entrada cuando la aplicación
         * está en reposo.
         *
         * Durante reconocimiento, traducción, cambio de idioma
         * o reproducción TTS alguno de estos controles permanece
         * bloqueado.
         */
        if (
            !binding.buttonEscuchar.isEnabled ||
            !binding.buttonIntercambiarIdiomas.isEnabled
        ) {

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_esperar
                )

            return
        }

        val portapapeles =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val contenido = portapapeles.primaryClip

        if (contenido == null || contenido.itemCount == 0) {

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_portapapeles_vacio
                )

            return
        }

        /*
         * coerceToText() permite obtener una representación textual
         * del primer elemento del portapapeles.
         */
        val textoPegado =
            contenido
                .getItemAt(0)
                .coerceToText(this)
                .toString()
                .trim()

        if (textoPegado.isBlank()) {

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_portapapeles_sin_texto
                )

            return
        }

        textoConfirmado = textoPegado

        mostrarTextoEntrada(textoPegado)
        mostrarPlaceholderTextoTraduccion()

        procesarEntradaTexto(
            textoPegado
        )
    }

    private fun copiarTraduccionAlPortapapeles() {

        val textoCopiar =
            ultimaTraduccion.trim()

        if (textoCopiar.isBlank()) {

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_sin_traduccion_copiar
                )

            return
        }

        val portapapeles =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        /*
         * Creamos un elemento textual que Android puede colocar
         * en el portapapeles del sistema.
         */
        val contenido =
            ClipData.newPlainText(
                textoApp(
                    R.string.etiqueta_portapapeles_traduccion
                ),
                textoCopiar
            )

        portapapeles.setPrimaryClip(contenido)

        binding.textoEstado.text =
            textoApp(
                R.string.estado_traduccion_copiada
            )
    }

    private fun modeloExisteEnAssets(
        modeloVosk: ModeloVosk
    ): Boolean {

        return try {

            /*
             * AssetManager.list() devuelve los elementos contenidos
             * dentro de una carpeta de assets.
             *
             * No necesitamos abrir el modelo completo.
             * Solo comprobamos que la carpeta exista y contenga algo.
             */
            val contenido =
                assets.list(modeloVosk.carpetaAssets)

            !contenido.isNullOrEmpty()

        } catch (excepcion: Exception) {

            false
        }
    }

    private fun modeloEstaInstalado(
        modeloVosk: ModeloVosk
    ): Boolean {

        /*
         * Los modelos descargados se guardarán posteriormente en:
         *
         * filesDir/modelos_vosk/<carpeta-del-modelo>
         */
        val carpetaModelo =
            File(
                filesDir,
                "modelos_vosk/${modeloVosk.carpetaInstalada}"
            )

        /*
         * No basta con comprobar que existe la carpeta.
         *
         * Una descarga interrumpida también podría dejar
         * una carpeta incompleta.
         *
         * Verificamos dos archivos fundamentales de la
         * estructura estándar de Vosk.
         */
        val modeloAcustico =
            File(
                carpetaModelo,
                "am/final.mdl"
            )

        val configuracion =
            File(
                carpetaModelo,
                "conf/model.conf"
            )

        return (
                carpetaModelo.isDirectory &&
                        modeloAcustico.isFile &&
                        configuracion.isFile
                )
    }

    private fun obtenerEstadoModeloVosk(
        modeloVosk: ModeloVosk
    ): EstadoModeloVosk {

        if (modeloExisteEnAssets(modeloVosk)) {

            return EstadoModeloVosk.INTEGRADO
        }

        if (modeloEstaInstalado(modeloVosk)) {

            return EstadoModeloVosk.INSTALADO
        }

        /*
         * Si DownloadManager está trabajando con este modelo,
         * todavía no está instalado pero tampoco debemos ofrecer
         * iniciar una segunda descarga.
         */
        if (
            idDescargaModelo != -1L &&
            modeloEnDescarga?.id == modeloVosk.id
        ) {

            return EstadoModeloVosk.DESCARGANDO
        }

        if (modeloVosk.urlDescarga != null) {

            return EstadoModeloVosk.DESCARGABLE
        }

        return EstadoModeloVosk.NO_DISPONIBLE
    }


    private fun dp(valor: Int): Int {

        return (
                valor * resources.displayMetrics.density
                ).toInt()
    }

    private fun mostrarGestorModelos() {

        /*
         * Contenedor vertical de todas las entradas.
         *
         * Reservamos espacio a la derecha para que una futura
         * scrollbar nunca quede encima de los textos.
         */
        val contenedor =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(22),
                    dp(4),
                    dp(14),
                    dp(10)
                )
            }

        /*
         * ScrollView:
         *
         * el gestor podrá crecer a muchos idiomas sin hacer
         * crecer indefinidamente el diálogo.
         */
        val desplazamiento =
            ScrollView(this).apply {

                isVerticalScrollBarEnabled = true

                // La barra permanece disponible visualmente
                // en lugar de desaparecer inmediatamente.
                isScrollbarFadingEnabled = false

                scrollBarStyle =
                    View.SCROLLBARS_INSIDE_INSET

                // Pequeña zona reservada para la scrollbar.
                setPadding(
                    0,
                    0,
                    dp(5),
                    0
                )

                addView(
                    contenedor
                )
            }

        /*
         * Los listeners de "Descargar" necesitan poder cerrar
         * el diálogo antes de abrir la confirmación de descarga.
         */
        lateinit var dialogo: AlertDialog

        CatalogoModelosVosk.TODOS.forEach { modeloVosk ->

            val estado =
                obtenerEstadoModeloVosk(
                    modeloVosk
                )

            /*
             * Cada modelo obtiene su propia pequeña sección.
             */
            val fila =
                LinearLayout(this).apply {

                    orientation =
                        LinearLayout.VERTICAL

                    setPadding(
                        0,
                        dp(10),
                        dp(8),
                        dp(12)
                    )
                }

            val nombreModelo =
                TextView(this).apply {

                    text =
                        "${nombreModeloVosk(modeloVosk)} · " +
                                modeloVosk.tamanoVisible

                    textSize = 18f

                    setTypeface(
                        typeface,
                        android.graphics.Typeface.BOLD
                    )

                    /*
                     * Aumentamos ligeramente la separación
                     * vertical del texto.
                     */
                    setLineSpacing(
                        dp(2).toFloat(),
                        1.10f
                    )
                }

            val textoEstado =
                TextView(this).apply {

                    textSize = 16f

                    setPadding(
                        0,
                        dp(5),
                        0,
                        dp(5)
                    )

                    setLineSpacing(
                        dp(2).toFloat(),
                        1.12f
                    )

                    when (estado) {

                        EstadoModeloVosk.INTEGRADO -> {

                            text =
                                textoApp(
                                    R.string.modelo_integrado
                                )
                        }

                        EstadoModeloVosk.INSTALADO -> {

                            text =
                                textoApp(
                                    R.string.modelo_instalado
                                )
                        }

                        EstadoModeloVosk.DESCARGANDO -> {

                            text =
                                textoApp(
                                    R.string.modelo_descargando
                                )
                        }

                        EstadoModeloVosk.DESCARGABLE -> {

                            /*
                             * "Descargar" ya no parece información.
                             *
                             * Se presenta como una acción:
                             * color del tema + subrayado.
                             */
                            text =
                                textoApp(
                                    R.string.accion_descargar
                                )

                            setTextColor(
                                binding.buttonMas.currentTextColor
                            )

                            paintFlags =
                                paintFlags or
                                        Paint.UNDERLINE_TEXT_FLAG

                            isClickable = true

                            setOnClickListener {

                                dialogo.dismiss()

                                mostrarConfirmacionDescarga(
                                    modeloVosk
                                )
                            }
                        }

                        EstadoModeloVosk.NO_DISPONIBLE -> {

                            text =
                                textoApp(
                                    R.string.modelo_no_disponible
                                )
                        }
                    }
                }

            fila.addView(
                nombreModelo
            )

            fila.addView(
                textoEstado
            )

            contenedor.addView(
                fila
            )
        }

        dialogo =
            AlertDialog.Builder(this)
                .setTitle(
                    textoApp(
                        R.string.dialogo_idiomas_modelos
                    )
                )
                .setView(
                    desplazamiento
                )
                .setNegativeButton(
                    textoApp(
                        R.string.accion_cerrar
                    ),
                    null
                )
                .create()

        dialogo.show()
    }

    private fun comprobarPermisoMicrofono() {

        // Pregunta a Android si RECORD_AUDIO ya fue autorizado
        // anteriormente para esta aplicación.
        val permisoConcedido = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (permisoConcedido) {

            // Si Android ya recuerda que autorizamos el micrófono,
            // no vuelve a mostrar la solicitud y podemos continuar.
            prepararFlujoActual()

        } else {

            solicitarPermisoMicrofono.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun reproducirTraduccion(textoDestino: String) {

        val tts = textoAVoz

        if (tts == null || !ttsListo) {

            binding.textoEstado.text =
                textoApp(
                    R.string.tts_no_listo
                )

            binding.buttonEscuchar.isEnabled = true
            binding.buttonIntercambiarIdiomas.isEnabled = true
            binding.buttonDetectar.isEnabled =
                false

            return
        }

        if (textoDestino.isBlank()) {

            binding.textoEstado.text =
                textoApp(
                    R.string.tts_sin_traduccion
                )

            binding.buttonEscuchar.isEnabled = true
            binding.buttonIntercambiarIdiomas.isEnabled = true

            return
        }

        binding.textoEstado.text =
            textoApp(
                R.string.estado_reproduciendo
            )

        binding.buttonEscuchar.isEnabled = false
        binding.buttonIntercambiarIdiomas.isEnabled = false

        val idReproduccion =
            "traduccion_${System.currentTimeMillis()}"

        val resultado = tts.speak(
            textoDestino,
            TextToSpeech.QUEUE_FLUSH,
            null,
            idReproduccion
        )

        if (resultado == TextToSpeech.ERROR) {

            ttsReproduciendo = false

            actualizarBotonAudioTraduccion()

            binding.textoEstado.text =
                textoApp(
                    R.string.error_reproducir_traduccion
                )

            binding.buttonEscuchar.isEnabled = true
            binding.buttonIntercambiarIdiomas.isEnabled = true
            binding.buttonDetectar.isEnabled =
                true

        } else {

            /*
             * Cambiamos inmediatamente 🔊 por ✕.
             * No esperamos al callback onStart().
             */
            ttsReproduciendo = true

            actualizarBotonAudioTraduccion()
        }
    }

    private fun reproducirTraduccionActual() {

        val texto =
            ultimaTraduccion.trim()

        if (texto.isBlank()) {

            binding.textoEstado.text =
                textoApp(
                    R.string.tts_sin_traduccion_actual
                )

            return
        }

        /*
         * reutilizamos exactamente el mismo TTS que utiliza
         * la traducción automática por voz.
         *
         * idiomaDestino ya determina qué idioma debe hablar.
         */
        reproducirTraduccion(
            texto
        )
    }

    private fun actualizarBotonAudioTraduccion() {

        if (ttsReproduciendo) {

            binding.buttonEscucharTraduccion.setImageResource(
                R.drawable.ic_cancelar_audio
            )

            binding.buttonEscucharTraduccion.contentDescription =
                textoApp(
                    R.string.accion_detener_audio
                )

        } else {

            binding.buttonEscucharTraduccion.setImageResource(
                R.drawable.ic_bocina
            )

            binding.buttonEscucharTraduccion.contentDescription =
                textoApp(
                    R.string.accion_escuchar_traduccion
                )
        }
    }

    private fun cancelarReproduccionTts() {

        textoAVoz?.stop()

        ttsReproduciendo = false

        actualizarBotonAudioTraduccion()

        binding.textoEstado.text =
            textoApp(
                R.string.estado_listo
            )

        binding.buttonEscuchar.isEnabled = true
        binding.buttonIntercambiarIdiomas.isEnabled = true
        binding.buttonDetectar.isEnabled =
            true
    }

    private fun obtenerModeloVoskDeIdioma(
        idioma: Idioma
    ): ModeloVosk? {

        return CatalogoModelosVosk.obtenerModeloDeIdioma(idioma)
    }

    private fun idiomaTieneModeloVoskDisponible(
        idioma: Idioma
    ): Boolean {

        val modeloVosk =
            obtenerModeloVoskDeIdioma(
                idioma
            )
                ?: return false

        return when (
            obtenerEstadoModeloVosk(
                modeloVosk
            )
        ) {

            EstadoModeloVosk.INTEGRADO,
            EstadoModeloVosk.INSTALADO -> {

                true
            }

            EstadoModeloVosk.DESCARGANDO,
            EstadoModeloVosk.DESCARGABLE,
            EstadoModeloVosk.NO_DISPONIBLE -> {

                false
            }
        }
    }

    private fun mostrarSelectorIdioma(
        seleccionarOrigen: Boolean
    ) {

        /*
         * No permitimos cambiar idioma mientras haya
         * reconocimiento, traducción, TTS o cambio de modelo.
         *
         * buttonIntercambiarIdiomas ya funciona como indicador
         * del estado disponible del flujo actual.
         */
        if (
            !binding.buttonIntercambiarIdiomas.isEnabled
        ) {

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_esperar
                )

            return
        }

        /*
 * Mostramos todos los idiomas cuyo modelo Vosk
 * está realmente disponible.
 *
 * Si el usuario selecciona el idioma que actualmente
 * está situado al otro lado, seleccionarIdioma()
 * intercambiará automáticamente la dirección.
 */
        val idiomasDisponibles =
            CatalogoIdiomas.TODOS
                .filter { idioma ->

                    idiomaTieneModeloVoskDisponible(
                        idioma
                    )
                }

        if (idiomasDisponibles.isEmpty()) {

            binding.textoEstado.text =
                textoApp(
                    R.string.selector_sin_idiomas
                )

            return
        }

        val nombres =
            idiomasDisponibles
                .map { idioma ->

                    nombreIdioma(idioma)
                }
                .toTypedArray()

        val idiomaActual =
            if (seleccionarOrigen) {

                idiomaOrigen

            } else {

                idiomaDestino
            }

        val indiceActual =
            idiomasDisponibles
                .indexOf(
                    idiomaActual
                )

        AlertDialog.Builder(this)
            .setTitle(
                if (seleccionarOrigen) {

                    textoApp(
                        R.string.dialogo_idioma_origen
                    )

                } else {

                    textoApp(
                        R.string.dialogo_idioma_destino
                    )
                }
            )
            .setSingleChoiceItems(
                nombres,
                indiceActual
            ) { dialogo, indice ->

                val idiomaElegido =
                    idiomasDisponibles[indice]

                seleccionarIdioma(
                    seleccionarOrigen,
                    idiomaElegido
                )

                dialogo.dismiss()
            }
            .setNegativeButton(
                textoApp(
                    R.string.accion_cancelar
                ),
                null
            )
            .show()
    }

    private fun seleccionarIdioma(
        seleccionarOrigen: Boolean,
        nuevoIdioma: Idioma
    ) {

        val idiomaActual =
            if (seleccionarOrigen) {

                idiomaOrigen

            } else {

                idiomaDestino
            }

        /*
         * Idioma situado actualmente en el lado contrario.
         */
        val idiomaOpuestoActual =
            if (seleccionarOrigen) {

                idiomaDestino

            } else {

                idiomaOrigen
            }

        /*
         * Si seleccionamos el idioma que ya ocupa el otro lado,
         * intercambiamos la dirección en vez de permitir:
         *
         * Inglés → Inglés
         */
        val requiereIntercambioAutomatico =
            nuevoIdioma ==
                    idiomaOpuestoActual

        /*
         * Elegir manualmente un idioma de origen
         * abandona Detectar.
         */
        if (
            seleccionarOrigen &&
            modoDeteccionAutomaticaActivo
        ) {

            modoDeteccionAutomaticaActivo =
                false

            deteccionAutomaticaSolicitada =
                false
        }

        /*
         * CASO 1
         *
         * El usuario tocó el mismo idioma que ya estaba activo.
         * No reconstruimos ningún recurso.
         */
        if (
            nuevoIdioma ==
            idiomaActual
        ) {

            if (seleccionarOrigen) {

                registrarIdiomaReciente(
                    historial =
                        historialIdiomasOrigen,

                    idioma =
                        nuevoIdioma,

                    limite =
                        maximoHistorialOrigen
                )

            } else {

                registrarIdiomaReciente(
                    historial =
                        historialIdiomasDestino,

                    idioma =
                        nuevoIdioma,

                    limite =
                        maximoHistorialDestino
                )
            }

            actualizarSelectorIdiomas()

            return
        }

        /*
         * La dirección realmente va a cambiar.
         *
         * El texto escrito pertenece al usuario y NO debe
         * desaparecer al cambiar origen o destino.
         *
         * Cancelamos primero cualquier debounce pendiente
         * perteneciente a la dirección anterior.
         */
        tareaTraduccionEdicion
            ?.let { tarea ->

                manejadorEdicionTexto
                    .removeCallbacks(
                        tarea
                    )
            }

        tareaTraduccionEdicion =
            null

        /*
         * Invalida traducciones asíncronas todavía en vuelo
         * correspondientes a la combinación anterior.
         */
        versionSolicitudTraduccion++

        /*
         * La misma cadena deberá poder traducirse otra vez
         * porque la dirección cambió.
         */
        ultimoTextoEditadoTraducido =
            ""

        /*
         * Conservamos exactamente el contenido REAL del EditText.
         *
         * Como el placeholder ahora es un hint, nunca entra aquí.
         */
        textoPendienteEntradaManual =
            binding.textoEntrada.text
                .toString()
                .trim()
                .takeIf {
                    it.isNotBlank()
                }

        binding.buttonEscuchar.isEnabled =
            false

        binding.buttonIntercambiarIdiomas.isEnabled =
            false

        binding.textoEstado.text =
            textoApp(
                R.string.estado_cambiando_idioma
            )

        textoAVoz?.stop()

        ttsListo =
            false

        /*
         * Liberamos los recursos correspondientes
         * a la dirección anterior.
         */
        liberarReconocimientoVosk()

        traductorActual?.close()

        traductorActual =
            null

        modeloTraduccionListo =
            false

        /*
         * CASO 2
         *
         * Seleccionamos el idioma que estaba en el otro lado.
         * Intercambiamos la dirección.
         */
        if (requiereIntercambioAutomatico) {

            if (seleccionarOrigen) {

                /*
                 * Español → Inglés
                 *
                 * elegimos Inglés como origen:
                 *
                 * Inglés → Español
                 */
                idiomaOrigen =
                    nuevoIdioma

                idiomaDestino =
                    idiomaActual

            } else {

                /*
                 * Español → Inglés
                 *
                 * elegimos Español como destino:
                 *
                 * Inglés → Español
                 */
                idiomaDestino =
                    nuevoIdioma

                idiomaOrigen =
                    idiomaActual
            }

            /*
             * Un intercambio manual establece una dirección
             * explícita, por lo que Detectar se desactiva.
             */
            modoDeteccionAutomaticaActivo =
                false

            deteccionAutomaticaSolicitada =
                false

            registrarIdiomaReciente(
                historial =
                    historialIdiomasOrigen,

                idioma =
                    idiomaOrigen,

                limite =
                    maximoHistorialOrigen
            )

            registrarIdiomaReciente(
                historial =
                    historialIdiomasDestino,

                idioma =
                    idiomaDestino,

                limite =
                    maximoHistorialDestino
            )

        } else {

            /*
             * CASO 3
             *
             * Cambio normal de un solo lado.
             */
            if (seleccionarOrigen) {

                idiomaOrigen =
                    nuevoIdioma

                registrarIdiomaReciente(
                    historial =
                        historialIdiomasOrigen,

                    idioma =
                        nuevoIdioma,

                    limite =
                        maximoHistorialOrigen
                )

            } else {

                idiomaDestino =
                    nuevoIdioma

                registrarIdiomaReciente(
                    historial =
                        historialIdiomasDestino,

                    idioma =
                        nuevoIdioma,

                    limite =
                        maximoHistorialDestino
                )
            }
        }

        actualizarSelectorIdiomas()

        /*
         * MUY IMPORTANTE:
         *
         * No borramos textoEntrada.
         *
         * Cambiar idioma solo cambia cómo se interpreta
         * el contenido, no el contenido mismo.
         */
        mostrarPlaceholderTextoTraduccion()

        prepararFlujoActual()
    }

    private fun permisoMicrofonoConcedido(): Boolean {

        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun reconocimientoActualPreparado(): Boolean {

        val decision =
            resolverMotorReconocimientoActual()

        return when (decision.motor) {

            MotorReconocimiento.ANDROID -> {

                /*
                 * Si el resolver eligió Android significa que
                 * el gestor lo considera utilizable para el
                 * idioma actual.
                 *
                 * SpeechRecognizer se crea posteriormente al
                 * comenzar la escucha.
                 */
                true
            }

            MotorReconocimiento.VOSK -> {

                /*
                 * Para Vosk sí necesitamos que el objeto Model
                 * haya terminado realmente de cargarse.
                 */
                modelo != null
            }

            null -> {

                false
            }
        }
    }

    private fun actualizarControlesSegunPreparacion() {

        /*
         * Traducción y TTS forman una parte independiente
         * del reconocimiento.
         */
        val traduccionYVozListas =
            modeloTraduccionListo &&
                    ttsListo

        /*
         * El micrófono exige además:
         *
         * - permiso;
         * - un motor resuelto;
         * - que dicho motor esté preparado.
         */
        val reconocimientoListo =
            permisoMicrofonoConcedido() &&
                    reconocimientoActualPreparado()

        binding.buttonEscuchar.isEnabled =
            traduccionYVozListas &&
                    reconocimientoListo

        /*
         * Intercambiar idiomas no depende de Vosk.
         *
         * Una vez preparada traducción + TTS,
         * la dirección puede cambiarse.
         */
        binding.buttonIntercambiarIdiomas.isEnabled =
            traduccionYVozListas
    }

    /*
 * Prepara las dos partes del flujo de manera independiente.
 *
 * ML Kit + TTS ya no dependen de que Vosk haya terminado
 * correctamente de cargar.
 *
 * Por ahora seguimos precargando Vosk porque todavía puede
 * utilizarse como motor principal o fallback.
 *
 * En el siguiente paso haremos también esa carga selectiva.
 */
    private fun prepararReconocimientoSegunResolver() {

        /*
         * En Android 13+ necesitamos esperar la consulta real
         * de RecognitionSupport.
         *
         * De lo contrario SIN_CONSULTAR podría hacer que el
         * resolver eligiera Vosk temporalmente aunque el modelo
         * Android ya estuviera instalado.
         */
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU &&
            !consultaModelosAndroidCompletada
        ) {

            binding.buttonEscuchar.isEnabled =
                false

            return
        }

        val decision =
            resolverMotorReconocimientoActual()

        when (decision.motor) {

            MotorReconocimiento.ANDROID -> {

                /*
                 * Android será el motor actual.
                 *
                 * Ya no necesitamos mantener cargado en RAM
                 * un modelo Vosk solamente "por si acaso".
                 */
                liberarReconocimientoVosk()

                actualizarControlesSegunPreparacion()
            }

            MotorReconocimiento.VOSK -> {

                /*
                 * Vosk solo se carga cuando el resolver
                 * determina que realmente será utilizado.
                 */
                cargarModeloVosk()
            }

            null -> {

                /*
                 * No existe todavía ningún motor utilizable.
                 *
                 * No cargamos recursos innecesarios.
                 */
                liberarReconocimientoVosk()

                mostrarSinMotorReconocimiento()
            }
        }
    }

    private fun prepararFlujoActual() {

        binding.buttonEscuchar.isEnabled =
            false

        binding.buttonIntercambiarIdiomas.isEnabled =
            false

        /*
         * Traducción y TTS son independientes
         * del motor de reconocimiento.
         */
        prepararTraductor()

        /*
         * Android o Vosk se prepara según la decisión
         * centralizada del resolver.
         */
        prepararReconocimientoSegunResolver()
    }

    private fun cargarModeloVosk() {

        /*
 * Al cambiar únicamente el destino conservamos
 * el texto origen.
 *
 * En cualquier otra preparación normal seguimos
 * mostrando el placeholder.
 */
        if (
            textoPendienteEntradaManual
                .isNullOrBlank()
        ) {

            mostrarPlaceholderTextoEntrada()
        }

        mostrarPlaceholderTextoTraduccion()

        binding.textoEstado.text =
            textoApp(
                R.string.modelo_cargando,
                nombreIdioma(idiomaOrigen)
            )

        binding.buttonEscuchar.isEnabled =
            false

        binding.buttonIntercambiarIdiomas.isEnabled =
            false

        val configuracionModelo =
            obtenerModeloVoskDeIdioma(
                idiomaOrigen
            )

        if (configuracionModelo == null) {

            binding.textoEstado.text =
                textoApp(
                    R.string.modelo_sin_configurar,
                    nombreIdioma(idiomaOrigen)
                )

            binding.buttonIntercambiarIdiomas.isEnabled =
                true

            return
        }

        val estadoModelo =
            obtenerEstadoModeloVosk(
                configuracionModelo
            )

        when (estadoModelo) {

            /*
             * MODELO INTEGRADO
             *
             * Español e inglés actualmente llegan aquí
             * porque viven dentro de assets.
             */
            EstadoModeloVosk.INTEGRADO -> {

                StorageService.unpack(
                    this,
                    configuracionModelo.carpetaAssets,
                    configuracionModelo.carpetaInterna,

                    { modeloCargado: Model ->

                        modelo =
                            modeloCargado

                        binding.textoEstado.text =
                            textoApp(
                                R.string.modelo_listo,
                                nombreIdioma(idiomaOrigen)
                            )

                        actualizarControlesSegunPreparacion()
                    },

                    { excepcion: IOException ->

                        binding.textoEstado.text =
                            textoApp(
                                R.string.modelo_error_carga,
                                excepcion.message ?: ""
                            )

                        binding.buttonEscuchar.isEnabled =
                            false

                        binding.buttonIntercambiarIdiomas.isEnabled =
                            true
                    }
                )
            }

            /*
             * MODELO DESCARGADO
             *
             * Francés llegará aquí después de instalarse.
             *
             * No utilizamos StorageService.unpack porque ya
             * existe como una carpeta real en filesDir.
             */
            EstadoModeloVosk.INSTALADO -> {

                val carpetaModelo =
                    File(
                        filesDir,
                        "modelos_vosk/" +
                                configuracionModelo.carpetaInstalada
                    )

                /*
                 * Model(...) puede tardar.
                 *
                 * No lo ejecutamos en el hilo de interfaz.
                 */
                Thread {

                    try {

                        val modeloCargado =
                            Model(
                                carpetaModelo.absolutePath
                            )

                        runOnUiThread {

                            modelo =
                                modeloCargado

                            binding.textoEstado.text =
                                textoApp(
                                    R.string.modelo_listo,
                                    nombreIdioma(idiomaOrigen)
                                )

                            actualizarControlesSegunPreparacion()
                        }

                    } catch (excepcion: Exception) {

                        runOnUiThread {

                            binding.textoEstado.text =
                                textoApp(
                                    R.string.modelo_error_carga,
                                    excepcion.message ?: ""
                                )

                            binding.buttonEscuchar.isEnabled =
                                false

                            binding.buttonIntercambiarIdiomas.isEnabled =
                                true
                        }
                    }

                }.start()
            }

            EstadoModeloVosk.DESCARGANDO -> {

                binding.textoEstado.text =
                    textoApp(
                        R.string.modelo_descargando_idioma,
                        nombreIdioma(idiomaOrigen)
                    )

                binding.buttonEscuchar.isEnabled =
                    false

                binding.buttonIntercambiarIdiomas.isEnabled =
                    true
            }

            EstadoModeloVosk.DESCARGABLE -> {

                binding.textoEstado.text =
                    textoApp(
                        R.string.modelo_no_instalado_idioma,
                        nombreIdioma(idiomaOrigen)
                    )

                binding.buttonEscuchar.isEnabled =
                    false

                binding.buttonIntercambiarIdiomas.isEnabled =
                    true
            }

            EstadoModeloVosk.NO_DISPONIBLE -> {

                binding.textoEstado.text =
                    textoApp(
                        R.string.modelo_no_disponible_idioma,
                        nombreIdioma(idiomaOrigen)
                    )

                binding.buttonEscuchar.isEnabled =
                    false

                binding.buttonIntercambiarIdiomas.isEnabled =
                    true
            }
        }
    }

    private fun iniciarEscuchaVosk() {

        escuchaConAndroid = false

        // El reconocimiento no puede comenzar si el modelo
        // todavía no terminó de cargarse.
        val modeloDisponible = modelo

        if (modeloDisponible == null) {
            binding.textoEstado.text =
                textoApp(
                    R.string.reconocimiento_modelo_no_listo,
                    nombreIdioma(idiomaOrigen)
                )
            return
        }

        try {

            // Creamos el Recognizer solamente cuando todavía no existe
// uno asociado al modelo Vosk actualmente cargado.
            // 16000 Hz debe coincidir con la frecuencia del audio
            // que SpeechService entregará a Vosk.
            if (reconocedor == null) {
                reconocedor = Recognizer(
                    modeloDisponible,
                    16000.0f
                )
            }

            val reconocedorActual = reconocedor ?: return

            // SpeechService se encarga de capturar audio mediante
            // AudioRecord y enviarlo al Recognizer.
            if (servicioVoz == null) {
                servicioVoz = SpeechService(
                    reconocedorActual,
                    16000.0f
                )
            }

            val servicioActual = servicioVoz ?: return

            textoConfirmado = ""

            // Reinicia resultados anteriores antes de comenzar
            // una nueva sesión de escucha.
            reconocedorActual.reset()

            val inicioCorrecto =
                servicioActual.startListening(this)

            if (inicioCorrecto) {

                escuchando = true

                binding.buttonIntercambiarIdiomas.isEnabled = false

                binding.textoEstado.text =
                    textoApp(
                        R.string.estado_escuchando
                    )

            } else {

                escuchando = false

                binding.buttonEscuchar.isEnabled = true
                binding.buttonIntercambiarIdiomas.isEnabled = true

                binding.textoEstado.text =
                    textoApp(
                        R.string.reconocimiento_no_iniciado
                    )
            }

        } catch (excepcion: IOException) {

            escuchando = false

            // Permitimos volver a intentar o cambiar de idioma.
            binding.buttonEscuchar.isEnabled = true
            binding.buttonIntercambiarIdiomas.isEnabled = true

            binding.textoEstado.text =
                textoApp(
                    R.string.reconocimiento_error_inicio,
                    excepcion.message ?: ""
                )
        }
    }

    private fun obtenerCapacidadesReconocimiento():
            CapacidadesReconocimiento {

        val androidLocalDisponible =
            GestorReconocimientoAndroid
                .reconocimientoLocalDisponible(
                    this
                )

        return CompatibilidadReconocimiento.evaluar(
            apiAndroid =
                Build.VERSION.SDK_INT,

            androidLocalDisponible =
                androidLocalDisponible
        )
    }

    private fun obtenerDisponibilidadReconocimiento(
        idioma: Idioma
    ): DisponibilidadReconocimientoIdioma {

        /*
         * Estado que nos comunicó el gestor Android.
         */
        val estadoAndroid =
            estadosModelosAndroid[idioma]
                ?: EstadoModeloAndroid.SIN_CONSULTAR

        /*
         * La asociación idioma -> modelo Vosk
         * pertenece al catálogo de Vosk,
         * no al propio Idioma.
         */
        val modeloVosk =
            obtenerModeloVoskDeIdioma(
                idioma
            )

        val estadoVosk =
            if (modeloVosk == null) {

                EstadoModeloVosk.NO_DISPONIBLE

            } else {

                obtenerEstadoModeloVosk(
                    modeloVosk
                )
            }

        return DisponibilidadReconocimientoIdioma(
            idioma =
                idioma,

            estadoAndroid =
                estadoAndroid,

            estadoVosk =
                estadoVosk
        )
    }

    private fun actualizarEstadosModelosAndroid() {

        consultaModelosAndroidCompletada = false

        GestorReconocimientoAndroid
            .consultarEstadosModelos(
                context = this,
                idiomas = CatalogoIdiomas.TODOS
            ) { estados ->

                estadosModelosAndroid =
                    estados

                consultaModelosAndroidCompletada =
                    true

                /*
                 * Ahora ya conocemos el estado real de Android.
                 *
                 * Podemos preparar el motor que el resolver considere
                 * mejor sin cargar Vosk innecesariamente.
                 */
                prepararReconocimientoSegunResolver()

                actualizarControlesSegunPreparacion()
            }
    }

    private fun resolverMotorReconocimientoActual():
            DecisionMotorReconocimiento {

        val disponibilidad =
            obtenerDisponibilidadReconocimiento(
                idiomaOrigen
            )

        /*
         * Si Android ya falló realmente durante esta ejecución,
         * no queremos volver a seleccionarlo automáticamente.
         *
         * Modificamos solamente la fotografía que recibe
         * el resolver. No alteramos el estado real guardado
         * del modelo Android.
         */
        val disponibilidadEfectiva =
            if (androidFalloDuranteEjecucion) {

                disponibilidad.copy(
                    estadoAndroid =
                        EstadoModeloAndroid.ERROR_CONSULTA
                )

            } else {

                disponibilidad
            }

        return ResolverMotorReconocimiento.resolver(
            capacidades =
                obtenerCapacidadesReconocimiento(),

            disponibilidad =
                disponibilidadEfectiva,

            preferencia =
                preferenciaMotorReconocimiento
        )
    }

    private fun idiomasAndroidInstaladosParaDeteccion():
            List<Idioma> {

        return CatalogoIdiomas.TODOS
            .filter { idioma ->

                estadosModelosAndroid[idioma] ==
                        EstadoModeloAndroid.INSTALADO
            }
    }



    private fun activarDeteccionAutomatica() {

        /*
         * Nuestro nuevo detector utilizará EXTRA_AUDIO_SOURCE
         * para analizar posteriormente el PCM.
         *
         * Esa infraestructura comienza en Android 13.
         */
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {

            binding.textoEstado.text =
                textoApp(
                    R.string.deteccion_requiere_android13
                )

            return
        }

        if (!consultaModelosAndroidCompletada) {

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_esperar
                )

            return
        }

        val idiomasInstalados =
            idiomasAndroidInstaladosParaDeteccion()

        /*
         * Necesitamos al menos dos candidatos para que
         * "detectar" tenga sentido.
         */
        if (idiomasInstalados.size < 2) {

            binding.textoEstado.text =
                textoApp(
                    R.string.deteccion_modelos_insuficientes
                )

            return
        }

        audioPendienteDeteccion =
            null

        /*
         * Detectar pasa a ser el modo persistente de origen.
         */
        modoDeteccionAutomaticaActivo =
            true

        /*
         * No existe todavía una captura de voz concreta.
         *
         * Cuando el usuario presione posteriormente el micrófono,
         * ACTION_DOWN volverá a poner esta bandera en true.
         */
        deteccionAutomaticaSolicitada =
            false

        actualizarSelectorIdiomas()

        /*
         * Detectar también debe reaccionar al texto que ya existe
         * en el cuadro.
         *
         * Ejemplo:
         *
         * Inglés → Francés
         * "Hola"
         *
         * al pulsar Detectar:
         *
         * Español → Francés
         * "Hola" → "Bonjour"
         */
        val textoActual =
            binding.textoEntrada.text
                .toString()
                .trim()

        if (textoActual.isNotBlank()) {

            /*
             * Puede haber una traducción programada por el debounce
             * de la edición. Ya no queremos que se ejecute utilizando
             * la dirección manual anterior.
             */
            tareaTraduccionEdicion
                ?.let { tarea ->

                    manejadorEdicionTexto
                        .removeCallbacks(
                            tarea
                        )
                }

            tareaTraduccionEdicion =
                null

            /*
             * Permitimos volver a procesar exactamente la misma cadena,
             * porque ahora se interpretará mediante Detectar.
             */
            ultimoTextoEditadoTraducido =
                ""

            /*
             * Eliminamos pendientes de una dirección anterior.
             */
            textoPendienteEntradaManual =
                null

            textoPendienteDeteccionEscrita =
                null

            /*
             * Esta función ya sabe:
             *
             * modo manual   → traducir directamente
             * Detectar      → identificar idioma primero
             *
             * Y Detectar acaba de quedar activo.
             */
            procesarEntradaTexto(
                textoActual
            )

        } else {

            /*
             * Si el cuadro está vacío, simplemente esperamos
             * que el usuario escriba, pegue o utilice el micrófono.
             */
            binding.textoEstado.text =
                textoApp(
                    R.string.deteccion_lista_hablar
                )
        }
    }

    private fun liberarFuenteAudioAndroid() {

        fuenteAudioAndroid?.close()

        fuenteAudioAndroid =
            null

        usandoFuenteAudioAndroid =
            false
    }

    private fun crearIntentReconocimientoAndroidManual(): Intent {

        return Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                idiomaOrigen.etiquetaReconocimientoSistema
            )

            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                true
            )

            /*
             * Este camino queda destinado principalmente
             * a Android 12 cuando el usuario elija Android
             * Speech voluntariamente.
             */
            putExtra(
                RecognizerIntent
                    .EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                2500L
            )

            putExtra(
                RecognizerIntent
                    .EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                5000L
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun crearIntentReconocimientoAndroidConFuenteAudio(
        descriptorAudio: ParcelFileDescriptor
    ): Intent {

        return Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            /*
 * Android 14+:
 *
 * Detectar activa la identificación lingüística
 * solamente para esta pulsación.
 */

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                idiomaOrigen.etiquetaReconocimientoSistema
            )

            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                true
            )

            /*
             * SpeechRecognizer ya no abre directamente
             * el micrófono.
             *
             * Recibirá PCM desde nuestro pipe.
             */
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
             * Ésta es la diferencia decisiva.
             *
             * La sesión segmentada está gobernada por
             * EXTRA_AUDIO_SOURCE.
             *
             * Android establece que, en esta modalidad,
             * la sesión termina cuando se cierra el audio.
             */
            putExtra(
                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_AUDIO_SOURCE
            )
        }
    }

    private fun programarNuevaEscuchaAndroid() {

        /*
 * Una fuente AudioRecord controlada NO debe
 * fabricarse mediante reinicios consecutivos.
 *
 * En API 33+ la continuidad la proporciona el pipe.
 */
        if (usandoFuenteAudioAndroid) {
            return
        }

        /*
         * Android acaba de entregar el resultado de una frase.
         *
         * No iniciamos inmediatamente otra sesión porque algunos
         * servicios necesitan un instante para abandonar por completo
         * la anterior. Así evitamos ERROR_RECOGNIZER_BUSY.
         */
        manejadorReconocimientoAndroid.postDelayed(
            {

                /*
                 * El usuario pudo soltar el botón durante esta
                 * pequeña espera.
                 */
                if (!microfonoPresionado) {

                    finalizarReconocimientoAndroidAcumulado()
                    return@postDelayed
                }

                val reconocedor =
                    reconocedorAndroid

                if (reconocedor == null) {

                    finalizarReconocimientoAndroidAcumulado()
                    return@postDelayed
                }

                try {

                    escuchando = true
                    escuchaConAndroid = true

                    /*
 * Ésta es una nueva sesión utilizada solamente como
 * fallback. Todavía no sabemos si el proveedor aceptará
 * la segmentación en esta nueva petición.
 */
                    segmentacionAndroidConfirmada = false

                    binding.textoEstado.text =
                        textoApp(
                            R.string.estado_escuchando_android
                        )

                    reconocedor.startListening(
                        crearIntentReconocimientoAndroidManual()
                    )

                } catch (excepcion: Exception) {

                    escuchando = false
                    escuchaConAndroid = false
                    microfonoPresionado = false

                    binding.buttonEscuchar.isEnabled = true
                    binding.buttonIntercambiarIdiomas.isEnabled = true

                    binding.textoEstado.text =
                        textoApp(
                            R.string.reconocimiento_error,
                            excepcion.message ?: ""
                        )
                }

            },
            150L
        )
    }

    private fun analizarAudioDeteccionMultilingue() {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {

            return
        }

        val audio =
            audioPendienteDeteccion

        if (
            audio == null ||
            audio.isEmpty()
        ) {

            mostrarPlaceholderTextoEntrada()
            mostrarPlaceholderTextoTraduccion()

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_no_voz
                )

            binding.buttonEscuchar.isEnabled =
                true

            binding.buttonIntercambiarIdiomas.isEnabled =
                true

            binding.buttonDetectar.isEnabled =
                true

            return
        }

        /*
         * Utilizamos solamente idiomas que Android
         * confirmó como instalados en el dispositivo.
         *
         * En el Honor actualmente deberían ser:
         *
         * Español
         * Inglés
         * Francés
         */
        val idiomasCandidatos =
            idiomasAndroidInstaladosParaDeteccion()

        if (idiomasCandidatos.isEmpty()) {

            audioPendienteDeteccion =
                null

            binding.textoEstado.text =
                textoApp(
                    R.string.deteccion_no_identificada
                )

            binding.buttonEscuchar.isEnabled =
                true

            binding.buttonIntercambiarIdiomas.isEnabled =
                true

            binding.buttonDetectar.isEnabled =
                true

            return
        }

        analizadorAudioDeteccion
            ?.cancelar()

        val analizador =
            AnalizadorAudioReconocimientoAndroid(
                this
            )

        analizadorAudioDeteccion =
            analizador

        binding.buttonEscuchar.isEnabled =
            false

        binding.buttonIntercambiarIdiomas.isEnabled =
            false

        binding.buttonDetectar.isEnabled =
            false

        mostrarPlaceholderTextoEntrada()
        mostrarPlaceholderTextoTraduccion()

        /*
         * Aquí guardaremos solamente los candidatos
         * que hayan producido una transcripción válida.
         *
         * Un idioma que devuelva ERROR_NO_MATCH,
         * por ejemplo, simplemente no participará
         * en la comparación final.
         */
        val resultados =
            mutableListOf<ResultadoReconocimientoAudio>()

        analizarSiguienteIdiomaDeteccion(
            analizador =
                analizador,

            audio =
                audio,

            idiomas =
                idiomasCandidatos,

            indice =
                0,

            resultados =
                resultados
        )
    }

    private fun obtenerIdentificadorIdiomaTexto():
            LanguageIdentifier {

        val existente =
            identificadorIdiomaTexto

        if (existente != null) {

            return existente
        }

        val nuevo =
            LanguageIdentification.getClient()

        identificadorIdiomaTexto =
            nuevo

        return nuevo
    }

    private fun detectarIdiomaDeTexto(
        texto: String
    ) {

        val textoLimpio =
            texto.trim()

        if (textoLimpio.isBlank()) {
            return
        }

        /*
         * Creamos una versión concreta de esta petición.
         *
         * Si el usuario continúa escribiendo antes de que
         * Language ID responda, el resultado anterior
         * quedará invalidado.
         */
        val versionEstaDeteccion =
            ++versionSolicitudTraduccion

        binding.textoEstado.text =
            textoApp(
                R.string.estado_traduciendo
            )

        obtenerIdentificadorIdiomaTexto()
            .identifyLanguage(
                textoLimpio
            )
            .addOnSuccessListener(
                this
            ) { etiquetaDetectada ->

                /*
                 * El usuario pudo modificar el texto mientras
                 * Language ID estaba trabajando.
                 */
                if (
                    versionEstaDeteccion !=
                    versionSolicitudTraduccion
                ) {

                    return@addOnSuccessListener
                }

                /*
                 * "und" significa que ML Kit no tuvo suficiente
                 * confianza para identificar el idioma.
                 *
                 * En ese caso conservamos el idioma origen actual
                 * como fallback, para que frases extremadamente
                 * cortas todavía puedan traducirse.
                 */
                if (
                    etiquetaDetectada.equals(
                        "und",
                        ignoreCase = true
                    )
                ) {

                    traducirTexto(
                        textoOrigen =
                            textoLimpio,

                        reproducirVoz =
                            false
                    )

                    return@addOnSuccessListener
                }

                val idiomaDetectado =
                    obtenerIdiomaPorCodigo(
                        Locale.forLanguageTag(
                            etiquetaDetectada
                        )
                            .language
                            .lowercase(
                                Locale.ROOT
                            )
                    )

                /*
                 * Language ID puede reconocer idiomas que nuestra
                 * aplicación todavía no ofrece.
                 *
                 * Ejemplo:
                 * alemán detectado, pero solo tenemos ES/EN/FR.
                 *
                 * En ese caso conservamos el idioma actual.
                 */
                if (idiomaDetectado == null) {

                    traducirTexto(
                        textoOrigen =
                            textoLimpio,

                        reproducirVoz =
                            false
                    )

                    return@addOnSuccessListener
                }

                aplicarIdiomaDetectadoEnTexto(
                    texto =
                        textoLimpio,

                    idiomaDetectado =
                        idiomaDetectado
                )
            }
            .addOnFailureListener(
                this
            ) {

                /*
                 * Language ID es una ayuda adicional.
                 *
                 * Un fallo suyo no debe impedir completamente
                 * que el usuario traduzca.
                 */
                if (
                    versionEstaDeteccion !=
                    versionSolicitudTraduccion
                ) {

                    return@addOnFailureListener
                }

                traducirTexto(
                    textoOrigen =
                        textoLimpio,

                    reproducirVoz =
                        false
                )
            }
    }

    private fun aplicarIdiomaDetectadoEnTexto(
        texto: String,
        idiomaDetectado: Idioma
    ) {

        /*
         * CASO 1
         *
         * La dirección actual ya es correcta.
         *
         * Ejemplo:
         *
         * Detectar
         * Francés → Español
         *
         * usuario escribe francés.
         */
        if (idiomaDetectado == idiomaOrigen) {

            registrarIdiomaReciente(
                historial =
                    historialIdiomasOrigen,

                idioma =
                    idiomaDetectado,

                limite =
                    maximoHistorialOrigen
            )

            actualizarSelectorIdiomas()

            textoConfirmado =
                texto

            traducirTexto(
                textoOrigen =
                    texto,

                reproducirVoz =
                    false
            )

            return
        }

        /*
         * CASO 2
         *
         * El texto pertenece al idioma que actualmente
         * estaba situado como destino.
         *
         * Francés → Español
         * escribimos Español
         *
         * pasa a:
         *
         * Español → Francés
         */
        val nuevoIdiomaDestino =
            if (
                idiomaDetectado ==
                idiomaDestino
            ) {

                idiomaOrigen

            } else {

                /*
                 * CASO 3
                 *
                 * Aparece un tercer idioma.
                 *
                 * Francés → Español
                 * escribimos Inglés
                 *
                 * pasa a:
                 *
                 * Inglés → Español
                 */
                idiomaDestino
            }

        textoPendienteTrasIntercambio =
            null

        textoPendienteDeteccionAutomatica =
            null

        textoPendienteDeteccionEscrita =
            texto

        binding.buttonEscuchar.isEnabled =
            false

        binding.buttonIntercambiarIdiomas.isEnabled =
            false

        binding.buttonDetectar.isEnabled =
            false

        textoAVoz?.stop()

        ttsListo =
            false

        liberarReconocimientoVosk()

        traductorActual?.close()

        traductorActual =
            null

        modeloTraduccionListo =
            false

        idiomaOrigen =
            idiomaDetectado

        idiomaDestino =
            nuevoIdiomaDestino

        /*
         * Detectar permanece seleccionado.
         *
         * La dirección interna cambia, pero seguimos
         * en modo automático.
         */
        registrarIdiomaReciente(
            historial =
                historialIdiomasOrigen,

            idioma =
                idiomaOrigen,

            limite =
                maximoHistorialOrigen
        )

        registrarIdiomaReciente(
            historial =
                historialIdiomasDestino,

            idioma =
                idiomaDestino,

            limite =
                maximoHistorialDestino
        )

        actualizarSelectorIdiomas()

        textoConfirmado =
            texto

        /*
         * Mientras el usuario está editando no ejecutamos
         * setText(), porque podría mover el cursor.
         *
         * Si vino de Pegar, el texto ya está visible igualmente.
         */
        if (!editandoTextoOrigen) {

            mostrarTextoEntrada(
                texto
            )
        }

        mostrarPlaceholderTextoTraduccion()

        binding.textoEstado.text =
            textoApp(
                R.string.estado_cambiando_idioma
            )

        prepararFlujoActual()
    }

    private fun validarIdiomaTextoReconocido(
        resultado: ResultadoReconocimientoAudio,
        alCompletar: (Boolean) -> Unit
    ) {

        val texto =
            resultado.texto.trim()

        if (texto.isBlank()) {

            alCompletar(
                false
            )

            return
        }

        obtenerIdentificadorIdiomaTexto()
            .identifyLanguage(
                texto
            )
            .addOnSuccessListener(
                this
            ) { etiquetaDetectada ->

                /*
                 * "und" significa que ML Kit no alcanzó
                 * suficiente confianza.
                 *
                 * No descartamos el candidato en ese caso:
                 * dejamos que nuestra heurística acústica
                 * tradicional decida.
                 */
                if (
                    etiquetaDetectada.equals(
                        "und",
                        ignoreCase = true
                    )
                ) {

                    alCompletar(
                        true
                    )

                    return@addOnSuccessListener
                }

                val idiomaTexto =
                    Locale.forLanguageTag(
                        etiquetaDetectada
                    )
                        .language
                        .lowercase(
                            Locale.ROOT
                        )

                val idiomaCandidato =
                    Locale.forLanguageTag(
                        resultado
                            .idioma
                            .etiquetaReconocimientoSistema
                    )
                        .language
                        .lowercase(
                            Locale.ROOT
                        )

                /*
                 * Ejemplos:
                 *
                 * resultado candidato ES
                 * texto identificado EN
                 *     -> false
                 *
                 * resultado candidato EN
                 * texto identificado EN
                 *     -> true
                 *
                 * es-US frente a es-ES
                 *     -> true
                 *
                 * porque comparamos el idioma base.
                 */
                alCompletar(
                    idiomaTexto ==
                            idiomaCandidato
                )
            }
            .addOnFailureListener(
                this
            ) {

                /*
                 * Language ID funciona como una validación
                 * adicional, no como punto único de fallo.
                 *
                 * Si excepcionalmente falla, conservamos
                 * el candidato y dejamos trabajar a la
                 * heurística existente.
                 */
                alCompletar(
                    true
                )
            }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun analizarSiguienteIdiomaDeteccion(
        analizador: AnalizadorAudioReconocimientoAndroid,
        audio: ByteArray,
        idiomas: List<Idioma>,
        indice: Int,
        resultados: MutableList<ResultadoReconocimientoAudio>
    ) {

        /*
         * Ya probamos todos los idiomas.
         */
        if (indice >= idiomas.size) {

            finalizarComparacionIdiomasDeteccion(
                resultados
            )

            return
        }

        val idiomaPrueba =
            idiomas[indice]

        binding.textoEstado.text =
            textoApp(
                R.string.deteccion_analizando_idioma,
                nombreIdioma(
                    idiomaPrueba
                )
            )

        /*
         * MUY IMPORTANTE:
         *
         * "audio" siempre es exactamente el mismo
         * ByteArray PCM.
         *
         * No volvemos a utilizar el micrófono.
         */
        analizador.reconocer(
            audioPcm =
                audio,

            idioma =
                idiomaPrueba,

            alResultado = { resultado ->

                validarIdiomaTextoReconocido(
                    resultado
                ) { idiomaTextoCompatible ->

                    /*
                     * Solamente entra en la competición si:
                     *
                     * 1. SpeechRecognizer produjo texto;
                     * 2. ese texto pertenece realmente al idioma
                     *    que estamos probando.
                     */
                    if (idiomaTextoCompatible) {

                        resultados.add(
                            resultado
                        )
                    }

                    /*
                     * Tanto si fue aceptado como rechazado,
                     * continuamos con el siguiente candidato.
                     *
                     * IMPORTANTE:
                     * conserva aquí exactamente los parámetros
                     * que ya tiene actualmente tu llamada recursiva.
                     */
                    analizarSiguienteIdiomaDeteccion(
                        analizador =
                            analizador,

                        audio =
                            audio,

                        idiomas =
                            idiomas,

                        indice =
                            indice + 1,

                        resultados =
                            resultados
                    )
                }
            },

            alError = {

                /*
                 * Un fallo de reconocimiento con un candidato
                 * NO significa que la detección completa falle.
                 *
                 * Simplemente probamos el siguiente idioma.
                 */
                analizarSiguienteIdiomaDeteccion(
                    analizador =
                        analizador,

                    audio =
                        audio,

                    idiomas =
                        idiomas,

                    indice =
                        indice + 1,

                    resultados =
                        resultados
                )
            }
        )
    }

    private fun puntuacionResultadoDeteccion(
        resultado: ResultadoReconocimientoAudio,
        longitudMaxima: Int
    ): Double {

        /*
         * COBERTURA
         *
         * Un modelo incorrecto suele reconocer solo
         * algunos fragmentos o producir mucho menos texto.
         *
         * El candidato con la transcripción más extensa
         * obtiene cobertura 1.0.
         */
        val cobertura =
            if (longitudMaxima > 0) {

                resultado.texto.length.toDouble() /
                        longitudMaxima.toDouble()

            } else {

                0.0
            }

        /*
         * CONFIANZA ACÚSTICA
         *
         * Android puede proporcionar una confianza entre
         * 0 y 1.
         *
         * No todos los proveedores la entregan.
         */
        val confianza =
            resultado.confianzaPromedio
                ?.coerceIn(
                    0.0f,
                    1.0f
                )
                ?.toDouble()

        /*
         * Si existe confianza, tiene mayor peso que
         * la longitud del texto.
         *
         * Si Android no proporciona confianza,
         * utilizamos únicamente la cobertura.
         */
        return if (confianza != null) {

            confianza * 0.70 +
                    cobertura * 0.30

        } else {

            cobertura
        }
    }

    private fun aplicarResultadoDeteccionAutomatica(
        resultado: ResultadoReconocimientoAudio
    ) {

        val textoDetectado =
            resultado.texto.trim()

        if (textoDetectado.isBlank()) {

            textoConfirmado =
                ""

            mostrarPlaceholderTextoEntrada()
            mostrarPlaceholderTextoTraduccion()

            binding.textoEstado.text =
                textoApp(
                    R.string.deteccion_no_identificada
                )

            binding.buttonEscuchar.isEnabled =
                true

            binding.buttonIntercambiarIdiomas.isEnabled =
                true

            binding.buttonDetectar.isEnabled =
                true

            return
        }

        val idiomaDetectado =
            resultado.idioma

        /*
         * CASO 1
         *
         * El idioma detectado ya es el idioma origen.
         *
         * Ejemplo:
         *
         * Español → Inglés
         * hablamos Español
         *
         * No reconstruimos Translator ni TTS.
         */
        if (idiomaDetectado == idiomaOrigen) {

            registrarIdiomaReciente(
                historial =
                    historialIdiomasOrigen,

                idioma =
                    idiomaDetectado,

                limite =
                    maximoHistorialOrigen
            )

            actualizarSelectorIdiomas()

            textoConfirmado =
                textoDetectado

            mostrarTextoEntrada(
                textoDetectado
            )

            mostrarPlaceholderTextoTraduccion()

            traducirTexto(
                textoOrigen =
                    textoDetectado,

                reproducirVoz =
                    true
            )

            return
        }

        /*
         * CASO 2
         *
         * El idioma detectado coincide con el destino.
         *
         * Ejemplo:
         *
         * Español → Inglés
         * hablamos Inglés
         *
         * Debemos obtener:
         *
         * Inglés → Español
         */
        val nuevoIdiomaDestino =
            if (idiomaDetectado == idiomaDestino) {

                idiomaOrigen

            } else {

                /*
                 * CASO 3
                 *
                 * Apareció un tercer idioma.
                 *
                 * Ejemplo:
                 *
                 * Español → Inglés
                 * hablamos Francés
                 *
                 * Resultado:
                 *
                 * Francés → Inglés
                 */
                idiomaDestino
            }

        /*
         * Esta entrada sustituye cualquier texto que pudiera
         * estar esperando por un intercambio manual anterior.
         */
        textoPendienteTrasIntercambio =
            null

        textoPendienteDeteccionAutomatica =
            textoDetectado

        binding.buttonEscuchar.isEnabled =
            false

        binding.buttonIntercambiarIdiomas.isEnabled =
            false

        binding.buttonDetectar.isEnabled =
            false

        /*
         * Detenemos una eventual reproducción anterior.
         */
        textoAVoz?.stop()

        ttsListo =
            false

        /*
         * El reconocimiento manual también debe corresponder
         * al nuevo idioma origen.
         */
        liberarReconocimientoVosk()

        /*
         * El Translator existente pertenece a la dirección
         * anterior y ya no debe reutilizarse.
         */
        traductorActual?.close()

        traductorActual =
            null

        modeloTraduccionListo =
            false

        /*
         * Aplicamos la nueva dirección.
         */
        idiomaOrigen =
            idiomaDetectado

        idiomaDestino =
            nuevoIdiomaDestino

        registrarIdiomaReciente(
            historial =
                historialIdiomasOrigen,

            idioma =
                idiomaOrigen,

            limite =
                maximoHistorialOrigen
        )

        registrarIdiomaReciente(
            historial =
                historialIdiomasDestino,

            idioma =
                idiomaDestino,

            limite =
                maximoHistorialDestino
        )

        /*
         * Desde aquí textoApp() también utilizará el nuevo
         * idioma origen para la interfaz.
         */
        actualizarSelectorIdiomas()

        textoConfirmado =
            textoDetectado

        mostrarTextoEntrada(
            textoDetectado
        )

        mostrarPlaceholderTextoTraduccion()

        binding.textoEstado.text =
            textoApp(
                R.string.estado_cambiando_idioma
            )

        /*
         * prepararFlujoActual() reconstruye:
         *
         * - Translator;
         * - TTS;
         * - motor de reconocimiento apropiado.
         *
         * La traducción del texto detectado ocurrirá cuando
         * configurarIdiomaTtsDestino() confirme que todo está listo.
         */
        prepararFlujoActual()
    }

    private fun finalizarComparacionIdiomasDeteccion(
        resultados: List<ResultadoReconocimientoAudio>
    ) {

        analizadorAudioDeteccion =
            null

        /*
         * Ya no necesitamos conservar el PCM una vez
         * terminadas todas las pruebas.
         */
        audioPendienteDeteccion =
            null

        if (resultados.isEmpty()) {

            textoConfirmado =
                ""

            mostrarPlaceholderTextoEntrada()
            mostrarPlaceholderTextoTraduccion()

            binding.textoEstado.text =
                textoApp(
                    R.string.deteccion_no_identificada
                )

            binding.buttonEscuchar.isEnabled =
                true

            binding.buttonIntercambiarIdiomas.isEnabled =
                true

            binding.buttonDetectar.isEnabled =
                true

            return
        }

        /*
         * Necesitamos conocer la transcripción más extensa
         * para normalizar la cobertura entre 0 y 1.
         */
        val longitudMaxima =
            resultados
                .maxOf {
                    it.texto.length
                }
                .coerceAtLeast(
                    1
                )

        val resultadoGanador =
            resultados
                .maxByOrNull { resultado ->

                    puntuacionResultadoDeteccion(
                        resultado =
                            resultado,

                        longitudMaxima =
                            longitudMaxima
                    )
                }

        if (resultadoGanador == null) {

            textoConfirmado =
                ""

            mostrarPlaceholderTextoEntrada()
            mostrarPlaceholderTextoTraduccion()

            binding.textoEstado.text =
                textoApp(
                    R.string.deteccion_no_identificada
                )

            binding.buttonEscuchar.isEnabled =
                true

            binding.buttonIntercambiarIdiomas.isEnabled =
                true

            binding.buttonDetectar.isEnabled =
                true

            return
        }

        /*
 * La comparación ya terminó.
 *
 * A partir de aquí el ganador deja de ser un
 * diagnóstico y se convierte en una entrada real.
 */
        aplicarResultadoDeteccionAutomatica(
            resultadoGanador
        )
    }

    private fun finalizarCapturaDeteccion() {

        val fuente =
            fuenteAudioAndroid

        /*
         * Primero detenemos la entrada.
         *
         * close() espera brevemente al hilo de captura,
         * por lo que obtenemos el PCM después de que
         * deje de modificarse.
         */
        fuente?.detenerEntrada()

        fuente?.close()

        val audioCapturado =
            fuente
                ?.obtenerAudioCapturado()
                ?: ByteArray(0)

        fuenteAudioAndroid =
            null

        usandoFuenteAudioAndroid =
            false

        escuchando =
            false

        escuchaConAndroid =
            false

        finalizacionAndroidProcesada =
            true

        deteccionAutomaticaSolicitada =
            false

        /*
         * Esta grabación sobrevivirá a la fuente.
         *
         * La utilizaremos en 17C.10D.
         */
        audioPendienteDeteccion =
            if (audioCapturado.isNotEmpty()) {

                audioCapturado

            } else {

                null
            }

        textoConfirmado =
            ""

        mostrarPlaceholderTextoEntrada()

        binding.buttonEscuchar.isEnabled =
            true

        binding.buttonIntercambiarIdiomas.isEnabled =
            true

        /*
 * La captura ya fue validada.
 *
 * Ahora utilizamos exactamente ese mismo PCM
 * para la primera prueba de reconocimiento diferido.
 */
        if (audioPendienteDeteccion == null) {

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_no_voz
                )

            return
        }

        analizarAudioDeteccionMultilingue()
    }

    private fun finalizarReconocimientoAndroidAcumulado() {

        if (finalizacionAndroidProcesada) {
            return
        }

        finalizacionAndroidProcesada =
            true

        liberarFuenteAudioAndroid()

        escuchando =
            false

        escuchaConAndroid =
            false

        val textoFinal =
            textoConfirmado.trim()

        if (textoFinal.isBlank()) {

            mostrarPlaceholderTextoEntrada()

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_no_voz
                )

            binding.buttonEscuchar.isEnabled =
                true

            binding.buttonIntercambiarIdiomas.isEnabled =
                true

            return
        }

        mostrarTextoEntrada(
            textoFinal
        )

        traducirTexto(
            textoOrigen =
                textoFinal,

            reproducirVoz =
                true
        )
    }

    private fun iniciarCapturaDeteccion(): Boolean {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {

            return false
        }

        return try {

            liberarFuenteAudioAndroid()

            /*
             * Aquí NO creamos SpeechRecognizer.
             *
             * El único responsable durante la pulsación
             * será AudioRecord.
             */
            val fuente =
                FuenteAudioReconocimientoAndroid(
                    this
                )

            /*
             * La guardamos antes de preparar para que,
             * si algo falla, el catch pueda liberarla.
             */
            fuenteAudioAndroid =
                fuente

            fuente.prepararSoloCaptura()

            usandoFuenteAudioAndroid =
                true

            textoConfirmado =
                ""

            finalizacionAndroidProcesada =
                false

            escuchaConAndroid =
                true

            escuchando =
                true

            binding.buttonIntercambiarIdiomas.isEnabled =
                false

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_escuchando_android
                )

            fuente.iniciarCaptura()

            true

        } catch (excepcion: Exception) {

            liberarFuenteAudioAndroid()

            deteccionAutomaticaSolicitada =
                false

            escuchaConAndroid =
                false

            escuchando =
                false

            binding.buttonEscuchar.isEnabled =
                true

            binding.buttonIntercambiarIdiomas.isEnabled =
                true

            binding.textoEstado.text =
                textoApp(
                    R.string.reconocimiento_error,
                    excepcion.message ?: ""
                )

            false
        }
    }

    private fun iniciarEscuchaAndroid(): Boolean {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.S
        ) {

            return false
        }

        return try {

            val reconocedor =
                reconocedorAndroid
                    ?: SpeechRecognizer
                        .createOnDeviceSpeechRecognizer(
                            this
                        )
                        .also { nuevoReconocedor ->

                            nuevoReconocedor
                                .setRecognitionListener(
                                    listenerReconocimientoAndroid
                                )

                            reconocedorAndroid =
                                nuevoReconocedor
                        }

            textoConfirmado =
                ""

            segmentacionAndroidConfirmada =
                false

            finalizacionAndroidProcesada =
                false

            escuchaConAndroid =
                true

            escuchando =
                true

            binding.buttonIntercambiarIdiomas.isEnabled =
                false

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_escuchando_android
                )

            /*
             * Android 13+:
             *
             * TraductorAndroid controla físicamente
             * la captura del micrófono.
             */
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                liberarFuenteAudioAndroid()

                val fuente =
                    FuenteAudioReconocimientoAndroid(
                        this
                    )

                val descriptorAudio =
                    fuente.preparar()

                fuenteAudioAndroid =
                    fuente

                usandoFuenteAudioAndroid =
                    true

                /*
                 * Primero entregamos el descriptor.
                 */
                reconocedor.startListening(
                    crearIntentReconocimientoAndroidConFuenteAudio(
                        descriptorAudio
                    )
                )

                /*
                 * Después comenzamos a alimentar el pipe.
                 */
                try {

                    fuente.iniciarCaptura()

                } catch (excepcion: Exception) {

                    reconocedor.cancel()

                    liberarFuenteAudioAndroid()

                    throw excepcion
                }

            } else {

                /*
                 * Android 12:
                 *
                 * camino anterior.
                 */
                usandoFuenteAudioAndroid =
                    false

                reconocedor.startListening(
                    crearIntentReconocimientoAndroidManual()
                )
            }

            true

        } catch (excepcion: Exception) {

            liberarFuenteAudioAndroid()

            reconocedorAndroid?.cancel()
            reconocedorAndroid?.destroy()

            reconocedorAndroid =
                null

            escuchaConAndroid =
                false

            escuchando =
                false

            false
        }
    }

    private fun mostrarSinMotorReconocimiento() {

        escuchando = false
        escuchaConAndroid = false
        microfonoPresionado = false

        binding.buttonEscuchar.isEnabled =
            false

        binding.buttonIntercambiarIdiomas.isEnabled =
            true

        binding.textoEstado.text =
            textoApp(
                R.string.reconocimiento_sin_motor_disponible,
                nombreIdioma(idiomaOrigen)
            )
    }

    private fun iniciarEscucha() {

        /*
 * Detectar tiene una ruta independiente.
 *
 * No consultamos el motor manual ni iniciamos
 * SpeechRecognizer mientras el usuario habla.
 */
        if (deteccionAutomaticaSolicitada) {

            iniciarCapturaDeteccion()

            return
        }

        val decision =
            resolverMotorReconocimientoActual()

        when (decision.motor) {

            MotorReconocimiento.ANDROID -> {

                /*
                 * El resolver decidió Android.
                 *
                 * Sin embargo el servicio todavía podría
                 * fallar al comenzar por una condición
                 * externa inesperada.
                 */
                val inicioAndroidCorrecto =
                    iniciarEscuchaAndroid()

                if (inicioAndroidCorrecto) {

                    return
                }

                /*
 * El resolver consideraba Android válido,
 * pero el servicio real no pudo iniciar.
 *
 * Recordamos el fallo para que esta sesión de la app
 * pueda degradarse a Vosk.
 */
                androidFalloDuranteEjecucion =
                    true

                /*
                 * Fallback de ejecución:
                 *
                 * no basta con resolver correctamente.
                 * Un servicio real puede fallar justo al abrirse.
                 */
                if (decision.voskUtilizable) {

                    /*
                     * Android falló inesperadamente.
                     *
                     * Vosk existe como fallback, pero ya no permanece
                     * cargado innecesariamente en memoria.
                     *
                     * Lo preparamos ahora. En esta primera versión del
                     * fallback dinámico el usuario podrá volver a pulsar
                     * el micrófono cuando termine la carga.
                     */
                    cargarModeloVosk()

                } else {

                    mostrarSinMotorReconocimiento()
                }
            }

            MotorReconocimiento.VOSK -> {

                iniciarEscuchaVosk()
            }

            null -> {

                mostrarSinMotorReconocimiento()
            }
        }
    }

    private fun detenerEscuchaAndroid() {

        escuchando =
            false

        binding.buttonEscuchar.isEnabled =
            false

        binding.textoEstado.text =
            textoApp(
                R.string.estado_finalizando
            )

        /*
 * Detectar no está esperando callbacks de
 * SpeechRecognizer.
 *
 * Nosotros mismos cerramos y conservamos el PCM.
 */
        if (deteccionAutomaticaSolicitada) {

            finalizarCapturaDeteccion()

            return
        }

        /*
         * Android 13+ con fuente controlada.
         *
         * NO llamamos stopListening().
         *
         * Cerramos nuestra entrada de audio.
         * El reconocedor recibe EOF y termina la
         * sesión segmentada.
         */
        if (usandoFuenteAudioAndroid) {

            fuenteAudioAndroid
                ?.detenerEntrada()

            return
        }

        /*
         * Android 12 conserva el comportamiento anterior.
         */
        try {

            reconocedorAndroid
                ?.stopListening()

        } catch (excepcion: Exception) {

            escuchaConAndroid =
                false

            binding.buttonEscuchar.isEnabled =
                true

            binding.buttonIntercambiarIdiomas.isEnabled =
                true

            binding.textoEstado.text =
                textoApp(
                    R.string.reconocimiento_error,
                    excepcion.message ?: ""
                )
        }
    }

    private fun detenerEscucha() {

        if (escuchaConAndroid) {

            detenerEscuchaAndroid()

        } else {

            detenerEscuchaVosk()
        }
    }

    private fun detenerEscuchaVosk() {

        escuchando = false

        binding.buttonEscuchar.isEnabled = false

        binding.textoEstado.text =
            textoApp(
                R.string.estado_finalizando
            )

        servicioVoz?.stop()
    }

    override fun onPartialResult(hypothesis: String) {

        val resultadoJson = JSONObject(hypothesis)

        // Vosk utiliza la clave "partial" para la hipótesis
        // que todavía puede cambiar mientras seguimos hablando.
        val textoParcial =
            resultadoJson.optString("partial")

        val textoMostrado =
            listOf(textoConfirmado, textoParcial)
                .filter { it.isNotBlank() }
                .joinToString(" ")

        if (textoMostrado.isBlank()) {
            mostrarPlaceholderTextoEntrada()
        } else {
            mostrarTextoEntrada(textoMostrado)
        }
    }

    override fun onResult(hypothesis: String) {

        val resultadoJson = JSONObject(hypothesis)

        // "text" contiene una frase que Vosk ya considera terminada.
        val textoNuevo =
            resultadoJson.optString("text")

        if (textoNuevo.isNotBlank()) {

            textoConfirmado =
                listOf(textoConfirmado, textoNuevo)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
        }

        if (textoConfirmado.isBlank()) {
            mostrarPlaceholderTextoEntrada()
        } else {
            mostrarTextoEntrada(textoConfirmado)
        }
    }

    override fun onFinalResult(hypothesis: String) {

        val resultadoJson = JSONObject(hypothesis)

        val textoFinal =
            resultadoJson.optString("text")

        if (textoFinal.isNotBlank()) {

            textoConfirmado =
                listOf(textoConfirmado, textoFinal)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
        }

        if (textoConfirmado.isBlank()) {
            mostrarPlaceholderTextoEntrada()
        } else {
            mostrarTextoEntrada(textoConfirmado)
        }

        escuchando = false

        if (textoConfirmado.isBlank()) {

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_no_voz
                )

            binding.buttonEscuchar.isEnabled = true
            binding.buttonIntercambiarIdiomas.isEnabled = true

            return
        }

// No volvemos a habilitar el botón todavía.
// Primero debe terminar la traducción.
        traducirTexto(
            textoOrigen = textoConfirmado,
            reproducirVoz = true
        )
    }

    override fun onError(exception: Exception) {

        escuchando = false

        binding.buttonEscuchar.isEnabled = true
        binding.buttonIntercambiarIdiomas.isEnabled = true

        binding.textoEstado.text =
            textoApp(
                R.string.reconocimiento_error,
                exception.message ?: ""
            )
    }

    override fun onTimeout() {

        // Por ahora no iniciamos la escucha con un tiempo límite,
        // pero implementamos el callback porque RecognitionListener
        // exige esta función.
        escuchando = false

        binding.buttonEscuchar.isEnabled = true
        binding.buttonIntercambiarIdiomas.isEnabled = true

        binding.textoEstado.text =
            textoApp(
                R.string.reconocimiento_timeout
            )
    }

    override fun onDestroy() {

        manejadorReconocimientoAndroid.removeCallbacksAndMessages(
            null
        )

        manejadorEdicionTexto.removeCallbacksAndMessages(
            null
        )

        analizadorAudioDeteccion
            ?.cancelar()

        analizadorAudioDeteccion =
            null

        identificadorIdiomaTexto
            ?.close()

        identificadorIdiomaTexto =
            null

        liberarFuenteAudioAndroid()

        /*
         * SpeechRecognizer exige liberar explícitamente
         * los recursos cuando deja de utilizarse.
         */
        reconocedorAndroid?.cancel()
        reconocedorAndroid?.destroy()
        reconocedorAndroid = null

        escuchaConAndroid = false

        // Libera reconocimiento y modelo Vosk.
        liberarReconocimientoVosk()

        // Libera los recursos utilizados por ML Kit Translation.
        traductorActual?.close()
        traductorActual = null

        // Detiene y libera TextToSpeech.
        textoAVoz?.stop()
        textoAVoz?.shutdown()
        textoAVoz = null
        ttsListo = false

        if (receptorDescargaRegistrado) {

            unregisterReceiver(
                receptorDescargaModelo
            )

            receptorDescargaRegistrado = false
        }

        super.onDestroy()
    }

    private fun prepararTraductor() {

        binding.buttonEscuchar.isEnabled = false
        binding.buttonIntercambiarIdiomas.isEnabled = false

        binding.textoEstado.text =
            textoApp(
                R.string.traduccion_preparando,
                nombreIdioma(idiomaOrigen),
                nombreIdioma(idiomaDestino)
            )

        /*
         * Si ya existía un Translator correspondiente
         * a la dirección anterior, lo liberamos.
         *
         * Ejemplo:
         *
         * antes: Español → Inglés
         * ahora: Inglés → Español
         */
        traductorActual?.close()
        traductorActual = null

        modeloTraduccionListo = false

        /*
         * La dirección ya no está escrita de forma fija.
         *
         * idiomaOrigen.codigoMlKit
         * idiomaDestino.codigoMlKit
         *
         * determinan qué Translator se construirá.
         */
        val opciones = TranslatorOptions.Builder()
            .setSourceLanguage(idiomaOrigen.codigoMlKit)
            .setTargetLanguage(idiomaDestino.codigoMlKit)
            .build()

        val traductor = Translation.getClient(opciones)

        traductorActual = traductor

        val condiciones = DownloadConditions.Builder()
            .requireWifi()
            .build()

        traductor.downloadModelIfNeeded(condiciones)
            .addOnSuccessListener {

                modeloTraduccionListo = true

                binding.textoEstado.text =
                    textoApp(
                        R.string.modelo_traduccion_listo
                    )

                /*
                 * La traducción ya está preparada.
                 * Ahora configuramos la voz del idioma DESTINO.
                 */
                prepararTtsDestino()
            }
            .addOnFailureListener { excepcion ->

                modeloTraduccionListo = false

                binding.textoEstado.text =
                    textoApp(
                        R.string.traduccion_error_preparar,
                        excepcion.message ?: ""
                    )

                binding.buttonEscuchar.isEnabled = false
                binding.buttonIntercambiarIdiomas.isEnabled = true
            }
    }

    private fun traducirTexto(
        textoOrigen: String,
        reproducirVoz: Boolean
    ) {

        /*
 * Identificador de esta petición concreta.
 *
 * Si el usuario modifica el texto antes de que
 * ML Kit termine, una versión posterior invalidará
 * esta respuesta.
 */
        val versionEstaSolicitud =
            ++versionSolicitudTraduccion

        val traductor =
            traductorActual

        if (
            traductor == null ||
            !modeloTraduccionListo
        ) {

            /*
             * Si la entrada provino de escritura o pegado,
             * no la perdemos simplemente porque acabemos de
             * cambiar idioma y ML Kit todavía se esté preparando.
             */
            if (!reproducirVoz) {

                textoPendienteEntradaManual =
                    textoOrigen
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }

                binding.textoEstado.text =
                    textoApp(
                        R.string.traduccion_preparando,
                        nombreIdioma(
                            idiomaOrigen
                        ),
                        nombreIdioma(
                            idiomaDestino
                        )
                    )

                return
            }

            /*
             * Las entradas de voz conservan su flujo anterior.
             */
            binding.textoEstado.text =
                textoApp(
                    R.string.modelo_traduccion_no_listo
                )

            binding.buttonEscuchar.isEnabled =
                false

            binding.buttonIntercambiarIdiomas.isEnabled =
                true

            binding.buttonDetectar.isEnabled =
                false

            return
        }

        if (textoOrigen.isBlank()) {

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_no_voz
                )

            binding.buttonEscuchar.isEnabled = true
            binding.buttonIntercambiarIdiomas.isEnabled = true

            return
        }

        mostrarPlaceholderTextoTraduccion()

        binding.textoEstado.text =
            textoApp(
                R.string.estado_traduciendo
            )

        binding.buttonEscuchar.isEnabled = false
        binding.buttonIntercambiarIdiomas.isEnabled = false

        traductor.translate(textoOrigen)
            .addOnSuccessListener { textoDestino ->

                if (
                    versionEstaSolicitud !=
                    versionSolicitudTraduccion
                ) {

                    return@addOnSuccessListener
                }

                /*
 * Durante edición directa el texto visible ya es
 * exactamente el escrito por el usuario.
 *
 * No ejecutamos setText() porque eso recolocaría
 * innecesariamente el cursor.
 */
                if (!editandoTextoOrigen) {

                    mostrarTextoEntrada(
                        textoOrigen
                    )
                }

                mostrarTextoTraduccion(
                    textoDestino
                )

                binding.textoEstado.text =
                    textoApp(
                        R.string.estado_traduccion_lista
                    )

                /*
                 * La traducción originada por voz se reproduce automáticamente.
                 *
                 * La traducción de texto pegado solamente se muestra,
                 * siguiendo un comportamiento más natural para entrada escrita.
                 */
                if (reproducirVoz) {

                    reproducirTraduccion(textoDestino)

                } else {

                    binding.buttonEscuchar.isEnabled = true
                    binding.buttonIntercambiarIdiomas.isEnabled = true
                    binding.buttonDetectar.isEnabled =
                        true
                }
            }
            .addOnFailureListener { excepcion ->

                if (
                    versionEstaSolicitud !=
                    versionSolicitudTraduccion
                ) {

                    return@addOnFailureListener
                }

                binding.textoEstado.text =
                    textoApp(
                        R.string.traduccion_error,
                        excepcion.message ?: ""
                    )

                binding.buttonEscuchar.isEnabled = true
                binding.buttonIntercambiarIdiomas.isEnabled = true
                binding.buttonDetectar.isEnabled =
                    true
            }
    }

    private fun prepararTtsDestino() {

        binding.buttonEscuchar.isEnabled = false
        binding.buttonIntercambiarIdiomas.isEnabled = false

        binding.textoEstado.text =
            textoApp(
                R.string.tts_preparando_voz,
                nombreIdioma(idiomaDestino)
            )

        /*
         * Si todavía no existe el motor TTS, lo creamos.
         *
         * Su inicialización terminará posteriormente en onInit().
         */
        if (textoAVoz == null) {

            ttsListo = false
            textoAVoz = TextToSpeech(this, this)

            return
        }

        /*
         * Si TextToSpeech ya existe, no necesitamos crear otro.
         *
         * Solamente cambiamos el idioma que debe pronunciar.
         */
        configurarIdiomaTtsDestino()
    }

    private fun actualizarTextosBotones() {

        binding.buttonTerminarEdicion.contentDescription =
            textoApp(
                R.string.accion_finalizar_edicion
            )

        binding.textoEntrada.hint =
            textoApp(
                R.string.placeholder_texto_reconocido
            )

        binding.buttonDetectar.text =
            textoApp(
                R.string.accion_detectar
            )

        binding.buttonMas.text =
            textoApp(
                R.string.accion_mas
            )

        binding.buttonPegar.contentDescription =
            textoApp(
                R.string.accion_pegar
            )

        binding.buttonCopiar.contentDescription =
            textoApp(
                R.string.accion_copiar
            )

        binding.buttonLimpiarOrigen.contentDescription =
            textoApp(
                R.string.accion_limpiar
            )

        binding.buttonOpciones.contentDescription =
            textoApp(
                R.string.accion_opciones
            )

        actualizarBotonAudioTraduccion()
    }

    private fun obtenerIdiomaPorCodigo(
        codigo: String?
    ): Idioma? {

        if (codigo.isNullOrBlank()) {
            return null
        }

        return CatalogoIdiomas.TODOS
            .firstOrNull { idioma ->

                idioma.codigoMlKit ==
                        codigo
            }
    }

    private fun codificarHistorialIdiomas(
        historial: List<Idioma>
    ): String {

        return historial
            .distinct()
            .joinToString(
                separator = "|"
            ) { idioma ->

                idioma.codigoMlKit
            }
    }

    private fun decodificarHistorialIdiomas(
        valorGuardado: String?,
        limite: Int
    ): MutableList<Idioma> {

        if (valorGuardado.isNullOrBlank()) {

            return mutableListOf()
        }

        return valorGuardado
            .split("|")
            .mapNotNull { codigo ->

                obtenerIdiomaPorCodigo(
                    codigo
                )
            }
            .distinct()
            .take(
                limite
            )
            .toMutableList()
    }

    private fun guardarEstadoIdiomas() {

        preferenciasIdiomas
            .edit()
            .putString(
                "idioma_origen",
                idiomaOrigen.codigoMlKit
            )
            .putString(
                "idioma_destino",
                idiomaDestino.codigoMlKit
            )
            .putString(
                "historial_origen",
                codificarHistorialIdiomas(
                    historialIdiomasOrigen
                )
            )
            .putString(
                "historial_destino",
                codificarHistorialIdiomas(
                    historialIdiomasDestino
                )
            )
            .putBoolean(
                "modo_detectar",
                modoDeteccionAutomaticaActivo
            )
            .apply()
    }

    private fun restaurarEstadoIdiomas() {

        /*
         * Primera ejecución:
         *
         * si todavía no existe estado guardado,
         * conservamos los valores declarados en las variables:
         *
         * Español → Inglés
         */
        val origenGuardado =
            obtenerIdiomaPorCodigo(
                preferenciasIdiomas
                    .getString(
                        "idioma_origen",
                        null
                    )
            )

        val destinoGuardado =
            obtenerIdiomaPorCodigo(
                preferenciasIdiomas
                    .getString(
                        "idioma_destino",
                        null
                    )
            )

        if (origenGuardado != null) {

            idiomaOrigen =
                origenGuardado
        }

        if (
            destinoGuardado != null &&
            destinoGuardado != idiomaOrigen
        ) {

            idiomaDestino =
                destinoGuardado
        }

        /*
         * Protección ante preferencias antiguas, dañadas
         * o modificadas por una futura versión.
         *
         * Origen y destino nunca pueden ser iguales.
         */
        if (idiomaOrigen == idiomaDestino) {

            idiomaDestino =
                CatalogoIdiomas.TODOS
                    .firstOrNull { idioma ->

                        idioma != idiomaOrigen
                    }
                    ?: CatalogoIdiomas.INGLES
        }

        val historialOrigenGuardado =
            decodificarHistorialIdiomas(
                valorGuardado =
                    preferenciasIdiomas.getString(
                        "historial_origen",
                        null
                    ),

                limite =
                    maximoHistorialOrigen
            )

        val historialDestinoGuardado =
            decodificarHistorialIdiomas(
                valorGuardado =
                    preferenciasIdiomas.getString(
                        "historial_destino",
                        null
                    ),

                limite =
                    maximoHistorialDestino
            )

        historialIdiomasOrigen.clear()

        historialIdiomasDestino.clear()

        /*
         * El idioma actualmente activo siempre debe ocupar
         * la primera posición, incluso si el historial
         * guardado estuviera incompleto.
         */
        historialIdiomasOrigen.add(
            idiomaOrigen
        )

        historialOrigenGuardado
            .filter { idioma ->

                idioma != idiomaOrigen
            }
            .forEach { idioma ->

                if (
                    historialIdiomasOrigen.size <
                    maximoHistorialOrigen
                ) {

                    historialIdiomasOrigen.add(
                        idioma
                    )
                }
            }

        historialIdiomasDestino.add(
            idiomaDestino
        )

        historialDestinoGuardado
            .filter { idioma ->

                idioma != idiomaDestino
            }
            .forEach { idioma ->

                if (
                    historialIdiomasDestino.size <
                    maximoHistorialDestino
                ) {

                    historialIdiomasDestino.add(
                        idioma
                    )
                }
            }

        /*
         * Detectar solamente tiene sentido desde API 33,
         * porque nuestra implementación utiliza
         * EXTRA_AUDIO_SOURCE.
         *
         * Así evitamos restaurarlo accidentalmente
         * como activo en una versión incompatible.
         */
        modoDeteccionAutomaticaActivo =
            Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU &&
                    preferenciasIdiomas.getBoolean(
                        "modo_detectar",
                        false
                    )

        /*
         * Esta variable NO representa un modo persistente.
         *
         * Significa que la pulsación actual del micrófono
         * debe comenzar una captura para Detectar.
         *
         * Por eso al arrancar siempre comienza en false.
         */
        deteccionAutomaticaSolicitada =
            false
    }

    private fun inicializarHistorialIdiomas() {

        historialIdiomasOrigen.clear()

        historialIdiomasDestino.clear()

        historialIdiomasOrigen.add(
            idiomaOrigen
        )

        historialIdiomasDestino.add(
            idiomaDestino
        )
    }

    private fun registrarIdiomaReciente(
        historial: MutableList<Idioma>,
        idioma: Idioma,
        limite: Int
    ) {

        /*
         * Evitamos duplicados.
         *
         * Si ya existía, lo retiramos y vuelve a entrar
         * en la primera posición.
         */
        historial.remove(
            idioma
        )

        historial.add(
            0,
            idioma
        )

        while (
            historial.size >
            limite
        ) {

            historial.removeAt(
                historial.lastIndex
            )
        }
    }

    private fun obtenerColorTema(
        atributo: Int
    ): Int {

        val valor =
            TypedValue()

        theme.resolveAttribute(
            atributo,
            valor,
            true
        )

        return if (
            valor.resourceId != 0
        ) {

            ContextCompat.getColor(
                this,
                valor.resourceId
            )

        } else {

            valor.data
        }
    }

    private fun aplicarEstiloOpcionIdioma(
        vista: TextView,
        seleccionado: Boolean
    ) {

        val colorAcento =
            obtenerColorTema(
                android.R.attr.colorAccent
            )

        val colorTextoNormal =
            obtenerColorTema(
                android.R.attr.textColorPrimary
            )

        val colorTextoSeleccionado =
            obtenerColorTema(
                android.R.attr.textColorPrimaryInverse
            )

        val fondo =
            GradientDrawable().apply {

                cornerRadius =
                    dp(15).toFloat()

                if (seleccionado) {

                    setColor(
                        colorAcento
                    )

                } else {

                    setColor(
                        android.graphics.Color.TRANSPARENT
                    )
                }

                setStroke(
                    dp(1),
                    colorAcento
                )
            }

        vista.background =
            fondo

        vista.setTextColor(
            if (seleccionado) {

                colorTextoSeleccionado

            } else {

                colorTextoNormal
            }
        )

        vista.setTypeface(
            vista.typeface,
            if (seleccionado) {

                android.graphics.Typeface.BOLD

            } else {

                android.graphics.Typeface.NORMAL
            }
        )

        vista.alpha =
            if (seleccionado) {

                1.0f

            } else {

                0.82f
            }
    }

    private fun crearOpcionIdiomaHistorial(
        idioma: Idioma,
        seleccionarOrigen: Boolean
    ): TextView {

        return TextView(
            this
        ).apply {

            text =
                nombreIdioma(
                    idioma
                )

            textSize =
                13f

            gravity =
                android.view.Gravity.CENTER

            isSingleLine =
                true

            isClickable =
                true

            isFocusable =
                true

            setPadding(
                dp(12),
                0,
                dp(12),
                0
            )

            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(30)
                ).apply {

                    marginStart =
                        dp(2)

                    marginEnd =
                        dp(2)
                }

            /*
             * Los elementos dinámicos siempre representan
             * idiomas NO seleccionados actualmente.
             */
            aplicarEstiloOpcionIdioma(
                this,
                seleccionado =
                    false
            )

            setOnClickListener {

                seleccionarIdioma(
                    seleccionarOrigen =
                        seleccionarOrigen,

                    nuevoIdioma =
                        idioma
                )
            }
        }
    }

    private fun actualizarHistorialVisualOrigen() {

        /*
         * Hijos fijos definidos en XML:
         *
         * índice 0 -> Detectar
         * índice 1 -> idiomaOrigen actual
         *
         * Todo lo situado después es historial dinámico.
         */
        while (
            binding.contenedorIdiomasOrigen.childCount >
            2
        ) {

            binding.contenedorIdiomasOrigen.removeViewAt(
                2
            )
        }

        binding.buttonDetectar.text =
            textoApp(
                R.string.accion_detectar
            )

        aplicarEstiloOpcionIdioma(
            binding.buttonDetectar,
            seleccionado =
                modoDeteccionAutomaticaActivo
        )

        binding.textoIdiomaOrigen.text =
            nombreIdioma(
                idiomaOrigen
            )

        aplicarEstiloOpcionIdioma(
            binding.textoIdiomaOrigen,
            seleccionado =
                !modoDeteccionAutomaticaActivo
        )

        /*
         * idiomaOrigen ya aparece como botón fijo.
         *
         * No lo repetimos dentro de los recientes.
         */
        historialIdiomasOrigen
            .filter { idioma ->

                idioma !=
                        idiomaOrigen
            }
            .take(
                maximoHistorialOrigen - 1
            )
            .forEach { idioma ->

                binding.contenedorIdiomasOrigen.addView(
                    crearOpcionIdiomaHistorial(
                        idioma =
                            idioma,

                        seleccionarOrigen =
                            true
                    )
                )
            }
    }

    private fun actualizarHistorialVisualDestino() {

        /*
         * índice 0:
         * idiomaDestino actual definido en XML.
         *
         * Los demás son dinámicos.
         */
        while (
            binding.contenedorIdiomasDestino.childCount >
            1
        ) {

            binding.contenedorIdiomasDestino.removeViewAt(
                1
            )
        }

        binding.textoIdiomaDestino.text =
            nombreIdioma(
                idiomaDestino
            )

        aplicarEstiloOpcionIdioma(
            binding.textoIdiomaDestino,
            seleccionado =
                true
        )

        historialIdiomasDestino
            .filter { idioma ->

                idioma !=
                        idiomaDestino
            }
            .take(
                maximoHistorialDestino - 1
            )
            .forEach { idioma ->

                binding.contenedorIdiomasDestino.addView(
                    crearOpcionIdiomaHistorial(
                        idioma =
                            idioma,

                        seleccionarOrigen =
                            false
                    )
                )
            }
    }

    private fun actualizarSelectorIdiomas() {

        /*
         * Primero actualizamos los textos generales porque
         * textoApp() depende del idioma origen actual.
         */
        actualizarTextosBotones()

        /*
         * Después reconstruimos visualmente las dos filas.
         */
        actualizarHistorialVisualOrigen()

        actualizarHistorialVisualDestino()

        /*
         * Cualquier operación que cambie:
         *
         * - origen;
         * - destino;
         * - historial;
         * - modo Detectar;
         *
         * termina pasando por esta función.
         *
         * Por eso centralizamos aquí la persistencia.
         */
        guardarEstadoIdiomas()
    }

    private fun nombreIdioma(
        idioma: Idioma
    ): String {

        return textoApp(
            idioma.nombreResId
        )
    }

    private fun nombreModeloVosk(
        modeloVosk: ModeloVosk
    ): String {

        return textoApp(
            modeloVosk.nombreResId
        )
    }

    private fun mostrarPlaceholderTextoEntrada() {

        /*
         * El placeholder ya no forma parte del contenido.
         *
         * EditText.hint muestra el texto únicamente cuando
         * el cuadro está realmente vacío.
         */
        actualizandoTextoOrigenProgramaticamente =
            true

        binding.textoEntrada.text.clear()

        actualizandoTextoOrigenProgramaticamente =
            false

        binding.textoEntrada.hint =
            textoApp(
                R.string.placeholder_texto_reconocido
            )

        binding.textoEntrada.alpha =
            1.0f
    }

    private fun mostrarTextoEntrada(
        texto: String
    ) {

        actualizandoTextoOrigenProgramaticamente =
            true

        binding.textoEntrada.setText(
            texto
        )

        actualizandoTextoOrigenProgramaticamente =
            false

        /*
         * El hint puede permanecer configurado.
         *
         * Android lo oculta automáticamente mientras
         * exista texto real.
         */
        binding.textoEntrada.hint =
            textoApp(
                R.string.placeholder_texto_reconocido
            )

        binding.textoEntrada.alpha =
            1.0f
    }

    private fun mostrarPlaceholderTextoTraduccion() {

        ultimaTraduccion = ""

        binding.textoTraduccion.text =
            textoApp(
                R.string.placeholder_traduccion
            )

        binding.textoTraduccion.alpha = 0.45f

        // Todavía no existe una traducción real.
        binding.buttonCopiar.isEnabled = false
        binding.buttonEscucharTraduccion.isEnabled = false
    }

    private fun mostrarTextoTraduccion(texto: String) {

        ultimaTraduccion = texto

        binding.textoTraduccion.text = texto
        binding.textoTraduccion.alpha = 1.0f

        // Una traducción real puede copiarse o escucharse.
        binding.buttonCopiar.isEnabled = true
        binding.buttonEscucharTraduccion.isEnabled = true
    }

    private fun liberarReconocimientoVosk() {

        /*
         * Si todavía hubiera una escucha activa, la cancelamos.
         */
        servicioVoz?.cancel()

        /*
         * SpeechService posee recursos relacionados
         * con la captura y procesamiento de audio.
         */
        servicioVoz?.shutdown()
        servicioVoz = null

        /*
         * El Recognizer pertenece al modelo anterior.
         * No debemos reutilizarlo con el nuevo idioma.
         */
        reconocedor?.close()
        reconocedor = null

        /*
         * Liberamos también el modelo Vosk anterior.
         */
        modelo?.close()
        modelo = null

        escuchando = false
        textoConfirmado = ""
    }

    private fun intercambiarIdiomas() {

        /*
 * ⇄ representa una elección manual explícita
 * de la dirección.
 */
        modoDeteccionAutomaticaActivo =
            false

        deteccionAutomaticaSolicitada =
            false

        /*
         * Guardamos la traducción ANTES de limpiar la interfaz.
         */
        val textoQuePasaAlOrigen =
            ultimaTraduccion.trim()

        binding.buttonEscuchar.isEnabled =
            false

        binding.buttonIntercambiarIdiomas.isEnabled =
            false

        binding.textoEstado.text =
            textoApp(
                R.string.estado_cambiando_idioma
            )

        textoAVoz?.stop()

        ttsListo =
            false

        liberarReconocimientoVosk()

        traductorActual?.close()
        traductorActual =
            null

        modeloTraduccionListo =
            false

        /*
         * Intercambiamos origen y destino.
         */
        val idiomaTemporal =
            idiomaOrigen

        idiomaOrigen =
            idiomaDestino

        idiomaDestino =
            idiomaTemporal

        registrarIdiomaReciente(
            historial =
                historialIdiomasOrigen,

            idioma =
                idiomaOrigen,

            limite =
                maximoHistorialOrigen
        )

        registrarIdiomaReciente(
            historial =
                historialIdiomasDestino,

            idioma =
                idiomaDestino,

            limite =
                maximoHistorialDestino
        )

        actualizarSelectorIdiomas()

        /*
         * Conservamos la traducción anterior para procesarla
         * cuando el NUEVO traductor esté preparado.
         */
        textoPendienteTrasIntercambio =
            if (textoQuePasaAlOrigen.isNotBlank()) {

                textoQuePasaAlOrigen

            } else {

                null
            }

        /*
 * Preparamos independientemente:
 *
 * - la nueva dirección de traducción;
 * - el reconocimiento correspondiente al nuevo origen.
 */
        prepararFlujoActual()

        /*
         * Aunque la traducción todavía no pueda comenzar,
         * mostramos inmediatamente el texto que pasó al origen.
         */
        if (textoQuePasaAlOrigen.isNotBlank()) {

            textoConfirmado =
                textoQuePasaAlOrigen

            mostrarTextoEntrada(
                textoQuePasaAlOrigen
            )
        }
    }

    private fun configurarIdiomaTtsDestino() {

        val tts = textoAVoz

        if (tts == null) {

            ttsListo = false

            binding.textoEstado.text =
                textoApp(
                    R.string.tts_motor_no_disponible
                )

            binding.buttonEscuchar.isEnabled = false
            binding.buttonIntercambiarIdiomas.isEnabled = true

            return
        }

        /*
         * El TTS siempre habla en el idioma DESTINO.
         *
         * Español → Inglés
         *            ↑
         *           TTS
         *
         * Inglés → Español
         *            ↑
         *           TTS
         */
        val resultadoIdioma =
            tts.setLanguage(idiomaDestino.localeTts)

        if (
            resultadoIdioma == TextToSpeech.LANG_MISSING_DATA ||
            resultadoIdioma == TextToSpeech.LANG_NOT_SUPPORTED
        ) {

            ttsListo = false

            binding.textoEstado.text =
                textoApp(
                    R.string.tts_voz_no_disponible,
                    nombreIdioma(idiomaDestino)
                )

            binding.buttonEscuchar.isEnabled = false
            binding.buttonIntercambiarIdiomas.isEnabled = true

            return
        }

        configurarEventosTts()

        ttsListo = true

        binding.textoEstado.text =
            textoApp(
                R.string.estado_listo
            )

        actualizarControlesSegunPreparacion()

        /*
         * Si llegamos aquí después de pulsar ⇄,
         * ahora sí existe toda la infraestructura de la
         * nueva dirección y podemos retraducir.
         */
        procesarTextoPendienteTrasIntercambio()

        procesarTextoPendienteDeteccionAutomatica()

        procesarTextoPendienteDeteccionEscrita()

        procesarTextoPendienteEntradaManual()
    }

    private fun procesarTextoPendienteTrasIntercambio() {

        val textoPendiente =
            textoPendienteTrasIntercambio
                ?.trim()

        if (textoPendiente.isNullOrBlank()) {
            return
        }

        /*
         * Lo eliminamos ANTES de traducir.
         *
         * De esa manera una llamada posterior no puede
         * traducir accidentalmente el mismo texto dos veces.
         */
        textoPendienteTrasIntercambio =
            null

        textoConfirmado =
            textoPendiente

        mostrarTextoEntrada(
            textoPendiente
        )

        /*
         * La traducción ocurre automáticamente, pero no se
         * reproduce por voz solo por haber pulsado ⇄.
         */
        traducirTexto(
            textoOrigen = textoPendiente,
            reproducirVoz = false
        )
    }

    private fun procesarTextoPendienteEntradaManual() {

        val textoPendiente =
            textoPendienteEntradaManual
                ?.trim()

        if (textoPendiente.isNullOrBlank()) {

            return
        }

        if (
            !modeloTraduccionListo ||
            !ttsListo
        ) {

            return
        }

        textoPendienteEntradaManual =
            null

        textoConfirmado =
            textoPendiente

        mostrarPlaceholderTextoTraduccion()

        traducirTexto(
            textoOrigen =
                textoPendiente,

            reproducirVoz =
                false
        )
    }

    private fun procesarTextoPendienteDeteccionAutomatica() {

        val textoPendiente =
            textoPendienteDeteccionAutomatica
                ?.trim()

        if (textoPendiente.isNullOrBlank()) {

            return
        }

        /*
         * Esta función se llama cuando Translator + TTS
         * ya terminaron de prepararse.
         */
        if (
            !modeloTraduccionListo ||
            !ttsListo
        ) {

            return
        }

        /*
         * Lo eliminamos ANTES de comenzar la traducción
         * para impedir procesarlo accidentalmente dos veces.
         */
        textoPendienteDeteccionAutomatica =
            null

        textoConfirmado =
            textoPendiente

        mostrarTextoEntrada(
            textoPendiente
        )

        mostrarPlaceholderTextoTraduccion()

        traducirTexto(
            textoOrigen =
                textoPendiente,

            reproducirVoz =
                true
        )
    }

    private fun procesarTextoPendienteDeteccionEscrita() {

        val textoPendiente =
            textoPendienteDeteccionEscrita
                ?.trim()

        if (textoPendiente.isNullOrBlank()) {

            return
        }

        if (
            !modeloTraduccionListo ||
            !ttsListo
        ) {

            return
        }

        /*
         * Lo retiramos antes de traducir para evitar
         * ejecutar la misma petición más de una vez.
         */
        textoPendienteDeteccionEscrita =
            null

        textoConfirmado =
            textoPendiente

        traducirTexto(
            textoOrigen =
                textoPendiente,

            reproducirVoz =
                false
        )
    }

    private fun registrarReceptorDescargaModelo() {

        if (receptorDescargaRegistrado) {
            return
        }

        val filtro =
            IntentFilter(
                DownloadManager.ACTION_DOWNLOAD_COMPLETE
            )

        /*
         * El aviso proviene del servicio de descargas del sistema.
         *
         * Validamos además el ID recibido antes de procesarlo.
         */
        ContextCompat.registerReceiver(
            this,
            receptorDescargaModelo,
            filtro,
            ContextCompat.RECEIVER_EXPORTED
        )

        receptorDescargaRegistrado = true
    }

    private fun obtenerTipoRedDescarga():
            TipoRedDescarga {

        val gestorConectividad =
            getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val redActiva =
            gestorConectividad.activeNetwork
                ?: return TipoRedDescarga.SIN_CONEXION

        val capacidades =
            gestorConectividad
                .getNetworkCapabilities(
                    redActiva
                )
                ?: return TipoRedDescarga.SIN_CONEXION

        return when {

            capacidades.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI
            ) -> {

                TipoRedDescarga.WIFI
            }

            capacidades.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR
            ) -> {

                TipoRedDescarga.DATOS_MOVILES
            }

            capacidades.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            ) -> {

                /*
                 * Por ejemplo Ethernet u otra conexión
                 * que Android considere capaz de acceder
                 * a Internet.
                 */
                TipoRedDescarga.OTRA
            }

            else -> {

                TipoRedDescarga.SIN_CONEXION
            }
        }
    }

    private fun mostrarConfirmacionDescarga(
        modeloVosk: ModeloVosk
    ) {

        val tipoRed =
            obtenerTipoRedDescarga()

        /*
         * No lanzamos una petición a DownloadManager
         * si actualmente no existe una conexión utilizable.
         */
        if (
            tipoRed ==
            TipoRedDescarga.SIN_CONEXION
        ) {

            AlertDialog.Builder(this)
                .setTitle(
                    nombreModeloVosk(
                        modeloVosk
                    )
                )
                .setMessage(
                    textoApp(
                        R.string.descarga_sin_conexion
                    )
                )
                .setPositiveButton(
                    textoApp(
                        R.string.accion_cerrar
                    ),
                    null
                )
                .show()

            return
        }

        val usaDatosMoviles =
            tipoRed ==
                    TipoRedDescarga.DATOS_MOVILES

        val mensaje =
            if (usaDatosMoviles) {

                textoApp(
                    R.string.descarga_advertencia_datos_moviles,
                    modeloVosk.tamanoVisible
                )

            } else {

                textoApp(
                    R.string.descarga_confirmacion_red,
                    modeloVosk.tamanoVisible
                )
            }

        AlertDialog.Builder(this)
            .setTitle(
                nombreModeloVosk(
                    modeloVosk
                )
            )
            .setMessage(
                mensaje
            )
            .setNegativeButton(
                textoApp(
                    R.string.accion_cancelar
                ),
                null
            )
            .setPositiveButton(
                textoApp(
                    R.string.accion_descargar
                )
            ) { _, _ ->

                iniciarDescargaModelo(
                    modeloVosk =
                        modeloVosk,

                    permitirDatosMoviles =
                        usaDatosMoviles
                )
            }
            .show()
    }

    private fun iniciarDescargaModelo(
        modeloVosk: ModeloVosk,
        permitirDatosMoviles: Boolean
    ) {

        if (idDescargaModelo != -1L) {

            binding.textoEstado.text =
                textoApp(
                    R.string.descarga_ya_en_curso
                )

            return
        }

        val url =
            modeloVosk.urlDescarga

        if (url == null) {

            binding.textoEstado.text =
                textoApp(
                    R.string.descarga_sin_url
                )

            return
        }

        /*
         * Carpeta privada de la aplicación utilizada temporalmente
         * para almacenar el ZIP descargado.
         */
        val carpetaDescargasBase =
            getExternalFilesDir(
                Environment.DIRECTORY_DOWNLOADS
            )

        if (carpetaDescargasBase == null) {

            binding.textoEstado.text =
                textoApp(
                    R.string.descarga_almacenamiento_no_disponible
                )

            return
        }

        val carpetaDescargas =
            File(
                carpetaDescargasBase,
                "modelos_vosk"
            )

        if (
            !carpetaDescargas.exists() &&
            !carpetaDescargas.mkdirs()
        ) {

            binding.textoEstado.text =
                textoApp(
                    R.string.descarga_error_crear_carpeta
                )

            return
        }

        /*
         * Si existe un ZIP anterior incompleto o de una prueba previa,
         * lo eliminamos antes de pedir una nueva descarga.
         */
        val archivoZip =
            File(
                carpetaDescargas,
                "${modeloVosk.id}.zip"
            )

        if (archivoZip.exists()) {

            archivoZip.delete()
        }

        val solicitud =
            DownloadManager.Request(
                Uri.parse(url)
            )
                .setTitle(
                    textoApp(
                        R.string.descarga_notificacion_titulo,
                        nombreModeloVosk(modeloVosk)
                    )
                )
                .setDescription(
                    textoApp(
                        R.string.descarga_notificacion_descripcion
                    )
                )
                .setMimeType(
                    "application/zip"
                )
                .setNotificationVisibility(
                    DownloadManager.Request
                        .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(
                    this,
                    Environment.DIRECTORY_DOWNLOADS,
                    "modelos_vosk/${modeloVosk.id}.zip"
                )

        /*
 * Política de esta descarga concreta.
 *
 * Wi-Fi:
 *     la descarga permanece limitada a Wi-Fi.
 *
 * Datos móviles:
 *     solamente se permiten porque el usuario acaba
 *     de aceptar explícitamente la advertencia.
 */
        if (permitirDatosMoviles) {

            solicitud.setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or
                        DownloadManager.Request.NETWORK_MOBILE
            )

            solicitud.setAllowedOverMetered(
                true
            )

        } else {

            solicitud.setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI
            )

            solicitud.setAllowedOverMetered(
                false
            )
        }

        try {

            val gestorDescargas =
                getSystemService(
                    Context.DOWNLOAD_SERVICE
                ) as DownloadManager

            val idDescarga =
                gestorDescargas.enqueue(
                    solicitud
                )

            idDescargaModelo =
                idDescarga

            modeloEnDescarga =
                modeloVosk

            /*
             * Persistimos ambos datos porque DownloadManager puede
             * seguir trabajando incluso si cerramos esta Activity.
             */
            preferenciasDescargas
                .edit()
                .putLong(
                    "id_descarga",
                    idDescarga
                )
                .putString(
                    "modelo_id",
                    modeloVosk.id
                )
                .apply()

            binding.textoEstado.text =
                if (permitirDatosMoviles) {

                    textoApp(
                        R.string.descarga_iniciada_datos
                    )

                } else {

                    textoApp(
                        R.string.descarga_iniciada_wifi
                    )
                }

        } catch (excepcion: Exception) {

            idDescargaModelo = -1L
            modeloEnDescarga = null

            binding.textoEstado.text =
                textoApp(
                    R.string.descarga_error_inicio,
                    excepcion.message ?: ""
                )
        }
    }

    private fun consultarEstadoDescarga(
        idDescarga: Long
    ): Int? {

        val gestorDescargas =
            getSystemService(
                Context.DOWNLOAD_SERVICE
            ) as DownloadManager

        val consulta =
            DownloadManager.Query()
                .setFilterById(
                    idDescarga
                )

        val cursor =
            gestorDescargas.query(
                consulta
            )

        cursor.use {

            if (!it.moveToFirst()) {

                return null
            }

            val columnaEstado =
                it.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_STATUS
                )

            return it.getInt(
                columnaEstado
            )
        }
    }

    private fun procesarDescargaFinalizada(
        idDescarga: Long
    ) {

        val estado =
            consultarEstadoDescarga(
                idDescarga
            )

        when (estado) {

            DownloadManager.STATUS_SUCCESSFUL -> {

                val modelo =
                    modeloEnDescarga
                        ?: recuperarModeloDescargaGuardado()

                if (modelo == null) {

                    limpiarDescargaPendiente()

                    binding.textoEstado.text =
                        textoApp(
                            R.string.descarga_modelo_no_identificado
                        )

                    return
                }

                instalarModeloDescargadoEnSegundoPlano(
                    modelo
                )
            }

            DownloadManager.STATUS_FAILED -> {

                limpiarDescargaPendiente()

                binding.textoEstado.text =
                    textoApp(
                        R.string.descarga_fallo
                    )
            }

            else -> {

                /*
                 * Si todavía está pendiente, pausada o ejecutándose,
                 * DownloadManager continuará administrándola.
                 */
            }
        }
    }

    private fun recuperarModeloDescargaGuardado():
            ModeloVosk? {

        val modeloId =
            preferenciasDescargas.getString(
                "modelo_id",
                null
            )

        if (modeloId == null) {
            return null
        }

        return CatalogoModelosVosk.TODOS
            .firstOrNull { modeloVosk ->

                modeloVosk.id == modeloId
            }
    }

    private fun recuperarDescargaPendiente() {

        val idGuardado =
            preferenciasDescargas.getLong(
                "id_descarga",
                -1L
            )

        if (idGuardado == -1L) {
            return
        }

        val modeloGuardado =
            recuperarModeloDescargaGuardado()

        if (modeloGuardado == null) {

            limpiarDescargaPendiente()
            return
        }

        idDescargaModelo =
            idGuardado

        modeloEnDescarga =
            modeloGuardado

        when (
            consultarEstadoDescarga(
                idGuardado
            )
        ) {

            DownloadManager.STATUS_SUCCESSFUL -> {

                procesarDescargaFinalizada(
                    idGuardado
                )
            }

            DownloadManager.STATUS_FAILED,
            null -> {

                limpiarDescargaPendiente()
            }

            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED,
            DownloadManager.STATUS_RUNNING -> {

                binding.textoEstado.text =
                    textoApp(
                        R.string.descarga_pendiente,
                        nombreModeloVosk(modeloGuardado)
                    )
            }
        }
    }

    private fun limpiarDescargaPendiente() {

        idDescargaModelo = -1L
        modeloEnDescarga = null

        preferenciasDescargas
            .edit()
            .remove(
                "id_descarga"
            )
            .remove(
                "modelo_id"
            )
            .apply()
    }

    private fun instalarModeloDescargadoEnSegundoPlano(
        modeloVosk: ModeloVosk
    ) {

        if (instalacionModeloEnCurso) {
            return
        }

        instalacionModeloEnCurso = true

        binding.textoEstado.text =
            textoApp(
                R.string.modelo_instalando,
                nombreModeloVosk(modeloVosk)
            )

        Thread {

            val resultado =
                try {

                    instalarModeloDesdeZip(
                        modeloVosk
                    )

                    null

                } catch (excepcion: Exception) {

                    excepcion
                }

            runOnUiThread {

                instalacionModeloEnCurso = false

                if (resultado == null) {

                    limpiarDescargaPendiente()

                    binding.textoEstado.text =
                        textoApp(
                            R.string.modelo_instalado_correctamente,
                            nombreModeloVosk(modeloVosk)
                        )

                } else {

                    limpiarDescargaPendiente()

                    binding.textoEstado.text =
                        textoApp(
                            R.string.modelo_error_instalacion,
                            resultado.message ?: ""
                        )
                }
            }

        }.start()
    }

    private fun instalarModeloDesdeZip(
        modeloVosk: ModeloVosk
    ) {

        val carpetaDescargasBase =
            getExternalFilesDir(
                Environment.DIRECTORY_DOWNLOADS
            )
                ?: throw IOException(
                    "No está disponible la carpeta de descargas"
                )

        val archivoZip =
            File(
                carpetaDescargasBase,
                "modelos_vosk/${modeloVosk.id}.zip"
            )

        if (!archivoZip.isFile) {

            throw IOException(
                "No se encontró el archivo descargado"
            )
        }

        /*
         * Ubicación definitiva de modelos descargados.
         */
        val raizModelos =
            File(
                filesDir,
                "modelos_vosk"
            )

        if (
            !raizModelos.exists() &&
            !raizModelos.mkdirs()
        ) {

            throw IOException(
                "No fue posible crear la carpeta de modelos"
            )
        }

        /*
         * Descomprimimos primero en una carpeta temporal.
         *
         * De esta manera una instalación interrumpida nunca se
         * confunde con un modelo correctamente instalado.
         */
        val carpetaTemporal =
            File(
                filesDir,
                "modelos_vosk_temporales/${modeloVosk.id}"
            )

        if (carpetaTemporal.exists()) {

            carpetaTemporal.deleteRecursively()
        }

        if (!carpetaTemporal.mkdirs()) {

            throw IOException(
                "No fue posible crear la carpeta temporal"
            )
        }

        val prefijoModelo =
            "${modeloVosk.carpetaAssets}/"

        ZipInputStream(
            BufferedInputStream(
                FileInputStream(
                    archivoZip
                )
            )
        ).use { zip ->

            var entrada =
                zip.nextEntry

            while (entrada != null) {

                val nombreEntrada =
                    entrada.name
                        .replace(
                            '\\',
                            '/'
                        )

                /*
                 * El ZIP oficial contiene una carpeta raíz:
                 *
                 * vosk-model-small-fr-0.22/
                 *
                 * La quitamos porque carpetaTemporal ya representa
                 * directamente ese modelo.
                 */
                val rutaRelativa =
                    when {

                        nombreEntrada ==
                                modeloVosk.carpetaAssets -> {

                            ""
                        }

                        nombreEntrada ==
                                prefijoModelo -> {

                            ""
                        }

                        nombreEntrada.startsWith(
                            prefijoModelo
                        ) -> {

                            nombreEntrada.removePrefix(
                                prefijoModelo
                            )
                        }

                        else -> {

                            throw IOException(
                                "El ZIP contiene una estructura inesperada"
                            )
                        }
                    }

                if (rutaRelativa.isNotBlank()) {

                    val archivoDestino =
                        File(
                            carpetaTemporal,
                            rutaRelativa
                        )

                    /*
                     * Protección contra Zip Slip:
                     * una entrada del ZIP nunca puede escribir fuera
                     * de nuestra carpeta temporal.
                     */
                    val rutaRaiz =
                        carpetaTemporal
                            .canonicalFile
                            .path +
                                File.separator

                    val rutaDestino =
                        archivoDestino
                            .canonicalFile
                            .path

                    if (
                        !rutaDestino.startsWith(
                            rutaRaiz
                        )
                    ) {

                        throw IOException(
                            "Se detectó una ruta ZIP no segura"
                        )
                    }

                    if (entrada.isDirectory) {

                        if (
                            !archivoDestino.exists() &&
                            !archivoDestino.mkdirs()
                        ) {

                            throw IOException(
                                "No se pudo crear una carpeta del modelo"
                            )
                        }

                    } else {

                        archivoDestino.parentFile
                            ?.mkdirs()

                        FileOutputStream(
                            archivoDestino
                        ).use { salida ->

                            zip.copyTo(
                                salida
                            )
                        }
                    }
                }

                zip.closeEntry()

                entrada =
                    zip.nextEntry
            }
        }

        /*
         * No declaramos el modelo como instalado únicamente
         * porque la descompresión haya terminado.
         *
         * Validamos archivos fundamentales de Vosk.
         */
        val modeloAcustico =
            File(
                carpetaTemporal,
                "am/final.mdl"
            )

        val configuracion =
            File(
                carpetaTemporal,
                "conf/model.conf"
            )

        if (
            !modeloAcustico.isFile ||
            !configuracion.isFile
        ) {

            carpetaTemporal.deleteRecursively()

            throw IOException(
                "El modelo descargado está incompleto"
            )
        }

        val carpetaFinal =
            File(
                raizModelos,
                modeloVosk.carpetaInstalada
            )

        if (carpetaFinal.exists()) {

            carpetaFinal.deleteRecursively()
        }

        /*
         * Como ambas carpetas están dentro del almacenamiento
         * interno de la app, normalmente renameTo() será inmediato.
         */
        val movido =
            carpetaTemporal.renameTo(
                carpetaFinal
            )

        if (!movido) {

            val copiado =
                carpetaTemporal.copyRecursively(
                    target = carpetaFinal,
                    overwrite = true
                )

            if (!copiado) {

                throw IOException(
                    "No se pudo mover el modelo a su ubicación definitiva"
                )
            }

            carpetaTemporal.deleteRecursively()
        }

        /*
         * El ZIP ya no es necesario después de una instalación válida.
         */
        archivoZip.delete()
    }

    private fun activarModoVisualEdicionDirecta() {

        /*
         * Ocultamos los controles que consumen espacio
         * mientras el usuario escribe.
         */
        binding.textoEstado.visibility =
            View.GONE

        binding.buttonEscuchar.visibility =
            View.GONE

        binding.buttonMas.visibility =
            View.GONE

        binding.buttonOpciones.visibility =
            View.GONE

        /*
         * ✓ permanece dentro del panel de origen y permite
         * abandonar explícitamente el modo edición.
         */
        binding.buttonTerminarEdicion.visibility =
            View.VISIBLE
    }

    private fun desactivarModoVisualEdicionDirecta() {

        binding.buttonTerminarEdicion.visibility =
            View.GONE

        binding.textoEstado.visibility =
            View.VISIBLE

        binding.buttonEscuchar.visibility =
            View.VISIBLE

        binding.buttonMas.visibility =
            View.VISIBLE

        binding.buttonOpciones.visibility =
            View.VISIBLE
    }

    private fun iniciarEdicionDirectaTextoOrigen() {

        editandoTextoOrigen =
            true

        activarModoVisualEdicionDirecta()

        binding.textoEntrada.isFocusable =
            true

        binding.textoEntrada.isFocusableInTouchMode =
            true

        binding.textoEntrada.isCursorVisible =
            true

        binding.textoEntrada.requestFocus()

        binding.textoEntrada.setSelection(
            binding.textoEntrada.text.length
        )

        val teclado =
            getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager

        teclado.showSoftInput(
            binding.textoEntrada,
            InputMethodManager.SHOW_IMPLICIT
        )
    }

    private fun programarTraduccionTextoEditado() {

        tareaTraduccionEdicion
            ?.let { tareaAnterior ->

                manejadorEdicionTexto
                    .removeCallbacks(
                        tareaAnterior
                    )
            }

        val versionTexto =
            versionSolicitudTraduccion

        val nuevaTarea =
            Runnable {

                /*
                 * Si desde que programamos esta tarea apareció
                 * otra modificación, esta tarea ya caducó.
                 */
                if (
                    versionTexto !=
                    versionSolicitudTraduccion
                ) {

                    return@Runnable
                }

                traducirTextoEditadoActual()
            }

        tareaTraduccionEdicion =
            nuevaTarea

        manejadorEdicionTexto.postDelayed(
            nuevaTarea,
            retrasoTraduccionEdicionMs
        )
    }

    private fun procesarEntradaTexto(
        texto: String
    ) {

        /*
         * En modo manual conocemos explícitamente
         * el idioma origen.
         */
        if (!modoDeteccionAutomaticaActivo) {

            traducirTexto(
                textoOrigen =
                    texto,

                reproducirVoz =
                    false
            )

            return
        }

        /*
         * Detectar también debe funcionar con texto.
         *
         * ML Kit Language Identification analiza únicamente
         * la cadena. No necesita micrófono ni PCM.
         */
        detectarIdiomaDeTexto(
            texto
        )
    }

    private fun traducirTextoEditadoActual() {

        if (!editandoTextoOrigen) {
            return
        }

        val texto =
            binding.textoEntrada.text
                .toString()
                .trim()

        if (texto.isBlank()) {

            ultimoTextoEditadoTraducido =
                ""

            textoConfirmado =
                ""

            mostrarPlaceholderTextoTraduccion()

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_listo
                )

            return
        }

        /*
         * No repetimos exactamente la misma petición.
         */
        if (
            texto ==
            ultimoTextoEditadoTraducido
        ) {

            return
        }

        ultimoTextoEditadoTraducido =
            texto

        textoConfirmado =
            texto

        mostrarPlaceholderTextoTraduccion()

        procesarEntradaTexto(
            texto
        )
    }

    private fun traducirEdicionPendienteInmediatamente() {

        tareaTraduccionEdicion
            ?.let { tarea ->

                manejadorEdicionTexto
                    .removeCallbacks(
                        tarea
                    )
            }

        tareaTraduccionEdicion =
            null

        traducirTextoEditadoActual()
    }

    private fun cerrarTecladoEdicion() {

        val teclado =
            getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager

        teclado.hideSoftInputFromWindow(
            binding.textoEntrada.windowToken,
            0
        )

        binding.textoEntrada.clearFocus()

        binding.textoEntrada.isCursorVisible =
            false

        /*
         * El cuadro sigue siendo editable.
         *
         * Al tocarlo otra vez recuperará inmediatamente
         * foco y cursor.
         */
        editandoTextoOrigen =
            false

        desactivarModoVisualEdicionDirecta()
    }

    private fun limpiarTextoOrigen() {

        /*
         * No modificamos la entrada mientras otra operación
         * importante está cambiando el flujo.
         */
        if (
            !binding.buttonIntercambiarIdiomas.isEnabled
        ) {

            binding.textoEstado.text =
                textoApp(
                    R.string.estado_esperar
                )

            return
        }

        /*
         * Recordamos si el usuario estaba editando.
         *
         * Limpiar NO debe significar "terminar edición".
         */
        val estabaEditando =
            editandoTextoOrigen

        /*
         * Cancelamos cualquier traducción programada por
         * el debounce de 1.5 segundos.
         */
        tareaTraduccionEdicion
            ?.let { tarea ->

                manejadorEdicionTexto
                    .removeCallbacks(
                        tarea
                    )
            }

        tareaTraduccionEdicion =
            null

        /*
         * Invalida cualquier traducción asíncrona anterior
         * que todavía pudiera terminar después de limpiar.
         */
        versionSolicitudTraduccion++

        /*
         * Ya no existe texto válido asociado a ninguno de
         * los flujos pendientes.
         */
        textoConfirmado =
            ""

        ultimoTextoEditadoTraducido =
            ""

        textoPendienteTrasIntercambio =
            null

        textoPendienteDeteccionAutomatica =
            null

        textoPendienteDeteccionEscrita =
            null

        textoPendienteEntradaManual =
            null

        /*
         * Dejamos realmente vacío el EditText.
         *
         * El texto gris es ahora un hint, no contenido real.
         */
        mostrarPlaceholderTextoEntrada()

        /*
         * La traducción anterior también deja de ser válida.
         *
         * Esta función además limpia ultimaTraduccion y
         * deshabilita Copiar / Escuchar.
         */
        mostrarPlaceholderTextoTraduccion()

        /*
         * CASO 1:
         *
         * El usuario pulsó Limpiar mientras escribía.
         *
         * Conservamos completamente el modo edición.
         */
        if (estabaEditando) {

            editandoTextoOrigen =
                true

            activarModoVisualEdicionDirecta()

            binding.textoEntrada.isFocusable =
                true

            binding.textoEntrada.isFocusableInTouchMode =
                true

            binding.textoEntrada.isCursorVisible =
                true

            binding.textoEntrada.requestFocus()

            /*
             * El cuadro está vacío, así que el cursor
             * queda naturalmente en la posición 0.
             */
            binding.textoEntrada.setSelection(
                binding.textoEntrada.text.length
            )

            val teclado =
                getSystemService(
                    Context.INPUT_METHOD_SERVICE
                ) as InputMethodManager

            teclado.showSoftInput(
                binding.textoEntrada,
                InputMethodManager.SHOW_IMPLICIT
            )

        } else {

            /*
             * CASO 2:
             *
             * Limpiar se pulsó desde el modo normal.
             *
             * Permanecemos en modo normal.
             */
            editandoTextoOrigen =
                false

            binding.textoEntrada.clearFocus()

            binding.textoEntrada.isCursorVisible =
                false

            desactivarModoVisualEdicionDirecta()
        }

        /*
         * Si estamos editando, textoEstado está oculto,
         * pero dejamos preparado su contenido para cuando
         * vuelva a mostrarse.
         */
        binding.textoEstado.text =
            textoApp(
                R.string.estado_listo
            )
    }

    private fun contextoIdiomaInterfaz(): Context {

        val configuracion =
            Configuration(
                resources.configuration
            )

        /*
         * La interfaz sigue al idioma de ORIGEN.
         *
         * localeTts ya contiene:
         *
         * es-MX
         * en-US
         * fr-FR
         */
        configuracion.setLocale(
            Locale.forLanguageTag(
                idiomaOrigen.localeTts.toLanguageTag()
            )
        )

        return createConfigurationContext(
            configuracion
        )
    }

    private fun textoApp(
        @StringRes idTexto: Int,
        vararg argumentos: Any
    ): String {

        return contextoIdiomaInterfaz()
            .resources
            .getString(
                idTexto,
                *argumentos
            )
    }

    /**
     * A native method that is implemented by the 'traductorandroid' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        // Used to load the 'traductorandroid' library on application startup.
        init {
            System.loadLibrary("traductorandroid")
        }
    }
}
