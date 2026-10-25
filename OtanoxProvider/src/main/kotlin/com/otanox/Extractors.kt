package com.otanox

import com.lagradost.cloudstream3.SubtitleFile
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
// Ravok (AnimeSalt SERVER 1, multi audio HLS)
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
// MegaPlay (Anikoto servers: HD-1 / HD-2 / Vidstream-1 / Vidstream-2)
// ---------------------------------------------------------------------------
// Copied from HianimeqProvider.
//   embed page -> data-id -> /stream/getSources?id= -> tracks + enc
//   enc is AES-256-CBC (fixed key / iv) -> {"file": master.m3u8}
// Naming rule asked for Otanox:
//   url with ?s=tcdn or ?s=bcdn (HD servers)  -> "Megaplay [Sub|Dub]"
//   url without ?s= or /videojs/ (Vidstream)  -> "Vidstream [Sub|Dub]"
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
        val (cleanUrl, _) = splitLabel(url)
        val serverName = if (cleanUrl.contains("?s=") && !cleanUrl.contains("/videojs/")) "Megaplay" else "Vidstream"
        val langTag = langFromUrl(cleanUrl)

        val html = app.get(
            cleanUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to (referer ?: "https://otanox.online/")
            )
        ).text
        val fileId = Regex("""data-id=["'](\d+)["']""")
            .find(html)?.groupValues?.get(1) ?: return

        val json = JSONObject(
            app.get(
                "$mainUrl/stream/getSources?id=$fileId",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to cleanUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text
        )

        // Soft subtitles (the thumbnails track is skipped).
        json.optJSONArray("tracks")?.let { tracks ->
            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                val kind = track.optString("kind")
                if (kind.isNotBlank() && !kind.equals("captions", true) && !kind.equals("subtitles", true)) continue
                val file = track.optString("file")
                if (file.isBlank() || file.contains("thumbnails")) continue
                subtitleCallback.invoke(SubtitleFile(track.optString("label").ifBlank { "Unknown" }, file))
            }
        }

        val enc = json.optString("enc").ifBlank { return }
        val sourceJson = JSONObject(decryptEnc(enc) ?: return)
        val masterUrl = sourceJson.optString("file")
            .ifBlank { sourceJson.optString("url") }
            .ifBlank { return }

        // The CDN answers 403 without the megaplay referer.
        callback.invoke(
            newExtractorLink(
                source = serverName,
                name = "$serverName [$langTag]",
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
            }
        )
    }

    private fun decryptEnc(enc: String): String? {
        return try {
            val keyBytes = ByteArray(32)
            val ivBytes = ByteArray(16)
            val keySrc = aesKey.toByteArray(Charsets.UTF_8)
            val ivSrc = aesIv.toByteArray(Charsets.UTF_8)
            System.arraycopy(keySrc, 0, keyBytes, 0, minOf(keySrc.size, 32))
            System.arraycopy(ivSrc, 0, ivBytes, 0, minOf(ivSrc.size, 16))

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            String(cipher.doFinal(Base64.getUrlDecoder().decode(enc)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun langFromUrl(url: String): String {
        val path = url.substringBefore("?").trimEnd('/').lowercase()
        return if (path.endsWith("/dub")) "Dub" else "Sub"
    }
}
