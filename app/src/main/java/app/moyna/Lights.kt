package app.moyna

import android.util.Log

/**
 * The smart-plug light buttons on the hub. Which plugs, and what the buttons say, come from
 * LIGHTS in personal.properties:  LIGHTS=English name|Bengali name|IP address, ... (one per plug)
 * e.g.  LIGHTS=Lamp|বাতি|10.0.0.20,Kitchen|রান্নাঘর|10.0.0.21
 * Give the plugs fixed addresses in the router so they don't change.
 */
object Lights {
    class Light(val nameEn: String, val nameBn: String, val ip: String) {
        @Volatile var on: Boolean? = null          // null = not known yet / plug not answering
        @Volatile var busy = false
    }

    val all: List<Light> = BuildConfig.LIGHTS.split(",").mapNotNull { entry ->
        val p = entry.split("|").map { it.trim() }
        if (p.size == 3 && p[2].isNotEmpty()) Light(p[0], p[1], p[2]) else null
    }

    /** Called (on a background thread) whenever a light's on/off state is learned or changes. */
    @Volatile var listener: (() -> Unit)? = null

    fun ready() = all.isNotEmpty() && Kasa.configured()

    fun get(index: Int): Light? = all.getOrNull(index)

    /** Ask every plug whether it is on (lights can also change from Alexa or their own timers). */
    fun refreshAll() {
        if (!ready()) return
        all.forEach { refresh(it) }
    }

    private fun refresh(l: Light, after: (() -> Unit)? = null) {
        Kasa.send(l.ip, """{"system":{"get_sysinfo":{}}}""") { r ->
            val info = r?.optJSONObject("system")?.optJSONObject("get_sysinfo")
            l.on = info?.let { it.optInt("relay_state", 0) == 1 }
            listener?.invoke()
            after?.invoke()
        }
    }

    /** Switch a light to the other state. [done] gets true if the plug confirmed it. */
    fun toggle(index: Int, done: (Boolean) -> Unit) {
        val l = get(index) ?: return done(false)
        set(index, l.on != true, done)                // unknown counts as off: turn it on
    }

    /** Find a light by its English or Bengali name (as Moyna's voice tool names it), or -1. */
    fun indexOf(name: String): Int = all.indexOfFirst {
        it.nameEn.equals(name.trim(), ignoreCase = true) || it.nameBn == name.trim()
    }

    /** Switch a light on or off. [done] gets true if the plug confirmed it. */
    fun set(index: Int, want: Boolean, done: (Boolean) -> Unit) {
        val l = get(index) ?: return done(false)
        if (l.busy) return done(false)
        l.busy = true; listener?.invoke()
        Kasa.send(l.ip, """{"system":{"set_relay_state":{"state":${if (want) 1 else 0}}}}""") { r ->
            val ok = r?.optJSONObject("system")?.optJSONObject("set_relay_state")?.optInt("err_code", -1) == 0
            Log.i("Moyna", "light ${l.nameEn} -> ${if (want) "on" else "off"}: ${if (ok) "ok" else "failed"}")
            refresh(l) { l.busy = false; listener?.invoke(); done(ok && l.on == want) }
        }
    }
}
