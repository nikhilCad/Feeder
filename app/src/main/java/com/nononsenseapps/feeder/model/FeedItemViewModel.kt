package com.nononsenseapps.feeder.model

import android.app.Activity
import android.app.Application
import android.graphics.Point
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.LiveDataScope
import androidx.lifecycle.asLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.blob.blobFullFile
import com.nononsenseapps.feeder.blob.blobFullInputStream
import com.nononsenseapps.feeder.blob.blobInputStream
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.db.room.FeedItemWithFeed
import com.nononsenseapps.feeder.ui.text.UrlClickListener
import com.nononsenseapps.feeder.ui.text.toSpannedWithImages
import com.nononsenseapps.feeder.ui.text.toSpannedWithNoImages
import com.nononsenseapps.feeder.util.TabletUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.kodein.di.DI
import org.kodein.di.instance
import java.io.InputStream
import java.net.URL
import kotlin.math.roundToInt

@FlowPreview
class FeedItemViewModel(di: DI) : DIAwareViewModel(di) {
    private val dao: FeedItemDao by instance()
    val context: Application by instance()
    private val okHttpClient: OkHttpClient by instance()

    private lateinit var liveItem: LiveData<FeedItemWithFeed?>
    private lateinit var feedItem: FeedItemWithFeed

    private lateinit var liveDefaultText: LiveData<Spanned>
    private var currentDefaultTextOptions: TextOptions? = null

    private lateinit var liveFullText: LiveData<Spanned>
    private var currentFullTextOptions: TextOptions? = null

    private var fragmentUrlClickListener: UrlClickListener? = null

    fun getLiveItem(id: Long): LiveData<FeedItemWithFeed?> {
        if (!this::liveItem.isInitialized) {
            liveItem = dao.loadLiveFeedItem(id).asLiveData()
        }
        return liveItem
    }

    suspend fun getItem(id: Long): FeedItemWithFeed {
        if (!this::feedItem.isInitialized || feedItem.id != id) {
            feedItem = dao.loadFeedItemWithFeed(id) ?: error("no such item $id")
        }
        return feedItem
    }

    suspend fun getLiveTextMaybeFull(
        options: TextOptions,
        urlClickListener: UrlClickListener?
    ): LiveData<Spanned> =
        when (getItem(options.itemId).fullTextByDefault) {
            true -> getLiveFullText(options, urlClickListener)
            false -> getLiveDefaultText(options, urlClickListener)
        }

    suspend fun getLiveDefaultText(
        options: TextOptions,
        urlClickListener: UrlClickListener?
    ): LiveData<Spanned> {
        // Always update urlClickListener
        fragmentUrlClickListener = urlClickListener

        if (this::liveDefaultText.isInitialized && currentDefaultTextOptions == options) {
            Log.d("FeederItemViewModel", "Requested default text for old options: $options")
            return liveDefaultText
        }

        Log.d("FeederItemViewModel", "Requested default text for new options: $options")

        liveDefaultText = liveData(context = viewModelScope.coroutineContext) {
            loadTextFrom(options) {
                blobInputStream(
                    itemId = options.itemId,
                    filesDir = context.filesDir
                )
            }
        }

        currentDefaultTextOptions = options
        return liveDefaultText
    }

    fun getLiveFullText(
        options: TextOptions,
        urlClickListener: UrlClickListener?
    ): LiveData<Spanned> {
        // Always update urlClickListener
        fragmentUrlClickListener = urlClickListener

        if (this::liveFullText.isInitialized && currentFullTextOptions == options) {
            Log.d("FeederItemViewModel", "Requested full text for old options: $options")
            return liveFullText
        }

        Log.d("FeederItemViewModel", "Requested full text for new options: $options")

        liveFullText = liveData(context = viewModelScope.coroutineContext) {
            val fullTextPresent = fetchFullArticleIfMissing(
                itemId = options.itemId
            )
            if (fullTextPresent) {
                loadTextFrom(options) {
                    blobFullInputStream(
                        itemId = options.itemId,
                        filesDir = context.filesDir
                    )
                }
            }
        }

        currentFullTextOptions = options
        return liveFullText
    }

    suspend fun markAsRead(id: Long, unread: Boolean = false) = dao.markAsRead(id = id, unread = unread)
    suspend fun markAsReadAndNotified(id: Long) = dao.markAsReadAndNotified(id = id)

    private suspend fun LiveDataScope<Spanned>.loadTextFrom(
        options: TextOptions,
        streamProvider: () -> InputStream
    ) {
        val feedUrl = dao.loadFeedUrlOfFeedItem(id = options.itemId)
            ?: URL("https://missing.feedurl")

        Log.d("FeederItemViewModel", "Loading noImages for $options")
        val hasImageSpans = try {
            val noImages = withContext(Dispatchers.IO) {
                streamProvider().bufferedReader().use { reader ->
                    toSpannedWithNoImages(
                        di = di,
                        source = reader,
                        siteUrl = feedUrl,
                        maxSize = options.maxImageSize,
                        urlClickListener = { link ->
                            fragmentUrlClickListener?.invoke(link)
                        }
                    )
                }
            }
            emit(noImages)
            noImages.getAllImageSpans().isNotEmpty()
        } catch (e: Exception) {
            Log.e(
                "FeederItemViewModel",
                "Failed to load text with no images for $options",
                e
            )
            emit(
                SpannableString("Could not read blob for item with id [${options.itemId}]")
            )
            false
        }

        try {
            if (hasImageSpans) {
                Log.d("FeederItemViewModel", "Loading withImages for $options")
                val withImages = withContext(Dispatchers.IO) {
                    streamProvider().bufferedReader().use { reader ->
                        toSpannedWithImages(
                            di = di,
                            source = reader,
                            siteUrl = feedUrl,
                            maxSize = options.maxImageSize,
                            urlClickListener = { link ->
                                fragmentUrlClickListener?.invoke(link)
                            }
                        )
                    }
                }
                emit(
                    withImages
                )
            }
        } catch (e: Exception) {
            Log.e(
                "FeederItemViewModel",
                "Failed to load text with images for $options",
                e
            )
        }
    }

    private suspend fun LiveDataScope<Spanned>.fetchFullArticleIfMissing(itemId: Long): Boolean {
        return if (blobFullFile(itemId, context.filesDir).isFile) {
            true
        } else {
            Log.d("FeederItemViewModel", "Fetching full text for $itemId")
            emit(SpannableString(context.getString(R.string.fetching_full_article)))

            val item = dao.loadFeedItem(itemId)

            if (item == null) {
                Log.e("FeederItemViewModel", "No such item: $itemId")
                emit(SpannableString(context.getString(R.string.failed_to_fetch_full_article)))
                return false
            }

            val (result, throwable) = parseFullArticle(
                item,
                okHttpClient,
                context.filesDir
            )

            if (!result) {
                var reason = context.getString(R.string.failed_to_fetch_full_article)
                if (throwable != null) {
                    reason += "\n${throwable.message}"
                }
                emit(SpannableString(reason))
            }

            return result
        }
    }
}

internal fun Activity.maxImageSize(): Point {
    val size = Point()
    windowManager?.defaultDisplay?.getSize(size)
    if (TabletUtils.isTablet(this)) {
        // Using twice window height since we do scroll vertically
        size.set(resources.getDimension(R.dimen.reader_tablet_width).roundToInt(), 2 * size.y)
    } else {
        // Base it on window size
        size.set(size.x - 2 * resources.getDimension(R.dimen.keyline_1).roundToInt(), 2 * size.y)
    }

    return size
}

private fun Spanned.getAllImageSpans(): Array<out ImageSpan> =
    getSpans(0, length, ImageSpan::class.java) ?: emptyArray()

data class TextOptions(
    val itemId: Long,
    val maxImageSize: Point,
    val nightMode: Boolean
)
