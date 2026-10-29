package com.rawpocket

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Guarda los recientes en un JSON en el almacenamiento privado de la app. */
class RecentStore(private val file: File) {

    fun load(): List<RecentItem> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString("uri")
                if (uri.isEmpty()) null
                else RecentItem(
                    uri = uri,
                    name = o.optString("name", "foto"),
                    openedAt = o.optLong("at", 0L),
                    thumb = o.optString("thumb").ifEmpty { null },
                )
            }
        }
    } catch (e: Exception) {
        emptyList()  // archivo dañado: se empieza de cero
    }

    fun save(list: List<RecentItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("at", it.openedAt).put("thumb", it.thumb ?: ""))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }
}
