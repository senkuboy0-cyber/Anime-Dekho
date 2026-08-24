package com.tooniboy

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TooniboyPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Tooniboy())
        registerExtractorAPI(Zephyrflick())
        registerExtractorAPI(Abyss())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(Cloudy())
        registerExtractorAPI(GDMirrorbot())
        registerExtractorAPI(GDMirrorbotFHD())
        registerExtractorAPI(EmTurboVid())
        registerExtractorAPI(VidMolyNet())
        registerExtractorAPI(Blakite())
    }
}
