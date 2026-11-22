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

    fun comment(videoId: String, text: String) = post("comment/create_comment") { body ->
        body.value("videoId", videoId).value("commentText", text)
    }

    private fun post(endpoint: String, payload: (JsonBuilder<JsonObject>) -> Unit): Completable =
        Completable.fromAction {
            val service = ServiceList.YouTube
            check(service.hasTokens())
            val headers = YoutubeParsingHelper.getClientInfoHeaders()
            YoutubeParsingHelper.addLoggedInHeaders(headers)
            check(headers.containsKey("Authorization"))
            headers["Content-Type"] = listOf("application/json")
            val body = YoutubeParsingHelper.prepareDesktopJsonBuilder(service.localization, service.contentCountry)
            payload(body)
            val response = NewPipe.getDownloader().post(
                YoutubeParsingHelper.YOUTUBEI_V1_URL + endpoint + "?prettyPrint=false",
                headers, JsonWriter.string(body.done()).toByteArray(Charsets.UTF_8), service.localization,
            )
            check(response.responseCode() in 200..299)
            val result = org.json.JSONObject(YoutubeParsingHelper.getValidJsonResponseBody(response))
            check(!result.has("error") && result.length() > 0)
            if (endpoint == "comment/create_comment") {
                check(result.optJSONArray("actions")?.length()?.let { it > 0 } == true)
            }
        }
}
