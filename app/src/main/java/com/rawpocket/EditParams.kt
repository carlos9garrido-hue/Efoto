package com.rawpocket

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Group(val label: String) {
    LIGHT("Luz"),
    COLOR("Color"),
    CURVE("Curva"),
    HSL("HSL"),
    CROP("Recorte"),
}

/** Proporciones de recorte. [ratio] es lado largo / lado corto; null = la de la foto. */
enum class AspectPreset(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    SQUARE("1:1", 1f),
    FOUR_FIVE("4:5", 1.25f),
    THREE_TWO("3:2", 1.5f),
    SIXTEEN_NINE("16:9", 16f / 9f),
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

        val EXPOSURE: Adjustment = entries[0]
        val CONTRAST: Adjustment = entries[1]
        val HIGHLIGHTS: Adjustment = entries[2]
        val SHADOWS: Adjustment = entries[3]
        val WHITES: Adjustment = entries[4]
        val BLACKS: Adjustment = entries[5]
        val TEMPERATURE: Adjustment = entries[6]
        val TINT: Adjustment = entries[7]

        /** Posición de la banda dentro de `values` (para saber si tiene ediciones). */
        fun bandRange(bandIndex: Int): IntRange = (HSL_START + bandIndex * 3)..(HSL_START + bandIndex * 3 + 2)
    }
}

/** Ediciones no destructivas: solo parámetros; el RAW original nunca se modifica. */
class EditParams private constructor(val values: List<Float>) {

    constructor() : this(List(SIZE) { 0f })

    val isDefault: Boolean get() = values.all { it == 0f }

    /** Giros de 90° en sentido horario (0..3). */
    val rotation: Int get() = values[ROTATE].roundToInt() and 3

    /** Enderezado en grados (positivo = la foto gira en sentido horario). */
    val angle: Float get() = values[ANGLE]

    /** Índice de [AspectPreset] elegido. */
    val aspect: Int get() = values[ASPECT].roundToInt().coerceIn(0, AspectPreset.entries.size - 1)

    /** ¿Hay giro, enderezado o recorte? */
    val hasGeometry: Boolean get() = (ANGLE..CROP_B).any { values[it] != 0f }

    /** Sin geometría (conserva el resto de las ediciones). */
    fun withoutGeometry(): EditParams = EditParams(values.mapIndexed { i, v -> if (i >= ANGLE) 0f else v })

    /**
     * Cambia giro, enderezado y proporción y recalcula el recorte automático: el mayor rectángulo
     * centrado con esa proporción que cabe dentro de la foto girada (sin esquinas vacías).
     * [srcW] x [srcH] es el tamaño de la foto sin girar.
     */
    fun withGeometry(rotation: Int, angle: Float, aspectIndex: Int, srcW: Int, srcH: Int): EditParams {
        val rot = rotation and 3
        val frameW = (if (rot and 1 == 1) srcH else srcW).toFloat()
        val frameH = (if (rot and 1 == 1) srcW else srcH).toFloat()
        val a = angle.coerceIn(-MAX_ANGLE, MAX_ANGLE)
        val preset = AspectPreset.entries[aspectIndex.coerceIn(0, AspectPreset.entries.size - 1)]
        val crop = fitCrop(frameW, frameH, a, preset.ratio)
        val m = values.toMutableList()
        m[ROTATE] = rot.toFloat()
        m[ANGLE] = a
        m[CROP_L] = crop[0]; m[CROP_T] = crop[1]; m[CROP_R] = crop[2]; m[CROP_B] = crop[3]
        m[ASPECT] = aspectIndex.toFloat()
        return EditParams(m)
    }

    fun with(index: Int, v: Float): EditParams =
        EditParams(values.toMutableList().also { it[index] = v })

    fun toFloatArray(): FloatArray = values.toFloatArray()

    /** Desplazamientos de la curva tonal (5 puntos, x = 0, .25, .5, .75, 1). */
    fun curveOffsets(): List<Float> = values.subList(Adjustment.CURVE_START, Adjustment.HSL_START)

    fun toJson(): String = JSONObject().apply {
        put("version", 2)
        Adjustment.entries.forEach { put(it.name, it.get(this@EditParams).toDouble()) }
        GEO_KEYS.forEachIndexed { i, key -> put(key, values[ANGLE + i].toDouble()) }
    }.toString()

    override fun equals(other: Any?) = other is EditParams && other.values == values
    override fun hashCode() = values.hashCode()

    companion object {
        // Geometría: las posiciones 39..44 las lee el motor (Params::kGeomStart); la 45 es solo de la app.
        const val ANGLE = Adjustment.COUNT  // 39
        const val ROTATE = ANGLE + 1
        const val CROP_L = ANGLE + 2
        const val CROP_T = ANGLE + 3
        const val CROP_R = ANGLE + 4
        const val CROP_B = ANGLE + 5
        const val ASPECT = ANGLE + 6
        const val SIZE = ASPECT + 1
        const val ENGINE_COUNT = ASPECT  // lo que lee el motor (Params::kCount)
        const val MAX_ANGLE = 45f
        private val GEO_KEYS = listOf("angle", "rotate90", "crop_l", "crop_t", "crop_r", "crop_b", "aspect")

        /** Lee ediciones guardadas (también las de versiones anteriores, sin curva/HSL/geometría). */
        fun fromJson(text: String): EditParams? = runCatching {
            val json = JSONObject(text)
            var p = EditParams()
            Adjustment.entries.forEach { adj ->
                val v = json.optDouble(adj.name, 0.0).toFloat()
                p = adj.set(p, if (v.isNaN()) 0f else v.coerceIn(adj.min, adj.max))
            }
            val geo = GEO_KEYS.map { key -> json.optDouble(key, 0.0).toFloat().let { if (it.isNaN()) 0f else it } }
            val rot = geo[1].roundToInt() and 3
            val aspect = geo[6].roundToInt().coerceIn(0, AspectPreset.entries.size - 1)
            val m = p.values.toMutableList()
            m[ANGLE] = geo[0].coerceIn(-MAX_ANGLE, MAX_ANGLE)
            m[ROTATE] = rot.toFloat()
            for (i in 0 until 4) m[CROP_L + i] = geo[2 + i].coerceIn(0f, 0.95f)
            m[ASPECT] = aspect.toFloat()
            EditParams(m)
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

/**
 * Recorte automático: el mayor rectángulo centrado, con la proporción pedida, que cabe dentro de
 * un marco [frameW] x [frameH] girado [angleDeg] grados. Devuelve el recorte por lado
 * (izquierda, arriba, derecha, abajo) como fracción del marco, listo para el motor.
 *
 * [ratio] es lado largo / lado corto (null = la proporción del marco); se orienta como el marco.
 * Las cuatro esquinas del rectángulo cumplen |x cos + y sin| <= W/2 y |-x sin + y cos| <= H/2.
 */
fun fitCrop(frameW: Float, frameH: Float, angleDeg: Float, ratio: Float?): FloatArray {
    val aspect = when {
        ratio == null -> frameW / frameH
        frameW >= frameH -> ratio
        else -> 1f / ratio
    }
    val t = Math.toRadians(abs(angleDeg).toDouble())
    val c = cos(t).toFloat()
    val s = sin(t).toFloat()
    // Media anchura "a" del recorte (la media altura es a / aspect).
    val a = min(
        (frameW / 2f) / (c + s / aspect),
        (frameH / 2f) / (s + c / aspect),
    ).coerceAtMost(frameW / 2f).coerceAtMost(frameH / 2f * aspect)
    val b = a / aspect
    fun snap(x: Float) = ((x * 10000f).roundToInt() / 10000f).coerceAtLeast(0f)  // evita restos de redondeo
    val insetX = snap((frameW / 2f - a) / frameW)
    val insetY = snap((frameH / 2f - b) / frameH)
    return floatArrayOf(insetX, insetY, insetX, insetY)
}
