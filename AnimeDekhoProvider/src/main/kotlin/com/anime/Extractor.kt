package com.anime

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
import com.lagradost.cloudstream3.utils.loadExtractor
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

// Base extractor backend.
// Fetches the HLS video source via a POST request and unpacks JavaScript to extract subtitle captions.
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

        val response = app.post(m3u8Url, headers = header, data = formdata).parsedSafe<Response>()
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

// Extractor logic. 
// Uses an external API to decrypt the AES encrypted payload containing video sources.
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

        val encrypted = Regex("""const\s+datas\s*=\s*\u0022([^\u0022]*)\u0022""")
            .find(scripts)?.groupValues?.getOrNull(1) ?: return

        val decrypted = app.post(
            url = "https://enc-dec.app/api/dec-abyss",
            headers = headers,
            requestBody = """{"text":"$encrypted"}""".toRequestBody("application/json".toMediaType())
        ).parsedSafe<AbyssResponse>()?.result ?: return

        val validSources = decrypted.sources.filter { it.status }
        for (source in validSources) {
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

// Unpacks the StreamRuby player page to extract the master M3U8 and VTT subtitle tracks.
// Handles BOTH url formats with ONE extractor:
//   old:  https://nuvorel.top/embed-wh8qymiqj463.html
//   new:  https://rubystm.com/e/gh6br0e4uu5a.html   (mirror -> 301 nuvorel.top)
//
// Fixes vs the old version:
//   F1  fileCode regex matches /e/{code}.html AND embed-{code}.html
//   F2  redirect resolved first (rubystm.com -> nuvorel.top); the /dl POST
//       goes to the FINAL host, because the POST body is dropped on redirect
//   F3  player page is now plain jwplayer().setup() (NOT packed) -> plain
//       regex first, JsUnpacker only as legacy fallback
//   F4  tracks parsed per-object: kind:"thumbnails" (seekbar sprite) skipped,
//       label used as subtitle name; lang-code fallback if label missing
//   F5  tracks sometimes keep an OLD file code while the m3u8 uses a remapped
//       code (code_eng.vtt 404s, m3u8-path code works) -> code swapped from
//       the m3u8 path
//
// Verified live on 2026-11-11:
//   rubystm.com/e/gh6br0e4uu5a.html      -> m3u8 OK (360p..1080p, hi/ja audio)
//   nuvorel.top/embed-wh8qymiqj463.html  -> m3u8 OK + [English] sub OK
class StreamRuby : ExtractorApi() {
    override var name = "StreamRuby"
    override var mainUrl = "https://rubystm.com"
    override val requiresReferer = true

    // F1: both /e/{code}.html and embed-{code}.html
    private val codeRegex = Regex("""(?:/e/|embed-)([A-Za-z0-9]+)""")
    private val m3u8Regex = Regex("""file\s*:\s*\u0022(https?://[^\u0022]+\.m3u8[^\u0022]*)\u0022""")
    // F4: one track object: { file: "...vtt", label: "...", kind: "..." }
    private val trackRegex = Regex("""\{\s*file\s*:\s*\u0022(https?://[^\u0022]+\.vtt[^\u0022]*)\u0022\s*,([^}]*)\}""")
    private val labelRegex = Regex("""label\s*:\s*\u0022([^\u0022]*)\u0022""")
    private val kindRegex = Regex("""kind\s*:\s*\u0022([^\u0022]*)\u0022""")
    // F5: internal storage code inside the m3u8 path: /hls2/XX/NNNNN/{code}_...
    private val hlsCodeRegex = Regex("""/hls2/[^/]+/[^/]+/([A-Za-z0-9]{12})_""")
    private val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]+?'\|'\)\)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileCode = codeRegex.find(url)?.groupValues?.get(1) ?: return

        // F2: GET the embed url (follows the mirror 301) and take the FINAL host.
        val page = app.get(url, referer = referer ?: mainUrl)
        val base = Regex("""https?://[^/]+""").find(page.url)?.value
            ?: return

        // F3: the old embed- format returns the player page on GET;
        // the new /e/ format returns a snapshot landing page -> POST /dl.
        var html = page.text
        if (!html.contains(".m3u8") && !html.contains("jwplayer(")) {
            html = app.post(
                url = "$base/dl",
                data = mapOf(
                    "op" to "embed",
                    "file_code" to fileCode,
                    "auto" to "1",
                    "referer" to (referer ?: "")
                ),
                referer = page.url
            ).text
        }

        // F3: plain jwplayer setup first; unpack only for legacy packed pages.
        val player = if (m3u8Regex.containsMatchIn(html)) html
        else {
            val packed = packedRegex.find(html)?.value ?: return
            JsUnpacker(packed).unpack() ?: return
        }

        val m3u8 = m3u8Regex.find(player)?.groupValues?.get(1) ?: return
        val realCode = hlsCodeRegex.find(m3u8)?.groupValues?.get(1)

        // F4 + F5: subtitle name = label; skip thumbnails; fix remapped code.
        for (t in trackRegex.findAll(player)) {
            val subUrl = t.groupValues[1]
            val rest = t.groupValues[2]
            if (kindRegex.find(rest)?.groupValues?.get(1)?.contains("thumbnail") == true) continue
            val label = labelRegex.find(rest)?.groupValues?.get(1)
                ?: subUrl.substringAfterLast("_").substringBefore(".vtt").uppercase()
            val fixedUrl = if (realCode != null)
                subUrl.replace(Regex("""/([A-Za-z0-9]{12})_"""), "/$realCode" + "_")
            else subUrl
            subtitleCallback.invoke(SubtitleFile(label, fixedUrl))
        }

        callback.invoke(
            newExtractorLink(source = name, name = name, url = m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = base          // resolved host, e.g. nuvorel.top
                this.quality = Qualities.Unknown.value   // master m3u8 -> all qualities
            }
        )
    }
}

// Handles domain by leveraging the extraction logic.
class Cloudy : UpnsPlayer() {
    override var name = "Cloudy"
    override var mainUrl = "https://cloudy.upns.one"
}

// Fetches AES-encoded JSON from a backend API, decrypts it, and builds the HLS URL from the streaming config.
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

        val refHost = try {
            URI(referer ?: mainUrl).host ?: mainUrl.removePrefix("https://")
        } catch (e: Exception) {
            mainUrl.removePrefix("https://")
        }

        val encoded = try {
            app.get(
                "$baseurl/api/v1/video?id=$hash&w=1280&h=720&r=$refHost",
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "*/*"),
                referer = referer ?: "$baseurl/"
            ).text.trim()
        } catch (e: Exception) {
            Log.e(name, "API failed: ${e.message}")
            return
        }
        if (encoded.isBlank()) return

        val decryptedJson = decryptHex(encoded) ?: run {
            Log.e(name, "AES decrypt failed")
            return
        }
        val obj = try {
            JSONObject(decryptedJson)
        } catch (e: Exception) {
            Log.e(name, "JSON parse failed: ${e.message}")
            return
        }

        val finalUrl = buildFromStreamingConfig(obj)
            ?: buildFromAbsolutePath(obj)
            ?: run {
                Log.e(name, "no playable URL found")
                return
            }

        callback.invoke(
            newExtractorLink(name, name, url = finalUrl, type = ExtractorLinkType.M3U8) {
                this.referer = "$baseurl/"
                this.quality = Qualities.Unknown.value
            }
        )

        val subs = obj.optJSONObject("subtitle")
        if (subs != null) {
            val keys = subs.keys()
            while (keys.hasNext()) {
                val lang = keys.next()
                val rawPath = subs.optString(lang).split("#").firstOrNull().orEmpty()
                if (rawPath.isNotBlank()) {
                    val subUrl = if (rawPath.startsWith("http")) rawPath else "$baseurl$rawPath"
                    subtitleCallback.invoke(SubtitleFile(lang.uppercase(), subUrl))
                }
            }
        }
    }

    private fun buildFromStreamingConfig(obj: JSONObject): String? {
        return try {
            val videoPath = obj.optString("source").takeIf { it.isNotEmpty() && !it.startsWith("http") }
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
                val keys = adjust.keys()
                while (keys.hasNext()) {
                    adjust.optJSONObject(keys.next())?.let { candidates.add(it) }
                }
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
                    val keys = params.keys()
                    var first = true
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
            Log.e(name, "streamingConfig parse failed: ${e.message}")
            null
        }
    }

    private fun buildFromAbsolutePath(obj: JSONObject): String? {
        val source = obj.optString("source").takeIf { it.startsWith("http") }
        val hls = obj.optString("hls").takeIf { it.startsWith("http") }
        return source ?: hls
    }

    private fun decryptHex(hex: String): String? = try {
        val clean = hex.trim().removeSurrounding("\"")
        val data = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(), "AES"),
            IvParameterSpec(AES_IV.toByteArray())
        )
        String(cipher.doFinal(data))
    } catch (e: Exception) {
        null
    }

    protected fun getBaseUrl(url: String): String =
        try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            mainUrl
        }
}

// Acts as a router by parsing responses to delegate to direct URLs.
open class GDMirrorbot : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = true

    companion object {
        private const val STREAMHG_BASE = "https://hanerix.com/e/"
        private val PACKED_REGEX =
            Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]+?'\|'\)\)""")
        private val HLS_LINKS_REGEX =
            Regex("""\u0022(hls\d)\u0022\s*:\s*\u0022(https?://[^\u0022]+)\u0022""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sid = url.substringAfterLast("embed/").substringBefore("?").trimEnd('/')
        if (sid.isBlank()) return

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

        mirrors["smwh"]?.takeIf { it.isNotBlank() }?.let { smwhId ->
            try {
                extractStreamHg(smwhId, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "StreamHG failed: ${e.message}")
            }
        }

        mirrors["strmp2"]?.takeIf { it.isNotBlank() }?.let { p2pId ->
            val siteUrl = root.sources?.get("strmp2")?.siteUrl
                ?: "https://cloudy.p2pplay.pro/#"
            val fullUrl = if (siteUrl.endsWith("#")) "$siteUrl$p2pId"
            else "${siteUrl.trimEnd('/')}#$p2pId"
            try {
                UpnsPlayer().apply {
                    this.name = "StreamPro"
                    this.mainUrl = getHost(fullUrl)
                }.getUrl(fullUrl, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "StreamPro failed: ${e.message}")
            }
        }

        mirrors["flls"]?.takeIf { it.isNotBlank() }?.let { evId ->
            val siteUrl = root.sources?.get("flls")?.siteUrl
                ?: "https://smoothpre.com/v/"
            try {
                loadExtractor("${siteUrl.trimEnd('/')}/$evId", referer ?: mainUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.d(name, "EarnVids unavailable: ${e.message}")
            }
        }
    }

    private suspend fun extractStreamHg(
        mirrorId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = try {
            app.get("$STREAMHG_BASE$mirrorId", referer = mainUrl).text
        } catch (e: Exception) {
            Log.e(name, "StreamHG page failed: ${e.message}")
            return
        }

        val packed = PACKED_REGEX.find(html)?.value ?: run {
            Log.e(name, "StreamHG: no packed JS")
            return
        }
        val unpacked = JsUnpacker(packed).unpack() ?: run {
            Log.e(name, "StreamHG: unpack failed")
            return
        }

        val hlsLinks = HLS_LINKS_REGEX.findAll(unpacked)
            .associate { it.groupValues[1] to it.groupValues[2] }
        if (hlsLinks.isEmpty()) {
            Log.e(name, "StreamHG: no hls links found")
            return
        }

        var chosenUrl: String? = null
        var manifestBody: String? = null
        for (key in listOf("hls2", "hls4", "hls3", "hls1")) {
            val candidate = hlsLinks[key] ?: continue
            try {
                val body = app.get(candidate, referer = STREAMHG_BASE).text
                if (body.contains("#EXTM3U")) {
                    chosenUrl = candidate
                    manifestBody = body
                    break
                }
            } catch (e: Exception) {
                Log.d(name, "$key unreachable, trying next")
            }
        }

        val finalUrl = chosenUrl
            ?: hlsLinks["hls2"]
            ?: hlsLinks["hls3"]
            ?: return

        val quality = when {
            manifestBody == null -> Qualities.Unknown.value
            manifestBody.contains("1920x1080") -> Qualities.P1080.value
            manifestBody.contains("1280x720") -> Qualities.P720.value
            manifestBody.contains("856x480") || manifestBody.contains("854x480") ||
                manifestBody.contains("640x360") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        callback.invoke(
            newExtractorLink(name, name, url = finalUrl, type = ExtractorLinkType.M3U8) {
                this.referer = STREAMHG_BASE
                this.quality = quality
            }
        )
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

// Domain configuration subclass.
class GDMirrorbotFHD : GDMirrorbot() {
    override var name = "StreamHG"
    override var mainUrl = "https://gdmirrorbot.nl"
}

// Domain configuration utilizing logic.
class FilesForever : GDMirrorbot() {
    override var name = "StreamHG"
    override var mainUrl = "https://filesforever.link"
}

// Simply scrapes the HTML content or embedded script tags for a direct string.
class EmTurboVid : ExtractorApi() {
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

        var m3u8 = doc.selectFirst("#video_player[data-hash]")
            ?.attr("data-hash")
            ?.takeIf { it.contains(".m3u8") }
            ?: doc.selectFirst("[data-hash]")?.attr("data-hash")?.takeIf { it.contains(".m3u8") }

        if (m3u8 == null) {
            m3u8 = doc.select("script")
                .firstOrNull { it.data().contains("var urlPlay") }
                ?.data()
                ?.substringAfter("var urlPlay = '", "")
                ?.substringBefore("'")
                ?.takeIf { it.startsWith("http") }
        }

        val finalUrl = m3u8 ?: return

        callback.invoke(
            newExtractorLink(name, name, url = finalUrl, type = ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
            }
        )
    }
}

// Parses raw HTML text using Regular Expressions to identify standard extensions.
class VidMolyNet : ExtractorApi() {
    override var name = "VidMoly"
    override var mainUrl = "https://vidmoly.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val txt = app.get(url, referer = referer ?: mainUrl).text

        val m3u8 = Regex("""file\s*:\s*[\u0022']([^\u0022']+\.m3u8[^\u0022']*)[\u0022']""")
            .find(txt)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s\u0022'<>]+\.m3u8[^\s\u0022'<>]*""").find(txt)?.value
            ?: return

        val match = Regex("""file\s*:\s*[\u0022'](https[^\u0022']+\.vtt[^\u0022']*)[\u0022'][\s\S]{0,200}?label\s*:\s*[\u0022']([^\u0022']*)[\u0022']""")
            .find(txt)
        if (match != null) {
            subtitleCallback.invoke(SubtitleFile(match.groupValues[2].ifBlank { "English" }, match.groupValues[1]))
        }

        callback.invoke(
            newExtractorLink(name, name, url = m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// Intercepts the API request mapped with data, building stream CDN links mapped to chunk ranges.
class Blakite : ExtractorApi() {
    override var name = "Blakite"
    override var mainUrl = "https://blakiteapi.xyz"
    override val requiresReferer = false

    companion object {
        private const val CDN_BASE = "https://hugh.cdn.rumble.cloud/video/"
        private val QUALITY_CODES = listOf("oaa", "baa", "caa", "gaa", "haa")
        private val QUALITY_LABELS = listOf("240p", "360p", "480p", "720p", "1080p")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val path = url.substringAfter("$mainUrl/embed/").trimEnd('/')

        val tmdbId: String
        val uniqueId: String?

        if (path.contains("/")) {
            tmdbId = path.substringBefore("/")
            uniqueId = path.substringAfter("/")
        } else {
            tmdbId = path
            uniqueId = null
        }

        val apiUrl = if (uniqueId != null) {
            "$mainUrl/api/get.php?id=$uniqueId&tmdbId=$tmdbId"
        } else {
            "$mainUrl/api/get.php?tmdbId=$tmdbId"
        }

        val json = try {
            app.get(
                apiUrl,
                headers = mapOf(
                    "Referer" to url,
                    "Accept" to "application/json",
                    "User-Agent" to USER_AGENT,
                )
            ).parsedSafe<BlakiteResponse>()
        } catch (e: Exception) {
            Log.e(name, "API failed: ${e.message}")
            return
        }

        val data = json?.takeIf { it.success }?.data ?: run {
            Log.e(name, "API returned no data")
            return
        }
        val dataId = data.dataId ?: return

        if (data.format.equals("M3U8", ignoreCase = true)) {
            val rangeMap = mutableMapOf<String, String>()
            val lines = data.ranges?.split("\n")
            if (lines != null) {
                for (line in lines) {
                    val m = Regex("""(\d+-\d+)\s*\(([^)]+)\)""").find(line.trim())
                    if (m != null) {
                        rangeMap[m.groupValues[2].trim()] = m.groupValues[1]
                    }
                }
            }

            var emitted = false
            for (i in QUALITY_LABELS.indices) {
                val label = QUALITY_LABELS[i]
                val code = QUALITY_CODES[i]
                val range = rangeMap[label] ?: continue

                val streamUrl = CDN_BASE +
                    "$dataId.$code.tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl&r_range=$range"

                callback.invoke(
                    newExtractorLink(name, name, streamUrl, ExtractorLinkType.M3U8) {
                        this.referer = ""
                        this.quality = getQualityFromName(label)
                    }
                )
                emitted = true
            }

            if (!emitted) {
                val qid = (data.qid ?: QUALITY_LABELS.size).coerceIn(1, QUALITY_LABELS.size)
                for (i in 0 until qid) {
                    val label = QUALITY_LABELS[i]
                    val code = QUALITY_CODES[i]
                    val streamUrl = CDN_BASE + "$dataId.$code.tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl"
                    callback.invoke(
                        newExtractorLink(name, name, streamUrl, ExtractorLinkType.M3U8) {
                            this.referer = ""
                            this.quality = getQualityFromName(label)
                        }
                    )
                }
            }
        } else {
            val qid = (data.qid ?: 1).coerceIn(1, QUALITY_LABELS.size)
            for (i in 0 until qid) {
                val label = QUALITY_LABELS[i]
                val code = QUALITY_CODES[i]
                val streamUrl = "$CDN_BASE$dataId.$code.mp4"
                callback.invoke(
                    newExtractorLink(name, name, streamUrl, INFER_TYPE) {
                        this.referer = ""
                        this.quality = getQualityFromName(label)
                    }
                )
            }
        }
    }

    data class BlakiteResponse(
        val success: Boolean = false,
        val data: BlakiteData? = null,
    )

    data class BlakiteData(
        val animeTitle: String? = null,
        val tmdbId: String? = null,
        val type: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val title: String? = null,
        val dataId: String? = null,
        val qid: Int? = null,
        val quality: String? = null,
        val format: String? = null,
        val ranges: String? = null,
    )
}

// multi-server router
class WorldMirror : ExtractorApi() {
    override var name = "WorldMirror"
    override var mainUrl = "https://animeworld.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer ?: mainUrl).document
        
        val options = doc.select("select#serverSelect option[value], option[data-server]")
        for (opt in options) {
            val embed = opt.attr("value").trim()
            if (embed.startsWith("http")) {
                loadExtractor(embed, url, subtitleCallback, callback)
            }
        }
    }
}

class Rpmshare : UpnsPlayer() {
    override var name = "Rpmshare"
    override var mainUrl = "https://zoro.rpmhub.site"
}

class StreamPro : UpnsPlayer() {
    override var name = "StreamPro"
    override var mainUrl = "https://zoro.streamcasthub.store"
}

// Unpack JWPlayer for m3u8
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

class Earnvids : Streamhg() {
    override var name = "Earnvids"
    override var mainUrl = "https://morencius.com"
}

/**
 * Flow:
 *  1) URL: /e/{code}
 *  2) GET  /api/videos/{code}
 *  3) playback = AES-256-GCM encrypted JSON
 *  4) version N → key_parts[N-1] + key_parts[31-N-1] (1-based indices N and 31-N)
 *  5) decrypt → sources[].url (m3u8) + tracks[] (subs)
 */
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
            val isHls = mime.contains("mpegurl", true) ||
                streamUrl.contains(".m3u8", true)

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
     * version "N" → use key_parts indices N and (31-N) (1-based).
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

        // 1-based indices: v and 31-v (same as JS Qa()/Ea())
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

// Progressive file URLs
open class XerverMirror : ExtractorApi() {
    override var name = "XerverMirror"
    override var mainUrl = "https://mirror.xerver.xyz"
    override val requiresReferer = true

    private val preferredKeys = listOf("instant_dl", "cloud_r2", "direct_mgt")
    private val qualityRegex = Regex("""(2160|1440|1080|720|480|360|240)p""", RegexOption.IGNORE_CASE)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val apiUrl = when {
            url.contains("fetch=1") -> url
            url.contains("?") -> "$url&fetch=1"
            else -> "$url?fetch=1"
        }

        val json = try {
            app.get(
                apiUrl,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to (referer ?: mainUrl),
                ),
                referer = referer ?: mainUrl
            ).parsedSafe<XerverResponse>()
        } catch (e: Exception) {
            Log.e(name, "fetch failed: ${e.message}")
            return
        } ?: return

        val results = json.results ?: return

        val qualityLabel = detectQuality(results)
        val qualityValue = getQualityFromName(qualityLabel ?: "")

        for (key in preferredKeys) {
            val entry = results[key] ?: continue
            val streamUrl = entry.url?.takeIf { it.startsWith("http") } ?: continue
            val serverLabel = entry.label ?: key
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name [$serverLabel]",
                    url = streamUrl,
                    type = INFER_TYPE
                ) {
                    this.quality = qualityValue
                    this.referer = mainUrl
                }
            )
        }

        for ((key, entry) in results) {
            if (key in preferredKeys) continue
            if (key.contains("telegram", ignoreCase = true)) continue
            if (key.contains("gofile", ignoreCase = true)) continue

            val streamUrl = entry.url?.takeIf { it.startsWith("http") } ?: continue
            val serverLabel = entry.label ?: key
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name [$serverLabel]",
                    url = streamUrl,
                    type = INFER_TYPE
                ) {
                    this.quality = qualityValue
                    this.referer = mainUrl
                }
            )
        }
    }

    private fun detectQuality(results: Map<String, XerverEntry>): String? {
        val texts = mutableListOf<String>()
        for (e in results.values) {
            e.url?.let { texts.add(java.net.URLDecoder.decode(it, "UTF-8")) }
            e.page?.let { texts.add(java.net.URLDecoder.decode(it, "UTF-8")) }
            e.label?.let { texts.add(it) }
        }
        for (t in texts) {
            qualityRegex.find(t)?.groupValues?.get(1)?.let { return "${it}p" }
        }
        return null
    }

    data class XerverResponse(
        val results: Map<String, XerverEntry>? = null,
        val cached: Boolean? = null,
        val error: String? = null,
    )

    data class XerverEntry(
        val label: String? = null,
        val url: String? = null,
        val page: String? = null,
    )
}

class VidSrcGet : XerverMirror() {
    override var name = "VidSrcGet"
    override var mainUrl = "https://animedekho.tv/aaa/get"
}

// =========================================================================
// HubCopy — aggregator player used by animedekho.tv (HubCopy-480p/720p/1080p).
// The provider already unwraps its player.php wrapper via resolveRealIframe(),
// so this extractor receives the direct page: https://cdn.hubcopy.vip/e/{slug}
// (301 -> hubcopy.lol).
//
// Chain (verified live 2026-11-11, Psyren 1x3):
//   page JS   : const D = {"slug":"..","players":[{"host":"..","label":".."}],"t":".."}, KEY = ".."
//   inner     : https://cdn.hubcopy.vip/e/{slug}/v/{host}?t={t}
//   trick     : append &fetch=1 -> HTTP 302, Location = REAL embed URL
//
//   StreamRuby  302 -> https://nuvorel.top/embed-fb2tpz15el2f.html
//   Vidmoly     302 -> https://vidmoly.biz/embed-3dly23r517pd.html
//   Byse        302 -> https://bysezejataos.com/e/30qcde8qsmcg
//   Content.st  302 -> https://content.st/e/5ju35vspmuh
//   LuluStream  302 -> https://luluvido.com/e/rfbkbeybnxgr
//   Vidara      302 -> https://vidara.so/e/TvsXip2R6rfrF
//   VOE         302 -> https://voe.sx/e/ccdnuamuigzi
//
// Each real URL is routed to its extractor. The proven hosts are called
// directly because their live domains drifted away from the extractors'
// mainUrl (nuvorel.top != rubystm.com, bysezejataos.com != bysetayico.com,
// vidmoly.biz != vidmoly.net), so the global loadExtractor() cannot match
// them. Everything else falls through to loadExtractor() as usual.
// =========================================================================
class HubCopy : ExtractorApi() {
    override var name = "HubCopy"
    override var mainUrl = "https://cdn.hubcopy.vip"   // 301 -> hubcopy.lol
    override val requiresReferer = true

    // const D = {...}, KEY  (DOT_MATCHES_ALL ~ re.S; lazy so it stops at ", KEY")
    private val dRegex = Regex("""const D = (\{.*?\}), KEY""", RegexOption.DOT_MATCHES_ALL)
    private val hostRegex = Regex("""https?://[^/]+""")

    data class HubCopyD(
        val slug: String? = null,
        val t: String? = null,
        val players: List<HubCopyPlayer>? = null,
    )

    data class HubCopyPlayer(
        val host: String? = null,
        val label: String? = null,
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1) GET the hubcopy page (cdn.hubcopy.vip redirects to hubcopy.lol)
        val page = try {
            app.get(url, referer = referer ?: mainUrl)
        } catch (e: Exception) {
            Log.e(name, "page failed: ${e.message}")
            return
        }
        val base = hostRegex.find(page.url)?.value ?: return

        // 2) Parse the D object: slug + token + player list
        val d = dRegex.find(page.text)?.groupValues?.get(1) ?: run {
            Log.e(name, "D object not found")
            return
        }
        val json = tryParseJson<HubCopyD>(d) ?: return
        val slug = json.slug ?: return
        val token = json.t ?: return
        val parent = "$base/e/$slug"

        // 3) For every player: /v/{host}?t={t}&fetch=1 -> 302 -> real embed URL
        for (p in json.players.orEmpty()) {
            val host = p.host ?: continue
            val inner = "$parent/v/$host?t=$token"
            val real = try {
                val r = app.get("$inner&fetch=1", referer = parent, allowRedirects = false)
                r.headers.entries
                    .firstOrNull { it.key.equals("Location", true) }?.value
                    ?.takeIf { it.startsWith("http") }
            } catch (e: Exception) {
                Log.e(name, "$host fetch=1 failed: ${e.message}")
                null
            }
            if (real == null) {
                Log.e(name, "${p.label ?: host}: no real URL (token dead?)")
                continue
            }

            Log.i(name, "${p.label ?: host} -> $real")
            delegate(real, referer ?: parent, subtitleCallback, callback)
        }
    }

    // Route a resolved embed URL to the extractor that can parse it.
    private suspend fun delegate(
        realUrl: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            listOf("streamruby", "rubystm", "nuvorel").any { realUrl.contains(it, true) } -> {
                StreamRuby().getUrl(realUrl, referer, subtitleCallback, callback); true
            }
            realUrl.contains("vidmoly", true) -> {
                VidMolyNet().getUrl(realUrl, referer, subtitleCallback, callback); true
            }
            realUrl.contains("byse", true) -> {
                Byse().getUrl(realUrl, referer, subtitleCallback, callback); true
            }
            else -> loadExtractor(realUrl, referer, subtitleCallback, callback)
        }
    }
}
