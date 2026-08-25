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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ─────────────────────────────────────────────────────────────
// ★ Default: VidStreamX → as-cdn26.top  (AWSStream pattern)
// ─────────────────────────────────────────────────────────────
class Zephyrflick : AWSStream() {
    override val name = "Zephyrflick"
    override val mainUrl = "https://as-cdn26.top"
    override val requiresReferer = true
}

open class AWSStream : ExtractorApi() {
    override val name = "AWSStream"
    override val mainUrl = "https://z.awstream.net"
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
                Regex("\"kind\"\\s*:\\s*\"captions\"\\s*,\\s*\"file\"\\s*:\\s*\"(https.*?\\.srt)\"")
                    .find(unpacked)?.groupValues?.get(1)?.let { subtitleUrl ->
                        subtitleCallback.invoke(SubtitleFile("English", subtitleUrl))
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

// ─────────────────────────────────────────────────────────────
// Key 0: SHORT → abyssplayer.com  (enc-dec.app decrypt)
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
            requestBody = """
        {
            "text": "$encrypted"
        }
    """.trimIndent().toRequestBody("application/json".toMediaType())
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
// Key 1: RUBY → rubystm.com  (/dl POST + JS unpack)
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
// Key 2: CLOUDY → cloudy.upns.one
// API returns AES-CBC encrypted hex JSON.
// Video: hlsVideoTiktok path + streamingConfig.adjust.Tiktok domain & v param
// Verified live: full URL = https://{domain}{path}?v={ts}
// NOTE: tiktokcdn (Akamai) blocks DATACENTER IPs but works on
// residential/mobile connections — normal for Cloudstream users.
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

        // id from "#fragment" (cloudy.upns.one/#tye61y)
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

        // ── Build final stream URL from hlsVideoTiktok + streamingConfig ──
        var videoPath = obj.optString("hlsVideoTiktok")
        if (videoPath.isEmpty()) videoPath = obj.optString("source")
        if (videoPath.isEmpty()) videoPath = obj.optString("hls")
        if (videoPath.isEmpty()) {
            Log.e(name, "no video path in response")
            return
        }

        // Parse streamingConfig: {"adjust":{"Tiktok":{"domain":"...","params":{"v":"..."}}}}
        var finalUrl = ""
        try {
            val cfgObj = obj.optJSONObject("streamingConfig")
            val cfgRaw = cfgObj?.toString() ?: obj.optString("streamingConfig")
            if (!cfgRaw.isNullOrBlank()) {
                val cfg = JSONObject(cfgRaw)
                val adjust = cfg.optJSONObject("adjust")
                val order = cfg.optJSONArray("order")
                val candidates = mutableListOf<JSONObject>()
                if (order != null) {
                    for (i in 0 until order.length()) {
                        adjust?.optJSONObject(order.getString(i))?.let { candidates.add(it) }
                    }
                } else {
                    adjust?.keys()?.forEach { k -> adjust.optJSONObject(k)?.let { candidates.add(it) } }
                }
                for (c in candidates) {
                    if (c.optBoolean("disabled", false)) continue
                    val domain = c.optString("domain")
                    if (domain.isBlank()) continue
                    val sb = StringBuilder("https://").append(domain).append(videoPath)
                    val params = c.optJSONObject("params")
                    if (params != null && params.length() > 0) {
                        sb.append("?")
                        val keys = params.keys()
                        var first = true
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (!first) sb.append("&")
                            sb.append(k).append("=").append(params.optString(k))
                            first = false
                        }
                    }
                    finalUrl = sb.toString()
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(name, "config parse failed: ${e.message}")
        }

        // Fallback: serve path from own origin
        if (finalUrl.isEmpty()) {
            finalUrl = "$baseurl$videoPath"
        }

        callback(
            newExtractorLink(name, name, url = finalUrl, type = ExtractorLinkType.M3U8) {
                this.referer = "$baseurl/"
                this.quality = Qualities.Unknown.value
            }
        )

        // Subtitles: {"subtitle":{"en":"/xxx/en.vtt#en","hi":"..."}}
        val subs = obj.optJSONObject("subtitle")
        subs?.keys()?.forEach { lang ->
            val rawPath = subs.optString(lang).split("#").firstOrNull().orEmpty()
            if (rawPath.isNotBlank()) {
                val subUrl = if (rawPath.startsWith("http")) rawPath else "$baseurl$rawPath"
                subtitleCallback(SubtitleFile(lang.uppercase(), subUrl))
            }
        }
    }

    private fun decryptHex(hex: String): String? {
        return try {
            val clean = hex.trim().removeSurrounding("\"")
            val data = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(), "AES"), IvParameterSpec(AES_IV.toByteArray()))
            String(cipher.doFinal(data))
        } catch (e: Exception) {
            null
        }
    }

    protected fun getBaseUrl(url: String): String =
        try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            mainUrl
        }
}

// ─────────────────────────────────────────────────────────────
// Keys 3-5: GDMirrorbot SD / HD / FHD
//
// VERIFIED LIVE PIPELINE (works for all three qualities):
//  1. GET  https://gdmirrorbot.nl/embed/{sid}     ← ALWAYS root domain!
//     → redirects to a player origin e.g. pro.iqsmartgames.com/svid/...
//  2. POST {playerOrigin}/embedhelper2.php
//       sid={sid}&UserFavSite=&currentDomain={playerHost}
//     → JSON { sources:{smwh,strmp2,flmn,flls}, mresult: base64 }
//  3. mresult decoded → {"smwh":"id","strmp2":"id","flmn":"id","flls":"id"}
//  4. smwh (StreamHG) → GET https://hanerix.com/e/{id}
//     → Dean-Edwards packed JS → JsUnpacker → links.hls2/hls3
//     → verify manifest (#EXTM3U) & read RESOLUTION for quality
//  5. strmp2 (StreamP2P) → cloudy.p2pplay.pro/#{id} → UpnsPlayer
//
// Quality auto-detected from manifest RESOLUTION:
//   856x480 → 480p | 1280x720 → 720p | 1920x1080 → 1080p
//
// App display name: StreamHG (the mirror that actually serves the video)
// ─────────────────────────────────────────────────────────────
open class GDMirrorbot : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = true

    companion object {
        private const val STREAMHG_BASE = "https://hanerix.com/e/"
        private val PACKED_REGEX =
            Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]+?'\|'\)\)""")
        private val HLS_LINKS_REGEX =
            Regex(""""(hls\d)"\s*:\s*"(https?://[^"]+)"""")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Extract sid; FORCE root domain (fgdmirrorbot.nl is DNS-dead but
        // its sids resolve fine on gdmirrorbot.nl)
        val sid = url.substringAfterLast("embed/").substringBefore("?").trimEnd('/')
        if (sid.isBlank()) return

        // ── Step 1: resolve embed page → player origin ──
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

        // ── Step 2: helper API v2 on the resolved player origin ──
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

        // ── Step 3: route each mirror ──

        // smwh = StreamHG (hanerix.com) — PRIMARY, fully extractable
        mirrors["smwh"]?.takeIf { it.isNotBlank() }?.let { smwhId ->
            try {
                extractStreamHg(smwhId, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "StreamHG failed: ${e.message}")
            }
        }

        // strmp2 = StreamP2P (upns-family player) → our UpnsPlayer
        mirrors["strmp2"]?.takeIf { it.isNotBlank() }?.let { p2pId ->
            val siteUrl = root.sources?.get("strmp2")?.siteUrl
                ?: "https://cloudy.p2pplay.pro/#"
            val fullUrl = if (siteUrl.endsWith("#")) "$siteUrl$p2pId"
                          else "${siteUrl.trimEnd('/')}#$p2pId"
            try {
                UpnsPlayer().apply {
                    this.name = "StreamP2P"
                    this.mainUrl = getHost(fullUrl)
                }.getUrl(fullUrl, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "StreamP2P failed: ${e.message}")
            }
        }

        // flls (EarnVids) usually expired, flmn (Byse) captcha-protected:
        // attempt generic extraction, failures are non-fatal
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

    /**
     * StreamHG: unpack Dean-Edwards packer → pick first WORKING hls link
     * (prefer .m3u8 variants, fall back to .txt master) → read manifest
     * RESOLUTION for accurate quality label.
     */
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

        // Prefer real .m3u8 playlists; verify each until one responds
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
            // last resort: hand back unverified best-guess rather than nothing
            ?: hlsLinks["hls2"]
            ?: hlsLinks["hls3"]
            ?: return

        // Quality from manifest RESOLUTION (fallback: Unknown)
        val quality = when {
            manifestBody == null -> Qualities.Unknown.value
            manifestBody.contains("1920x1080") -> Qualities.P1080.value
            manifestBody.contains("1280x720") -> Qualities.P720.value
            manifestBody.contains("856x480") || manifestBody.contains("854x480") ||
                manifestBody.contains("640x360") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        callback(
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

class GDMirrorbotFHD : GDMirrorbot() {
    override var name = "StreamHG"
    override var mainUrl = "https://gdmirrorbot.nl"   // same root; sid differs
}

// ─────────────────────────────────────────────────────────────
// Key 6: TURBO → emturbovid.com  (data-hash attribute)
// ─────────────────────────────────────────────────────────────
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

        callback(
            newExtractorLink(name, name, url = finalUrl, type = ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Key 7: VIDMOLY → vidmoly.net  (jwplayer file regex)
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
// Key 8: MULTIQ → blakiteapi.xyz  (API → rumble CDN tar-HLS)
// ─────────────────────────────────────────────────────────────
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
            data.ranges?.split("\n")?.forEach { line ->
                val m = Regex("""(\d+-\d+)\s*\(([^)]+)\)""").find(line.trim())
                if (m != null) {
                    rangeMap[m.groupValues[2].trim()] = m.groupValues[1]
                }
            }

            var emitted = false
            for (i in QUALITY_LABELS.indices) {
                val label = QUALITY_LABELS[i]
                val code = QUALITY_CODES[i]
                val range = rangeMap[label] ?: continue

                val streamUrl = CDN_BASE +
                    "$dataId.$code.tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl&r_range=$range"

                callback(
                    newExtractorLink(name, "$name [$label]", streamUrl, ExtractorLinkType.M3U8) {
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
                    callback(
                        newExtractorLink(name, "$name [$label]", streamUrl, ExtractorLinkType.M3U8) {
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
                callback(
                    newExtractorLink(name, "$name [$label]", streamUrl, INFER_TYPE) {
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
