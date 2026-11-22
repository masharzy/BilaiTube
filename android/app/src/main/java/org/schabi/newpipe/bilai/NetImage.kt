package org.schabi.newpipe.bilai

import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.schabi.newpipe.util.PicassoHelper

/** Network image drawn with the app's shared Picasso instance. */
@Composable
fun NetImage(
    url: String?,
    modifier: Modifier = Modifier,
    avatar: Boolean = false,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF262320.toInt())
            }
        },
        update = { view ->
            if (view.tag != url) {
                view.tag = url
                if (url.isNullOrEmpty()) {
                    PicassoHelper.cancelRequest(view)
                    view.setImageDrawable(null)
                } else if (avatar) {
                    PicassoHelper.loadAvatar(url).into(view)
                } else {
                    PicassoHelper.loadThumbnail(url).into(view)
                }
            }
        },
    )
}
