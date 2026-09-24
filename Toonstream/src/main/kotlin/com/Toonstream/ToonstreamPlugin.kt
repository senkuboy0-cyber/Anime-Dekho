package com.Toonstream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ToonstreamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ToonstreamProvider())

        // Servers present on toonstream.us iframes
        registerExtractorAPI(Zephyrflick())
        registerExtractorAPI(Abyss())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(Cloudy())
        registerExtractorAPI(EmTurboVid())
        registerExtractorAPI(VidMolyNet())

        // (not always in current iframes, but kept for coverage)
        registerExtractorAPI(GDMirrorbot())
        registerExtractorAPI(GDMirrorbotFHD())
        registerExtractorAPI(Blakite())

        // filesforever.link (Watch/DL SD-HD-FHD)
        registerExtractorAPI(FilesForever())
    }
}