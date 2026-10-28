package com.rawpocket

import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

enum class Group(val label: String) {
    LIGHT("Luz"),
    COLOR("Color"),
}

/** Cada control del editor. El orden de [EditParams.toFloatArray] debe coincidir con `Params` en develop.h. */
enum class Adjustment(
    val label: String,
    val min: Float,
    val max: Float,
    val group: Group,
    private val isEv: Boolean = false,
) {
    EXPOSURE("Exposición", -5f, 5f, Group.LIGHT, isEv = true),
    CONTRAST("Contraste", -1f, 1f, Group.LIGHT),
    HIGHLIGHTS("Luces altas", -1f, 1f, Group.LIGHT),
    SHADOWS("Sombras", -1f, 1f, Group.LIGHT),
    WHITES("Blancos", -1f, 1f, Group.LIGHT),
    BLACKS("Negros", -1f, 1f, Group.LIGHT),
    TEMPERATURE("Temperatura", -1f, 1f, Group.COLOR),
    TINT("Matiz", -1f, 1f, Group.COLOR),
    VIBRANCE("Intensidad", -1f, 1f, Group.COLOR),
    SATURATION("Saturación", -1f, 1f, Group.COLOR);

    fun get(p: EditParams): Float = when (this) {
        EXPOSURE -> p.exposure
        CONTRAST -> p.contrast
        HIGHLIGHTS -> p.highlights
        SHADOWS -> p.shadows
        WHITES -> p.whites
        BLACKS -> p.blacks
        TEMPERATURE -> p.temperature
        TINT -> p.tint
        VIBRANCE -> p.vibrance
        SATURATION -> p.saturation
    }

    fun set(p: EditParams, v: Float): EditParams = when (this) {
        EXPOSURE -> p.copy(exposure = v)
        CONTRAST -> p.copy(contrast = v)
        HIGHLIGHTS -> p.copy(highlights = v)
        SHADOWS -> p.copy(shadows = v)
        WHITES -> p.copy(whites = v)
        BLACKS -> p.copy(blacks = v)
        TEMPERATURE -> p.copy(temperature = v)
        TINT -> p.copy(tint = v)
        VIBRANCE -> p.copy(vibrance = v)
        SATURATION -> p.copy(saturation = v)
    }

    /** Texto del valor junto al control. */
    fun format(v: Float): String = when {
        isEv -> if (v == 0f) "0" else String.format(Locale.US, "%+.2f EV", v)
        else -> {
            val n = (v * 100f).roundToInt()
            if (n == 0) "0" else String.format(Locale.US, "%+d", n)
        }
    }
}

/** Ediciones no destructivas: solo parámetros; el RAW original nunca se modifica. */
data class EditParams(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
) {
    val isDefault: Boolean get() = this == EditParams()

    fun toFloatArray(): FloatArray = floatArrayOf(
        exposure, contrast, highlights, shadows, whites, blacks,
        temperature, tint, vibrance, saturation,
    )

    fun toJson(): String = JSONObject().apply {
        put("version", 1)
        Adjustment.entries.forEach { put(it.name.lowercase(Locale.US), it.get(this@EditParams).toDouble()) }
    }.toString()

    companion object {
        fun fromJson(text: String): EditParams? = runCatching {
            val json = JSONObject(text)
            var p = EditParams()
            Adjustment.entries.forEach { adj ->
                val v = json.optDouble(adj.name.lowercase(Locale.US), 0.0).toFloat()
                p = adj.set(p, v.coerceIn(adj.min, adj.max))
            }
            p
        }.getOrNull()
    }
}
