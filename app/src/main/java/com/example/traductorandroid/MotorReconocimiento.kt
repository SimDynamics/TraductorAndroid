package com.example.traductorandroid

/*
 * Motores de reconocimiento que TraductorAndroid
 * puede utilizar.
 */
enum class MotorReconocimiento {

    ANDROID,
    VOSK
}

/*
 * Estado de un modelo lingüístico administrado
 * por el reconocimiento local de Android.
 *
 * Es independiente de EstadoModeloVosk.
 */
enum class EstadoModeloAndroid {

    /*
     * Todavía no hemos preguntado al sistema.
     */
    SIN_CONSULTAR,

    /*
     * Android informa que el idioma ya está listo
     * para utilizarse localmente.
     */
    INSTALADO,

    /*
     * Android soporta el idioma localmente,
     * pero todavía necesita descargar su modelo.
     */
    DESCARGABLE,

    /*
     * Android informa que la descarga ya fue
     * programada pero todavía no está lista.
     */
    DESCARGA_PENDIENTE,

    /*
     * Android 12 / API 31–32:
     *
     * existe reconocimiento local, pero todavía
     * no existe la API 33 RecognitionSupport que
     * permite consultar el estado por idioma.
     *
     * Por tanto podemos intentar utilizar Android,
     * pero no afirmar previamente que el modelo
     * concreto está instalado.
     */
    NO_COMPROBABLE,

    /*
     * El dispositivo o versión de Android no ofrece
     * el reconocimiento local que necesitamos.
     */
    MOTOR_NO_DISPONIBLE,

    /*
     * Existe reconocimiento local, pero el proveedor
     * no informa este idioma como instalado,
     * descargable ni pendiente.
     */
    IDIOMA_NO_SOPORTADO,

    /*
     * La consulta existía pero falló.
     *
     * Esto es diferente de afirmar que el idioma
     * no está soportado.
     */
    ERROR_CONSULTA
}

/*
 * Fotografía del estado de un idioma frente
 * a los dos motores.
 *
 * Más adelante ResolverMotorReconocimiento
 * recibirá precisamente esta información.
 */
data class DisponibilidadReconocimientoIdioma(

    val idioma: Idioma,

    val estadoAndroid: EstadoModeloAndroid,

    val estadoVosk: EstadoModeloVosk
) {

    /*
     * Android está confirmado y listo.
     */
    val androidListo: Boolean
        get() =
            estadoAndroid ==
                    EstadoModeloAndroid.INSTALADO

    /*
     * En Android 12 puede existir un modelo utilizable
     * aunque no podamos consultarlo previamente.
     *
     * Por eso distinguimos "listo confirmado"
     * de "puede intentarse".
     */
    val androidPuedeIntentarse: Boolean
        get() =
            estadoAndroid ==
                    EstadoModeloAndroid.INSTALADO ||
                    estadoAndroid ==
                    EstadoModeloAndroid.NO_COMPROBABLE

    /*
     * Vosk está listo cuando vive todavía en assets
     * o cuando ya fue instalado en filesDir.
     */
    val voskListo: Boolean
        get() =
            estadoVosk ==
                    EstadoModeloVosk.INTEGRADO ||
                    estadoVosk ==
                    EstadoModeloVosk.INSTALADO
}

/*
 * Capacidades generales de reconocimiento disponibles
 * en la versión actual de Android.
 *
 * Estas capacidades NO significan que un modelo
 * lingüístico concreto esté instalado.
 */
data class CapacidadesReconocimiento(

    val androidLocalDisponible: Boolean,

    val motorPredeterminado: MotorReconocimiento,

    val androidPuedeElegirse: Boolean,

    val androidAdmiteSesionSegmentada: Boolean,

    val androidAdmiteDeteccionIdioma: Boolean,

    val androidAdmiteCambioIdioma: Boolean,

    val mostrarAdvertenciaAndroid12: Boolean
)

object CompatibilidadReconocimiento {

    fun evaluar(
        apiAndroid: Int,
        androidLocalDisponible: Boolean
    ): CapacidadesReconocimiento {

        /*
         * Android 14+.
         *
         * Android Speech es nuestra opción automática
         * cuando el reconocimiento local existe.
         */
        if (
            apiAndroid >= 34 &&
            androidLocalDisponible
        ) {

            return CapacidadesReconocimiento(
                androidLocalDisponible = true,

                motorPredeterminado =
                    MotorReconocimiento.ANDROID,

                androidPuedeElegirse = true,

                androidAdmiteSesionSegmentada = true,

                androidAdmiteDeteccionIdioma = true,

                androidAdmiteCambioIdioma = true,

                mostrarAdvertenciaAndroid12 = false
            )
        }

        /*
         * Android 13.
         *
         * Ya dispone de sesiones segmentadas,
         * pero todavía no de detección automática
         * de idioma de API 34.
         */
        if (
            apiAndroid == 33 &&
            androidLocalDisponible
        ) {

            return CapacidadesReconocimiento(
                androidLocalDisponible = true,

                motorPredeterminado =
                    MotorReconocimiento.ANDROID,

                androidPuedeElegirse = true,

                androidAdmiteSesionSegmentada = true,

                androidAdmiteDeteccionIdioma = false,

                androidAdmiteCambioIdioma = false,

                mostrarAdvertenciaAndroid12 = false
            )
        }

        /*
         * Android 12 / 12L.
         *
         * Puede existir reconocimiento local Android,
         * pero nuestra opción predeterminada continúa
         * siendo Vosk por su escucha continua.
         *
         * Android podrá elegirse manualmente después.
         */
        if (
            apiAndroid >= 31 &&
            androidLocalDisponible
        ) {

            return CapacidadesReconocimiento(
                androidLocalDisponible = true,

                motorPredeterminado =
                    MotorReconocimiento.VOSK,

                androidPuedeElegirse = true,

                androidAdmiteSesionSegmentada = false,

                androidAdmiteDeteccionIdioma = false,

                androidAdmiteCambioIdioma = false,

                mostrarAdvertenciaAndroid12 = true
            )
        }

        /*
         * Android 7–11 o dispositivo sin
         * reconocimiento local compatible.
         */
        return CapacidadesReconocimiento(
            androidLocalDisponible = false,

            motorPredeterminado =
                MotorReconocimiento.VOSK,

            androidPuedeElegirse = false,

            androidAdmiteSesionSegmentada = false,

            androidAdmiteDeteccionIdioma = false,

            androidAdmiteCambioIdioma = false,

            mostrarAdvertenciaAndroid12 = false
        )
    }
}