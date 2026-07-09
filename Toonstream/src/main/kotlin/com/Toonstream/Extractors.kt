package com.Toonstream


import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

class StreamSB8 : StreamSB() {
    override var mainUrl = "https://streamsb.net"
}

class Cloudy : VidStack() {
    override var mainUrl = "https://cloudy.upns.one"
}

open class GDMirrorbot : ExtractorApi() {
    override var name = "GDMirrorbot"
    override var mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val (sid, host) = if (!url.contains("key=")) {
            Pair(url.substringAfterLast("embed/"), getBaseUrl(app.get(url).url))
        } else {
            var pageText = app.get(url).text
            val finalId = Regex("""FinalID\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val myKey = Regex("""myKey\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val idType = Regex("""idType\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1) ?: "imdbid"
            val baseUrl = Regex("""let\s+baseUrl\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val hostUrl = baseUrl?.let { getBaseUrl(it) }

            if (finalId != null && myKey != null) {
                val apiUrl = if (url.contains("/tv/")) {
                    val season = Regex("""/tv/\d+/(\d+)/""").find(url)?.groupValues?.get(1) ?: "1"
                    val episode = Regex("""/tv/\d+/\d+/(\d+)""").find(url)?.groupValues?.get(1) ?: "1"
                    "$mainUrl/myseriesapi?tmdbid=$finalId&season=$season&epname=$episode&key=$myKey"
                } else {
                    "$mainUrl/mymovieapi?$idType=$finalId&key=$myKey"
                }
                pageText = app.get(apiUrl).text
            }

            val jsonElement = JsonParser.parseString(pageText)
            if (!jsonElement.isJsonObject) return
            val jsonObject = jsonElement.asJsonObject

            val embedId = url.substringAfterLast("/")
            val sidValue = jsonObject["data"]?.asJsonArray
                ?.takeIf { it.size() > 0 }
                ?.get(0)?.asJsonObject
                ?.get("fileslug")?.asString
                ?.takeIf { it.isNotBlank() } ?: embedId

            Pair(sidValue, hostUrl)
        }

        val postData = mapOf("sid" to sid)
        val responseText = app.post("$host/embedhelper.php", data = postData).text

        val rootElement = JsonParser.parseString(responseText)
        if (!rootElement.isJsonObject) return
        val root = rootElement.asJsonObject

        val siteUrls = root["siteUrls"]?.asJsonObject ?: return
        val siteFriendlyNames = root["siteFriendlyNames"]?.asJsonObject

        val decodedMresult = when {
            root["mresult"]?.isJsonObject == true -> root["mresult"]!!.asJsonObject
            root["mresult"]?.isJsonPrimitive == true -> try {
                base64Decode(root["mresult"]!!.asString)
                    .let { JsonParser.parseString(it).asJsonObject }
            } catch (e: Exception) {
                Log.e("GDMirrorbot", "Failed to decode mresult: $e")
                return
            }
            else -> return
        }

        siteUrls.keySet().intersect(decodedMresult.keySet()).forEach { key ->
            val base = siteUrls[key]?.asString?.trimEnd('/') ?: return@forEach
            val path = decodedMresult[key]?.asString?.trimStart('/') ?: return@forEach
            val fullUrl = "$base/$path"
            val friendlyName = siteFriendlyNames?.get(key)?.asString ?: key

            try {
                when (friendlyName) {
                    "StreamHG","EarnVids" -> VidHidePro().getUrl(fullUrl, referer, subtitleCallback, callback)
                    "RpmShare", "UpnShare", "StreamP2p" -> VidStack().getUrl(fullUrl, referer, subtitleCallback, callback)
                    else -> loadExtractor(fullUrl, referer ?: mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("GDMirrorbot", "Failed to extract from $friendlyName at $fullUrl: $e")
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}

class Techinmind: GDMirrorbot() {
    override var name = "Techinmind Cloud AIO"
    override var mainUrl = "https://stream.techinmind.space"
    override var requiresReferer = true
}

// -----------------------------------------------------------
// FIX: Changed mainUrl from "streamruby.com" to "rubystm.com"
// The site now uses rubystm.com as the embed domain
// -----------------------------------------------------------
open class Streamruby : ExtractorApi() {
    override var name = "Streamruby"
    override var mainUrl = "rubystm.com"  // WAS: "streamruby.com" — FIXED
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val newUrl = if (url.contains("/e/")) url.replace("/e/", "/") else url
        val txt = app.get(newUrl, referer = referer).text
        val m3u8 = Regex("""file:\s*["'](.*?m3u8.*?)["']""").find(txt)?.groupValues?.getOrNull(1)

        return m3u8?.takeIf { it.isNotEmpty() }?.let {
            listOf(
                newExtractorLink(
                    name = this.name,
                    source = this.name,
                    url = it,
                    type = INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}


class Cdnwish : StreamWishExtractor() {
    override var mainUrl = "https://cdnwish.com"
}

class vidhidevip : VidhideExtractor() {
    override var mainUrl = "https://vidhidevip.com"
}

class D000d : DoodLaExtractor() {
    override var mainUrl = "https://d000d.com"
}


class FileMoonnl : Filesim() {
    override val mainUrl = "https://filemoon.nl"
    override val name = "FileMoon"
}

// -----------------------------------------------------------
// NEW: VidMoly.net extractor (different domain from vidmoly.me)
// Server 6 (Moly) uses vidmoly.net — not vidmoly.me
// Parses m3u8 URL directly from the page HTML
// -----------------------------------------------------------
class VidMolyNet : ExtractorApi() {
    override var name = "VidMolyNet"
    override var mainUrl = "https://vidmoly.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val txt = app.get(url, referer = referer ?: mainUrl).text

        // Try jwplayer "file" key first
        val m3u8fromFile = Regex("""["']file["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            .find(txt)?.groupValues?.getOrNull(1)

        // Fallback: search raw m3u8 URL in page
        val m3u8fromRaw = if (m3u8fromFile == null) {
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(txt)?.value
        } else null

        val m3u8 = m3u8fromFile ?: m3u8fromRaw

        return m3u8?.takeIf { it.isNotEmpty() }?.let {
            listOf(
                newExtractorLink(
                    name = this.name,
                    source = this.name,
                    url = it,
                    type = INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

// -----------------------------------------------------------
// NEW: EmTurboVid extractor
// Server 9 (Turbo) — m3u8 URL is stored in data-hash attribute
// of #video_player div. Standard EmturbovidExtractor may not
// handle this pattern.
// -----------------------------------------------------------
open class EmTurboVid : ExtractorApi() {
    override var name = "EmTurboVid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer ?: mainUrl).document

        // m3u8 is in data-hash attribute of #video_player
        val m3u8 = doc.selectFirst("#video_player[data-hash]")?.attr("data-hash")
            ?.takeIf { it.contains(".m3u8") }
            ?: return

        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// Mirror for turboviplay.com (same player, different domain)
class TurboViPlay : EmTurboVid() {
    override var name = "TurboViPlay"
    override var mainUrl = "https://turboviplay.com"
}
