package com.example.traductorandroid

import androidx.annotation.StringRes

/*
 * Describe un modelo concreto de reconocimiento Vosk.
 *
 * El nombre del modelo también se obtiene mediante recursos
 * localizables en lugar de almacenarse como texto español.
 */
data class ModeloVosk(

    val id: String,

    @StringRes
    val nombreResId: Int,

    val tamanoVisible: String,

    val carpetaAssets: String,

    val carpetaInstalada: String,

    // Carpeta de destino utilizada por StorageService para los assets.
    val carpetaInterna: String = carpetaInstalada,

    val urlDescarga: String?
)

enum class EstadoModeloVosk {

    INTEGRADO,
    INSTALADO,
    DESCARGANDO,
    DESCARGABLE,
    NO_DISPONIBLE
}

object CatalogoModelosVosk {

    val ESPANOL_LIGERO =
        ModeloVosk(
            id = "es-small-0.42",

            carpetaInterna = "model-es",

            nombreResId =
                R.string.modelo_espanol_ligero,

            tamanoVisible = "39 MB",

            carpetaAssets =
                "vosk-model-small-es-0.42",

            carpetaInstalada =
                "vosk-model-small-es-0.42",

            urlDescarga = null
        )

    val INGLES_LIGERO =
        ModeloVosk(
            id = "en-us-small-0.15",

            carpetaInterna = "model-en",

            nombreResId =
                R.string.modelo_ingles_ligero,

            tamanoVisible = "40 MB",

            carpetaAssets =
                "vosk-model-small-en-us-0.15",

            carpetaInstalada =
                "vosk-model-small-en-us-0.15",

            urlDescarga = null
        )

    val FRANCES_LIGERO =
        ModeloVosk(
            id = "fr-small-0.22",

            carpetaInterna = "model-fr",

            nombreResId =
                R.string.modelo_frances_ligero,

            tamanoVisible = "41 MB",

            carpetaAssets =
                "vosk-model-small-fr-0.22",

            carpetaInstalada =
                "vosk-model-small-fr-0.22",

            urlDescarga =
                "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
        )

    val TODOS =
        listOf(
            ESPANOL_LIGERO,
            INGLES_LIGERO,
            FRANCES_LIGERO
        )

    // La asociación con Vosk pertenece al motor,
    // no a la definición del idioma.
    //
    // Cada idioma utiliza actualmente un único modelo ligero.
    fun obtenerModeloDeIdioma(
        idioma: Idioma
    ): ModeloVosk? =
        when (idioma) {

            CatalogoIdiomas.ESPANOL ->
                ESPANOL_LIGERO

            CatalogoIdiomas.INGLES ->
                INGLES_LIGERO

            CatalogoIdiomas.FRANCES ->
                FRANCES_LIGERO

            else ->
                null
        }
}