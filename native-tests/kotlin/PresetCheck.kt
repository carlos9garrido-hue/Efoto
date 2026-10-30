import com.rawpocket.*

private var fails = 0
private fun check(c: Boolean, what: String) { println((if (c) "  ok:   " else "  FAIL: ") + what); if (!c) fails++ }

fun main() {
    val byName = Adjustment.entries.associateBy { it.name }

    // Presets de fábrica: claves válidas, valores dentro de rango, ids únicos
    val keysOk = FactoryPresets.all.all { pr -> pr.values.keys.all { it in byName } }
    val rangeOk = FactoryPresets.all.all { pr -> pr.values.all { (k, v) -> v >= byName[k]!!.min && v <= byName[k]!!.max } }
    check(keysOk, "todas las claves de los presets de fábrica existen")
    check(rangeOk, "todos los valores de los presets de fábrica están dentro de rango")
    check(FactoryPresets.all.map { it.id }.toSet().size == FactoryPresets.all.size && FactoryPresets.all.all { it.builtIn }, "ids únicos y marcados de fábrica")
    check(FactoryPresets.all.size >= 6, "hay presets de fábrica")

    // Aplicar: pone lo que menciona, conserva lo demás y la geometría
    var base = EditParams().withGeometry(1, 8f, 1, 6000, 4000)
    base = byName["exposure"]!!.set(base, 0.7f)
    base = byName["contrast"]!!.set(base, 0.1f)
    val bw = FactoryPresets.all.first { it.id == "factory:bw" }
    val out = PresetLogic.apply(base, bw)
    check(byName["saturation"]!!.get(out) == -1f && byName["contrast"]!!.get(out) == 0.25f, "el preset fija sus ajustes")
    check(byName["exposure"]!!.get(out) == 0.7f, "lo que el preset no menciona se conserva")
    check(out.hasGeometry && out.rotation == 1 && out.angle == 8f && out.values[EditParams.CROP_L] == base.values[EditParams.CROP_L], "no toca giro, enderezado ni recorte")

    // Claves desconocidas y valores fuera de rango
    val weird = PresetLogic.applyValues(EditParams(), mapOf("no_existe" to 1f, "exposure" to 99f, "contrast" to Float.NaN))
    check(byName["exposure"]!!.get(weird) == 5f && byName["contrast"]!!.get(weird) == 0f, "ignora claves desconocidas y NaN; recorta a rango")

    // Capturar: solo ajustes no nulos, sin geometría; aplicar lo capturado reproduce las ediciones
    val edited = byName["clarity"]!!.set(byName["grade1_hue"]!!.set(byName["exposure"]!!.set(base, -0.4f), 120f), 0.5f)
    val cap = PresetLogic.capture(edited)
    check(cap.keys == setOf("exposure", "contrast", "grade1_hue", "clarity"), "capture: solo lo que no es cero y sin geometría")
    val back = PresetLogic.applyValues(EditParams(), cap)
    check(Adjustment.entries.all { it.get(back) == it.get(edited) } && !back.hasGeometry, "capturar y aplicar reproduce los ajustes (sin geometría)")

    // Nombres únicos
    check(PresetLogic.uniqueName("  Mi look ", listOf("Otro")) == "Mi look", "recorta espacios")
    check(PresetLogic.uniqueName("Mi look", listOf("Mi look", "Mi look 2")) == "Mi look 3", "evita duplicados con un número")
    check(PresetLogic.uniqueName("   ", emptyList()) == "Mi preset", "nombre vacío -> por defecto")
    check(PresetLogic.uniqueName("x".repeat(80), emptyList()).length == 32, "máximo 32 caracteres")

    println(if (fails == 0) "Presets OK" else "Presets FALLÓ ($fails)")
    if (fails != 0) kotlin.system.exitProcess(1)
}
