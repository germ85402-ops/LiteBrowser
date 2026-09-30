package app.svetlo

import app.svetlo.data.Prefs

/** Small per-origin overrides; private grants remain in the Activity's in-memory map. */
object SiteSettings {
    private fun key(url: String, setting: String) = Origin.of(url)?.let { "site:$it|$setting" }
    fun javascript(url: String, default: Boolean) = key(url, "js")?.let { Prefs.sp.getBoolean(it, default) } ?: default
    fun setJavascript(url: String, value: Boolean) { key(url, "js")?.let { Prefs.sp.edit().putBoolean(it, value).apply() } }
    fun desktop(url: String) = key(url, "desktop")?.let { Prefs.sp.getBoolean(it, false) } ?: false
    fun setDesktop(url: String, value: Boolean) { key(url, "desktop")?.let { Prefs.sp.edit().putBoolean(it, value).apply() } }
    fun decision(origin: String, resource: String): Boolean? {
        val k = key(origin, "permission:$resource") ?: return null
        return if (Prefs.sp.contains(k)) Prefs.sp.getBoolean(k, false) else null
    }
    fun decision(origin: String, resource: String, value: Boolean) { key(origin, "permission:$resource")?.let { Prefs.sp.edit().putBoolean(it, value).apply() } }
    fun clearDecision(origin: String, resource: String) { key(origin, "permission:$resource")?.let { Prefs.sp.edit().remove(it).apply() } }
    fun reset(url: String) {
        val origin = Origin.of(url) ?: return
        val edit = Prefs.sp.edit()
        Prefs.sp.all.keys.filter { it.startsWith("site:$origin|") }.forEach(edit::remove)
        edit.apply()
    }
}
