package dev.etran.livemaps.web

import dev.etran.livemaps.LiveMapsPlugin
import org.bukkit.entity.Player
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WebSession(val player: UUID, val name: String, @Volatile var expires: Long) {
    @Volatile var lastPreview = 0L
}

/** One-time login links (created in game) and the browser sessions they turn into. */
class WebAuth(private val plugin: LiveMapsPlugin) {
    private val rng = SecureRandom()
    private val links = ConcurrentHashMap<String, Triple<UUID, String, Long>>()
    private val sessions = ConcurrentHashMap<String, WebSession>()

    private fun token(): String {
        val b = ByteArray(24)
        rng.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun newLink(p: Player): String {
        purge()
        val t = token()
        links[t] = Triple(p.uniqueId, p.name, System.currentTimeMillis() + plugin.cfg.linkMinutes * 60_000L)
        return t
    }

    /** Exchanges a one-time link token for a session id (null if invalid/expired). */
    fun redeem(token: String): String? {
        val l = links.remove(token) ?: return null
        if (l.third < System.currentTimeMillis()) return null
        val sid = token()
        sessions[sid] = WebSession(l.first, l.second, System.currentTimeMillis() + plugin.cfg.sessionMinutes * 60_000L)
        return sid
    }

    fun session(id: String?): WebSession? {
        if (id == null) return null
        val s = sessions[id] ?: return null
        if (s.expires < System.currentTimeMillis()) { sessions.remove(id); return null }
        return s
    }

    fun end(id: String?) { if (id != null) sessions.remove(id) }

    private fun purge() {
        val now = System.currentTimeMillis()
        links.entries.removeIf { it.value.third < now }
        sessions.entries.removeIf { it.value.expires < now }
    }
}
