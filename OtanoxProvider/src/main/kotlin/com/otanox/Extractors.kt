package com.otanox

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
            "Referer" to (referer ?: "https://www.otanox.online/")
        )

        val html = app.get(url, headers = pageHeaders).text
        val fileId = Regex("data-id=[\"'](\\d+)[\"']")
            .find(html)?.groupValues?.get(1) ?: return

        val langTag = langFromUrl(url)

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
                if (kind.isNotBlank()
                    && !kind.equals("captions", true)
                    && !kind.equals("subtitles", true)
                ) {
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
                name = "$name $langTag",
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
                    name = "$name $langTag",
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

class Flixcloud : ExtractorApi() {
    override var name = "Flixcloud"
    override var mainUrl = "https://flixcloud.cc"
    override val requiresReferer = true

    private val decApi = "https://enc-dec.app/api"
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://www.otanox.online/")
        )

        val html = app.get(url, headers = pageHeaders).text
        val rawData = Regex(
            "type:\\s*\"data\",\\s*data:\\s*(\\{.*?\})\\s*,\\s*uses:",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.get(1) ?: return

        val dataJson = jsObjectToJson(rawData)
        val dataObj = JSONObject(dataJson)

        val subs = dataObj.optJSONArray("subtitles")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                val subUrl = sub.optString("url")
                val label = sub.optString("language")
                    .ifBlank { sub.optString("label") }
                    .ifBlank { "Unknown" }
                if (subUrl.isNotBlank()) {
                    subtitleCallback.invoke(SubtitleFile(label, subUrl))
                }
            }
        }

        val langTag = langFromUrl(url)

        val tokenBody = mapOf("data" to mapper.readValue<Map<String, Any?>>(dataJson))
        val tokenRes = app.post(
            "$decApi/dec-flixcloud?type=token",
            json = tokenBody
        ).text
        val tokenJson = JSONObject(tokenRes)
        if (tokenJson.optInt("status") != 200) return
        val tokenResult = tokenJson.optJSONObject("result") ?: return
        val token = tokenResult.optString("token").ifBlank { return }
        val context = tokenResult.optJSONObject("context") ?: return

        val streamHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mainUrl
        )
        val encStreamText = app.get(
            "$mainUrl/api/m3u8/$token",
            headers = streamHeaders
        ).text
        val encStream = JSONObject(encStreamText)

        val streamPayload = mapOf(
            "data" to mapOf(
                "context" to mapper.readValue<Map<String, Any?>>(context.toString()),
                "stream_response" to mapper.readValue<Map<String, Any?>>(encStream.toString())
            )
        )
        val decRes = app.post(
            "$decApi/dec-flixcloud?type=stream",
            json = streamPayload
        ).text
        val decJson = JSONObject(decRes)
        if (decJson.optInt("status") != 200) return
        val masterUrl = decJson.optJSONObject("result")
            ?.optString("stream")
            ?.ifBlank { null } ?: return

        val playlistHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mainUrl
        )

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name $langTag",
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
                    name = "$name $langTag",
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

    private fun jsObjectToJson(raw: String): String {
        var s = raw
        s = Regex("([{\\[,]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*:")
            .replace(s) { mr ->
                mr.groupValues[1] + "\"" + mr.groupValues[2] + "\":"
            }
        s = Regex(",\\s*}").replace(s, "}")
        s = Regex(",\\s*]").replace(s, "]")
        return s
    }
}

private fun langFromUrl(url: String): String {
    val path = url.trimEnd('/').lowercase()
    return when {
        path.endsWith("/dub") || path.contains("/dub/") || path.contains("type=dub") -> "Dub"
        path.endsWith("/sub") || path.contains("/sub/") || path.contains("type=sub") -> "Sub"
        else -> "Sub"
    }
}

private fun parseHlsVariants(masterUrl: String, body: String): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    val lines = body.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
            val resolution = Regex("RESOLUTION=(\\d+)x(\\d+)")
                .find(line)?.groupValues?.get(2)
            val nameAttr = Regex("NAME=\"([^\"]+)\"")
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
                        else -> ""
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
        val origin = Regex("^(https?://[^/]+)").find(base)?.groupValues?.get(1) ?: baseTrim
        origin + path
    } else {
        "$baseTrim/$path"
    }
}