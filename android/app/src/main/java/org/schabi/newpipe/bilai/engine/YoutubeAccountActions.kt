package org.schabi.newpipe.bilai.engine

import com.grack.nanojson.JsonBuilder
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonWriter
import io.reactivex.rxjava3.core.Completable
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

internal object YoutubeAccountActions {
    enum class Reaction(val endpoint: String) {
        LIKE("like/like"), DISLIKE("like/dislike"), NONE("like/removelike"),
    }

    fun react(videoId: String, reaction: Reaction) = post(reaction.endpoint) { body ->
        body.`object`("target").value("videoId", videoId).end()
    }

    fun subscribe(channelUrl: String, enabled: Boolean) =
        post(if (enabled) "subscription/subscribe" else "subscription/unsubscribe") { body ->
            val channelId = ServiceList.YouTube.channelLHFactory.fromUrl(channelUrl).id
            require(channelId.matches(Regex("UC[A-Za-z0-9_-]{22}")))
            body.array("channelIds").value(channelId).end()
        }

    /** What the signed-in account already did on a video: like status and channel subscription. */
    data class VideoState(val reaction: Reaction?, val subscribed: Boolean?)

    fun state(videoId: String): VideoState {
        val text = request("next") { body -> body.value("videoId", videoId) }
        val like = Regex("\"likeStatus\"\\s*:\\s*\"(LIKE|DISLIKE|INDIFFERENT)\"").find(text)?.groupValues?.get(1)
        val subscribed = Regex("\"subscribed\"\\s*:\\s*(true|false)").find(text)?.groupValues?.get(1)
        return VideoState(when (like) {
            "LIKE" -> Reaction.LIKE
            "DISLIKE" -> Reaction.DISLIKE
            "INDIFFERENT" -> Reaction.NONE
            else -> null
        }, subscribed?.toBoolean())
    }

    fun comment(videoId: String, text: String) = post("comment/create_comment") { body ->
        body.value("videoId", videoId).value("commentText", text)
    }

    private fun request(endpoint: String, payload: (JsonBuilder<JsonObject>) -> Unit): String {
        val service = ServiceList.YouTube
        check(service.hasTokens()) { "Not signed in" }
        val headers = YoutubeParsingHelper.getClientInfoHeaders()
        YoutubeParsingHelper.addLoggedInHeaders(headers)
        check(headers.containsKey("Authorization")) { "Sign-in session is incomplete; sign in again" }
        headers["Content-Type"] = listOf("application/json")
        headers["X-Goog-AuthUser"] = listOf("0")
        headers["X-Youtube-Bootstrap-Logged-In"] = listOf("true")
        val body = YoutubeParsingHelper.prepareDesktopJsonBuilder(service.localization, service.contentCountry)
        payload(body)
        val response = NewPipe.getDownloader().post(
            YoutubeParsingHelper.YOUTUBEI_V1_URL + endpoint + "?prettyPrint=false",
            headers, JsonWriter.string(body.done()).toByteArray(Charsets.UTF_8), service.localization,
        )
        val text = response.responseBody()
        if (response.responseCode() !in 200..299) {
            val message = runCatching { org.json.JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            android.util.Log.w("BilaiAccount", "$endpoint -> HTTP ${response.responseCode()}: ${text.take(400)}")
            error("HTTP ${response.responseCode()}" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
        }
        return text
    }

    private fun post(endpoint: String, payload: (JsonBuilder<JsonObject>) -> Unit): Completable =
        Completable.fromAction {
            val result = org.json.JSONObject(request(endpoint, payload))
            check(!result.has("error") && result.length() > 0) { result.optJSONObject("error")?.optString("message") ?: "Empty answer" }
            if (endpoint == "comment/create_comment") {
                check(result.optJSONArray("actions")?.length()?.let { it > 0 } == true)
            }
        }
}
