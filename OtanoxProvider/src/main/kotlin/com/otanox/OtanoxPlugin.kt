package com.otanox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OtanoxPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(OtanoxProvider())
        registerExtractorAPI(MegaPlay())
        registerExtractorAPI(Flixcloud())
    }
}