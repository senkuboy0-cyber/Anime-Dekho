package com.hianime

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HiAnimePlugin : BasePlugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(HiAnimeProvider())
        registerExtractorAPI(ZokoAnime())
        registerExtractorAPI(MegaPlay())
    }
}
