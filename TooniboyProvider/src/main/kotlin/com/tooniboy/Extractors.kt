package com.tooniboy

import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ═════════════════════════════════════════════════════════════════════
// Tooniboy Extractors (14 total)
//
// Direct iframe servers on tooniboy.xyz pages:
//   1. Ravok        - ravok.buzz            (top priority)
//   2. StreamRuby   - rubystm.com
//   3. VidMolyNet   - vidmoly.net
//   4. Abyss        - abyssplayer.com
//   5. Cloudy       - cloudy.upns.one
//   6. Blakite      - blakiteapi.xyz        (rumble CDN multi-quality)
//
// FilesForever system (embed -> embedhelper2.php -> 7 mirrors):
//   7. FilesForever - filesforever.link     (main router)
//   8. Streamhg     - hanerix.com           (mirror key: smwh)
//   9. UpnsPlayer   - upns.one / p2pplay    (mirror key: strmp2)
//  10. Byse         - bysetayico.com        (mirror key: flmn)
//  11. Earnvids     - morencius.com         (mirror key: flls)
//  12. StreamTape   - streamtape.site       (mirror key: strmtp)
//  13. MixDrop      - mixdrop.ag            (mirror key: mxdp)
//  14. DoodStream   - dood.li               (mirror key: ddstm)
//
// Unknown iframe hosts (voe, vidara, vidstreaming, ...) are NOT handled
// here - the provider routes them to CloudStream's built-in loadExtractor().
// ═════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────
// Priority 1: RAVOK -> ravok.buzz
// Fetches the HLS video source via a POST request and unpacks
// JavaScript to extract subtitle captions.
// ─────────────────────────────────────────────────────────────
open class Ravok : ExtractorApi() {
    override val name = "Ravok"
    override val mainUrl = "https://ravok.buzz"
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

        val response = app.post(m3u8Url, headers = header, data = formdata).parsedSafe<RavokResponse>()
        response?.videoSource?.let { m3u8 ->
            callback.invoke(
                newExtractorLink(name, name, url = m3u8, type = ExtractorLinkType.M3U8) {
                    this.referer = ""
                    this.quality = Qualities.P1080.value
                }
            )

            val extractedPack = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().orEmpty()
            JsUnpacker(extractedPack).unpack()?.let { unpacked ->
                val regex = Regex("""\u0022kind\u0022\s*:\s*\u0022captions\u0022\s*,\s*\u0022file\u0022\s*:\s*\u0022(https[^\u0022]+)\u0022.*?(?:,\s*\u0022label\u0022\s*:\s*\u0022([^\u0022]+)\u0022)?""")
                val matches = regex.findAll(unpacked).toList()
                for (i in matches.indices) {
                    val matchResult = matches[i]
                    val subtitleUrl = matchResult.groupValues[1]
                    val subtitleName = matchResult.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "Subtitle ${i + 1}"
                    subtitleCallback.invoke(SubtitleFile(subtitleName, subtitleUrl))
                }
            }
        }
    }

    data class RavokResponse(
        val hls: Boolean,
        val videoImage: String,
        val videoSource: String,
        val securedLink: String,
        val downloadLinks: List<Any?>,
        val attachmentLinks: List<Any?>,
        val ck: String,
    )
}

// ─────────────────────────────────────────────────────────────
// ABYSS -> abyssplayer.com
// Uses an external API to decrypt the AES encrypted payload
// containing video sources.
// ─────────────────────────────────────────────────────────────
class Abyss : ExtractorApi() {
    override var name = "Abyss"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/"
        )

        val document = app.get(url, headers = headers).document
        val scripts = document.select("script").joinToString("\n") { it.data() }

        val encrypted = Regex("const\\s+datas\\s*=\\s*\"([^\"]*)\"")
            .find(scripts)?.groupValues?.getOrNull(1) ?: return

        val decrypted = app.post(
            url = "https://enc-dec.app/api/dec-abyss",
            headers = headers,
            requestBody = """{"text":"$encrypted"}""".toRequestBody("application/json".toMediaType())
        ).parsedSafe<AbyssResponse>()?.result ?: return

        decrypted.sources
            .filter { it.status }
            .forEach { source ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name [${source.codec.uppercase()}]",
                        url = source.url,
                        type = INFER_TYPE
                    ) {
                        this.quality = getQualityFromName(source.type)
                        this.headers = mapOf("Referer" to "https://playhydrax.com/")
                    }
                )
            }
    }

    data class AbyssResponse(val status: Long, val result: Result)
    data class Result(val sources: List<AbyssSource>)
    data class AbyssSource(
        val url: String,
        val size: Long,
        val type: String,
        val codec: String,
        val status: Boolean,
    )
}

// ─────────────────────────────────────────────────────────────
// STREAMRUBY -> rubystm.com (/dl POST + JS unpack)
// ─────────────────────────────────────────────────────────────
class StreamRuby : ExtractorApi() {
    override var name = "StreamRuby"
    override var mainUrl = "https://rubystm.com"
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
            url = "$mainUrl/dl",
            data = mapOf(
                "op" to "embed",
                "file_code" to fileCode,
                "auto" to "1",
                "referer" to (referer ?: "")
            ),
            referer = "$mainUrl/e/$fileCode.html"
        ).text

        val packed = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]+?'\|'\)\)""")
            .find(html)?.value ?: return
        val unpacked = JsUnpacker(packed).unpack() ?: return

        val m3u8 = Regex("""file\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""")
            .find(unpacked)?.groupValues?.get(1) ?: return

        Regex("""file\s*:\s*"(https?://[^"]+_([a-z]{2,3})\.vtt[^"]*)""[\s\S]+?kind\s*:\s*"captions"""")
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

// ─────────────────────────────────────────────────────────────
// VIDMOLY -> vidmoly.net (jwplayer file regex)
// ─────────────────────────────────────────────────────────────
class VidMolyNet : ExtractorApi() {
    override var name = "VidMolyNet"
    override var mainUrl = "https://vidmoly.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val txt = app.get(url, referer = referer ?: mainUrl).text

        val m3u8 = Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
            .find(txt)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(txt)?.value
            ?: return

        Regex("""file\s*:\s*['"](https[^'"]+\.vtt[^'"]*)['"][\s\S]{0,200}?label\s*:\s*['"]([^'"]*)['"]""")
            .find(txt)?.let { match ->
                subtitleCallback(SubtitleFile(match.groupValues[2].ifBlank { "English" }, match.groupValues[1]))
            }

        callback(
            newExtractorLink(name, name, url = m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// CLOUDY -> cloudy.upns.one (and UpnsPlayer base for p2pplay)
// API returns AES-CBC encrypted hex JSON.
// ─────────────────────────────────────────────────────────────
class Cloudy : UpnsPlayer() {
    override var name = "Cloudy"
    override var mainUrl = "https://cloudy.upns.one"
}

open class UpnsPlayer : ExtractorApi() {
    override var name = "Upns"
    override var mainUrl = "https://upns.one"
    override val requiresReferer = true

    companion object {
        private const val AES_KEY = "kiemtienmua911ca"
        private const val AES_IV = "1234567890oiuytr"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseurl = getBaseUrl(url)
        val hash = url.substringAfterLast("#").substringBefore("&").substringBefore("?")
            .ifBlank { url.trimEnd('/').substringAfterLast('/') }
        if (hash.isBlank()) return

        val refHost = try { URI(referer ?: mainUrl).host ?: mainUrl.removePrefix("https://") }
            catch (e: Exception) { mainUrl.removePrefix("https://") }

        val encoded = try {
            app.get("$baseurl/api/v1/video?id=$hash&w=1280&h=720&r=$refHost",
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "*/*"),
                referer = referer ?: "$baseurl/").text.trim()
        } catch (e: Exception) { Log.e(name, "API failed: ${e.message}"); return }
        if (encoded.isBlank()) return

        val decryptedJson = decryptHex(encoded) ?: run { Log.e(name, "AES decrypt failed"); return }
        val obj = try { JSONObject(decryptedJson) } catch (e: Exception) { Log.e(name, "JSON parse failed: ${e.message}"); return }

        // streamingConfig gives the real CDN URL; source/hls are fallbacks.
        val finalUrl = buildFromStreamingConfig(obj)
            ?: buildFromAbsolutePath(obj)
            ?: run { Log.e(name, "no playable URL found in response"); return }

        callback(newExtractorLink(name, name, url = finalUrl, type = ExtractorLinkType.M3U8) {
            this.referer = "$baseurl/"; this.quality = Qualities.Unknown.value
        })

        val subs = obj.optJSONObject("subtitle")
        subs?.keys()?.forEach { lang ->
            val rawPath = subs.optString(lang).split("#").firstOrNull().orEmpty()
            if (rawPath.isNotBlank()) {
                val subUrl = if (rawPath.startsWith("http")) rawPath else "$baseurl$rawPath"
                subtitleCallback(SubtitleFile(lang.uppercase(), subUrl))
            }
        }
    }

    /**
     * Builds the video URL from streamingConfig.adjust domains + the relative video path.
     * Returns null if streamingConfig is absent or all candidates are disabled.
     */
    private fun buildFromStreamingConfig(obj: JSONObject): String? {
        return try {
            var videoPath = obj.optString("source").takeIf { it.isNotEmpty() && !it.startsWith("http") }
                ?: obj.optString("hls").takeIf { it.isNotEmpty() && !it.startsWith("http") }
                ?: return null

            val cfgRaw = obj.optJSONObject("streamingConfig")?.toString()
                ?: obj.optString("streamingConfig").takeIf { it.isNotBlank() }
                ?: return null

            val cfg = JSONObject(cfgRaw)
            val adjust = cfg.optJSONObject("adjust") ?: return null
            val order = cfg.optJSONArray("order")

            val candidates = mutableListOf<JSONObject>()
            if (order != null) {
                for (i in 0 until order.length())
                    adjust.optJSONObject(order.getString(i))?.let { candidates.add(it) }
            } else {
                adjust.keys().forEach { k -> adjust.optJSONObject(k)?.let { candidates.add(it) } }
            }

            for (c in candidates) {
                if (c.optBoolean("disabled", false)) continue
                val rawDomain = c.optString("domain").takeIf { it.isNotBlank() } ?: continue
                val cleanDomain = rawDomain.removePrefix("https://").removePrefix("http://").trimEnd('/')
                val cleanPath = if (videoPath.startsWith("/")) videoPath else "/$videoPath"
                val sb = StringBuilder("https://").append(cleanDomain).append(cleanPath)
                val params = c.optJSONObject("params")
                if (params != null && params.length() > 0) {
                    sb.append(if (cleanPath.contains("?")) "&" else "?")
                    val keys = params.keys(); var first = true
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (!first) sb.append("&")
                        sb.append(k).append("=").append(params.optString(k))
                        first = false
                    }
                }
                return sb.toString()
            }
            null
        } catch (e: Exception) {
            Log.e(name, "streamingConfig parse failed: ${e.message}"); null
        }
    }

    /**
     * Fallback: if source/hls field already is an absolute URL use it directly.
     */
    private fun buildFromAbsolutePath(obj: JSONObject): String? {
        val source = obj.optString("source").takeIf { it.startsWith("http") }
        val hls = obj.optString("hls").takeIf { it.startsWith("http") }
        return source ?: hls
    }

    private fun decryptHex(hex: String): String? = try {
        val clean = hex.trim().removeSurrounding("\"")
        val data = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(), "AES"), IvParameterSpec(AES_IV.toByteArray()))
        String(cipher.doFinal(data))
    } catch (e: Exception) { null }

    protected fun getBaseUrl(url: String): String =
        try { URI(url).let { "${it.scheme}://${it.host}" } } catch (e: Exception) { mainUrl }
}

// ─────────────────────────────────────────────────────────────
// BLAKITE -> blakiteapi.xyz/embed/{tmdbId}/{uniqueId}
//
// Verified live pipeline:
//  1. GET {mainUrl}/api/get.php?id={uniqueId}&tmdbId={tmdbId}
//     (movie embeds have no uniqueId -> only ?tmdbId={tmdbId})
//     -> JSON { success, data:{ dataId, qid, quality, format, ranges } }
//  2. format M3U8: ranges = "39656448-39670373 (240p)\n..."
//     url = https://hugh.cdn.rumble.cloud/video/{dataId}.{code}.tar
//           ?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl
//           [&r_range={range}]
//     quality codes: 240p=oaa 360p=baa 480p=caa 720p=gaa 1080p=haa
//  3. format MP4: url = .../{dataId}.{code}.mp4 (qid fallback)
//  4. No subtitles are served by this host.
// ─────────────────────────────────────────────────────────────
class Blakite : ExtractorApi() {
    override var name = "Blakite"
    override var mainUrl = "https://blakiteapi.xyz"
    override val requiresReferer = false

    companion object {
        private const val CDN_BASE = "https://hugh.cdn.rumble.cloud/video/"
        private val QUALITY_CODES = listOf("oaa", "baa", "caa", "gaa", "haa")
        private val QUALITY_LABELS = listOf("240p", "360p", "480p", "720p", "1080p")
        private val RANGE_REGEX = Regex("""(\d+-\d+)\s*\((\d+p)\)""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // embed/{tmdbId}/{uniqueId} - uniqueId is absent on movie embeds
        val path = url.substringAfter("/embed/").substringBefore("?").trimEnd('/')
        val tmdbId = path.substringBefore("/")
        val uniqueId = path.substringAfter("/", "")
        if (tmdbId.isBlank()) return

        val apiUrl = if (uniqueId.isBlank())
            "$mainUrl/api/get.php?tmdbId=$tmdbId"
        else
            "$mainUrl/api/get.php?id=$uniqueId&tmdbId=$tmdbId"

        val response = try {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to url,
                    "Accept" to "application/json"
                )
            ).parsedSafe<BlakiteResponse>()
        } catch (e: Exception) {
            Log.e(name, "API failed: ${e.message}")
            null
        } ?: return

        val data = response.data ?: return
        val dataId = data.dataId?.takeIf { it.isNotBlank() } ?: return

        // Build the quality list: prefer explicit ranges, fall back to qid count
        data class QualityEntry(val label: String, val code: String, val range: String?)

        val qualityList = mutableListOf<QualityEntry>()
        if (data.format.equals("M3U8", ignoreCase = true) && !data.ranges.isNullOrBlank()) {
            val rangeMap = RANGE_REGEX.findAll(data.ranges)
                .associate { it.groupValues[2] to it.groupValues[1] }
            for ((index, label) in QUALITY_LABELS.withIndex()) {
                rangeMap[label]?.let { range ->
                    qualityList.add(QualityEntry(label, QUALITY_CODES[index], range))
                }
            }
        }
        if (qualityList.isEmpty()) {
            val maxIndex = minOf(data.qid ?: QUALITY_LABELS.size, QUALITY_LABELS.size) - 1
            for (i in 0..maxIndex) {
                qualityList.add(QualityEntry(QUALITY_LABELS[i], QUALITY_CODES[i], null))
            }
        }

        val isHls = data.format.equals("M3U8", ignoreCase = true)
        for (q in qualityList) {
            val streamUrl = if (isHls) {
                CDN_BASE + dataId + "." + q.code +
                    ".tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl" +
                    (q.range?.let { "&r_range=$it" } ?: "")
            } else {
                CDN_BASE + dataId + "." + q.code + ".mp4"
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name [${q.label}]",
                    url = streamUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = getQualityFromName(q.label)
                    this.referer = "$mainUrl/"
                }
            )
        }
    }

    data class BlakiteResponse(
        val success: Boolean? = null,
        val data: BlakiteData? = null
    )

    data class BlakiteData(
        val animeTitle: String? = null,
        val dataId: String? = null,
        val qid: Int? = null,
        val quality: String? = null,
        val format: String? = null,
        val ranges: String? = null
    )
}

// ─────────────────────────────────────────────────────────────
// FILESFOREVER -> filesforever.link/embed/{sid}
//
// Pipeline (verified live):
//  1. GET  https://filesforever.link/embed/{sid}
//         -> 302 chain -> player origin e.g. https://pro.iqsmartgames.com/svid/{token}
//  2. POST {playerOrigin}/embedhelper2.php
//         sid={sid}&UserFavSite=&currentDomain={playerOrigin host}
//     -> JSON { sources:{smwh,strmp2,flmn,strmtp,mxdp,ddstm,flls}, mresult: base64 }
//  3. mresult decoded -> {"smwh":"id","strmp2":"id", ...}
//  4. iframe URL = sources[key].siteUrl + mresult[key] + embedSuffix
//  5. Route each mirror to its custom extractor (never to loadExtractor):
//     smwh   StreamHG    hanerix.com/e/{id}
//     strmp2 StreamP2P   cloudy.p2pplay.pro/#{id}   -> UpnsPlayer
//     flmn   Byse        bysetayico.com/e/{id}
//     flls   EarnVids    morencius.com/v/{id}
//     strmtp StreamTape  streamtape.site/e/{id}/
//     mxdp   MixDrop     mixdrop.ag/e/{id}
//     ddstm  DoodStream  dood.li/e/{id}
// ─────────────────────────────────────────────────────────────
class FilesForever : ExtractorApi() {
    override var name = "FilesForever"
    override var mainUrl = "https://filesforever.link"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // sid
        val sid = url.substringAfterLast("/embed/").substringBefore("?").trimEnd('/')
        if (sid.isBlank()) return

        // Step 1: resolve embed page -> player origin (follows the 302 chain)
        val resolved = try {
            app.get("$mainUrl/embed/$sid", referer = referer ?: mainUrl)
        } catch (e: Exception) {
            Log.e(name, "embed resolve failed: ${e.message}")
            return
        }

        val playerOrigin = try {
            val u = URI(resolved.url)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            Log.e(name, "bad redirect url: ${resolved.url}")
            return
        }

        // Step 2: helper API v2 on the resolved player origin
        val responseText = try {
            app.post(
                "$playerOrigin/embedhelper2.php",
                data = mapOf(
                    "sid" to sid,
                    "UserFavSite" to "",
                    "currentDomain" to playerOrigin.removePrefix("https://"),
                ),
                headers = mapOf(
                    "Referer" to "$mainUrl/embed/$sid",
                    "Origin" to playerOrigin,
                    "X-Requested-With" to "XMLHttpRequest",
                )
            ).text
        } catch (e: Exception) {
            Log.e(name, "embedhelper2 failed: ${e.message}")
            return
        }

        val root = tryParseJson<GDEmbedHelper>(responseText) ?: run {
            Log.e(name, "embedhelper2 unparsable")
            return
        }

        // mresult: object OR base64-encoded JSON string
        val rawMresult = root.mresult
        val mirrors: Map<String, String> = when (rawMresult) {
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") (rawMresult as Map<String, String>)
            is String -> try {
                val jo = JsonParser.parseString(base64Decode(rawMresult)).asJsonObject
                jo.keySet().associateWith { jo[it]?.asString.orEmpty() }
            } catch (e: Exception) {
                Log.e(name, "mresult decode failed: ${e.message}")
                return
            }
            else -> {
                Log.e(name, "mresult missing")
                return
            }
        }

        // helper: siteUrl + id + embedSuffix (same as JS constructIframeSrc)
        fun mirrorUrl(key: String, id: String, fallback: String): String {
            val src = root.sources?.get(key)
            val site = (src?.siteUrl ?: fallback)
            val suffix = src?.embedSuffix ?: ""
            return site + id + (suffix ?: "")
        }

        // Step 3: route each mirror to its custom extractor

        // smwh = StreamHG (hanerix.com)
        mirrors["smwh"]?.takeIf { it.isNotBlank() }?.let { id ->
            try {
                Streamhg().getUrl(mirrorUrl("smwh", id, "https://hanerix.com/e/"), referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "StreamHG failed: ${e.message}")
            }
        }

        // strmp2 = StreamP2P (upns-family player)
        mirrors["strmp2"]?.takeIf { it.isNotBlank() }?.let { id ->
            try {
                val fullUrl = mirrorUrl("strmp2", id, "https://cloudy.p2pplay.pro/#")
                UpnsPlayer().apply {
                    this.name = "StreamPro"
                    this.mainUrl = getHost(fullUrl)
                }.getUrl(fullUrl, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "StreamP2P failed: ${e.message}")
            }
        }

        // flmn = Byse (bysetayico.com, AES-256-GCM)
        mirrors["flmn"]?.takeIf { it.isNotBlank() }?.let { id ->
            try {
                Byse().getUrl(mirrorUrl("flmn", id, "https://bysetayico.com/e/"), referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "Byse failed: ${e.message}")
            }
        }

        // flls = EarnVids (morencius.com)
        mirrors["flls"]?.takeIf { it.isNotBlank() }?.let { id ->
            try {
                Earnvids().getUrl(mirrorUrl("flls", id, "https://morencius.com/v/"), referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "EarnVids failed: ${e.message}")
            }
        }

        // strmtp = StreamTape
        mirrors["strmtp"]?.takeIf { it.isNotBlank() }?.let { id ->
            try {
                StreamTape().getUrl(mirrorUrl("strmtp", id, "https://streamtape.site/e/"), referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "StreamTape failed: ${e.message}")
            }
        }

        // mxdp = MixDrop
        mirrors["mxdp"]?.takeIf { it.isNotBlank() }?.let { id ->
            try {
                MixDrop().getUrl(mirrorUrl("mxdp", id, "https://mixdrop.ag/e/"), referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "MixDrop failed: ${e.message}")
            }
        }

        // ddstm = DoodStream
        mirrors["ddstm"]?.takeIf { it.isNotBlank() }?.let { id ->
            try {
                DoodStream().getUrl(mirrorUrl("ddstm", id, "https://dood.li/e/"), referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "DoodStream failed: ${e.message}")
            }
        }
    }

    protected fun getHost(url: String): String =
        try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            mainUrl
        }

    data class GDSource(
        val encryptedValue: String? = null,
        val encryptedSiteName: String? = null,
        val encryptedApiKey: String? = null,
        val siteUrl: String? = null,
        val embedSuffix: String? = null,
        val friendlyName: String? = null,
    )

    data class GDEmbedHelper(
        val sources: Map<String, GDSource>? = null,
        val mresult: Any? = null,
        val sid: String? = null,
    )
}

// ─────────────────────────────────────────────────────────────
// StreamHG mirror -> hanerix.com
// Unpack JWPlayer -> m3u8 + .vtt subtitles
// ─────────────────────────────────────────────────────────────
open class Streamhg : ExtractorApi() {
    override var name = "Streamhg"
    override var mainUrl = "https://hanerix.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: mainUrl).text

        val packed = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]+?\.split\('\|'\)\)\)""")
            .find(html)?.value
            ?: Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]+?\}\('[\s\S]+?'\.split\('\|'\)""").find(html)?.value
            ?: return

        val unpacked = JsUnpacker(packed).unpack() ?: return

        // master.txt or .m3u8
        val m3u8 = Regex("""(https?://[^"'\s\\]+(?:master\.txt|master\.m3u8|\.m3u8)[^"'\s\\]*)""")
            .find(unpacked)?.groupValues?.get(1)
            ?: Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(unpacked)?.groupValues?.get(1)
            ?: return

        // subtitles .vtt
        val subMatches = Regex("""file\s*:\s*["'](https?://[^"']+\.vtt[^"']*)["'][^}]*?(?:label\s*:\s*["']([^"']+)["'])?""").findAll(unpacked)
        var counter = 1
        for (m in subMatches) {
            val subtitleUrl = m.groupValues[1]
            val subtitleName = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "Subtitle ${counter++}"
            subtitleCallback.invoke(SubtitleFile(subtitleName, subtitleUrl))
        }

        callback.invoke(
            newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// EarnVids mirror -> morencius.com (same JWPlayer unpack family)
class Earnvids : Streamhg() {
    override var name = "Earnvids"
    override var mainUrl = "https://morencius.com"
}

// ─────────────────────────────────────────────────────────────
// Byse mirror -> bysetayico.com
//  1) URL: /e/{code}
//  2) GET  /api/videos/{code}
//  3) playback = AES-256-GCM encrypted JSON
//  4) version N -> key_parts[N-1] + key_parts[31-N-1] (1-based N and 31-N)
//  5) decrypt -> sources[].url (m3u8) + tracks[] (subs)
// ─────────────────────────────────────────────────────────────
class Byse : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://bysetayico.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = Regex("""/e/([A-Za-z0-9]+)""")
            .find(url)?.groupValues?.getOrNull(1)
            ?: url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (code.isBlank()) return

        val apiUrl = "$mainUrl/api/videos/$code"
        val raw = try {
            app.get(
                apiUrl,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to url,
                ),
                referer = referer ?: url
            ).text
        } catch (e: Exception) {
            Log.e(name, "API failed: ${e.message}")
            return
        }

        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            Log.e(name, "JSON parse failed: ${e.message}")
            return
        }

        emitTracks(root.optJSONArray("tracks"), subtitleCallback)

        val playback = root.optJSONObject("playback") ?: run {
            Log.e(name, "no playback object")
            return
        }

        val decrypted = try {
            decryptPlayback(playback)
        } catch (e: Exception) {
            Log.e(name, "decrypt failed: ${e.message}")
            return
        }

        val sources = decrypted.optJSONArray("sources")
        if (sources == null || sources.length() == 0) {
            Log.e(name, "no sources after decrypt")
            return
        }

        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val streamUrl = src.optString("url").takeIf { it.startsWith("http") } ?: continue
            val label = src.optString("label").ifBlank { src.optString("quality") }.ifBlank { "Unknown" }
            val mime = src.optString("mime_type")
            val isHls = mime.contains("mpegurl", true) || streamUrl.contains(".m3u8", true)

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = getQualityFromName(label)
                    this.referer = mainUrl
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                }
            )
        }

        emitTracks(decrypted.optJSONArray("tracks"), subtitleCallback)
    }

    private fun emitTracks(
        tracks: org.json.JSONArray?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (tracks == null) return
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val subUrl = t.optString("url")
                .ifBlank { t.optString("file") }
                .ifBlank { t.optString("src") }
            if (!subUrl.startsWith("http")) continue
            val lang = t.optString("label")
                .ifBlank { t.optString("language") }
                .ifBlank { t.optString("lang") }
                .ifBlank { "Unknown" }
            subtitleCallback.invoke(SubtitleFile(lang, subUrl))
        }
    }

    /**
     * AES-256-GCM decrypt of playback object.
     * version "N" -> use key_parts indices N and (31-N) (1-based).
     */
    private fun decryptPlayback(playback: JSONObject): JSONObject {
        val version = playback.optString("version").trim()
        val v = version.toIntOrNull()
            ?: throw IllegalArgumentException("bad version: $version")

        val keyPartsArr = playback.optJSONArray("key_parts")
            ?: throw IllegalArgumentException("missing key_parts")
        val keyParts = buildList {
            for (i in 0 until keyPartsArr.length()) {
                add(keyPartsArr.optString(i))
            }
        }

        // 1-based indices: v and 31-v
        val i1 = v
        val i2 = 31 - v
        if (i1 < 1 || i2 < 1 || i1 > keyParts.size || i2 > keyParts.size) {
            throw IllegalArgumentException("version indices out of range: $i1, $i2 size=${keyParts.size}")
        }

        val keyBytes = b64UrlDecode(keyParts[i1 - 1]) + b64UrlDecode(keyParts[i2 - 1])
        val iv = b64UrlDecode(playback.optString("iv"))
        val ciphertext = b64UrlDecode(playback.optString("payload"))

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(128, iv) // 128-bit auth tag
        )
        val plain = cipher.doFinal(ciphertext)
        return JSONObject(String(plain, Charsets.UTF_8))
    }

    private fun b64UrlDecode(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        val pad = (4 - t.length % 4) % 4
        t += "=".repeat(pad)
        return Base64.getDecoder().decode(t)
    }
}

// ─────────────────────────────────────────────────────────────
// STREAMTAPE -> streamtape.site
// The embed page builds the link inside inline JS:
//   document.getElementById('robotlink').innerHTML =
//       '//streamtape.s' + ('xcdite/get_video?id=...').substring(2).substring(1)
// Apply the same substring operations and prefix "https:".
// Verified live: https://streamtape.site/get_video?id=...&expires=...&ip=...&token=...
// ─────────────────────────────────────────────────────────────
class StreamTape : ExtractorApi() {
    override var name = "StreamTape"
    override var mainUrl = "https://streamtape.site"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: mainUrl).text

        val match = Regex(
            """getElementById\(['"]robotlink['"]\)\.innerHTML\s*=\s*'([^']*)'\s*((?:\.substring\(\d+\))*)\s*\+\s*\('([^']*)'\)\s*((?:\.substring\(\d+\))*)"""
        ).find(html) ?: run {
            Log.e(name, "robotlink pattern not found")
            return
        }

        var part1 = match.groupValues[1]
        Regex("""\.substring\((\d+)\)""").findAll(match.groupValues[2]).forEach {
            part1 = part1.substring(it.groupValues[1].toInt())
        }

        var part2 = match.groupValues[3]
        Regex("""\.substring\((\d+)\)""").findAll(match.groupValues[4]).forEach {
            part2 = part2.substring(it.groupValues[1].toInt())
        }

        val finalUrl = "https:$part1$part2"
        if (!finalUrl.contains("get_video")) {
            Log.e(name, "unexpected link: $finalUrl")
            return
        }

        callback(
            newExtractorLink(name, name, url = finalUrl, type = ExtractorLinkType.VIDEO) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// MIXDROP -> mixdrop.ag
// Embed page contains a Dean-Edwards packed eval block whose
// unpacked source holds: wurl = "//host/path/{id}.mp4?s=...&e=..."
// Verified live: //h5y4th9xb2.mxcontent.net/v2/{id}.mp4?...
// ─────────────────────────────────────────────────────────────
class MixDrop : ExtractorApi() {
    override var name = "MixDrop"
    override var mainUrl = "https://mixdrop.ag"
    override val requiresReferer = true

    companion object {
        private val PACKED_REGEX =
            Regex("""eval\(function\(p,a,c,k,e,[dr]\)\{[\s\S]{50,}?\.split\('\|'\)[\s\S]{0,20}?\)\)""")
        private val WURL_REGEX = Regex("""wurl\s*=\s*["']([^"']+)["']""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: mainUrl).text

        val packed = PACKED_REGEX.find(html)?.value ?: run {
            Log.e(name, "packed JS not found")
            return
        }

        val unpacked = JsUnpacker(packed).unpack() ?: run {
            Log.e(name, "unpack failed")
            return
        }

        // wurl can live in the first unpack level or in a nested packed block
        val raw = WURL_REGEX.find(unpacked)?.groupValues?.get(1)
            ?: JsUnpacker(unpacked).unpack()?.let { nested ->
                WURL_REGEX.find(nested)?.groupValues?.get(1)
            }
            ?: run {
                Log.e(name, "wurl not found")
                return
            }

        val finalUrl = if (raw.startsWith("//")) "https:$raw" else raw
        if (!finalUrl.contains(".mp4") && !finalUrl.contains(".m3u8")) {
            Log.e(name, "unexpected source: $finalUrl")
            return
        }

        callback(
            newExtractorLink(name, name, url = finalUrl, type = ExtractorLinkType.VIDEO) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// DOODSTREAM -> dood.li
//  1) GET embed page -> find /pass_md5/{token}/{file} path
//  2) GET {mainUrl}/pass_md5/... (Referer: mainUrl) -> direct CDN url text
//  3) final link = direct url + "?" + random 10-char token
// Verified live: pass_md5 -> https://*.cloudatacdn.com/...
// ─────────────────────────────────────────────────────────────
class DoodStream : ExtractorApi() {
    override var name = "DoodStream"
    override var mainUrl = "https://dood.li"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer ?: "$mainUrl/").text

        val md5Path = Regex("""/pass_md5/[^'"]+""").find(res)?.value ?: run {
            Log.e(name, "pass_md5 path not found")
            return
        }

        val directUrl = try {
            app.get("$mainUrl$md5Path", referer = "$mainUrl/").text.trim()
        } catch (e: Exception) {
            Log.e(name, "pass_md5 request failed: ${e.message}")
            return
        }

        if (!directUrl.startsWith("http")) {
            Log.e(name, "unexpected pass_md5 response")
            return
        }

        val finalUrl = "$directUrl?${randomToken(10)}"

        callback(
            newExtractorLink(name, name, url = finalUrl, type = ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun randomToken(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }
}
