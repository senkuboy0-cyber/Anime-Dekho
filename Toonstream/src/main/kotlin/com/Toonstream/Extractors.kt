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
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

// ─── StreamSB ────────────────────────────────────────────────────
class StreamSB8 : StreamSB() {
    override var mainUrl = "https://streamsb.net"
}

// ─── Cloudy / VidStack ───────────────────────────────────────────
class Cloudy : VidStack() {
    override var mainUrl = "https://cloudy.upns.one"
}

// ─── GDMirrorbot ─────────────────────────────────────────────────
open class GDMirrorbot : ExtractorApi() {
    override var name            = "GDMirrorbot"
    override var mainUrl         = "https://gdmirrorbot.nl"
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
            val finalId  = Regex("""FinalID\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val myKey    = Regex("""myKey\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val idType   = Regex("""idType\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1) ?: "imdbid"
            val baseUrl  = Regex("""let\s+baseUrl\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            val hostUrl  = baseUrl?.let { getBaseUrl(it) }

            if (finalId != null && myKey != null) {
                val apiUrl = if (url.contains("/tv/")) {
                    val season  = Regex("""/tv/\d+/(\d+)/""").find(url)?.groupValues?.get(1) ?: "1"
                    val episode = Regex("""/tv/\d+/\d+/(\d+)""").find(url)?.groupValues?.get(1) ?: "1"
                    "$mainUrl/myseriesapi?tmdbid=$finalId&season=$season&epname=$episode&key=$myKey"
                } else {
                    "$mainUrl/mymovieapi?$idType=$finalId&key=$myKey"
                }
                pageText = app.get(apiUrl).text
            }

            val json = JsonParser.parseString(pageText)
            if (!json.isJsonObject) return
            val obj = json.asJsonObject
            val embedId  = url.substringAfterLast("/")
            val sidValue = obj["data"]?.asJsonArray
                ?.takeIf { it.size() > 0 }
                ?.get(0)?.asJsonObject
                ?.get("fileslug")?.asString
                ?.takeIf { it.isNotBlank() } ?: embedId
            Pair(sidValue, hostUrl)
        }

        val root = JsonParser.parseString(
            app.post("$host/embedhelper.php", data = mapOf("sid" to sid)).text
        ).takeIf { it.isJsonObject }?.asJsonObject ?: return

        val siteUrls         = root["siteUrls"]?.asJsonObject ?: return
        val siteFriendlyNames = root["siteFriendlyNames"]?.asJsonObject
        val decodedMresult   = when {
            root["mresult"]?.isJsonObject == true -> root["mresult"]!!.asJsonObject
            root["mresult"]?.isJsonPrimitive == true -> try {
                JsonParser.parseString(base64Decode(root["mresult"]!!.asString)).asJsonObject
            } catch (e: Exception) { return }
            else -> return
        }

        siteUrls.keySet().intersect(decodedMresult.keySet()).forEach { key ->
            val base     = siteUrls[key]?.asString?.trimEnd('/') ?: return@forEach
            val path     = decodedMresult[key]?.asString?.trimStart('/') ?: return@forEach
            val fullUrl  = "$base/$path"
            val friendly = siteFriendlyNames?.get(key)?.asString ?: key
            try {
                when (friendly) {
                    "StreamHG", "EarnVids" ->
                        VidHidePro().getUrl(fullUrl, referer, subtitleCallback, callback)
                    "RpmShare", "UpnShare", "StreamP2p" ->
                        VidStack().getUrl(fullUrl, referer, subtitleCallback, callback)
                    else ->
                        loadExtractor(fullUrl, referer ?: mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("GDMirrorbot", "Failed $friendly at $fullUrl: $e")
            }
        }
    }

    private fun getBaseUrl(url: String): String =
        URI(url).let { "${it.scheme}://${it.host}" }
}

class Techinmind : GDMirrorbot() {
    override var name            = "Techinmind Cloud AIO"
    override var mainUrl         = "https://stream.techinmind.space"
    override var requiresReferer = true
}

// ─── Streamruby ──────────────────────────────────────────────────
open class Streamruby : ExtractorApi() {
    override var name            = "Streamruby"
    override var mainUrl         = "https://rubystm.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileCode = url.substringAfterLast("/e/").substringBefore(".html")
        if (fileCode.isBlank()) return

        app.get("$mainUrl/e/$fileCode.html", referer = referer ?: mainUrl)

        val html = app.post(
            url     = "$mainUrl/dl",
            data    = mapOf(
                "op"        to "embed",
                "file_code" to fileCode,
                "auto"      to "1",
                "referer"   to (referer ?: "")
            ),
            referer = "$mainUrl/e/$fileCode.html"
        ).text

        val packed = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]+?'\|'\)\)""")
            .find(html)?.value ?: return
        val unpacked = JsUnpacker(packed).unpack() ?: return

        val m3u8 = Regex("""file\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""")
            .find(unpacked)?.groupValues?.get(1) ?: return

        Regex("""file\s*:\s*"(https?://[^"]+_([a-z]{2,3})\.vtt[^"]*)"[\s\S]+?kind\s*:\s*"captions"""")
            .findAll(unpacked).forEach { match ->
                subtitleCallback(SubtitleFile(match.groupValues[2], match.groupValues[1]))
            }

        callback(
            newExtractorLink(source = name, name = name, url = m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// ─── Misc extractors ─────────────────────────────────────────────
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
    override val name    = "FileMoon"
}

// ─── VidMolyNet ──────────────────────────────────────────────────
class VidMolyNet : ExtractorApi() {
    override var name            = "VidMolyNet"
    override var mainUrl         = "https://vidmoly.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val txt = app.get(url, referer = referer ?: mainUrl).text
        val m3u8 = Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
            .find(txt)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(txt)?.value
            ?: return null

        return listOf(
            newExtractorLink(name, name, url = m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// ─── EmTurboVid ──────────────────────────────────────────────────
open class EmTurboVid : ExtractorApi() {
    override var name            = "EmTurboVid"
    override var mainUrl         = "https://emturbovid.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc  = app.get(url, referer = referer ?: mainUrl).document
        val m3u8 = doc.selectFirst("#video_player[data-hash]")
            ?.attr("data-hash")
            ?.takeIf { it.contains(".m3u8") }
            ?: return
        callback(
            newExtractorLink(name, name, url = m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class TurboViPlay : EmTurboVid() {
    override var name    = "TurboViPlay"
    override var mainUrl = "https://turboviplay.com"
}

// ─── AsCdnBase Class (For Zephyrflick / as-cdn) ────────────
open class AsCdnBase : ExtractorApi() {
    override var name = "Zephyrflick"
    override var mainUrl = "https://z.awstream.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extractedHash = url.substringAfterLast("/")
        val doc = app.get(url).document
        
        val m3u8Url = "$mainUrl/player/index.php?data=$extractedHash&do=getVideo"
        val header = mapOf("x-requested-with" to "XMLHttpRequest")
        val formdata = mapOf("hash" to extractedHash, "r" to mainUrl)
        
        val response = app.post(m3u8Url, headers = header, data = formdata).parsedSafe<Response>()
        
        response?.videoSource?.let { m3u8 ->
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = ""
                    this.quality = Qualities.P1080.value
                }
            )
            
            val extractedPack = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().orEmpty()
            JsUnpacker(extractedPack).unpack()?.let { unpacked ->
                Regex("\"kind\":\\s*\"captions\"\\s*,\\s*\"file\":\\s*\"(https.*?\\.srt)\"")
                    .find(unpacked)
                    ?.groupValues
                    ?.get(1)
                    ?.let { subtitleUrl ->
                        subtitleCallback.invoke(
                            SubtitleFile(
                                "English",
                                subtitleUrl
                            )
                        )
                    }
            }
        }
    }

    data class Response(
        val hls: Boolean,
        val videoImage: String,
        val videoSource: String,
        val securedLink: String,
        val downloadLinks: List<Any?>,
        val attachmentLinks: List<Any?>,
        val ck: String,
    )
}

// ─── AsCdn21 & AsCdn23 Classes ──────────────────────────────────
class AsCdn21 : AsCdnBase() {
    override var name = "Zephyrflick"
    override var mainUrl = "https://as-cdn26.top"
}

class AsCdn23 : AsCdnBase() {
    override var name = "Zephyrflick"
    override var mainUrl = "https://as-cdn23.top"
}
com.lagradost.cloudstream3.SubtitleFile