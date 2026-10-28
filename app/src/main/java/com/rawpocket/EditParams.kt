package com.rawpocket

import org.json.JSONObject
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

enum class Group(val label: String) {
    LIGHT("Luz"),
    COLOR("Color"),
    CURVE("Curva"),
    HSL("HSL"),
}

/** Bandas de color del panel HSL, en el mismo orden que en el motor (develop.h / develop.frag). */
enum class Band(val key: String, val label: String, val argb: Long) {
    RED("red", "Rojo", 0xFFE5484D),
    ORANGE("orange", "Naranja", 0xFFF08A24),
    YELLOW("yellow", "Amarillo", 0xFFF5D90A),
    GREEN("green", "Verde", 0xFF46A758),
    AQUA("aqua", "Aqua", 0xFF12A594),
    BLUE("blue", "Azul", 0xFF3E63DD),
    PURPLE("purple", "Púrpura", 0xFF8E4EC6),
    MAGENTA("magenta", "Magenta", 0xFFD6409F),
}

/**
 * Un control del editor. [index] es la posición en el arreglo que recibe el motor:
 *   0-9   básicos · 10-14 curva · 15-38 HSL (banda * 3 + tono/saturación/luminosidad).
 * Debe coincidir con `Params::fromArray` en develop.cpp y con `uParams` en develop.frag.
 */
class Adjustment private constructor(
    /** Clave estable para guardar las ediciones. */
    val name: String,
    val label: String,
    val min: Float,
    val max: Float,
    val group: Group,
    val index: Int,
    private val isEv: Boolean = false,
    val band: Band? = null,
) {
    fun get(p: EditParams): Float = p.values[index]

    fun set(p: EditParams, v: Float): EditParams = p.with(index, v)

    /** Texto del valor junto al control. */
    fun format(v: Float): String = when {
        isEv -> if (v == 0f) "0" else String.format(Locale.US, "%+.2f EV", v)
        else -> {
            val n = (v * 100f).roundToInt()
            if (n == 0) "0" else String.format(Locale.US, "%+d", n)
        }
    }

    companion object {
        const val CURVE_START = 10
        const val CURVE_POINTS = 5
        const val HSL_START = CURVE_START + CURVE_POINTS
        const val COUNT = HSL_START + 8 * 3

        val entries: List<Adjustment> = buildList {
            add(Adjustment("exposure", "Exposición", -5f, 5f, Group.LIGHT, 0, isEv = true))
            add(Adjustment("contrast", "Contraste", -1f, 1f, Group.LIGHT, 1))
            add(Adjustment("highlights", "Luces altas", -1f, 1f, Group.LIGHT, 2))
            add(Adjustment("shadows", "Sombras", -1f, 1f, Group.LIGHT, 3))
            add(Adjustment("whites", "Blancos", -1f, 1f, Group.LIGHT, 4))
            add(Adjustment("blacks", "Negros", -1f, 1f, Group.LIGHT, 5))
            add(Adjustment("temperature", "Temperatura", -1f, 1f, Group.COLOR, 6))
            add(Adjustment("tint", "Matiz", -1f, 1f, Group.COLOR, 7))
            add(Adjustment("vibrance", "Intensidad", -1f, 1f, Group.COLOR, 8))
            add(Adjustment("saturation", "Saturación", -1f, 1f, Group.COLOR, 9))

            listOf("Negros", "Sombras", "Medios", "Luces", "Blancos").forEachIndexed { i, label ->
                add(Adjustment("curve$i", label, -0.25f, 0.25f, Group.CURVE, CURVE_START + i))
            }

            Band.entries.forEachIndexed { b, band ->
                val base = HSL_START + b * 3
                add(Adjustment("${band.key}_hue", "Tono", -1f, 1f, Group.HSL, base, band = band))
                add(Adjustment("${band.key}_sat", "Saturación", -1f, 1f, Group.HSL, base + 1, band = band))
                add(Adjustment("${band.key}_lum", "Luminosidad", -1f, 1f, Group.HSL, base + 2, band = band))
            }
        }

        /** Posición de la banda dentro de `values` (para saber si tiene ediciones). */
        fun bandRange(bandIndex: Int): IntRange = (HSL_START + bandIndex * 3)..(HSL_START + bandIndex * 3 + 2)
    }
}

/** Ediciones no destructivas: solo parámetros; el RAW original nunca se modifica. */
class EditParams private constructor(val values: List<Float>) {

    constructor() : this(List(Adjustment.COUNT) { 0f })

    val isDefault: Boolean get() = values.all { it == 0f }

    fun with(index: Int, v: Float): EditParams =
        EditParams(values.toMutableList().also { it[index] = v })

    fun toFloatArray(): FloatArray = values.toFloatArray()

    /** Desplazamientos de la curva tonal (5 puntos, x = 0, .25, .5, .75, 1). */
    fun curveOffsets(): List<Float> = values.subList(Adjustment.CURVE_START, Adjustment.HSL_START)

    fun toJson(): String = JSONObject().apply {
        put("version", 2)
        Adjustment.entries.forEach { put(it.name, it.get(this@EditParams).toDouble()) }
    }.toString()

    override fun equals(other: Any?) = other is EditParams && other.values == values
    override fun hashCode() = values.hashCode()

    companion object {
        /** Lee ediciones guardadas (también las de la versión 1, que solo tenían los 10 básicos). */
        fun fromJson(text: String): EditParams? = runCatching {
            val json = JSONObject(text)
            var p = EditParams()
            Adjustment.entries.forEach { adj ->
                val v = json.optDouble(adj.name, 0.0).toFloat()
                p = adj.set(p, if (v.isNaN()) 0f else v.coerceIn(adj.min, adj.max))
            }
            p
        }.getOrNull()
    }
}

/**
 * Curva tonal para dibujarla: mismo Hermite cúbico con tangentes monótonas que el motor
 * (Developer::curveAt en develop.cpp). [offsets] son los 5 desplazamientos sobre la diagonal.
 */
fun curveValue(offsets: List<Float>, x: Float): Float {
    val y = FloatArray(5) { (0.25f * it + offsets[it]).coerceIn(0f, 1f) }
    val d = FloatArray(4) { (y[it + 1] - y[it]) / 0.25f }
    val m = FloatArray(5)
    m[0] = d[0]
    m[4] = d[3]
    for (i in 1..3) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else 2f * d[i - 1] * d[i] / (d[i - 1] + d[i])
    val xs = x.coerceIn(0f, 1f) * 4f
    val i = minOf(3, floor(xs).toInt())
    val t = xs - i
    val t2 = t * t
    val t3 = t2 * t
    val r = (2f * t3 - 3f * t2 + 1f) * y[i] + (t3 - 2f * t2 + t) * 0.25f * m[i] +
        (-2f * t3 + 3f * t2) * y[i + 1] + (t3 - t2) * 0.25f * m[i + 1]
    return r.coerceIn(0f, 1f)
}
