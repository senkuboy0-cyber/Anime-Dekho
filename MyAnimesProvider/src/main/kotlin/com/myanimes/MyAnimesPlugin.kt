package com.myanimes

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.myanimes.settings.ScheduleScreen

@CloudstreamPlugin
class MyAnimesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MyAnimesProvider())
        registerExtractorAPI(Abyss())
        registerExtractorAPI(Cloudy())
        registerExtractorAPI(StreamP2P())
        registerExtractorAPI(RubyVidHub())
        registerExtractorAPI(Streamhg())
        registerExtractorAPI(Earnvids())
        registerExtractorAPI(Luluvdo())
        registerExtractorAPI(Vidara())
        registerExtractorAPI(Vidmoly())
        registerExtractorAPI(FilesForever())
        registerExtractorAPI(Byse())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(MixDrop())
        registerExtractorAPI(DoodStream())
        openSettings = { ctx ->
            ScheduleScreen(ctx).show()
        }
    }
}
