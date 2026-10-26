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

// ---------------------------------------------------------------------------
// Label helper
// ---------------------------------------------------------------------------
// The provider attaches the server name to an embed url as a fragment, for example
//   https://megaplay.buzz/stream/s-2/67753/sub?s=tcdn#HD-1
internal fun splitLabel(url: String): Pair<String, String?> {
    val idx = url.indexOf('#')
    if (idx < 0) return url to null
    val label = url.substring(idx + 1).ifBlank { null }
    return url.substring(0, idx) to label
}

// ---------------------------------------------------------------------------
// ZokoAnime (zokoanime.video/stream/mal/{malId}/{episode}/{sub|dub})
// ---------------------------------------------------------------------------
// Verified against the live site (Oct 2026):
//   1. The embed page holds  window.__P = "<base64>"
//   2. base64 decode, XOR every byte with the key "otaku-embed-v1", parse as JSON:
//        { src: master.m3u8, subtitles: [{ lang, label, default, src: .vtt }], ... }
//   3. The playlist, its segments and every .vtt answer 403 without Referer https://zokoanime.video/
// Links are shown as "ZokoAnime [Sub]" / "ZokoAnime [Dub]".
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
        val (embedUrl, _) = splitLabel(url)
        val lang = if (embedUrl.substringBefore("?").trimEnd('/').endsWith("/dub", true)) "Dub" else "Sub"
        val host = Regex("""^https?://[^/]+""").find(embedUrl)?.value ?: mainUrl
        val cdnReferer = "$host/"

        val html = app.get(
            embedUrl,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: "https://hianime.at/"))
        ).text
        val packed = Regex("""window\.__P\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return
        val json = decode(packed)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return

        val src = json.optString("src").ifBlank { null } ?: return
        val mediaHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to cdnReferer, "Origin" to host)
        val tag = "ZokoAnime-$lang"

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name [$lang]",
                url = "$src#$tag",
                type = ExtractorLinkType.M3U8
            ) {
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
            val ext = file.substringBefore('?').substringAfterLast('.', "vtt")
            subtitleCallback.invoke(
                newSubtitleFile("$label (ZokoAnime $lang)", "$file#$tag.$ext") {
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
// MegaPlay (megaplay.buzz, Anikoto servers HD-1 / HD-2 / Vidstream-1 / Vidstream-2)
// ---------------------------------------------------------------------------
// Same extractor as OtanoxProvider, verified against the live site (Oct 2026):
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
                "Referer" to (referer ?: "https://hianime.at/")
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
