package com.rawpocket

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Guarda los presets del usuario en un JSON en el almacenamiento privado de la app. */
class PresetStore(private val file: File) {

    fun load(): List<Preset> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val name = o.optString("name")
                val vals = o.optJSONObject("values") ?: return@mapNotNull null
                if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
                val map = buildMap {
                    vals.keys().forEach { k -> put(k, vals.optDouble(k, 0.0).toFloat()) }
                }
                Preset(id, name, map)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(list: List<Preset>) {
        val arr = JSONArray()
        list.filter { !it.builtIn }.forEach { p ->
            val vals = JSONObject()
            p.values.forEach { (k, v) -> vals.put(k, v.toDouble()) }
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("values", vals))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }
}
