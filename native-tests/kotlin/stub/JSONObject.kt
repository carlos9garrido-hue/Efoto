package org.json
// Sustituto mínimo SOLO para pruebas en PC (en Android se usa org.json real).
class JSONObject(text: String? = null) {
    private val m = linkedMapOf<String, Double>()
    init { text?.split(';')?.filter { it.contains('=') }?.forEach { val (k, v) = it.split('='); m[k] = v.toDouble() } }
    fun put(k: String, v: Int): JSONObject { m[k] = v.toDouble(); return this }
    fun put(k: String, v: Double): JSONObject { m[k] = v; return this }
    fun optDouble(k: String, d: Double) = m[k] ?: d
    override fun toString() = m.entries.joinToString(";") { "${it.key}=${it.value}" }
}
