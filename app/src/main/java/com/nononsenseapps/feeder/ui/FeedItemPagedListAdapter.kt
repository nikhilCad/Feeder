package com.nononsenseapps.feeder.ui

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.model.FeedItemsViewModel
import com.nononsenseapps.feeder.model.PreviewItem
import com.nononsenseapps.feeder.model.SettingsViewModel
import com.nononsenseapps.feeder.util.Prefs
import org.threeten.bp.format.DateTimeFormatter
import org.threeten.bp.format.FormatStyle
import java.util.Locale

class FeedItemPagedListAdapter(
    private val context: Context,
    private val feedItemsViewModel: FeedItemsViewModel,
    private val settingsViewModel: SettingsViewModel,
    private val prefs: Prefs,
    private val actionCallback: ActionCallback
) :
    PagedListAdapter<PreviewItem, RecyclerView.ViewHolder>(PreviewItemDiffer) {

    private val shortDateTimeFormat: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

    private val linkColor: Int by lazy {
        settingsViewModel.accentColor
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position)?.id ?: ID_UNSET
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        FeedItemHolder(
            LayoutInflater
                .from(parent.context)
                .inflate(
                    R.layout.list_story_item,
                    parent,
                    false
                ),
            feedItemsViewModel,
            settingsViewModel,
            actionCallback
        )

    override fun onViewRecycled(vHolder: RecyclerView.ViewHolder) {
        val holder = vHolder as FeedItemHolder
        holder.resetView()
    }

    override fun onBindViewHolder(vHolder: RecyclerView.ViewHolder, position: Int) {
        val holder = vHolder as FeedItemHolder

        // Make sure view is reset if it was dismissed
        holder.resetView()

        // Get item
        val item = getItem(position)

        holder.rssItem = item

        if (item == null) {
            // Placeholder
            return
        }

        // Set the title first - must be wrapped in unicode marks so it displays correctly
        // with RTL
        val titleText = SpannableStringBuilder(context.unicodeWrap(item.feedDisplayTitle))
        // If no body, display domain of link to be opened
        if (holder.rssItem!!.plainSnippet.isEmpty()) {
            if (holder.rssItem!!.enclosureLink != null && holder.rssItem!!.enclosureFilename != null) {
                titleText.append(context.unicodeWrap(" \u2014 ${holder.rssItem!!.enclosureFilename}"))
            } else if (holder.rssItem?.domain != null) {
                titleText.append(context.unicodeWrap(" \u2014 ${holder.rssItem!!.domain}"))
            }

            if (titleText.length > item.feedDisplayTitle.length) {
                titleText.setSpan(
                    ForegroundColorSpan(linkColor),
                    item.feedDisplayTitle.length + 3, titleText.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        holder.authorTextView.text = titleText

        item.pubDate.let {
            if (it == null) {
                holder.dateTextView.visibility = View.GONE
            } else {
                holder.dateTextView.visibility = View.VISIBLE
                holder.dateTextView.text = it.toLocalDate().format(shortDateTimeFormat)
            }
        }

        holder.fillTitle()

        if (item.imageUrl?.isNotEmpty() == true && prefs.showThumbnails) {
            // Take up width
            holder.imageView.visibility = View.VISIBLE
            // Load image when item has been measured
            holder.itemView.viewTreeObserver.addOnPreDrawListener(holder)
        } else {
            holder.imageView.visibility = View.GONE
        }
    }
}
