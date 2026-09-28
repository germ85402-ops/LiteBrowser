package app.svetlo

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import java.security.MessageDigest

/**
 * Records media that a page feeds into Media Source Extensions (YouTube and most modern players),
 * where there is no downloadable file URL. The page hook forwards every appended segment while a
 * recording is active; each SourceBuffer (usually one video and one audio track) becomes its own file.
 * What gets recorded is what the player buffers, so the video has to be played through.
 */
class MseRecorder(private val ctx: Context, private val onChange: () -> Unit) {
    private class Track(val id: Int, var type: String) {
        var target: OutputTarget? = null
        var entryId: String? = null
        var initHash: String? = null
        var init: ByteArray? = null
        var part = 1
        var bytes = 0L
        var reported = 0L
    }

    private val lock = Any()
    private val tracks = HashMap<Int, Track>()
    private var tab: Tab? = null
    private var base = "video"
    private var source = ""
    private val saved = ArrayList<String>()

    val recordingTab: Tab? get() = tab
    val bytes: Long get() = synchronized(lock) { tracks.values.sumOf { it.bytes } }

    fun start(tab: Tab, title: String?) = synchronized(lock) {
        stopLocked()
        this.tab = tab
        base = HlsDownloadService.fileBase(title)
        source = tab.url
        saved.clear()
    }

    /** Finishes all files; returns their display names. */
    fun stop(): List<String> = synchronized(lock) {
        stopLocked()
        tab = null
        ArrayList(saved)
    }.also { onChange() }

    private fun stopLocked() {
        tracks.values.forEach { closeTrack(it) }
        tracks.clear()
    }

    private fun closeTrack(t: Track) {
        val target = t.target ?: return
        t.target = null
        val id = t.entryId
        if (t.bytes == 0L) { target.abort(); id?.let(DownloadRegistry::remove); return }
        val ok = runCatching { target.commit() }.isSuccess
        if (ok) saved += target.displayName else target.abort()
        id?.let { eid ->
            DownloadRegistry.update(eid) {
                it.copy(
                    status = if (ok) DownloadStatus.DONE else DownloadStatus.FAILED, name = target.displayName,
                    contentUri = target.contentUri?.toString(), bytes = t.bytes, total = t.bytes, percent = 100,
                    message = if (ok) "Записано из плеера" else "Не удалось сохранить запись",
                )
            }
        }
    }

    /** JS bridge for one tab. Calls arrive on a WebView binder thread. */
    inner class Bridge(private val owner: Tab) {
        @JavascriptInterface
        fun detected(id: Int, type: String) {
            if (id !in 1..MAX_MSE_TRACKS) return
            val safeType = normalizeSourceBufferType(type) ?: return
            val added = synchronized(owner.mseTypes) {
                if (safeType in owner.mseTypes || owner.mseTypes.size >= MAX_MSE_TRACKS) false
                else owner.mseTypes.add(safeType)
            }
            if (added) onChange()
        }

        @JavascriptInterface
        fun chunk(id: Int, type: String, init: Boolean, b64: String) {
            // Every website can call a JavascriptInterface. Avoid decoding page-controlled data
            // unless this tab is actively recording, and bound the native allocation first.
            val recording = synchronized(lock) { tab === owner }
            if (!mayDecodeChunk(recording, id, b64.length)) return
            val data = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return
            if (data.isEmpty()) return
            synchronized(lock) {
                if (tab !== owner) return
                val t = tracks[id] ?: run {
                    if (tracks.size >= MAX_MSE_TRACKS) return
                    Track(id, normalizeSourceBufferType(type).orEmpty()).also { tracks[id] = it }
                }
                if (t.type.isBlank()) t.type = normalizeSourceBufferType(type).orEmpty()
                if (init) onInit(t, data) else write(t, data)
            }
        }
    }

    private fun onInit(t: Track, data: ByteArray) {
        val hash = MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }
        if (hash == t.initHash) return
        // A different init segment means a quality or codec switch; mixing them in one file breaks playback.
        if (t.target != null && t.bytes > 0) { closeTrack(t); t.part++; t.bytes = 0; t.reported = 0 }
        t.initHash = hash
        t.init = data
        if (t.type.isBlank()) t.type = sniffType(data)
    }

    private fun write(t: Track, data: ByteArray) {
        val init = t.init ?: return // segments without their init segment can't be played
        val target = t.target ?: open(t, init) ?: return
        runCatching { target.stream.write(data) }.onFailure { closeTrack(t); return }
        t.bytes += data.size
        if (t.bytes - t.reported > 512 * 1024) {
            t.reported = t.bytes
            t.entryId?.let { id -> DownloadRegistry.update(id) { it.copy(bytes = t.bytes, status = DownloadStatus.RUNNING) } }
            onChange()
        }
    }

    private fun open(t: Track, init: ByteArray): OutputTarget? {
        val (ext, mime, audio) = format(t.type, init)
        val name = base + (if (audio) " (аудио)" else "") + (if (t.part > 1) " (часть ${t.part})" else "") + ".$ext"
        val target = runCatching { OutputTarget(ctx, name, mime) }.getOrNull() ?: return null
        if (runCatching { target.stream.write(init) }.isFailure) { target.abort(); return null }
        t.target = target
        val id = "mse-${System.currentTimeMillis()}-${t.id}-${t.part}"
        t.entryId = id
        DownloadRegistry.put(
            DownloadEntry(id = id, name = name, source = source, status = DownloadStatus.RUNNING,
                createdAt = System.currentTimeMillis(), mime = mime, message = "Запись из плеера"),
        )
        return target
    }

    companion object {
        const val BRIDGE = "SvetloMse"

        // MSE pages usually use one video and one audio SourceBuffer. Keep generous room for
        // adaptive streams while bounding bridge allocations and per-page track bookkeeping.
        internal const val MAX_MSE_TRACKS = 8
        internal const val MAX_MSE_TYPE_LENGTH = 128
        internal const val MAX_MSE_CHUNK_BASE64_CHARS = 16 * 1024 * 1024

        internal fun mayDecodeChunk(recording: Boolean, id: Int, encodedLength: Int): Boolean =
            recording && id in 1..MAX_MSE_TRACKS && encodedLength in 1..MAX_MSE_CHUNK_BASE64_CHARS

        internal fun normalizeSourceBufferType(type: String): String? =
            type.takeIf { it.length <= MAX_MSE_TYPE_LENGTH }?.trim()?.ifBlank { "media" }

        internal fun sniffType(init: ByteArray): String {
            val s = String(init, Charsets.ISO_8859_1)
            val webm = init.size > 4 && init[0] == 0x1A.toByte() && init[1] == 0x45.toByte()
            val audio = if (webm) !s.contains("V_VP") && !s.contains("V_AV1") && (s.contains("A_OPUS") || s.contains("A_VORBIS"))
            else s.indexOf("hdlr").let { it >= 0 && s.startsWith("soun", it + 12) }
            return (if (audio) "audio/" else "video/") + if (webm) "webm" else "mp4"
        }

        /** File extension, MIME type and whether the track is audio-only. */
        internal fun format(type: String, init: ByteArray): Triple<String, String, Boolean> {
            val t = type.lowercase().ifBlank { sniffType(init) }.let { if (it.startsWith("audio") || it.startsWith("video")) it else sniffType(init) }
            val audio = t.startsWith("audio")
            val webm = "webm" in t
            return when {
                webm && audio -> Triple("weba", "audio/webm", true)
                webm -> Triple("webm", "video/webm", false)
                audio -> Triple("m4a", "audio/mp4", true)
                else -> Triple("mp4", "video/mp4", false)
            }
        }

        /**
         * Page hook, idempotent per document. [arm] starts forwarding immediately so a page reloaded for
         * recording is captured from its first segment.
         */
        fun hook(arm: Boolean) = (if (arm) "window.__svMseArm=1;if(window.__svMseRec)window.__svMseRec(true);" else "") + HOOK

        private const val HOOK = """(function(){if(window.__svMse||!window.MediaSource||!window.$BRIDGE)return;window.__svMse=1;
var B=window.$BRIDGE,MS=MediaSource.prototype,SB=SourceBuffer.prototype,add=MS.addSourceBuffer,app=SB.appendBuffer,n=0,rec=!!window.__svMseArm,all=[];
function u8(d){return d instanceof ArrayBuffer?new Uint8Array(d):new Uint8Array(d.buffer,d.byteOffset,d.byteLength)}
function isInit(u){if(u.length<8)return false;var t=String.fromCharCode(u[4],u[5],u[6],u[7]);
return t==='ftyp'||t==='moov'||(u[0]===0x1A&&u[1]===0x45&&u[2]===0xDF&&u[3]===0xA3)}
function b64(u){var s='',C=32768;for(var i=0;i<u.length;i+=C)s+=String.fromCharCode.apply(null,u.subarray(i,i+C));return btoa(s)}
function tag(sb,type){if(!sb.__sv){sb.__sv={id:++n,type:type||'',init:null,sent:false};all.push(sb);try{B.detected(sb.__sv.id,sb.__sv.type)}catch(e){}}return sb.__sv}
function send(m,u,init){m.sent=true;B.chunk(m.id,m.type,init,b64(u))}
MS.addSourceBuffer=function(type){var sb=add.apply(this,arguments);tag(sb,String(type));return sb};
SB.appendBuffer=function(d){try{var m=tag(this,''),u=u8(d);if(isInit(u)){m.init=u.slice(0);if(rec)send(m,u,true)}
else if(rec){if(!m.sent&&m.init)send(m,m.init,true);send(m,u,false)}}catch(e){}return app.apply(this,arguments)};
window.__svMseRec=function(on){rec=!!on;all.forEach(function(sb){sb.__sv.sent=false})};})();"""
    }
}
