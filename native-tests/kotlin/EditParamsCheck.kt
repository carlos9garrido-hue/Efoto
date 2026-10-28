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
    check(p.isDefault && p.toFloatArray().size == 39 && p.toFloatArray().all { it == 0f }, "por defecto: 39 ceros")
    val exp = all[0]; val blue = all.first { it.name == "blue_sat" }; val c2 = all.first { it.name == "curve2" }
    p = exp.set(p, 1.5f); p = blue.set(p, -0.4f); p = c2.set(p, 0.2f)
    check(!p.isDefault && p.toFloatArray()[0] == 1.5f && p.toFloatArray()[15 + 5 * 3 + 1] == -0.4f && p.toFloatArray()[12] == 0.2f, "set escribe en la posición correcta del arreglo")
    check(p == EditParams().let { exp.set(it, 1.5f) }.let { blue.set(it, -0.4f) }.let { c2.set(it, 0.2f) }, "igualdad por valores")
    val back = EditParams.fromJson(p.toJson())
    check(back == p, "guardar y leer ediciones conserva todo")
    val v1 = EditParams.fromJson("version=1.0;exposure=0.5;saturation=-0.25")
    check(v1 != null && exp.get(v1) == 0.5f && all[9].get(v1) == -0.25f && v1.toFloatArray().drop(10).all { it == 0f }, "ediciones de la versión 1 siguen cargando")
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
    println(if (fails == 0) "TODO BIEN" else "$fails FALLOS")
    if (fails > 0) System.exit(1)
}
