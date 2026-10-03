package com.anime

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeDekhoPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeDekhoProvider())
        
        registerExtractorAPI(Zephyrflick())
        registerExtractorAPI(Vexal())
        registerExtractorAPI(Abyss())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(Cloudy())
        registerExtractorAPI(EmTurboVid())
        registerExtractorAPI(VidMolyNet())
        registerExtractorAPI(GDMirrorbot())
        registerExtractorAPI(GDMirrorbotFHD())
        registerExtractorAPI(Blakite())
        registerExtractorAPI(FilesForever())
        registerExtractorAPI(WorldMirror())
        registerExtractorAPI(Rpmshare())
        registerExtractorAPI(Streamp2p())
        registerExtractorAPI(Streamhg())
        registerExtractorAPI(Earnvids())
        registerExtractorAPI(Byse())
        registerExtractorAPI(XerverMirror())
        registerExtractorAPI(NeoCDN())
    }
}
