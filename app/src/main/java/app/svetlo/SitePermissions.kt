package app.svetlo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest

/** Origin-scoped permission prompts, with stale/cancelled request checks at every async boundary. */
class SitePermissions(
    private val activity: Activity,
    private val incognito: Boolean,
    private val isCurrent: (Tab) -> Boolean,
    private val requestAndroid: (List<String>, () -> Unit) -> Unit,
    private val notify: (String) -> Unit,
) {
    private val siteDecisions = HashMap<String, Boolean>()
    private val cancelledMediaRequests = java.util.Collections.newSetFromMap(java.util.WeakHashMap<PermissionRequest, Boolean>())
    private var siteDialog: AlertDialog? = null
    fun dismiss() { siteDialog?.dismiss() }
    fun cancel(request: PermissionRequest) { cancelledMediaRequests.add(request); dismiss() }
    fun reset(origin: String) { siteDecisions.keys.removeAll { it.startsWith("$origin|") } }
    fun setDecision(origin: String, key: String, decision: Boolean?) {
        siteDecisions.remove("$origin|$key")
        siteDecisions.remove("$origin|media:true:true")
        if (!incognito) SiteSettings.clearDecision(origin, "media:true:true")
        if (decision == null) { if (!incognito) SiteSettings.clearDecision(origin, key) }
        else if (incognito) siteDecisions["$origin|$key"] = decision
        else SiteSettings.decision(origin, key, decision)
    }
    private fun hasPermission(p: String) = activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    /** Asks once per host and session, then requests the matching Android runtime permissions. */
    private fun askSite(host: String, what: String, key: String, perms: List<String>, anyOf: Boolean, valid: () -> Boolean, onResult: (Boolean) -> Unit) {
        val grantAndroid = {
            requestAndroid(perms) {
                val ok = if (anyOf) perms.any(::hasPermission) else perms.all(::hasPermission)
                if (!ok) notify("Нет разрешения Android на доступ к $what")
                onResult(ok && valid())
            }
        }
        if (!valid()) { onResult(false); return }
        val origin = Origin.of(host) ?: run { onResult(false); return }
        when (siteDecisions["$origin|$key"] ?: if (!incognito) SiteSettings.decision(origin, key) else null) {
            true -> { grantAndroid(); return }
            false -> { onResult(false); return }
            null -> Unit
        }
        siteDialog?.dismiss()
        var decided: Boolean? = null
        siteDialog = AlertDialog.Builder(activity)
            .setTitle(host)
            .setMessage("Сайт запрашивает доступ к $what")
            .setPositiveButton(activity.getString(app.svetlo.R.string.label_616bb19d6c)) { _, _ -> decided = true }
            .setNegativeButton(activity.getString(app.svetlo.R.string.label_21aba91387)) { _, _ -> decided = false }
            .setOnDismissListener {
                siteDialog = null
                if (!valid()) { onResult(false); return@setOnDismissListener }
                decided?.let {
                    siteDecisions["$origin|$key"] = it
                    if (!incognito) SiteSettings.decision(origin, key, it)
                }
                if (decided == true) grantAndroid() else onResult(false)
            }
            .show()
    }

    fun onSitePermission(owner: Tab, request: PermissionRequest) {
        val generation = owner.generation
        val valid = { isCurrent(owner) && owner.generation == generation && request !in cancelledMediaRequests && !activity.isFinishing && !activity.isDestroyed }
        if (!valid()) { request.deny(); return }
        val wanted = request.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        }
        if (wanted.isEmpty()) { request.deny(); return }
        val cam = PermissionRequest.RESOURCE_VIDEO_CAPTURE in wanted
        val mic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in wanted
        // Protected media (Widevine) needs no hardware access; grant it like other browsers do.
        if (!cam && !mic) { request.grant(wanted.toTypedArray()); return }
        val what = when { cam && mic -> activity.getString(app.svetlo.R.string.label_ca351e4fa2); cam -> activity.getString(app.svetlo.R.string.label_b6bb83bb8e); else -> activity.getString(app.svetlo.R.string.label_d5294ac6ae) }
        val perms = buildList { if (cam) add(Manifest.permission.CAMERA); if (mic) add(Manifest.permission.RECORD_AUDIO) }
        askSite(request.origin.toString(), what, "media:$cam:$mic", perms, anyOf = false, valid = valid) { ok ->
            runCatching { if (ok) request.grant(wanted.toTypedArray()) else request.deny() }
        }
    }

    fun onGeolocationPrompt(owner: Tab, origin: String, callback: GeolocationPermissions.Callback) {
        val perms = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val generation = owner.generation
        val valid = { isCurrent(owner) && owner.generation == generation && !activity.isFinishing && !activity.isDestroyed }
        askSite(origin, activity.getString(app.svetlo.R.string.label_ff29c197a8), "geo", perms, anyOf = true, valid = valid) { ok ->
            callback.invoke(origin, ok, false)
        }
    }

}
