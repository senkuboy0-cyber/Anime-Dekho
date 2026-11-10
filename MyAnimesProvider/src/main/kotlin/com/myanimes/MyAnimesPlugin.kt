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
        openSettings = { ctx ->
            ScheduleScreen(ctx).show()
        }
    }
}
