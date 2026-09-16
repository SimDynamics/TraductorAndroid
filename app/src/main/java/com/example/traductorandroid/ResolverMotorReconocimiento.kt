package com.example.traductorandroid

/*
 * Política de preferencia del motor de reconocimiento.
 *
 * El prototipo utiliza AUTOMATICO.
 *
 * Las demás alternativas se conservan en la arquitectura
 * para permitir una futura ampliación sin modificar
 * la lógica central del resolver.
 */
enum class PreferenciaMotorReconocimiento {

    AUTOMATICO,

    /*
     * Intenta Android primero.
     * Si no puede utilizarlo, permite Vosk como fallback.
     */
    PREFERIR_ANDROID,

    /*
     * Intenta Vosk primero.
     * Si no puede utilizarlo, permite Android como fallback.
     */
    PREFERIR_VOSK
}

/*
 * Describe por qué el resolver tomó una decisión.
 *
 * Puede utilizarse posteriormente para:
 *
 * - diagnósticos;
 * - documentación técnica;
 * - futuras ampliaciones de la interfaz.
 */
enum class MotivoDecisionMotor {

    /*
     * Se utilizó el motor recomendado automáticamente
     * para las capacidades de este dispositivo.
     */
    MOTOR_RECOMENDADO,

    /*
     * Se respetó la preferencia explícita del usuario.
     */
    PREFERENCIA_USUARIO,

    /*
     * El motor preferido no estaba disponible,
     * pero el alternativo sí.
     */
    FALLBACK_POR_NO_DISPONIBILIDAD,

    /*
     * Ninguno de los motores puede utilizarse
     * actualmente para este idioma.
     */
    NINGUN_MOTOR_DISPONIBLE
}

/*
 * Resultado final de la decisión.
 *
 * motor puede ser null si todavía no existe
 * ningún motor utilizable para el idioma.
 */
data class DecisionMotorReconocimiento(

    val motor: MotorReconocimiento?,

    val motivo: MotivoDecisionMotor,

    /*
     * Conservamos también la disponibilidad calculada.
     *
     * Así MainActivity no necesita volver a repetir
     * las mismas reglas para implementar un fallback.
     */
    val androidUtilizable: Boolean,

    val voskUtilizable: Boolean
)

/*
 * Contiene la política de selección de motores.
 *
 * Esta clase NO conoce:
 *
 * - Activity;
 * - botones;
 * - SpeechRecognizer;
 * - archivos;
 * - View Binding.
 *
 * Recibe solamente información y produce una decisión.
 *
 * Por eso la lógica puede reutilizarse desde:
 *
 * MainActivity
 * Más
 * Opciones
 * pruebas futuras
 */
object ResolverMotorReconocimiento {

    fun resolver(
        capacidades: CapacidadesReconocimiento,
        disponibilidad: DisponibilidadReconocimientoIdioma,
        preferencia: PreferenciaMotorReconocimiento
    ): DecisionMotorReconocimiento {

        /*
         * Android es utilizable cuando:
         *
         * 1. esta versión/dispositivo permite elegirlo;
         * 2. el modelo está confirmado como instalado,
         *    o estamos en una versión como Android 12
         *    donde puede intentarse aunque su estado
         *    no pueda consultarse previamente.
         */
        val androidUtilizable =
            capacidades.androidPuedeElegirse &&
                    disponibilidad.androidPuedeIntentarse

        /*
         * Vosk únicamente se considera listo cuando
         * su modelo está realmente integrado o instalado.
         */
        val voskUtilizable =
            disponibilidad.voskListo

        /*
         * Primero determinamos qué motor queremos intentar.
         */
        val motorPreferido =
            when (preferencia) {

                PreferenciaMotorReconocimiento.AUTOMATICO -> {

                    capacidades.motorPredeterminado
                }

                PreferenciaMotorReconocimiento.PREFERIR_ANDROID -> {

                    MotorReconocimiento.ANDROID
                }

                PreferenciaMotorReconocimiento.PREFERIR_VOSK -> {

                    MotorReconocimiento.VOSK
                }
            }

        /*
         * ¿El motor preferido puede utilizarse?
         */
        val motorPreferidoDisponible =
            when (motorPreferido) {

                MotorReconocimiento.ANDROID -> {

                    androidUtilizable
                }

                MotorReconocimiento.VOSK -> {

                    voskUtilizable
                }
            }

        if (motorPreferidoDisponible) {

            return DecisionMotorReconocimiento(
                motor = motorPreferido,

                motivo =
                    if (
                        preferencia ==
                        PreferenciaMotorReconocimiento.AUTOMATICO
                    ) {

                        MotivoDecisionMotor
                            .MOTOR_RECOMENDADO

                    } else {

                        MotivoDecisionMotor
                            .PREFERENCIA_USUARIO
                    },

                androidUtilizable =
                    androidUtilizable,

                voskUtilizable =
                    voskUtilizable
            )
        }

        /*
         * El preferido no está disponible.
         *
         * Probamos automáticamente el otro.
         */
        val motorAlternativo =
            when (motorPreferido) {

                MotorReconocimiento.ANDROID -> {

                    MotorReconocimiento.VOSK
                }

                MotorReconocimiento.VOSK -> {

                    MotorReconocimiento.ANDROID
                }
            }

        val motorAlternativoDisponible =
            when (motorAlternativo) {

                MotorReconocimiento.ANDROID -> {

                    androidUtilizable
                }

                MotorReconocimiento.VOSK -> {

                    voskUtilizable
                }
            }

        if (motorAlternativoDisponible) {

            return DecisionMotorReconocimiento(
                motor =
                    motorAlternativo,

                motivo =
                    MotivoDecisionMotor
                        .FALLBACK_POR_NO_DISPONIBILIDAD,

                androidUtilizable =
                    androidUtilizable,

                voskUtilizable =
                    voskUtilizable
            )
        }

        /*
         * Ninguno está disponible.
         */
        return DecisionMotorReconocimiento(
            motor = null,

            motivo =
                MotivoDecisionMotor
                    .NINGUN_MOTOR_DISPONIBLE,

            androidUtilizable =
                false,

            voskUtilizable =
                false
        )
    }
}