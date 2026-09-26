package app.svetlo

/** Quality list for HLS and DASH streams, used by the UI before [HlsDownloadService.start]. */
object StreamDownloader {
    /** Best first; empty when the stream has a single quality. Throws on network/DRM/live errors. */
    fun variants(url: String, headers: Map<String, String>, kind: MediaKind): List<HlsDownloader.Variant> {
        val dl = HlsDownloader(headers) { false }
        return if (kind == MediaKind.DASH) DashDownloader(dl).variants(url) else dl.variants(url)
    }

    fun pickDefault(variants: List<HlsDownloader.Variant>): HlsDownloader.Variant? = HlsDownloader.pickDefault(variants)
}
