package com.hianime

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

// Entry point of the HiAnime plugin.
// Registers the main provider and the two extractors used by its servers.
@CloudstreamPlugin
class HianimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HianimeProvider())
        registerExtractorAPI(ZokoAnime())
        registerExtractorAPI(MegaPlay())
    }
}
