import com.rawpocket.RecentItem
import com.rawpocket.RecentList

private var fails = 0
private fun check(c: Boolean, what: String) { if (!c) { println("  FALLA: $what"); fails++ } }

fun main() {
    fun it(n: Int, thumb: String? = "t$n.jpg") = RecentItem("content://x/$n", "f$n.dng", n.toLong(), thumb)
    var l = emptyList<RecentItem>()
    for (i in 1..3) l = RecentList.add(l, it(i))
    check(l.map { x -> x.name } == listOf("f3.dng", "f2.dng", "f1.dng"), "orden: lo último primero")
    l = RecentList.add(l, it(1, "nuevo.jpg"))
    check(l.size == 3 && l[0].uri.endsWith("/1") && l[0].thumb == "nuevo.jpg", "reabrir mueve al frente sin duplicar")
    for (i in 10..60) l = RecentList.add(l, it(i))
    check(l.size == RecentList.MAX && l[0].uri.endsWith("/60"), "tope de ${RecentList.MAX}")
    val before = l
    val after = RecentList.add(before, it(99))
    check(RecentList.dropped(before, after).map { x -> x.uri } == listOf(before.last().uri), "dropped = el que salió")
    check(RecentList.remove(after, "content://x/99").size == after.size - 1, "quitar")
    check(RecentList.remove(after, "content://x/zzz") == after, "quitar inexistente no cambia nada")
    println(if (fails == 0) "RecentList OK" else "RecentList FALLÓ ($fails)")
    if (fails != 0) kotlin.system.exitProcess(1)
}
