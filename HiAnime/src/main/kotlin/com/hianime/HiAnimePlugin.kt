package com.hianime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HiAnimePlugin : Plugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(HiAnimeProvider())
        registerExtractorAPI(ZokoAnime())
        registerExtractorAPI(MegaPlay())
    }
}