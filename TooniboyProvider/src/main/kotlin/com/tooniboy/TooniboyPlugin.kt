package com.tooniboy

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TooniboyPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Tooniboy())

        // Direct iframe servers on tooniboy.xyz
        registerExtractorAPI(Ravok())
        registerExtractorAPI(Abyss())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(VidMolyNet())
        registerExtractorAPI(Cloudy())
        registerExtractorAPI(UpnsPlayer())
        registerExtractorAPI(Blakite())
        registerExtractorAPI(Vidara())

        // FilesForever system + all its mirror extractors
        registerExtractorAPI(FilesForever())
        registerExtractorAPI(Streamhg())
        registerExtractorAPI(Earnvids())
        registerExtractorAPI(Luluvdo())
        registerExtractorAPI(Byse())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(MixDrop())
        registerExtractorAPI(DoodStream())
    }
}
