package org.schabi.newpipe.extractor.services.youtube.extractors;

import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse;
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Loads the YouTube home feed (browse id {@code FEwhat_to_watch}) together with its category
 * chips. Everything is fetched on the device through InnerTube; with a logged in session the
 * feed is personalised, without one YouTube returns its generic regional feed.
 */
public final class YoutubeHomeFeedExtractor {

    private static final int MAX_DEPTH = 12;

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
        final JsonObject data = getJsonPostResponse("browse", body, localization);

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
        final JsonObject data = getJsonPostResponse("browse", body, localization);

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
        if (depth > 4) {
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
