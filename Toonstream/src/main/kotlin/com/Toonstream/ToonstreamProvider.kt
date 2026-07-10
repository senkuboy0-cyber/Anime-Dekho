package com.Toonstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.extractors.Vidmolyme
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ToonstreamProvider : BasePlugin() {
    override fun load() {
        pingAnalytics("Toonstream")
        registerMainAPI(Toonstream())

        // ─── Video extractors ─────────────────────────────────────
        registerExtractorAPI(StreamSB8())
        registerExtractorAPI(Vidmolyme())          // vidmoly.me
        registerExtractorAPI(VidMolyNet())         // NEW: vidmoly.net (Server 6)
        registerExtractorAPI(Streamruby())         // FIX: rubystm.com (আগে streamruby.com)
        registerExtractorAPI(D000d())
        registerExtractorAPI(vidhidevip())
        registerExtractorAPI(Cdnwish())
        registerExtractorAPI(FileMoonnl())
        registerExtractorAPI(Cloudy())             // cloudy.upns.one VidStack (Server 5)
        registerExtractorAPI(GDMirrorbot())        // Server 2/3/4
        registerExtractorAPI(Techinmind())
        registerExtractorAPI(EmturbovidExtractor()) // built-in fallback
        registerExtractorAPI(EmTurboVid())         // NEW: data-hash extractor (Server 9)
        registerExtractorAPI(TurboViPlay())        // NEW: turboviplay.com mirror
        registerExtractorAPI(AsCdn21())            // NEW: as-cdn21.top → Zephyrflick (Server 8)
        registerExtractorAPI(AsCdn23())            // NEW: as-cdn23.top mirror
        registerExtractorAPI(Zephyrflick())        // play.zephyrflick.top
    }

    companion object {
        private const val DOMAINS_URL =
            "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
        var cachedDomains: Domains? = null

        suspend fun getDomains(forceRefresh: Boolean = false): Domains? {
            if (cachedDomains == null || forceRefresh) {
                try {
                    cachedDomains = app.get(DOMAINS_URL).parsedSafe<Domains>()
                } catch (e: Exception) {
                    e.printStackTrace()
                    return null
                }
            }
            return cachedDomains
        }

        data class Domains(
            @param:JsonProperty("toonstream")
            val Toonstream: String,
        )
    }
}
