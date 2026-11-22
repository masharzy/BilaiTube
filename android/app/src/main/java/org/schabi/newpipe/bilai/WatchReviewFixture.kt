package org.schabi.newpipe.bilai

import java.time.OffsetDateTime
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Data for the emulator's debug-only Watch entry point. */
internal object WatchReviewFixture {
    val date get() = DateWrapper(OffsetDateTime.now().minusDays(2))
    private const val CHANNEL = "BilaiTube Studio"
    private const val URL = "https://www.youtube.com/watch?v=fixture0000"
    private const val AVATAR = "bilai-review://avatar"

    fun stream(uri: String) = StreamInfo(0, "fixture0000", uri, "A quiet morning in the mountains").apply {
        uploaderName = CHANNEL
        uploaderAvatarUrl = AVATAR
        uploaderUrl = "https://www.youtube.com/channel/UC0000000000000000000000"
        uploaderSubscriberCount = 284_000
        viewCount = 434_000
        likeCount = 12_400
        dislikeCount = 1_200
        uploadDate = date
        description = Description("A weekend in the mountains, from the first light to the last trail.\n\nFilmed by BilaiTube Studio.", Description.PLAIN_TEXT)
        relatedItems = (1..10).map { index ->
            StreamInfoItem(0, "https://www.youtube.com/watch?v=fixture%04d".format(index),
                "Mountain field notes · Episode $index", StreamType.VIDEO_STREAM).apply {
                uploaderName = CHANNEL
                thumbnailUrl = "bilai-review://thumbnail/$index"
                duration = 480L + index * 73L
                viewCount = 91_000L + index * 1_000
                uploadDate = date
            }
        }
    }

    val comments = listOf(
        comment("1", "Nadia", "The light on the ridge is beautiful. I felt like I was on the trail with you.", 980, 2),
        comment("2", "Samir", "The quiet section at the start was my favorite.", 340, 1),
        comment("3", "Lin", "Saving this for my next weekend hike.", 128, 1),
    )

    fun replies(id: String): List<CommentsInfoItem> = listOf(
        comment("$id-a", CHANNEL, "Thanks for coming along!", 21, 0),
        comment("$id-b", "Maya", "I loved that part too.", 8, 0),
    ).take(comments.firstOrNull { it.commentId == id }?.replyCount ?: 0)

    private fun comment(id: String, author: String, body: String, likes: Int, replies: Int) =
        CommentsInfoItem(0, URL, author).apply {
            commentId = id
            uploaderName = author
            uploaderAvatarUrl = AVATAR
            commentText = Description(body, Description.PLAIN_TEXT)
            textualUploadDate = "2 days ago"
            likeCount = likes
            replyCount = replies
        }
}
