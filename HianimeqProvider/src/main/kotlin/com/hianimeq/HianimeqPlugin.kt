package com.hianimeq

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HianimeqPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HianimeqProvider())
        registerExtractorAPI(ZokoAnime())
        registerExtractorAPI(MegaPlay())
    }
}