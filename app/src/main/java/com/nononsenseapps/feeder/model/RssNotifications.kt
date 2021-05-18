package com.nononsenseapps.feeder.model

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Browser.EXTRA_CREATE_NEW_TAB
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.NavDeepLinkBuilder
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.COL_LINK
import com.nononsenseapps.feeder.db.URI_FEEDITEMS
import com.nononsenseapps.feeder.db.room.FeedDao
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.db.room.FeedItemWithFeed
import com.nononsenseapps.feeder.db.room.ID_ALL_FEEDS
import com.nononsenseapps.feeder.ui.ARG_FEED_ID
import com.nononsenseapps.feeder.ui.ARG_ID
import com.nononsenseapps.feeder.ui.EXTRA_FEEDITEMS_TO_MARK_AS_NOTIFIED
import com.nononsenseapps.feeder.ui.OpenLinkInDefaultActivity
import com.nononsenseapps.feeder.util.bundle
import com.nononsenseapps.feeder.util.notificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.android.closestDI
import org.kodein.di.instance

const val notificationId = 73583
const val channelId = "feederNotifications"

@FlowPreview
suspend fun notify(appContext: Context) = withContext(Dispatchers.Default) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        createNotificationChannel(appContext)
    }

    val di by closestDI(appContext)

    val nm: NotificationManagerCompat by di.instance()

    val feedItems = getItemsToNotify(di)

    val notifications: List<Pair<Int, Notification>> = if (feedItems.isEmpty()) {
        emptyList()
    } else {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N || feedItems.size < 4) {
            // Cancel inbox notification if present
            nm.cancel(notificationId)
            // Platform automatically bundles 4 or more notifications
            feedItems.map {
                it.id.toInt() to singleNotification(appContext, it)
            }
        } else {
            // In this case, also cancel any individual notifications
            feedItems.forEach {
                nm.cancel(it.id.toInt())
            }
            // Use an inbox style notification to bundle many notifications together
            listOf(notificationId to inboxNotification(appContext, feedItems))
        }
    }

    notifications.forEach { (id, notification) ->
        nm.notify(id, notification)
    }
}

@FlowPreview
suspend fun cancelNotification(context: Context, feedItemId: Long) = withContext(Dispatchers.Default) {
    val nm = context.notificationManager
    nm.cancel(feedItemId.toInt())

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        notify(context)
    }
}

/**
 * This is an update operation if channel already exists so it's safe to call multiple times
 */
@TargetApi(Build.VERSION_CODES.O)
@RequiresApi(Build.VERSION_CODES.O)
private fun createNotificationChannel(context: Context) {
    val name = context.getString(R.string.notification_channel_name)
    val description = context.getString(R.string.notification_channel_description)

    val notificationManager: NotificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    val channel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW)
    channel.description = description

    notificationManager.createNotificationChannel(channel)
}

@FlowPreview
private fun singleNotification(context: Context, item: FeedItemWithFeed): Notification {
    val style = NotificationCompat.BigTextStyle()
    val title = item.plainTitle
    val text = item.feedDisplayTitle

    style.bigText(text)
    style.setBigContentTitle(title)

    val contentIntent =
        NavDeepLinkBuilder(context)
            .setGraph(R.navigation.nav_graph)
            .setDestination(R.id.readerFragment)
            .setArguments(
                bundle {
                    putLong(ARG_ID, item.id)
                }
            )
            .createPendingIntent(requestCode = item.id.toInt())

    val builder = notificationBuilder(context)

    builder.setContentText(text)
        .setContentTitle(title)
        .setContentIntent(contentIntent)
        .setDeleteIntent(getPendingDeleteIntent(context, item))
        .setNumber(1)

    // Note that notifications must use PNG resources, because there is no compatibility for vector drawables here

    item.enclosureLink?.let { enclosureLink ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(enclosureLink))
        intent.putExtra(EXTRA_CREATE_NEW_TAB, true)
        builder.addAction(
            R.drawable.notification_play_circle_outline,
            context.getString(R.string.open_enclosed_media),
            PendingIntent.getActivity(
                context,
                item.id.toInt(),
                getOpenInDefaultActivityIntent(context, item.id, enclosureLink),
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
    }

    item.link?.let { link ->
        builder.addAction(
            R.drawable.notification_open_in_browser,
            context.getString(R.string.open_link_in_browser),
            PendingIntent.getActivity(
                context,
                item.id.toInt(),
                getOpenInDefaultActivityIntent(context, item.id, link),
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
    }

    builder.addAction(
        R.drawable.notification_check,
        context.getString(R.string.mark_as_read),
        PendingIntent.getActivity(
            context,
            item.id.toInt(),
            getOpenInDefaultActivityIntent(context, item.id, link = null),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    )

    style.setBuilder(builder)
    return style.build() ?: error("Null??")
}

@FlowPreview
internal fun getOpenInDefaultActivityIntent(context: Context, feedItemId: Long, link: String? = null): Intent =
    Intent(
        Intent.ACTION_VIEW,
        // Important to keep the URI different so PendingIntents don't collide
        URI_FEEDITEMS.buildUpon().appendPath("$feedItemId").also {
            if (link != null) {
                it.appendQueryParameter(COL_LINK, link)
            }
        }.build(),
        context,
        OpenLinkInDefaultActivity::class.java
    )

/**
 * Use this on platforms older than 24 to bundle notifications together
 */
private fun inboxNotification(context: Context, feedItems: List<FeedItemWithFeed>): Notification {
    val style = NotificationCompat.InboxStyle()
    val title = context.getString(R.string.updated_feeds)
    val text = feedItems.map { it.feedDisplayTitle }.toSet().joinToString(separator = ", ")

    style.setBigContentTitle(title)
    feedItems.forEach {
        style.addLine("${it.feedDisplayTitle} \u2014 ${it.plainTitle}")
    }

    val contentIntent = NavDeepLinkBuilder(context)
        .setGraph(R.navigation.nav_graph)
        .setDestination(R.id.feedFragment)
        .setArguments(
            bundle {
                putLongArray(EXTRA_FEEDITEMS_TO_MARK_AS_NOTIFIED, LongArray(feedItems.size) { i -> feedItems[i].id })
                // We can be a little bit smart - if all items are from the same feed then go to that feed
                // Otherwise we should go to All feeds
                val feedIds = feedItems.map { it.feedId }.toSet()
                if (feedIds.toSet().size == 1) {
                    feedIds.first()?.let {
                        putLong(ARG_FEED_ID, it)
                    }
                } else {
                    putLong(ARG_FEED_ID, ID_ALL_FEEDS)
                }
            }
        )
        .createPendingIntent(requestCode = notificationId)

    val builder = notificationBuilder(context)

    builder.setContentText(text)
        .setContentTitle(title)
        .setContentIntent(contentIntent)
        .setDeleteIntent(getDeleteIntent(context, feedItems))
        .setNumber(feedItems.size)

    style.setBuilder(builder)
    return style.build() ?: error("How null??")
}

private fun getDeleteIntent(context: Context, feedItems: List<FeedItemWithFeed>): PendingIntent {
    val intent = Intent(context, RssNotificationBroadcastReceiver::class.java)
    intent.action = ACTION_MARK_AS_NOTIFIED

    val ids = LongArray(feedItems.size) { i -> feedItems[i].id }
    intent.putExtra(EXTRA_FEEDITEM_ID_ARRAY, ids)

    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
}

internal fun getDeleteIntent(context: Context, feedItem: FeedItemWithFeed): Intent {
    val intent = Intent(context, RssNotificationBroadcastReceiver::class.java)
    intent.action = ACTION_MARK_AS_NOTIFIED
    intent.data = Uri.withAppendedPath(URI_FEEDITEMS, "${feedItem.id}")
    val ids: LongArray = longArrayOf(feedItem.id)
    intent.putExtra(EXTRA_FEEDITEM_ID_ARRAY, ids)

    return intent
}

private fun getPendingDeleteIntent(context: Context, feedItem: FeedItemWithFeed): PendingIntent =
    PendingIntent.getBroadcast(context, 0, getDeleteIntent(context, feedItem), PendingIntent.FLAG_UPDATE_CURRENT)

private fun notificationBuilder(context: Context): NotificationCompat.Builder {
    val bm = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)

    return NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_stat_f)
        .setLargeIcon(bm)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_SOCIAL)
        .setPriority(NotificationCompat.PRIORITY_LOW)
}

@FlowPreview
private suspend fun getItemsToNotify(di: DI): List<FeedItemWithFeed> {
    val feedDao: FeedDao by di.instance()
    val feedItemDao: FeedItemDao by di.instance()

    val feeds = feedDao.loadFeedIdsToNotify()

    return when (feeds.isEmpty()) {
        true -> emptyList()
        false -> feedItemDao.loadItemsToNotify(feeds)
    }
}

fun NavDeepLinkBuilder.createPendingIntent(requestCode: Int): PendingIntent? =
    this.createTaskStackBuilder().getPendingIntent(requestCode, PendingIntent.FLAG_UPDATE_CURRENT)
