package org.schabi.newpipe.bilai

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.util.NavigationHelper

/** "Watch later" is a normal local playlist with a fixed name, created on first use. */
object WatchLater {
    const val NAME = "Watch later"

    fun add(context: Context, item: StreamInfoItem) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val manager = LocalPlaylistManager(NewPipeDatabase.getInstance(appContext))
                    val existing = manager.playlists.blockingFirst().firstOrNull { it.name == NAME }
                    val entity = StreamEntity(item)
                    if (existing == null) {
                        manager.createPlaylist(NAME, listOf(entity)).blockingGet()
                        "Saved to Watch later"
                    } else {
                        val duplicate = manager.getPlaylistDuplicates(item.url).blockingFirst()
                            .any { it.uid == existing.uid && it.timesStreamIsContained > 0 }
                        if (duplicate) {
                            "Already in Watch later"
                        } else {
                            manager.appendToPlaylist(existing.uid, listOf(entity)).blockingGet()
                            "Saved to Watch later"
                        }
                    }
                } catch (e: Exception) {
                    "Couldn't save to Watch later"
                }
            }
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens the Watch later playlist, or the playlist list if nothing was saved yet. */
    fun open(context: Context, fm: FragmentManager) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val id = withContext(Dispatchers.IO) {
                try {
                    LocalPlaylistManager(NewPipeDatabase.getInstance(appContext))
                        .playlists.blockingFirst().firstOrNull { it.name == NAME }?.uid
                } catch (e: Exception) {
                    null
                }
            }
            if (id != null) {
                NavigationHelper.openLocalPlaylistFragment(fm, id, NAME)
            } else {
                Toast.makeText(appContext,
                    "Nothing saved yet. Use ⋮ → Watch later on any video.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
