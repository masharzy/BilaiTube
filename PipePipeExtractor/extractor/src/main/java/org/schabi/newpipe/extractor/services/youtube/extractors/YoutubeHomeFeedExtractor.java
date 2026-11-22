package org.schabi.newpipe.extractor.services.youtube.extractors;

import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.addLoggedInHeaders;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getValidJsonResponseBody;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getYouTubeHeaders;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextAtKey;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder;
import static org.schabi.newpipe.extractor.utils.Utils.UTF_8;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.localization.TimeAgoParser;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.utils.JsonUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Loads the YouTube home feed (browse id {@code FEwhat_to_watch}) together with its category
 * chips. Everything is fetched on the device through InnerTube; with a logged in session the
 * feed is personalised, without one YouTube returns its generic regional feed.
 */
public final class YoutubeHomeFeedExtractor {

    private static final int MAX_DEPTH = 24;

    private YoutubeHomeFeedExtractor() {
    }

    public static final class Chip {
        public final String title;
        /** Continuation token which loads the feed of this chip; {@code null} for the first chip. */
        @Nullable
        public final String token;
        public final boolean selected;

        Chip(final String title, @Nullable final String token, final boolean selected) {
            this.title = title;
            this.token = token;
            this.selected = selected;
        }
    }

    public static final class Page {
        public final List<StreamInfoItem> items;
        public final List<Chip> chips;
        /** Token for the next page of this feed, or {@code null} when it is the last one. */
        @Nullable
        public final String continuation;

        Page(final List<StreamInfoItem> items, final List<Chip> chips,
             @Nullable final String continuation) {
            this.items = items;
            this.chips = chips;
            this.continuation = continuation;
        }
    }

    @Nonnull
    public static Page fetchHome(@Nonnull final Localization localization,
                                 @Nonnull final ContentCountry country)
            throws IOException, ExtractionException {
        final byte[] body = JsonWriter.string(prepareDesktopJsonBuilder(localization, country)
                .value("browseId", "FEwhat_to_watch")
                .done()).getBytes(UTF_8);
        final JsonObject data = browse(body, localization);

        final JsonObject grid = findFirstObject(data, "richGridRenderer", 0);
        final List<Chip> chips = parseChips(findFirstObject(data, "feedFilterChipBarRenderer", 0));
        if (grid == null) {
            return new Page(Collections.emptyList(), chips, null);
        }
        return parseItems(grid.getArray("contents"), chips, localization);
    }

    @Nonnull
    public static Page fetchContinuation(@Nonnull final String token,
                                         @Nonnull final Localization localization,
                                         @Nonnull final ContentCountry country)
            throws IOException, ExtractionException {
        final byte[] body = JsonWriter.string(prepareDesktopJsonBuilder(localization, country)
                .value("continuation", token)
                .done()).getBytes(UTF_8);
        final JsonObject data = browse(body, localization);

        final List<Chip> chips = parseChips(findFirstObject(data, "feedFilterChipBarRenderer", 0));
        JsonArray items = null;
        final JsonArray actions = data.getArray("onResponseReceivedActions");
        for (final Object action : actions) {
            if (!(action instanceof JsonObject)) {
                continue;
            }
            final JsonObject a = (JsonObject) action;
            final JsonObject append = a.has("appendContinuationItemsAction")
                    ? a.getObject("appendContinuationItemsAction")
                    : a.getObject("reloadContinuationItemsCommand");
            if (append.has("continuationItems")) {
                items = append.getArray("continuationItems");
                break;
            }
        }
        if (items == null) {
            return new Page(Collections.emptyList(), chips, null);
        }
        return parseItems(items, chips, localization);
    }

    /** A playlist saved in the signed-in account's library. */
    public static final class AccountPlaylist {
        public final String id;
        public final String title;
        @Nullable
        public final String thumbnail;
        @Nullable
        public final String count;

        AccountPlaylist(final String id, final String title, @Nullable final String thumbnail,
                        @Nullable final String count) {
            this.id = id;
            this.title = title;
            this.thumbnail = thumbnail;
            this.count = count;
        }
    }

    /**
     * Loads a playlist page as the signed-in user (works for private lists such as {@code WL} and
     * {@code LL}). Continue with {@link #fetchContinuation}.
     */
    @Nonnull
    public static Page fetchPlaylist(@Nonnull final String playlistId,
                                     @Nonnull final Localization localization,
                                     @Nonnull final ContentCountry country)
            throws IOException, ExtractionException {
        final byte[] body = JsonWriter.string(prepareDesktopJsonBuilder(localization, country)
                .value("browseId", "VL" + playlistId)
                .done()).getBytes(UTF_8);
        final JsonObject data = browse(body, localization);
        final JsonObject list = findFirstObject(data, "playlistVideoListRenderer", 0);
        if (list != null) {
            return parseItems(list.getArray("contents"), Collections.emptyList(), localization);
        }
        final JsonObject section = findFirstObject(data, "sectionListRenderer", 0);
        if (section == null) {
            return new Page(Collections.emptyList(), Collections.emptyList(), null);
        }
        return parseItems(section.getArray("contents"), Collections.emptyList(), localization);
    }

    /**
     * Any signed-in feed, e.g. {@code FEhistory} (watch history) or {@code FEsubscriptions}
     * (latest uploads of subscribed channels). Continue with {@link #fetchContinuation}.
     */
    @Nonnull
    public static Page fetchFeed(@Nonnull final String browseId,
                                 @Nonnull final Localization localization,
                                 @Nonnull final ContentCountry country)
            throws IOException, ExtractionException {
        final byte[] body = JsonWriter.string(prepareDesktopJsonBuilder(localization, country)
                .value("browseId", browseId)
                .done()).getBytes(UTF_8);
        final JsonObject data = browse(body, localization);
        final JsonObject grid = findFirstObject(data, "richGridRenderer", 0);
        if (grid != null) {
            return parseItems(grid.getArray("contents"), Collections.emptyList(), localization);
        }
        final JsonObject section = findFirstObject(data, "sectionListRenderer", 0);
        if (section != null) {
            return parseItems(section.getArray("contents"), Collections.emptyList(), localization);
        }
        return new Page(Collections.emptyList(), Collections.emptyList(), null);
    }

    /** A channel the signed-in account is subscribed to. */
    public static final class AccountChannel {
        public final String url;
        public final String name;
        @Nullable
        public final String avatar;

        AccountChannel(final String url, final String name, @Nullable final String avatar) {
            this.url = url;
            this.name = name;
            this.avatar = avatar;
        }
    }

    /** Channels the signed-in account is subscribed to ({@code FEchannels}). */
    @Nonnull
    public static List<AccountChannel> fetchSubscribedChannels(@Nonnull final Localization localization,
                                                               @Nonnull final ContentCountry country)
            throws IOException, ExtractionException {
        final byte[] body = JsonWriter.string(prepareDesktopJsonBuilder(localization, country)
                .value("browseId", "FEchannels")
                .done()).getBytes(UTF_8);
        final List<AccountChannel> out = new ArrayList<>();
        JsonObject data = browse(body, localization);
        String token = collectChannels(data, out, 0);
        // The list is paged; follow a few continuations so large accounts are complete.
        for (int i = 0; i < 20 && token != null; i++) {
            final byte[] next = JsonWriter.string(prepareDesktopJsonBuilder(localization, country)
                    .value("continuation", token)
                    .done()).getBytes(UTF_8);
            data = browse(next, localization);
            token = collectChannels(data, out, 0);
        }
        return out;
    }

    @Nullable
    private static String collectChannels(final Object node, final List<AccountChannel> out,
                                          final int depth) {
        if (depth > 24) {
            return null;
        }
        String token = null;
        if (node instanceof JsonObject) {
            final JsonObject obj = (JsonObject) node;
            final JsonObject channel = obj.has("channelRenderer") ? obj.getObject("channelRenderer")
                    : obj.has("gridChannelRenderer") ? obj.getObject("gridChannelRenderer") : null;
            if (channel != null) {
                final String id = channel.getString("channelId");
                final String name = getTextAtKeySafe(channel, "title");
                String avatar = null;
                final JsonArray thumbs = channel.getObject("thumbnail").getArray("thumbnails");
                if (!thumbs.isEmpty()) {
                    avatar = thumbs.getObject(thumbs.size() - 1).getString("url");
                    if (avatar != null && avatar.startsWith("//")) {
                        avatar = "https:" + avatar;
                    }
                }
                if (id != null && name != null) {
                    out.add(new AccountChannel("https://www.youtube.com/channel/" + id, name, avatar));
                }
                return null;
            }
            if (obj.has("continuationItemRenderer")) {
                return obj.getObject("continuationItemRenderer").getObject("continuationEndpoint")
                        .getObject("continuationCommand").getString("token");
            }
            for (final Object value : obj.values()) {
                final String found = collectChannels(value, out, depth + 1);
                if (found != null) {
                    token = found;
                }
            }
        } else if (node instanceof JsonArray) {
            for (final Object value : (JsonArray) node) {
                final String found = collectChannels(value, out, depth + 1);
                if (found != null) {
                    token = found;
                }
            }
        }
        return token;
    }

    /** Playlists in the signed-in account's library ({@code FEplaylist_aggregation}). */
    @Nonnull
    public static List<AccountPlaylist> fetchLibraryPlaylists(@Nonnull final Localization localization,
                                                              @Nonnull final ContentCountry country)
            throws IOException, ExtractionException {
        final byte[] body = JsonWriter.string(prepareDesktopJsonBuilder(localization, country)
                .value("browseId", "FEplaylist_aggregation")
                .done()).getBytes(UTF_8);
        final JsonObject data = browse(body, localization);
        final List<AccountPlaylist> result = new ArrayList<>();
        collectPlaylists(data, result, 0);
        return result;
    }

    private static void collectPlaylists(final Object node, final List<AccountPlaylist> out,
                                         final int depth) {
        if (depth > 16) {
            return;
        }
        if (node instanceof JsonObject) {
            final JsonObject obj = (JsonObject) node;
            if (obj.has("lockupViewModel")) {
                final JsonObject lockup = obj.getObject("lockupViewModel");
                final String type = lockup.getString("contentType", "");
                if (type.contains("PLAYLIST") || type.contains("PODCAST")) {
                    final String id = lockup.getString("contentId");
                    final String title = lockup.getObject("metadata")
                            .getObject("lockupMetadataViewModel").getObject("title")
                            .getString("content");
                    String thumb = null;
                    String count = null;
                    final JsonObject image = findFirstObject(lockup.getObject("contentImage"),
                            "thumbnailViewModel", 0);
                    if (image != null) {
                        final JsonArray sources = image.getObject("image").getArray("sources");
                        if (!sources.isEmpty()) {
                            thumb = sources.getObject(0).getString("url");
                        }
                        final JsonObject badge = findFirstObject(image, "thumbnailBadgeViewModel", 0);
                        if (badge != null) {
                            count = badge.getString("text");
                        }
                    }
                    if (id != null && title != null) {
                        out.add(new AccountPlaylist(id, title, thumb, count));
                    }
                }
                return;
            }
            if (obj.has("gridPlaylistRenderer")) {
                final JsonObject grid = obj.getObject("gridPlaylistRenderer");
                final String id = grid.getString("playlistId");
                final String title = getTextAtKeySafe(grid, "title");
                String thumb = null;
                final JsonArray thumbs = grid.getObject("thumbnail").getArray("thumbnails");
                if (!thumbs.isEmpty()) {
                    thumb = thumbs.getObject(0).getString("url");
                }
                if (id != null && title != null) {
                    out.add(new AccountPlaylist(id, title, thumb,
                            getTextAtKeySafe(grid, "videoCountShortText")));
                }
                return;
            }
            for (final Object value : obj.values()) {
                collectPlaylists(value, out, depth + 1);
            }
        } else if (node instanceof JsonArray) {
            for (final Object value : (JsonArray) node) {
                collectPlaylists(value, out, depth + 1);
            }
        }
    }

    /**
     * Name, handle and photo of the signed-in account, or {@code null} without a session.
     * @return {name, handle, photoUrl}
     */
    @Nullable
    public static String[] fetchAccount(@Nonnull final Localization localization,
                                        @Nonnull final ContentCountry country)
            throws IOException, ExtractionException {
        if (!ServiceList.YouTube.hasTokens()) {
            return null;
        }
        final byte[] body = JsonWriter.string(prepareDesktopJsonBuilder(localization, country)
                .done()).getBytes(UTF_8);
        final JsonObject data = post("account/account_menu", body, localization);
        final JsonObject header = findFirstObject(data, "activeAccountHeaderRenderer", 0);
        if (header == null) {
            return null;
        }
        final String name = getTextAtKeySafe(header, "accountName");
        final String handle = getTextAtKeySafe(header, "channelHandle");
        String photo = null;
        final JsonArray photos = header.getObject("accountPhoto").getArray("thumbnails");
        if (!photos.isEmpty()) {
            photo = photos.getObject(photos.size() - 1).getString("url");
        }
        return new String[]{name, handle, photo};
    }

    /** InnerTube browse request that carries the account cookies when a session exists. */
    private static JsonObject browse(final byte[] body, final Localization localization)
            throws IOException, ExtractionException {
        return post("browse", body, localization);
    }

    private static JsonObject post(final String endpoint, final byte[] body,
                                   final Localization localization)
            throws IOException, ExtractionException {
        final Map<String, List<String>> headers = getYouTubeHeaders();
        addLoggedInHeaders(headers);
        headers.put("Content-Type", Collections.singletonList("application/json"));
        return JsonUtils.toJsonObject(getValidJsonResponseBody(NewPipe.getDownloader().post(
                YoutubeParsingHelper.YOUTUBEI_V1_URL + endpoint + "?prettyPrint=false", headers, body,
                localization)));
    }

    // ---- parsing -----------------------------------------------------------------------------

    @Nonnull
    private static List<Chip> parseChips(@Nullable final JsonObject chipBar) {
        final List<Chip> chips = new ArrayList<>();
        if (chipBar == null) {
            return chips;
        }
        for (final Object o : chipBar.getArray("contents")) {
            if (!(o instanceof JsonObject)) {
                continue;
            }
            final JsonObject chip = ((JsonObject) o).getObject("chipCloudChipRenderer");
            final String title = getTextAtKeySafe(chip, "text");
            if (title == null || title.isEmpty()) {
                continue;
            }
            final String token = chip.getObject("navigationEndpoint")
                    .getObject("continuationCommand").getString("token");
            chips.add(new Chip(title, token, chip.getBoolean("isSelected", false)));
        }
        return chips;
    }

    @Nullable
    private static String getTextAtKeySafe(final JsonObject obj, final String key) {
        try {
            return getTextAtKey(obj, key);
        } catch (final Exception e) {
            return null;
        }
    }

    @Nonnull
    private static Page parseItems(@Nonnull final JsonArray contents,
                                   @Nonnull final List<Chip> chips,
                                   @Nonnull final Localization localization) {
        final StreamInfoItemsCollector collector =
                new StreamInfoItemsCollector(ServiceList.YouTube.getServiceId());
        final TimeAgoParser timeAgoParser = ServiceList.YouTube.getTimeAgoParser(localization);
        final String[] continuation = new String[1];
        collect(contents, collector, timeAgoParser, continuation, 0);
        return new Page(new ArrayList<>(collector.getItems()), chips, continuation[0]);
    }

    private static void collect(@Nonnull final JsonArray contents,
                                @Nonnull final StreamInfoItemsCollector collector,
                                @Nonnull final TimeAgoParser timeAgoParser,
                                @Nonnull final String[] continuation,
                                final int depth) {
        if (depth > 6) {
            return;
        }
        for (final Object o : contents) {
            if (!(o instanceof JsonObject)) {
                continue;
            }
            final JsonObject item = (JsonObject) o;
            if (item.has("continuationItemRenderer")) {
                final String token = item.getObject("continuationItemRenderer")
                        .getObject("continuationEndpoint")
                        .getObject("continuationCommand").getString("token");
                if (token != null) {
                    continuation[0] = token;
                }
            } else if (item.has("playlistVideoRenderer")) {
                collector.commit(new YoutubeStreamInfoItemExtractor(
                        item.getObject("playlistVideoRenderer"), timeAgoParser));
            } else if (item.has("shelfRenderer")) {
                final JsonObject shelfContent = item.getObject("shelfRenderer").getObject("content");
                for (final String key : new String[]{"expandedShelfContentsRenderer",
                        "gridRenderer", "verticalListRenderer", "horizontalListRenderer"}) {
                    if (shelfContent.has(key)) {
                        final JsonObject inner = shelfContent.getObject(key);
                        collect(inner.has("items") ? inner.getArray("items") : inner.getArray("contents"),
                                collector, timeAgoParser, continuation, depth + 1);
                    }
                }
            } else if (item.has("gridVideoRenderer")) {
                collector.commit(new YoutubeStreamInfoItemExtractor(
                        item.getObject("gridVideoRenderer"), timeAgoParser));
            } else if (item.has("itemSectionRenderer")) {
                collect(item.getObject("itemSectionRenderer").getArray("contents"), collector,
                        timeAgoParser, continuation, depth + 1);
            } else if (item.has("playlistVideoListRenderer")) {
                collect(item.getObject("playlistVideoListRenderer").getArray("contents"), collector,
                        timeAgoParser, continuation, depth + 1);
            } else if (item.has("richItemRenderer")) {
                commitContent(item.getObject("richItemRenderer").getObject("content"),
                        collector, timeAgoParser);
            } else if (item.has("richSectionRenderer")) {
                final JsonObject shelf = item.getObject("richSectionRenderer")
                        .getObject("content").getObject("richShelfRenderer");
                if (shelf.has("contents")) {
                    collect(shelf.getArray("contents"), collector, timeAgoParser, continuation,
                            depth + 1);
                }
            } else {
                commitContent(item, collector, timeAgoParser);
            }
        }
    }

    private static void commitContent(@Nonnull final JsonObject content,
                                      @Nonnull final StreamInfoItemsCollector collector,
                                      @Nonnull final TimeAgoParser timeAgoParser) {
        if (content.has("videoRenderer")) {
            collector.commit(new YoutubeStreamInfoItemExtractor(
                    content.getObject("videoRenderer"), timeAgoParser));
        } else if (content.has("lockupViewModel")) {
            final JsonObject lockup = content.getObject("lockupViewModel");
            if ("LOCKUP_CONTENT_TYPE_VIDEO".equals(lockup.getString("contentType"))) {
                collector.commit(new YoutubeLockupStreamInfoItemExtractor(lockup, timeAgoParser));
            }
        } else if (content.has("shortsLockupViewModel")) {
            collector.commit(new YoutubeShortsInfoItemExtractor(
                    content.getObject("shortsLockupViewModel")) {
                @Override
                public String getUploaderName() {
                    return "YouTube Shorts";
                }
            });
        }
    }

    @Nullable
    private static JsonObject findFirstObject(final Object node, final String key,
                                              final int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        if (node instanceof JsonObject) {
            final JsonObject obj = (JsonObject) node;
            if (obj.has(key) && obj.get(key) instanceof JsonObject) {
                return obj.getObject(key);
            }
            for (final Object value : obj.values()) {
                final JsonObject found = findFirstObject(value, key, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof JsonArray) {
            for (final Object value : (JsonArray) node) {
                final JsonObject found = findFirstObject(value, key, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
