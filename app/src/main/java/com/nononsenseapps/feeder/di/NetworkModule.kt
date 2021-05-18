package com.nononsenseapps.feeder.di

import com.nononsenseapps.feeder.model.FeedParser
import com.nononsenseapps.feeder.ui.CustomTabsWarmer
import com.nononsenseapps.jsonfeed.Feed
import com.nononsenseapps.jsonfeed.JsonFeedParser
import com.nononsenseapps.jsonfeed.feedAdapter
import com.squareup.moshi.JsonAdapter
import okhttp3.OkHttpClient
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import org.kodein.di.provider
import org.kodein.di.singleton

val networkModule = DI.Module(name = "network") {
    // Parsers can carry state so safer to use providers
    bind<JsonAdapter<Feed>>() with provider { feedAdapter() }
    bind<JsonFeedParser>() with provider { JsonFeedParser(instance<OkHttpClient>(), instance()) }
    bind<FeedParser>() with provider { FeedParser(di) }
    bind<CustomTabsWarmer>() with singleton { CustomTabsWarmer(di) }
}
