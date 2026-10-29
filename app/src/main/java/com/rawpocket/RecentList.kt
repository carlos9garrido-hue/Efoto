package com.rawpocket

/** Una foto abierta antes. [uri] es el URI de contenido del archivo; [thumb] el nombre de su miniatura. */
data class RecentItem(
    val uri: String,
    val name: String,
    val openedAt: Long,
    val thumb: String? = null,
)

/** Lógica pura de la lista de recientes (sin Android, para poder probarla en PC). */
object RecentList {
    const val MAX = 30

    /** Pone [item] al principio; si ya estaba (mismo URI) se reemplaza. Conserva como máximo [MAX]. */
    fun add(list: List<RecentItem>, item: RecentItem): List<RecentItem> =
        (listOf(item) + list.filter { it.uri != item.uri }).take(MAX)

    fun remove(list: List<RecentItem>, uri: String): List<RecentItem> = list.filter { it.uri != uri }

    /** Elementos que quedan fuera de la lista tras añadir: sus miniaturas ya no hacen falta. */
    fun dropped(before: List<RecentItem>, after: List<RecentItem>): List<RecentItem> {
        val keep = after.map { it.uri }.toSet()
        return before.filter { it.uri !in keep }
    }
}
