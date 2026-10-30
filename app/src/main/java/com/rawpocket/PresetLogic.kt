package com.rawpocket

/**
 * Un preset es un conjunto de ajustes con nombre (por clave de ajuste, estable entre versiones).
 * No incluye giro, enderezado ni recorte: son de cada foto.
 */
data class Preset(
    val id: String,
    val name: String,
    val values: Map<String, Float>,
    val builtIn: Boolean = false,
)

/** Lógica pura de presets (sin Android, para poder probarla en PC). */
object PresetLogic {
    private val byName: Map<String, Adjustment> by lazy { Adjustment.entries.associateBy { it.name } }

    /** Aplica los ajustes del preset sobre [base]; lo que el preset no menciona se conserva. */
    fun apply(base: EditParams, preset: Preset): EditParams = applyValues(base, preset.values)

    fun applyValues(base: EditParams, values: Map<String, Float>): EditParams {
        var p = base
        for ((key, v) in values) {
            val adj = byName[key] ?: continue  // clave de otra versión: se ignora
            if (v.isNaN()) continue
            p = adj.set(p, v.coerceIn(adj.min, adj.max))
        }
        return p
    }

    /** Ajustes distintos de cero de [p] (sin geometría), para guardarlos como preset o copiarlos. */
    fun capture(p: EditParams): Map<String, Float> =
        Adjustment.entries.filter { it.get(p) != 0f }.associate { it.name to it.get(p) }

    /** Nombre libre: recorta espacios y evita duplicar uno existente añadiendo un número. */
    fun uniqueName(wanted: String, existing: Collection<String>): String {
        val base = wanted.trim().ifEmpty { "Mi preset" }.take(32)
        if (base !in existing) return base
        var n = 2
        while ("$base $n" in existing) n++
        return "$base $n"
    }
}

/** Presets de fábrica. Puntos de partida sobrios; se pueden aplicar y luego retocar. */
object FactoryPresets {
    private fun p(id: String, name: String, vararg v: Pair<String, Float>) = Preset("factory:$id", name, v.toMap(), builtIn = true)

    val all: List<Preset> = listOf(
        p("vivid", "Vívido",
            "contrast" to 0.15f, "vibrance" to 0.35f, "saturation" to 0.12f, "clarity" to 0.25f, "shadows" to 0.1f),
        p("warm_film", "Película cálida",
            "temperature" to 0.25f, "contrast" to -0.05f, "highlights" to -0.2f, "saturation" to -0.08f,
            "curve0" to 0.03f, "grain" to 0.25f, "vignette" to -0.2f,
            "grade0_hue" to 190f, "grade0_sat" to 0.25f, "grade2_hue" to 40f, "grade2_sat" to 0.3f),
        p("bw", "Blanco y negro",
            "saturation" to -1f, "contrast" to 0.25f, "clarity" to 0.3f, "grain" to 0.2f, "vignette" to -0.15f),
        p("cinema", "Cinemático",
            "contrast" to 0.15f, "temperature" to -0.05f, "saturation" to -0.1f, "highlights" to -0.15f,
            "clarity" to 0.1f, "vignette" to -0.35f,
            "grade0_hue" to 200f, "grade0_sat" to 0.45f, "grade2_hue" to 35f, "grade2_sat" to 0.35f),
        p("faded", "Desvanecido",
            "contrast" to -0.2f, "curve0" to 0.06f, "saturation" to -0.15f, "highlights" to -0.1f, "blacks" to 0.3f),
        p("soft_portrait", "Retrato suave",
            "clarity" to -0.2f, "texture" to -0.25f, "highlights" to -0.15f, "shadows" to 0.15f,
            "temperature" to 0.06f, "vibrance" to 0.1f, "nr_luma" to 0.3f),
        p("landscape", "Paisaje intenso",
            "contrast" to 0.2f, "dehaze" to 0.3f, "clarity" to 0.3f, "vibrance" to 0.3f, "saturation" to 0.1f,
            "highlights" to -0.25f, "shadows" to 0.25f, "sharpen" to 0.3f),
        p("cold_night", "Noche fría",
            "temperature" to -0.2f, "shadows" to 0.3f, "blue_sat" to 0.2f, "nr_luma" to 0.4f, "nr_color" to 0.5f,
            "grade0_hue" to 230f, "grade0_sat" to 0.4f),
    )
}
