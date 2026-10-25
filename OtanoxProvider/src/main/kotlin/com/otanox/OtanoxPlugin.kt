package com.otanox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

// Entry point of the Otanox plugin.
// Registers the main provider and every extractor used by its servers.
@CloudstreamPlugin
class OtanoxPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(OtanoxProvider())
        registerExtractorAPI(Ravok())
        registerExtractorAPI(Abyss())
        registerExtractorAPI(MegaPlay())
    }
}
