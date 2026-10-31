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
    DETAIL("Detalle"),
    CURVE("Curva"),
    HSL("HSL"),
    GRADE("Gradación"),
    EFFECTS("Efectos"),
    LOCAL("Local"),
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
 *   0-9   básicos · 10-14 curva · 15-38 HSL (banda * 3 + tono/saturación/luminosidad)
 *   45-50 detalle · 51-53 efectos · 54-60 gradación (las posiciones 39-44 son la geometría).
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
    private val isDegrees: Boolean = false,
) {
    /** ¿Es un tono en grados (0..360)? */
    val isHue: Boolean get() = isDegrees

    fun get(p: EditParams): Float = p.values[index]

    fun set(p: EditParams, v: Float): EditParams = p.with(index, v)

    /** Texto del valor junto al control. */
    fun format(v: Float): String = when {
        isEv -> if (v == 0f) "0" else String.format(Locale.US, "%+.2f EV", v)
        isDegrees -> "${v.roundToInt()}°"
        min == 0f -> v.times(100f).roundToInt().toString()
        else -> {
            val n = (v * 100f).roundToInt()
            if (n == 0) "0" else String.format(Locale.US, "%+d", n)
        }
    }

    companion object {
        const val CURVE_START = 10
        const val CURVE_POINTS = 5
        const val HSL_START = CURVE_START + CURVE_POINTS
        const val COUNT = HSL_START + 8 * 3  // 39; después vienen 6 posiciones de geometría
        const val FX_START = COUNT + 6       // 45
        const val FX_COUNT = 16

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

            // Detalle (45-50)
            add(Adjustment("clarity", "Claridad", -1f, 1f, Group.DETAIL, FX_START))
            add(Adjustment("texture", "Textura", -1f, 1f, Group.DETAIL, FX_START + 1))
            add(Adjustment("dehaze", "Quitar neblina", -1f, 1f, Group.DETAIL, FX_START + 2))
            add(Adjustment("sharpen", "Nitidez", 0f, 1f, Group.DETAIL, FX_START + 3))
            add(Adjustment("nr_luma", "Ruido (luminancia)", 0f, 1f, Group.DETAIL, FX_START + 4))
            add(Adjustment("nr_color", "Ruido (color)", 0f, 1f, Group.DETAIL, FX_START + 5))
            // Efectos (51-53)
            add(Adjustment("vignette", "Viñeta", -1f, 1f, Group.EFFECTS, FX_START + 6))
            add(Adjustment("vignette_mid", "Punto medio", -1f, 1f, Group.EFFECTS, FX_START + 7))
            add(Adjustment("grain", "Grano", 0f, 1f, Group.EFFECTS, FX_START + 8))
            // Gradación de color (54-60)
            listOf("Sombras", "Medios", "Luces").forEachIndexed { z, zone ->
                add(Adjustment("grade${z}_hue", "$zone · tono", 0f, 360f, Group.GRADE, FX_START + 9 + z * 2, isDegrees = true))
                add(Adjustment("grade${z}_sat", "$zone · saturación", 0f, 1f, Group.GRADE, FX_START + 10 + z * 2))
            }
            add(Adjustment("grade_balance", "Balance", -1f, 1f, Group.GRADE, FX_START + 15))
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
    fun withoutGeometry(): EditParams =
        EditParams(values.mapIndexed { i, v -> if (i in ANGLE..CROP_B || i == ASPECT) 0f else v })

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

    /** Máscara local [m] (0..3). */
    fun mask(m: Int): LocalMask {
        val b = MASK_START + m * MASK_FLOATS
        return LocalMask(
            type = values[b].roundToInt().coerceIn(0, 2),
            x1 = values[b + 1], y1 = values[b + 2], x2 = values[b + 3], y2 = values[b + 4],
            feather = values[b + 5], invert = values[b + 6] > 0.5f,
            adjust = List(LocalMask.ADJUST_COUNT) { values[b + 7 + it] },
        )
    }

    fun withMask(m: Int, mask: LocalMask): EditParams {
        val b = MASK_START + m * MASK_FLOATS
        val v = values.toMutableList()
        v[b] = mask.type.toFloat()
        v[b + 1] = mask.x1; v[b + 2] = mask.y1; v[b + 3] = mask.x2; v[b + 4] = mask.y2
        v[b + 5] = mask.feather; v[b + 6] = if (mask.invert) 1f else 0f
        for (i in 0 until LocalMask.ADJUST_COUNT) v[b + 7 + i] = mask.adjust[i]
        return EditParams(v)
    }

    /** ¿Alguna máscara está activa? */
    val hasMasks: Boolean get() = (0 until MASK_COUNT).any { values[MASK_START + it * MASK_FLOATS] >= 0.5f }

    fun with(index: Int, v: Float): EditParams =
        EditParams(values.toMutableList().also { it[index] = v })

    fun toFloatArray(): FloatArray = values.toFloatArray()

    /** Desplazamientos de la curva tonal (5 puntos, x = 0, .25, .5, .75, 1). */
    fun curveOffsets(): List<Float> = values.subList(Adjustment.CURVE_START, Adjustment.HSL_START)

    fun toJson(): String = JSONObject().apply {
        put("version", 2)
        Adjustment.entries.forEach { put(it.name, it.get(this@EditParams).toDouble()) }
        GEO_KEYS.forEachIndexed { i, key -> put(key, values[if (i < 6) ANGLE + i else ASPECT].toDouble()) }
        for (i in MASK_START until ASPECT) put("mk$i", values[i].toDouble())
    }.toString()

    override fun equals(other: Any?) = other is EditParams && other.values == values
    override fun hashCode() = values.hashCode()

    companion object {
        // Geometría: las posiciones 39..44 las lee el motor (Params::kGeomStart); 45..60 son los efectos; la 61 es solo de la app.
        const val ANGLE = Adjustment.COUNT  // 39
        const val ROTATE = ANGLE + 1
        const val CROP_L = ANGLE + 2
        const val CROP_T = ANGLE + 3
        const val CROP_R = ANGLE + 4
        const val CROP_B = ANGLE + 5
        const val MASK_START = Adjustment.FX_START + Adjustment.FX_COUNT  // 61
        const val MASK_COUNT = 4
        const val MASK_FLOATS = 13
        const val ASPECT = MASK_START + MASK_COUNT * MASK_FLOATS  // 113: solo la usa la app
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
            for (i in MASK_START until ASPECT) {
                val v = json.optDouble("mk$i", 0.0).toFloat()
                m[i] = if (v.isNaN()) 0f else v.coerceIn(-5f, 5f)
            }
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

/**
 * Una edición local: un degradado lineal o radial con sus propios ajustes (mismo formato que
 * Params::masks en develop.h). Coordenadas en 0..1 sobre la imagen final.
 *  - lineal: el efecto es pleno en (x1, y1) y llega a cero en (x2, y2)
 *  - radial: centro (x1, y1) y radios (x2, y2)
 */
data class LocalMask(
    val type: Int = 0,
    val x1: Float = 0f, val y1: Float = 0f, val x2: Float = 0f, val y2: Float = 0f,
    val feather: Float = 0f,
    val invert: Boolean = false,
    val adjust: List<Float> = List(ADJUST_COUNT) { 0f },
) {
    val isLinear get() = type == 1
    val isRadial get() = type == 2
    val isActive get() = type != 0

    companion object {
        const val ADJUST_COUNT = 6
        const val NONE = 0
        const val LINEAR = 1
        const val RADIAL = 2

        /** Ajustes que admite cada máscara, en el orden del motor. */
        val ADJUSTMENTS = listOf(
            LocalAdjustment("Exposición", -2f, 2f, isEv = true),
            LocalAdjustment("Contraste", -1f, 1f),
            LocalAdjustment("Luces altas", -1f, 1f),
            LocalAdjustment("Sombras", -1f, 1f),
            LocalAdjustment("Temperatura", -1f, 1f),
            LocalAdjustment("Saturación", -1f, 1f),
        )

        /** Una máscara nueva con una posición inicial razonable. */
        fun create(type: Int): LocalMask = when (type) {
            LINEAR -> LocalMask(LINEAR, 0.5f, 0.15f, 0.5f, 0.55f, adjust = listOf(-0.5f, 0f, 0f, 0f, 0f, 0f))
            else -> LocalMask(RADIAL, 0.5f, 0.5f, 0.3f, 0.25f, feather = 0.6f, adjust = listOf(0.5f, 0f, 0f, 0f, 0f, 0f))
        }
    }
}

class LocalAdjustment(val label: String, val min: Float, val max: Float, private val isEv: Boolean = false) {
    fun format(v: Float): String = if (isEv) {
        if (v == 0f) "0" else String.format(Locale.US, "%+.2f EV", v)
    } else {
        val n = (v * 100f).roundToInt()
        if (n == 0) "0" else String.format(Locale.US, "%+d", n)
    }
}
