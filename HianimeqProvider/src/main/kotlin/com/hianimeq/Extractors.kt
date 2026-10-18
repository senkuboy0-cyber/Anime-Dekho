package com.hianimeq

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ZokoAnime : ExtractorApi() {
    override var name = "Zoko"
    override var mainUrl = "https://zokoanime.video"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://hianimeq.de/")
        )

        val html = app.get(url, headers = headers).text
        val encoded = Regex("""window\.__P\s*=\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1) ?: return

        val json = JSONObject(deobfuscate(encoded))
        val masterUrl = json.optString("src").ifBlank { return }

        val subs = json.optJSONArray("subtitles")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                val label = sub.optString("label").ifBlank { sub.optString("lang") }
                val src = sub.optString("src")
                if (label.isNotBlank() && src.isNotBlank()) {
                    subtitleCallback.invoke(SubtitleFile(label, src))
                }
            }
        }

        val playlistHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mainUrl
        )

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name [Auto]",
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
                this.headers = playlistHeaders
            }
        )

        val masterBody = runCatching {
            app.get(masterUrl, headers = playlistHeaders).text
        }.getOrNull() ?: return

        parseHlsVariants(masterUrl, masterBody).forEach { (qualityName, variantUrl) ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name [$qualityName]",
                    url = variantUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = getQualityFromName(qualityName)
                    this.headers = playlistHeaders
                }
            )
        }
    }

    private fun deobfuscate(blob: String): String {
        val key = "otaku-embed-v1".toByteArray(Charsets.UTF_8)
        val decoded = Base64.getDecoder().decode(blob)
        val out = ByteArray(decoded.size)
        for (i in decoded.indices) {
            out[i] = (decoded[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }
}

class MegaPlay : ExtractorApi() {
    override var name = "MegaPlay"
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
        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://hianimeq.de/")
        )

        val html = app.get(url, headers = pageHeaders).text
        val fileId = Regex("""data-id=["'](\d+)["']""")
            .find(html)?.groupValues?.get(1) ?: return

        val apiHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to url,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/plain, */*"
        )

        val apiUrl = "$mainUrl/stream/getSources?id=$fileId"
        val responseText = app.get(apiUrl, headers = apiHeaders).text
        val json = JSONObject(responseText)

        val tracks = json.optJSONArray("tracks")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                val kind = track.optString("kind")
                if (kind.isNotBlank() && !kind.equals("captions", true) && !kind.equals("subtitles", true)) {
                    continue
                }
                val label = track.optString("label").ifBlank { "Unknown" }
                val file = track.optString("file")
                if (file.isNotBlank()) {
                    subtitleCallback.invoke(SubtitleFile(label, file))
                }
            }
        }

        val enc = json.optString("enc")
        if (enc.isBlank()) return

        val decrypted = decryptEnc(enc) ?: return
        val sourceJson = JSONObject(decrypted)
        val masterUrl = sourceJson.optString("file")
            .ifBlank { sourceJson.optString("url") }
            .ifBlank { return }

        val playlistHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mainUrl
        )

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name [Auto]",
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
                this.headers = playlistHeaders
            }
        )

        val masterBody = runCatching {
            app.get(masterUrl, headers = playlistHeaders).text
        }.getOrNull() ?: return

        parseHlsVariants(masterUrl, masterBody).forEach { (qualityName, variantUrl) ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name [$qualityName]",
                    url = variantUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = getQualityFromName(qualityName)
                    this.headers = playlistHeaders
                }
            )
        }
    }

    private fun decryptEnc(enc: String): String? {
        return try {
            val keyBytes = ByteArray(32)
            val ivBytes = ByteArray(16)
            val keySrc = aesKey.toByteArray(Charsets.UTF_8)
            val ivSrc = aesIv.toByteArray(Charsets.UTF_8)
            System.arraycopy(keySrc, 0, keyBytes, 0, minOf(keySrc.size, 32))
            System.arraycopy(ivSrc, 0, ivBytes, 0, minOf(ivSrc.size, 16))

            val cipherData = Base64.getUrlDecoder().decode(enc)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes)
            )
            String(cipher.doFinal(cipherData), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

private fun parseHlsVariants(masterUrl: String, body: String): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    val lines = body.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
            val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""")
                .find(line)?.groupValues?.get(2)
            val nameAttr = Regex("""NAME="([^"]+)"""")
                .find(line)?.groupValues?.get(1)

            var j = i + 1
            while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) {
                j++
            }
            if (j < lines.size) {
                val path = lines[j].trim()
                if (path.isNotBlank() && !path.startsWith("#")) {
                    val absolute = resolveUrl(masterUrl, path)
                    val qualityName = when {
                        !nameAttr.isNullOrBlank() -> nameAttr
                        !resolution.isNullOrBlank() -> "${resolution}p"
                        else -> "Unknown"
                    }
                    result.add(qualityName to absolute)
                }
            }
            i = j
        }
        i++
    }
    return result.distinctBy { it.second }
}

private fun resolveUrl(base: String, path: String): String {
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val baseTrim = base.substringBeforeLast("/")
    return if (path.startsWith("/")) {
        val origin = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.get(1) ?: baseTrim
        origin + path
    } else {
        "$baseTrim/$path"
    }
}