package com.myanimes

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

        val marker = "const datas = \""
        val start = scripts.indexOf(marker)
        if (start < 0) return
        val from = start + marker.length
        val end = scripts.indexOf('"', from)
        if (end < 0) return
        val encrypted = scripts.substring(from, end)
        if (encrypted.isBlank()) return

        val jsonBody = "{\"text\": \"$encrypted\"}"
        val decrypted = app.post(
            url = "https://enc-dec.app/api/dec-abyss",
            headers = headers,
            requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        ).parsedSafe<AbyssResponse>()?.result ?: return

        decrypted.sources
            .filter { it.status }
            .forEach { source ->
                val linkName = name + " [" + source.codec.uppercase() + "]"
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = linkName,
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

class StreamP2P : UpnsPlayer() {
    override var name = "StreamP2P"
    override var mainUrl = "https://hindianimezone.p2pplay.online"
}

class Cloudy : UpnsPlayer() {
    override var name = "Cloudy"
    override var mainUrl = "https://cloudy.upns.one"
}

/**
 * RubyVidHub -> rubyvidhub.com (redirects to nuvorel.top, streamruby family).
 *
 * Verified live flow (2025-11):
 *  1. GET https://rubyvidhub.com/embed-{code}.html  (follows 302 -> nuvorel.top)
 *     The embed page contains the jwplayer config inline - no POST /dl needed
 *     (unlike rubystm.com, POST /dl here returns a generic page).
 *  2. Video:  jwplayer("vplayer").setup({ sources: [{file: "...master.m3u8?t=..."}] })
 *  3. Subs:   tracks: [{file: "..._sli.vtt", kind: "thumbnails"},   <- preview, skip
 *                      {file: "..._eng.vtt", label: "English", kind: "captions", "default": true}]
 *     Only kind "captions" tracks are emitted as subtitles.
 *  4. The stream CDN rejects non-browser user agents, so playback must
 *     carry a desktop User-Agent header.
 */
class RubyVidHub : ExtractorApi() {
    override var name = "RubyVidHub"
    override var mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileCode = url.substringAfterLast("/embed-").substringBefore(".html")
        if (fileCode.isBlank()) return

        // Follow the redirect (rubyvidhub.com -> nuvorel.top) and grab the
        // embed page that already contains the full jwplayer setup.
        val embedRes = app.get(url, referer = referer ?: mainUrl)
        val html = embedRes.text

        val m3u8 = Regex("""file\s*:\s*"?(https?://[^"\s]+\.m3u8[^"\s]*)"?""")
            .find(html)?.groupValues?.get(1) ?: return

        // tracks: only kind "captions" - skip the "thumbnails" preview track
        Regex("""\{[^{}]*file\s*:\s*"?(https?://[^"\s]+\.vtt[^"\s]*)"?[^{}]*\}""").findAll(html)
            .forEach { track ->
                val obj = track.groupValues[0]
                if (!obj.contains("captions", ignoreCase = true)) return@forEach
                val label = Regex("""label\s*:\s*"([^"]*)"""").find(obj)
                    ?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: "English"
                subtitleCallback(SubtitleFile(label, track.groupValues[1]))
            }

        val playerBase = try {
            val u = URI(embedRes.url)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            mainUrl
        }

        callback(
            newExtractorLink(source = name, name = name, url = m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = "$playerBase/"
                this.quality = Qualities.Unknown.value
                // streamruby.net CDN rejects okhttp default UA (403),
                // so the playback request must carry a desktop UA
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$playerBase/"
                )
            }
        )
    }
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

        // Step 1: Try streamingConfig first - it gives the real CDN URL (94.x.x.x).
        // hlsVideoTiktok is a TikTok CDN URL that is geo-restricted and does not play.
        // source/hls are used only when streamingConfig produces nothing.
        val finalUrl = buildFromStreamingConfig(obj)
            ?: buildFromAbsolutePath(obj)
            ?: run { Log.e(name, "No playable URL found in response"); return }

        callback(newExtractorLink(name, name, url = finalUrl, type = ExtractorLinkType.M3U8) {
            this.referer = "$baseurl/"
            this.quality = Qualities.Unknown.value
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
     * This always produces the real CDN URL (e.g. 94.131.x.x) that actually plays.
     * Returns null if streamingConfig is absent or all candidates are disabled.
     */
    private fun buildFromStreamingConfig(obj: JSONObject): String? {
        return try {
            // Get the relative video path (must NOT be a full URL - we need a path like /v4/...)
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

    /**
     * Fallback: if source/hls field already is an absolute URL use it directly.
     * hlsVideoTiktok is intentionally skipped - it is a geo-restricted TikTok CDN URL.
     */
    private fun buildFromAbsolutePath(obj: JSONObject): String? {
        val source = obj.optString("source").takeIf { it.startsWith("http") }
        val hls    = obj.optString("hls").takeIf { it.startsWith("http") }
        return source ?: hls
    }

    private fun decryptHex(hex: String): String? = try {
        val clean = hex.trim().removeSurrounding("\"")
        val data = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(), "AES"), IvParameterSpec(AES_IV.toByteArray()))
        String(cipher.doFinal(data))
    } catch (e: Exception) { 
        null 
    }

    protected fun getBaseUrl(url: String): String =
        try { URI(url).let { "${it.scheme}://${it.host}" } } catch (e: Exception) { mainUrl }
}

/**
 * scheme://host of a url, or null when it cannot be parsed.
 * Every extractor builds its requests, referers and headers from the origin
 * of the url it was handed (resolved live from the site's wrapper iframe),
 * so a rotating player domain can never break extraction.
 */
private fun urlOrigin(url: String): String? = try {
    val u = URI(url)
    if (u.host.isNullOrBlank()) null else "${u.scheme}://${u.host}"
} catch (e: Exception) {
    null
}

// ─────────────────────────────────────────────────────────────
// StreamHG (also the "smwh" mirror inside FilesForever) and its
// mirrors. JWPlayer packed script -> master m3u8 + .vtt subs.
// The quality label is read from the embed page title / meta tags.
// ─────────────────────────────────────────────────────────────
open class Streamhg : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://hgcloud.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // live origin of the embed page (this family rotates domains)
        val base = urlOrigin(url) ?: mainUrl
        val html = app.get(url, referer = referer ?: base).text

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
                this.referer = base
                this.quality = detectQualityFromPage(html)
                // the stream CDN rejects non-browser user agents
                // (okhttp -> 403), so carry a desktop UA explicitly
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$base/"
                )
            }
        )
    }
}

// Earnvids mirror (same JWPlayer unpack family as StreamHG)
class Earnvids : Streamhg() {
    override var name = "Earnvids"
    override var mainUrl = "https://morencius.com"
}

// Luluvdo mirror (same JWPlayer unpack family as StreamHG)
class Luluvdo : Streamhg() {
    override var name = "Luluvdo"
    override var mainUrl = "https://luluvdo.com"
}

/**
 * Best-effort quality detection from an embed page.
 * Most of these hosts print the release name in the page head, e.g.
 *   <title>Movie 1994 720p HD BDRip ...</title>
 * The real quality of the stream is read from there so CloudStream can
 * display it as the quality label of the link. Returns Unknown when the
 * page carries no quality info.
 */
private fun detectQualityFromPage(html: String): Int {
    Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)
        ?.groupValues?.get(1)
        ?.let { title ->
            Regex("""(\d{3,4})\s*p\b""", RegexOption.IGNORE_CASE).find(title)?.let {
                return getQualityFromName(it.groupValues[1] + "p")
            }
        }
    Regex("""<meta[^>]+(?:name|property)\s*=\s*["'](og:title|description)["'][^>]*>""", RegexOption.IGNORE_CASE)
        .findAll(html)
        .forEach { tag ->
            val content = Regex("""content\s*=\s*["']([^"']+)['"]""", RegexOption.IGNORE_CASE)
                .find(tag.value)?.groupValues?.get(1) ?: return@forEach
            Regex("""(\d{3,4})\s*p\b""").find(content)?.let {
                return getQualityFromName(it.groupValues[1] + "p")
            }
        }
    return Qualities.Unknown.value
}

// ─────────────────────────────────────────────────────────────
// Byse (used as the "flmn" mirror inside FilesForever)
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

        // live origin of the embed page (the api host rotates with it)
        val base = urlOrigin(url) ?: run {
            Log.e(name, "bad url: $url")
            return
        }

        val apiUrl = "$base/api/videos/$code"
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
                    this.referer = base
                    this.headers = mapOf(
                        "Referer" to "$base/",
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
// StreamTape (used as the "strmtp" mirror inside FilesForever)
// The embed page builds the link inside inline JS:
//   document.getElementById('robotlink').innerHTML =
//       '//streamtape.s' + ('xcdite/get_video?id=...').substring(2).substring(1)
// Apply the same substring operations and prefix "https:".
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
                this.referer = urlOrigin(url) ?: mainUrl
                this.quality = detectQualityFromPage(html)
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// MixDrop (used as the "mxdp" mirror inside FilesForever)
// Embed page contains a Dean-Edwards packed eval block whose
// unpacked source holds: wurl = "//host/path/{id}.mp4?s=...&e=..."
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
                this.referer = urlOrigin(url) ?: mainUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// DoodStream (used as the "ddstm" mirror inside FilesForever)
//  1) GET embed page -> find /pass_md5/{token}/{file} path
//  2) GET {origin}/pass_md5/... -> direct CDN url text
//  3) final link = direct url + "?" + random 10-char token
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
        // live origin of the embed page (the pass_md5 host rotates with it)
        val base = urlOrigin(url) ?: run {
            Log.e(name, "bad url: $url")
            return
        }

        val res = app.get(url, referer = referer ?: "$base/").text

        val md5Path = Regex("""/pass_md5/[^'"]+""").find(res)?.value ?: run {
            Log.e(name, "pass_md5 path not found")
            return
        }

        val directUrl = try {
            app.get("$base$md5Path", referer = "$base/").text.trim()
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
                this.referer = "$base/"
                this.quality = detectQualityFromPage(res)
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$base/"
                )
            }
        )
    }

    private fun randomToken(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }
}

// ─────────────────────────────────────────────────────────────
// FilesForever -> /embed/{sid}
//  1) GET the embed page (follows a 302 chain to the player origin)
//  2) POST embedhelper2.php on the player origin
//     -> sources: mirror definitions, mresult: base64 mirror id map
//  3) route every mirror id to its dedicated extractor
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
            app.get(url, referer = referer ?: mainUrl)
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
                    "Referer" to url,
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

        // helper: live mirror site url + id + embedSuffix (same as JS
        // constructIframeSrc). The helper api always carries the current
        // domain of each mirror; when it is missing the mirror is skipped
        // instead of guessing a hardcoded domain that may no longer exist.
        fun mirrorUrl(key: String, id: String): String? {
            val src = root.sources?.get(key) ?: return null
            val site = src.siteUrl?.takeIf { it.isNotBlank() } ?: return null
            return site + id + (src.embedSuffix ?: "")
        }

        // Step 3: route each mirror to its custom extractor

        // smwh = StreamHG
        mirrors["smwh"]?.takeIf { it.isNotBlank() }?.let { id ->
            val mirrorPage = mirrorUrl("smwh", id) ?: return@let
            try {
                Streamhg().getUrl(mirrorPage, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "StreamHG failed: ${e.message}")
            }
        }

        // strmp2 = StreamP2P (upns-family player)
        mirrors["strmp2"]?.takeIf { it.isNotBlank() }?.let { id ->
            try {
                val fullUrl = mirrorUrl("strmp2", id) ?: return@let
                UpnsPlayer().apply {
                    this.name = "StreamPro"
                    this.mainUrl = getHost(fullUrl)
                }.getUrl(fullUrl, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "StreamP2P failed: ${e.message}")
            }
        }

        // flmn = Byse (AES-256-GCM)
        mirrors["flmn"]?.takeIf { it.isNotBlank() }?.let { id ->
            val mirrorPage = mirrorUrl("flmn", id) ?: return@let
            try {
                Byse().getUrl(mirrorPage, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "Byse failed: ${e.message}")
            }
        }

        // flls = EarnVids
        mirrors["flls"]?.takeIf { it.isNotBlank() }?.let { id ->
            val mirrorPage = mirrorUrl("flls", id) ?: return@let
            try {
                Earnvids().getUrl(mirrorPage, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "EarnVids failed: ${e.message}")
            }
        }

        // strmtp = StreamTape
        mirrors["strmtp"]?.takeIf { it.isNotBlank() }?.let { id ->
            val mirrorPage = mirrorUrl("strmtp", id) ?: return@let
            try {
                StreamTape().getUrl(mirrorPage, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "StreamTape failed: ${e.message}")
            }
        }

        // mxdp = MixDrop
        mirrors["mxdp"]?.takeIf { it.isNotBlank() }?.let { id ->
            val mirrorPage = mirrorUrl("mxdp", id) ?: return@let
            try {
                MixDrop().getUrl(mirrorPage, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "MixDrop failed: ${e.message}")
            }
        }

        // ddstm = DoodStream
        mirrors["ddstm"]?.takeIf { it.isNotBlank() }?.let { id ->
            val mirrorPage = mirrorUrl("ddstm", id) ?: return@let
            try {
                DoodStream().getUrl(mirrorPage, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "DoodStream failed: ${e.message}")
            }
        }

        // llstm = Luluvdo
        mirrors["llstm"]?.takeIf { it.isNotBlank() }?.let { id ->
            val mirrorPage = mirrorUrl("llstm", id) ?: return@let
            try {
                Luluvdo().getUrl(mirrorPage, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "Luluvdo failed: ${e.message}")
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
// Vidara -> /e/{code}
//  1) code = last path segment of the /e/{code} url
//  2) POST {origin}/api/stream  (JSON body)
//        { "filecode": code, "device": "web", "codecs": [] }
//     -> { streaming_url, title, subtitles: [{type, language, file_path}] }
//  3) The stream CDN rejects non-browser user agents (okhttp -> 404),
//     so the link MUST carry a desktop User-Agent header.
// ─────────────────────────────────────────────────────────────
class Vidara : ExtractorApi() {
    override var name = "Vidara"
    override var mainUrl = "https://vidara.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileCode = url.trimEnd('/').substringAfterLast('/')
        if (fileCode.isBlank()) return

        // live origin of the embed page (the api host rotates with it)
        val base = urlOrigin(url) ?: run {
            Log.e(name, "bad url: $url")
            return
        }

        val res = try {
            app.post(
                url = "$base/api/stream",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$base/e/$fileCode",
                    "Content-Type" to "application/json"
                ),
                requestBody = "{\"filecode\":\"$fileCode\",\"device\":\"web\",\"codecs\":[]}".toRequestBody("application/json".toMediaType())
            ).parsedSafe<VidaraResponse>()
        } catch (e: Exception) {
            Log.e(name, "api/stream failed: ${e.message}")
            null
        } ?: return

        val streamUrl = res.streaming_url?.takeIf { it.startsWith("http") } ?: return

        // type 0 = caption track
        res.subtitles.orEmpty().forEach { sub ->
            val subUrl = sub.file_path?.takeIf { it.startsWith("http") } ?: return@forEach
            subtitleCallback(SubtitleFile(sub.language?.takeIf { it.isNotBlank() } ?: "Subtitle", subUrl))
        }

        callback(
            newExtractorLink(source = name, name = name, url = streamUrl, type = ExtractorLinkType.M3U8) {
                this.referer = "$base/"
                this.quality = detectQualityFromPage(res.title.orEmpty())
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$base/"
                )
            }
        )
    }

    data class VidaraResponse(
        val streaming_url: String? = null,
        val title: String? = null,
        val subtitles: List<VidaraSubtitle>? = null,
        val default_sub_lang: String? = null
    )

    data class VidaraSubtitle(
        val type: Int? = null,
        val language: String? = null,
        val file_path: String? = null
    )
}

// ─────────────────────────────────────────────────────────────
// Vidmoly -> /embed-{code}.html (vidmoly.net family)
// JWPlayer config inline -> m3u8 + .vtt subtitles.
// ─────────────────────────────────────────────────────────────
class Vidmoly : ExtractorApi() {
    override var name = "Vidmoly"
    override var mainUrl = "https://vidmoly.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = urlOrigin(url) ?: mainUrl
        val txt = app.get(url, referer = referer ?: base).text

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
                this.referer = base
                this.quality = detectQualityFromPage(txt)
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$base/"
                )
            }
        )
    }
}
