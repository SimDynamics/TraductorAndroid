package com.example.traductorandroid

import androidx.annotation.StringRes
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/*
 * Representa un idioma utilizable por TraductorAndroid.
 *
 * El nombre visible ya no se almacena como texto fijo.
 * Guardamos el identificador de un recurso R.string.*
 * para que pueda mostrarse en cualquier idioma de interfaz.
 */
data class Idioma(

    @StringRes
    val nombreResId: Int,

    /*
 * Etiqueta BCP 47 utilizada por el reconocedor
 * de voz local proporcionado por Android.
 */
    val etiquetaReconocimientoSistema: String,

    // Código utilizado por ML Kit Translation.
    val codigoMlKit: String,

    // Idioma/acento solicitado a TextToSpeech.
    val localeTts: Locale
)

object CatalogoIdiomas {

    val ESPANOL =
        Idioma(
            nombreResId =
                R.string.idioma_espanol,

            etiquetaReconocimientoSistema =
                "es-US",

            codigoMlKit =
                TranslateLanguage.SPANISH,

            localeTts =
                Locale.forLanguageTag(
                    "es-MX"
                )
        )

    val INGLES =
        Idioma(
            nombreResId =
                R.string.idioma_ingles,

            etiquetaReconocimientoSistema =
                "en-US",

            codigoMlKit =
                TranslateLanguage.ENGLISH,

            localeTts =
                Locale.US
        )

    val FRANCES =
        Idioma(
            nombreResId =
                R.string.idioma_frances,

            etiquetaReconocimientoSistema =
                "fr-FR",

            codigoMlKit =
                TranslateLanguage.FRENCH,

            localeTts =
                Locale.forLanguageTag(
                    "fr-FR"
                )
        )

    val TODOS =
        listOf(
            ESPANOL,
            INGLES,
            FRANCES
        )
}
