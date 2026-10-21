package com.pocklet.companion

import java.util.concurrent.atomic.AtomicLong

data class Notif(
    val id: Long,
    val app: String,
    val title: String,
    val text: String,
    val time: Long
)

/** Ring buffer notifikasi di RAM saja (tidak ditulis ke disk). */
object NotifStore {
    private const val CAPACITY = 20
    private const val DEDUP_MS = 5_000L

    private val idGen = AtomicLong(0)
    private val items = ArrayDeque<Notif>()
    private val lastSeen = HashMap<String, Long>()

    @Volatile var selected: Set<String> = emptySet()

    fun offer(pkg: String, app: String, title: String, text: String): Boolean {
        if (pkg !in selected) return false
        if (title.isBlank() && text.isBlank()) return false
        val now = System.currentTimeMillis()
        val key = "$pkg|$title|$text"
        synchronized(this) {
            val prev = lastSeen[key]
            if (prev != null && now - prev < DEDUP_MS) return false
            lastSeen[key] = now
            if (lastSeen.size > 200) lastSeen.entries.removeIf { now - it.value > DEDUP_MS }
            items.addFirst(Notif(idGen.incrementAndGet(), app, title, text, now))
            while (items.size > CAPACITY) items.removeLast()
        }
        return true
    }

    fun latest(n: Int): List<Notif> = synchronized(this) { items.take(n) }
}
