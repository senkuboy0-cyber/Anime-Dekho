package com.hianime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// "/sub" or "/dub" at the end of an embed path.
private fun audioOf(embedUrl: String): String =
    if (embedUrl.substringBefore("?").trimEnd('/').endsWith("/dub", true)) "Dub" else "Sub"

// ---------------------------------------------------------------------------
// ZokoAnime  (HiAnime server "ZokoAnime")
// ---------------------------------------------------------------------------
// Embed:  zokoanime.video/stream/mal/{malId}/{episode}/{sub|dub}
// The page has window.__P = "<base64>". Decoded and XORed with "otaku-embed-v1" it is JSON:
//   src          master m3u8
//   subtitles[]  label, lang, default, src (.vtt)
// The m3u8, its segments and the .vtt files need Referer https://zokoanime.video/.
class ZokoAnime : ExtractorApi() {
    override var name = "ZokoAnime"
    override var mainUrl = "https://zokoanime.video"
    override val requiresReferer = false

    private val xorKey = "otaku-embed-v1".toByteArray(Charsets.UTF_8)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val audio = audioOf(url)
        val host = Regex("""^https?://[^/]+""").find(url)?.value ?: mainUrl
        val cdnReferer = "$host/"

        val html = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: "https://hianime.at/"))
        ).text
        val packed = Regex("""window\.__P\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return
        val json = decode(packed)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val src = json.optString("src").ifBlank { null } ?: return

        val mediaHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to cdnReferer, "Origin" to host)

        callback.invoke(
            newExtractorLink(name, "$name [$audio]", url = src, type = ExtractorLinkType.M3U8) {
                this.referer = cdnReferer
                this.quality = Qualities.P1080.value
                this.headers = mediaHeaders
            }
        )

        val subs = json.optJSONArray("subtitles") ?: return
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            val file = s.optString("src").ifBlank { null } ?: continue
            val label = s.optString("label").ifBlank { s.optString("lang").ifBlank { "Unknown" } }
            subtitleCallback.invoke(
                newSubtitleFile("$label ($name $audio)", file) {
                    this.headers = mediaHeaders
                }
            )
        }
    }

    private fun decode(packed: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(packed.trim())
        val out = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor xorKey[i % xorKey.size].toInt()).toByte() }
        String(out, Charsets.UTF_8)
    }.getOrNull()
}

// ---------------------------------------------------------------------------
// Megaplay  (HiAnime server "HD-1", shown as "Megaplay")
// ---------------------------------------------------------------------------
// Embed:  megaplay.buzz/stream/s-2/{id}/{sub|dub}?s=tcdn
//   1. The embed page has data-id="{fileId}".
//   2. stream/getSources?id={fileId}&s=tcdn  ->  { enc, tracks[] }
//   3. enc is base64url AES-256-CBC (key / iv below, zero padded)  ->  { file: master m3u8 }
//   4. tracks[] are the subtitles (thumbnail tracks are skipped).
// The m3u8 and the .vtt files need Referer https://megaplay.buzz/.
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
        val audio = audioOf(url)
        val cdn = Regex("""[?&]s=([a-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
        val host = Regex("""^https?://[^/]+""").find(url)?.value ?: mainUrl
        val cdnReferer = "$host/"

        // 1. file id
        val html = app.get(
            url,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: "https://hianime.at/"))
        ).text
        val fileId = Regex("""data-id=["'](\d+)["']""").find(html)?.groupValues?.get(1) ?: return

        // 2. sources
        val sourcesUrl = "$host/stream/getSources?id=$fileId" + (cdn?.let { "&s=$it" } ?: "")
        val json = runCatching {
            JSONObject(
                app.get(
                    sourcesUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to url,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/plain, */*"
                    )
                ).text
            )
        }.getOrNull() ?: return

        val mediaHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to cdnReferer)

        // 3. video
        val masterUrl = json.optString("enc").ifBlank { null }
            ?.let { decryptEnc(it) }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let { it.optString("file").ifBlank { it.optString("url") } }
            ?.ifBlank { null }
            ?: json.optJSONArray("sources")?.optJSONObject(0)?.optString("file")?.ifBlank { null }
            ?: return

        callback.invoke(
            newExtractorLink(name, "$name [$audio]", url = masterUrl, type = ExtractorLinkType.M3U8) {
                this.referer = cdnReferer
                this.quality = Qualities.P1080.value
                this.headers = mediaHeaders
            }
        )

        // 4. subtitles
        val tracks = json.optJSONArray("tracks") ?: return
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val kind = track.optString("kind")
            if (kind.isNotBlank() && !kind.equals("captions", true) && !kind.equals("subtitles", true)) continue
            val file = track.optString("file").ifBlank { null } ?: continue
            if (file.contains("thumbnails", true) || file.endsWith(".jpg", true)) continue
            val label = track.optString("label").ifBlank { "Unknown" }
            subtitleCallback.invoke(
                newSubtitleFile("$label ($name $audio)", file) {
                    this.headers = mediaHeaders
                }
            )
        }
    }

    private fun decryptEnc(enc: String): String? = runCatching {
        val keyBytes = aesKey.toByteArray(Charsets.UTF_8).copyOf(32)
        val ivBytes = aesIv.toByteArray(Charsets.UTF_8).copyOf(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        val data = Base64.getUrlDecoder().decode(enc.trim().trimEnd('='))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrNull()
}
