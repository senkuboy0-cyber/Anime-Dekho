package com.otanox

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
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
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ---------------------------------------------------------------------------
// Label helper
// ---------------------------------------------------------------------------
// The provider attaches a label to every embed url as a fragment, for example
//   https://player.abyssplayer.com/l0TSmiL3k#Hindi
//   https://ravok.buzz/video/xxxx#Hindi
// The extractor reads that fragment and shows it in the link name: "Abyss [Hindi]".
internal fun splitLabel(url: String): Pair<String, String?> {
    val idx = url.indexOf('#')
    if (idx < 0) return url to null
    val label = url.substring(idx + 1).ifBlank { null }
    return url.substring(0, idx) to label
}

internal fun withLabel(name: String, label: String?): String =
    if (label.isNullOrBlank()) name else "$name [$label]"

// ---------------------------------------------------------------------------
// Ravok (AnimeSalt SERVER 1, one HLS with every audio track, shown as plain "Ravok")
// ---------------------------------------------------------------------------
// Same backend as AWSStream / Zephyrflick in AnimeDekhoProvider.
// POST player/index.php?do=getVideo returns the master m3u8, captions live in a packed script.
class Ravok : ExtractorApi() {
    override val name = "Ravok"
    override val mainUrl = "https://ravok.buzz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val (cleanUrl, label) = splitLabel(url)
        val hash = cleanUrl.trimEnd('/').substringAfterLast("/")
        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://animesalt.cx/")
        )
        val doc = app.get(cleanUrl, headers = pageHeaders).document

        val response = app.post(
            "$mainUrl/player/index.php?data=$hash&do=getVideo",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to cleanUrl
            ),
            data = mapOf("hash" to hash, "r" to mainUrl)
        ).parsedSafe<RavokResponse>() ?: return

        val m3u8 = response.videoSource ?: response.securedLink ?: return

        callback.invoke(
            newExtractorLink(name, withLabel(name, label), url = m3u8, type = ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
            }
        )

        // Subtitles are inside the eval(function(p,a,c,k,e,d)) packed player config.
        val packed = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().orEmpty()
        if (packed.isNotBlank()) {
            JsUnpacker(packed).unpack()?.let { unpacked ->
                val regex = Regex(""""kind"\s*:\s*"captions"\s*,\s*"file"\s*:\s*"(https[^"]+)"(?:\s*,\s*"label"\s*:\s*"([^"]+)")?""")
                regex.findAll(unpacked).forEachIndexed { i, m ->
                    val subLabel = m.groupValues.getOrNull(2)?.ifBlank { null }
                        ?: if (i == 0) "English" else "Subtitle ${i + 1}"
                    subtitleCallback.invoke(SubtitleFile(subLabel, m.groupValues[1]))
                }
            }
        }
    }

    data class RavokResponse(
        val hls: Boolean? = null,
        val videoImage: String? = null,
        val videoSource: String? = null,
        val securedLink: String? = null,
    )
}

// ---------------------------------------------------------------------------
// Abyss (AnimeSalt SERVER 2, one embed per language)
// ---------------------------------------------------------------------------
// Copied from AnimeDekhoProvider. The page holds `const datas = "..."`,
// enc-dec.app decrypts it into a list of mp4 sources (360p / 720p / 1080p).
// The language comes from the url fragment, so names look like "Abyss [Hindi]".
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
        val (cleanUrl, label) = splitLabel(url)
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/"
        )

        val document = app.get(cleanUrl, headers = headers).document
        val scripts = document.select("script").joinToString("\n") { it.data() }

        val encrypted = Regex("""const\s+datas\s*=\s*"([^"]*)"""")
            .find(scripts)?.groupValues?.getOrNull(1) ?: return

        val decrypted = app.post(
            url = "https://enc-dec.app/api/dec-abyss",
            headers = headers,
            requestBody = """{"text":"$encrypted"}""".toRequestBody("application/json".toMediaType())
        ).parsedSafe<AbyssResponse>()?.result ?: return

        decrypted.sources.filter { it.status }.forEach { source ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = withLabel(name, label),
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

// ---------------------------------------------------------------------------
// MegaPlay (megaplay.buzz, Anikoto servers HD-1 / HD-2 / Vidstream-1 / Vidstream-2)
// ---------------------------------------------------------------------------
// Written from scratch for Otanox and verified against the live site (Oct 2026):
//
//   1. Embed page            /stream/s-2/{realId}/{sub|dub}[?s=tcdn|bcdn]
//                            /videojs/stream/s-2/{realId}/{sub|dub}   (Vidstream player)
//      The page carries data-id="{fileId}".
//   2. Sources               {base}stream/getSources?id={fileId}[&s=tcdn|bcdn]
//      base is https://megaplay.buzz/ or https://megaplay.buzz/videojs/.
//      The player page appends the ?s= value of the embed url to this call, so it must be
//      forwarded here: tcdn and bcdn return different CDN hosts.
//      Answer: { tracks: [{file,label,kind}], enc, intro, outro, server }
//   3. enc                   base64url, AES-256-CBC, key/iv below (zero padded) -> {"file": master.m3u8}
//   4. Playlist and every subtitle .vtt answer 403 without Referer https://megaplay.buzz/
//
// The provider tags each embed with its server name as a url fragment
// (#Megaplay-1, #Megaplay-2, #Vidstream-1, #Vidstream-2); links are shown as
// "Megaplay-1 [Dub]" and subtitles as "English (Megaplay-1 Dub)".
//
// CloudStream (RepoLinkGenerator) drops links and subtitles whose url it has already seen. Several servers
// share the same playlist / vtt files, so the server name is appended as a url fragment.
// Fragments are never sent to the server, so playback is not affected.
class MegaPlay : ExtractorApi() {
    override var name = "Megaplay"
    override var mainUrl = "https://megaplay.buzz"
    override val requiresReferer = true

    private val aesKey = "i?LMTAx0Q6,:}50U"
    private val aesIv = "W0;27ToaUpl_P%'c"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val (embedUrl, label) = splitLabel(url)
        val isVideoJs = embedUrl.contains("/videojs/")
        val serverName = label ?: if (isVideoJs || !embedUrl.contains("?s=")) "Vidstream" else "Megaplay"
        val lang = if (embedUrl.substringBefore("?").trimEnd('/').endsWith("/dub", true)) "Dub" else "Sub"
        val cdn = Regex("""[?&]s=([a-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(embedUrl)?.groupValues?.get(1)

        // Embed host (megaplay.buzz) and the player base used by its JS.
        val host = Regex("""^https?://[^/]+""").find(embedUrl)?.value ?: mainUrl
        val apiBase = if (isVideoJs) "$host/videojs/" else "$host/"
        val cdnReferer = "$host/"

        // 1. Embed page -> file id
        val html = app.get(
            embedUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to (referer ?: "https://otanox.online/")
            )
        ).text
        val fileId = Regex("""data-id=["'](\d+)["']""").find(html)?.groupValues?.get(1) ?: return

        // 2. Sources
        val sourcesUrl = apiBase + "stream/getSources?id=$fileId" + (cdn?.let { "&s=$it" } ?: "")
        val json = runCatching {
            JSONObject(
                app.get(
                    sourcesUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/plain, */*"
                    )
                ).text
            )
        }.getOrNull() ?: return

        val tag = "$serverName $lang"
        val mediaHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to cdnReferer)

        // 3. Video
        val masterUrl = json.optString("enc").ifBlank { null }
            ?.let { decryptEnc(it) }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let { it.optString("file").ifBlank { it.optString("url") } }
            ?.ifBlank { null }
            // Older answers sent plain sources instead of enc.
            ?: json.optJSONArray("sources")?.optJSONObject(0)?.optString("file")?.ifBlank { null }
            ?: return

        callback.invoke(
            newExtractorLink(
                source = serverName,
                name = "$serverName [$lang]",
                url = "$masterUrl#${tag.replace(' ', '-')}",
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = cdnReferer
                this.quality = Qualities.P1080.value
                this.headers = mediaHeaders
            }
        )

        // 4. Subtitles, every track of every server (thumbnail tracks skipped).
        val tracks = json.optJSONArray("tracks") ?: return
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val kind = track.optString("kind")
            if (kind.isNotBlank() && !kind.equals("captions", true) && !kind.equals("subtitles", true)) continue
            val file = track.optString("file").ifBlank { null } ?: continue
            if (file.contains("thumbnails", true) || file.endsWith(".jpg", true)) continue
            val subLang = track.optString("label").ifBlank { "Unknown" }
            // The fragment keeps the url unique per server and still ends in the file
            // extension, which CloudStream uses to pick the subtitle mime type.
            val ext = file.substringBefore('?').substringAfterLast('.', "vtt")
            subtitleCallback.invoke(
                newSubtitleFile("$subLang ($tag)", "$file#${tag.replace(' ', '-')}.$ext") {
                    this.headers = mediaHeaders
                }
            )
        }
    }

    // AES-256-CBC with a zero padded 32 byte key and 16 byte iv; input is base64url.
    private fun decryptEnc(enc: String): String? = runCatching {
        val keyBytes = aesKey.toByteArray(Charsets.UTF_8).copyOf(32)
        val ivBytes = aesIv.toByteArray(Charsets.UTF_8).copyOf(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        val data = Base64.getUrlDecoder().decode(enc.trim().trimEnd('='))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrNull()
}
