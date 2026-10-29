// Pruebas de EditParams.kt en PC (ver run.sh).
import com.rawpocket.*
var fails = 0
fun check(c: Boolean, msg: String) { println((if (c) "  ok:   " else "  FAIL: ") + msg); if (!c) fails++ }
fun main() {
    val all = Adjustment.entries
    check(all.size == 39 && Adjustment.COUNT == 39, "39 controles")
    check(all.map { it.index } == (0 until 39).toList(), "índices 0..38 en orden y sin repetir")
    check(all.map { it.name }.toSet().size == 39, "claves únicas")
    check(all.take(10).map { it.name } == listOf("exposure","contrast","highlights","shadows","whites","blacks","temperature","tint","vibrance","saturation"), "primeros 10 = orden del motor y claves de la versión 1")
    check(all[10].group == Group.CURVE && all[14].group == Group.CURVE && all[15].group == Group.HSL && all[38].group == Group.HSL, "grupos curva (10-14) y HSL (15-38)")
    check(all[15].name == "red_hue" && all[16].name == "red_sat" && all[17].name == "red_lum" && all[38].name == "magenta_lum", "HSL: banda*3 + tono/sat/lum")
    check(Band.entries.size == 8 && Band.entries.map { it.key } == listOf("red","orange","yellow","green","aqua","blue","purple","magenta"), "8 bandas en el orden del motor")
    check(all.filter { it.group == Group.HSL }.all { it.band != null } && all.filter { it.group != Group.HSL }.all { it.band == null }, "solo HSL tiene banda")

    var p = EditParams()
    check(p.isDefault && p.toFloatArray().size == 46 && p.toFloatArray().all { it == 0f }, "por defecto: 46 ceros (39 ajustes + 7 de geometría)")
    val exp = all[0]; val blue = all.first { it.name == "blue_sat" }; val c2 = all.first { it.name == "curve2" }
    p = exp.set(p, 1.5f); p = blue.set(p, -0.4f); p = c2.set(p, 0.2f)
    check(!p.isDefault && p.toFloatArray()[0] == 1.5f && p.toFloatArray()[15 + 5 * 3 + 1] == -0.4f && p.toFloatArray()[12] == 0.2f, "set escribe en la posición correcta del arreglo")
    check(p == EditParams().let { exp.set(it, 1.5f) }.let { blue.set(it, -0.4f) }.let { c2.set(it, 0.2f) }, "igualdad por valores")
    val back = EditParams.fromJson(p.toJson())
    check(back == p, "guardar y leer ediciones conserva todo")
    val v1 = EditParams.fromJson("version=1.0;exposure=0.5;saturation=-0.25")
    check(v1 != null && exp.get(v1) == 0.5f && all[9].get(v1) == -0.25f && v1.toFloatArray().drop(10).all { it == 0f } && !v1.hasGeometry, "ediciones de la versión 1 siguen cargando")
    val clamped = EditParams.fromJson("exposure=99.0;curve0=-5.0")!!
    check(exp.get(clamped) == 5f && all[10].get(clamped) == -0.25f, "valores fuera de rango se recortan")
    check(exp.format(0f) == "0" && exp.format(1.5f) == "+1.50 EV" && all[1].format(0.5f) == "+50" && all[1].format(-0.254f) == "-25", "formato de valores")

    // Curva: la identidad es la diagonal; subir el punto medio sube los medios; sin sobreimpulsos.
    val zero = List(5) { 0f }
    check((0..20).all { Math.abs(curveValue(zero, it / 20f) - it / 20f) < 1e-6f }, "curva sin cambios = diagonal")
    val mid = listOf(0f, 0f, 0.2f, 0f, 0f)
    check(Math.abs(curveValue(mid, 0.5f) - 0.7f) < 1e-6f && curveValue(mid, 0.4f) > 0.4f && curveValue(mid, 0f) == 0f && curveValue(mid, 1f) == 1f, "punto medio +0.2: pasa por (0.5, 0.7) y respeta extremos")
    val steep = listOf(0.25f, -0.25f, 0.25f, -0.25f, 0.25f)
    var mono = true; var prevY = -1f
    val ys = (0..100).map { curveValue(listOf(0f, 0.25f, -0.25f, 0.25f, 0f), it / 100f) }
    check(ys.all { it in 0f..1f } && curveValue(steep, 0.5f) in 0f..1f, "la curva siempre queda dentro de 0..1")
    val inc = (0..100).map { curveValue(listOf(0f, 0.1f, 0.2f, 0.1f, 0f), it / 100f) }
    check(inc.zipWithNext().all { (a, b) -> b >= a - 1e-6f }, "curva creciente se mantiene monótona (sin ondulaciones)")

    // ---- Geometría: giro, enderezado y recorte automático
    check(EditParams.ANGLE == 39 && EditParams.ROTATE == 40 && EditParams.CROP_L == 41 && EditParams.CROP_B == 44 && EditParams.ASPECT == 45 && EditParams.SIZE == 46 && EditParams.ENGINE_COUNT == 45,
        "posiciones 39..44 = ángulo, giros, recorte L/T/R/B (como Params en develop.h); 45 = proporción (solo app)")
    val d0 = EditParams()
    check(d0.withGeometry(0, 0f, 0, 6000, 4000).isDefault, "sin giro, ángulo 0 y proporción original: sigue siendo la imagen por defecto (recorte exactamente 0)")
    val r1 = d0.withGeometry(1, 0f, 0, 6000, 4000)
    check(r1.rotation == 1 && r1.values.subList(EditParams.CROP_L, EditParams.CROP_B + 1).all { it == 0f } && r1.hasGeometry, "girar 90° no recorta con proporción original")
    check(d0.withGeometry(5, 0f, 0, 6000, 4000).rotation == 1 && d0.withGeometry(-1, 0f, 0, 6000, 4000).rotation == 3, "los giros dan la vuelta (5 = 1, -1 = 3)")
    fun near(a: Float, b: Float, tol: Float = 2e-4f) = Math.abs(a - b) <= tol
    val sq = d0.withGeometry(0, 0f, 1, 6000, 4000).values
    check(near(sq[EditParams.CROP_L], 1f / 6f) && near(sq[EditParams.CROP_R], 1f / 6f) && sq[EditParams.CROP_T] == 0f && sq[EditParams.CROP_B] == 0f, "1:1 sobre 6000x4000: recorta 1/6 a cada lado")
    check(d0.withGeometry(0, 0f, 3, 6000, 4000).values.subList(41, 45).all { it == 0f }, "3:2 sobre una foto 3:2: no recorta")
    val w169 = d0.withGeometry(0, 0f, 4, 6000, 4000).values
    check(w169[EditParams.CROP_L] == 0f && near(w169[EditParams.CROP_T], 0.078125f), "16:9 sobre 6000x4000: recorta arriba y abajo 7,8 %")
    val por = d0.withGeometry(0, 0f, 2, 4000, 6000).values
    check(por[EditParams.CROP_L] == 0f && near(por[EditParams.CROP_T], 1f / 12f), "4:5 en vertical (4000x6000): queda 4000x5000")
    val rotSq = d0.withGeometry(1, 0f, 1, 6000, 4000).values  // girada: marco 4000x6000, cuadrado 4000x4000
    check(rotSq[EditParams.CROP_L] == 0f && near(rotSq[EditParams.CROP_T], 1f / 6f), "girar 90° y luego 1:1: el marco es vertical")

    // Ninguna esquina del recorte debe quedar fuera de la foto enderezada, y debe ser el mayor posible.
    var cornersOk = true; var maximal = true; var ratioOk = true
    var worst = ""
    for ((fw, fh) in listOf(6000 to 4000, 4000 to 6000, 5000 to 5000)) {
        for (aIdx in 0 until AspectPreset.entries.size) {
            for (deg in -45..45 step 5) {
                val p = d0.withGeometry(0, deg.toFloat(), aIdx, fw, fh).values
                val l = p[EditParams.CROP_L]; val t = p[EditParams.CROP_T]
                val cw = (1 - 2 * l) * fw; val ch = (1 - 2 * t) * fh
                val th = Math.toRadians(Math.abs(deg).toDouble()); val c = Math.cos(th); val s = Math.sin(th)
                val a = cw / 2; val b = ch / 2
                val limX = a * c + b * s; val limY = a * s + b * c  // esquina más lejana
                val fx = limX / (fw / 2.0); val fy = limY / (fh / 2.0)
                if (fx > 1.001 || fy > 1.001) { cornersOk = false; worst = "($fw x $fh, $aIdx, $deg°): $fx $fy" }
                if (Math.max(fx, fy) < 0.99 && !(l == 0f && t == 0f)) { maximal = false; worst = "no maximal ($fw x $fh, $aIdx, $deg°): $fx $fy" }
                val want = AspectPreset.entries[aIdx].ratio.let { r -> if (r == null) fw.toDouble() / fh else if (fw >= fh) r.toDouble() else 1.0 / r }
                if (Math.abs(cw / ch / want - 1) > 0.004) { ratioOk = false; worst = "proporción ($fw x $fh, $aIdx, $deg°): ${cw / ch} vs $want" }
            }
        }
    }
    check(cornersOk, "enderezar -45..45°: las 4 esquinas del recorte caben siempre en la foto $worst")
    check(maximal, "el recorte automático es el mayor posible (al menos un lado toca el borde)")
    check(ratioOk, "el recorte respeta la proporción pedida (±0,4 %)")

    val g = d0.withGeometry(3, 12.5f, 2, 6000, 4000).let { exp.set(it, 0.7f) }
    check(EditParams.fromJson(g.toJson()) == g && g.hasGeometry, "guardar y leer: la geometría se conserva")
    val noGeo = g.withoutGeometry()
    check(!noGeo.hasGeometry && exp.get(noGeo) == 0.7f && noGeo.aspect == 0, "quitar geometría conserva el resto de las ediciones")
    check(EditParams.fromJson("angle=99.0;crop_l=5.0;rotate90=7.0")!!.let { it.angle == 45f && it.values[EditParams.CROP_L] == 0.95f && it.rotation == 3 }, "valores de geometría fuera de rango se recortan")
    println(if (fails == 0) "TODO BIEN" else "$fails FALLOS")
    if (fails > 0) System.exit(1)
}
