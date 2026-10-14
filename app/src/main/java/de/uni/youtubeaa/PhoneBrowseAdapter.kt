@file:Suppress("DEPRECATION")
package de.uni.youtubeaa

import android.os.Parcelable
import android.support.v4.media.MediaDescriptionCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import de.uni.youtubeaa.car.clearThumbnailBinding
import de.uni.youtubeaa.car.hybrid.HybridChannel
import de.uni.youtubeaa.car.hybrid.FeedChip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal sealed interface PhoneBrowseRow {
    val key: String
    data class Video(val description: MediaDescriptionCompat, val playlist: Boolean = false) : PhoneBrowseRow {
        override val key = "video:${description.mediaId}"
    }
    data class Channels(val channels: List<HybridChannel>) : PhoneBrowseRow {
        override val key = "channels"
    }
    data class Header(val title: String) : PhoneBrowseRow {
        override val key = "heading"
    }
    data class Action(override val key: String, val title: String, val enabled: Boolean = true,
                      val click: () -> Unit) : PhoneBrowseRow
    /** Subscriptions filter row ("Alle", "Heute", "Videos", "Shorts", ...). */
    data class Chips(val chips: List<FeedChip>, val selectedTitle: String?,
                     val click: (FeedChip) -> Unit) : PhoneBrowseRow {
        override val key = "chips"
        // The click target is identity-compared by DiffUtil, so keep it out of equality.
        override fun equals(other: Any?) = other is Chips && other.chips == chips &&
            other.selectedTitle == selectedTitle
        override fun hashCode() = 31 * chips.hashCode() + (selectedTitle?.hashCode() ?: 0)
    }
}

/** Compact list rows, grid cards, and the horizontal subscription strip recycle their off-screen views. */
internal class PhoneBrowseAdapter(
    private val scope: CoroutineScope,
    private val bindVideo: (View, MediaDescriptionCompat, Boolean) -> Unit,
    private val bindChannel: (ImageView, HybridChannel) -> Unit,
    private val openChannel: (HybridChannel) -> Unit,
    private val repair: suspend (MediaDescriptionCompat) -> MediaDescriptionCompat,
    private val onRepaired: (MediaDescriptionCompat, MediaDescriptionCompat) -> Unit
) : ListAdapter<PhoneBrowseRow, PhoneBrowseAdapter.Holder>(DIFF) {
    private var channelScroll: Parcelable? = null
    var videoCards: Boolean = false
        private set

    init { stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY }

    fun setVideoCards(enabled: Boolean): Boolean {
        if (videoCards == enabled) return false
        videoCards = enabled
        // Video rows change their layout/view type only on an explicit user mode switch.
        notifyDataSetChanged()
        return true
    }

    fun isVideoPosition(position: Int): Boolean = currentList.getOrNull(position) is PhoneBrowseRow.Video

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is PhoneBrowseRow.Video -> if (videoCards) VIEW_VIDEO_CARD else VIEW_VIDEO_LIST
        is PhoneBrowseRow.Channels -> VIEW_CHANNELS
        is PhoneBrowseRow.Header -> VIEW_HEADER
        is PhoneBrowseRow.Action -> VIEW_ACTION
        is PhoneBrowseRow.Chips -> VIEW_CHIPS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val view = when (viewType) {
            VIEW_VIDEO_LIST -> LayoutInflater.from(context).inflate(R.layout.item_youtube_video, parent, false)
            VIEW_VIDEO_CARD -> LayoutInflater.from(context).inflate(R.layout.item_youtube_video_card, parent, false)
            VIEW_CHANNELS -> RecyclerView(context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                adapter = ChannelAdapter()
                isNestedScrollingEnabled = false
                itemAnimator = null
                setPadding(dp(12), dp(4), dp(12), dp(8))
                clipToPadding = false
            }
            VIEW_HEADER -> TextView(context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(context.getColor(R.color.md_theme_onSurface))
                setPadding(dp(16), dp(12), dp(16), dp(10))
            }
            VIEW_CHIPS -> HorizontalScrollView(context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(12), dp(4), dp(12), dp(8))
                    clipToPadding = false
                })
            }
            VIEW_ACTION -> MaterialButton(context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                    setMargins(dp(16), dp(6), dp(16), dp(12))
                }
                isAllCaps = false
                minHeight = dp(48)
            }
            else -> error("Unknown browse view type: $viewType")
        }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.job?.cancel()
        val row = getItem(position)
        holder.row = row
        when (row) {
            is PhoneBrowseRow.Video -> bindVideo(holder.itemView, row.description, row.playlist)
            is PhoneBrowseRow.Channels -> (holder.itemView as RecyclerView).let { strip ->
                (strip.adapter as ChannelAdapter).submitList(row.channels) {
                    channelScroll?.let { strip.layoutManager?.onRestoreInstanceState(it) }
                }
            }
            is PhoneBrowseRow.Header -> (holder.itemView as TextView).text = row.title
            is PhoneBrowseRow.Chips -> bindChips(holder.itemView as HorizontalScrollView, row)
            is PhoneBrowseRow.Action -> (holder.itemView as MaterialButton).apply {
                text = row.title
                isEnabled = row.enabled
                setOnClickListener { if (row.enabled) row.click() }
            }
        }
        if (holder.itemView.isAttachedToWindow) repairVisible(holder)
    }

    override fun onViewAttachedToWindow(holder: Holder) = repairVisible(holder)
    override fun onViewDetachedFromWindow(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
        (holder.itemView as? RecyclerView)?.let { channelScroll = it.layoutManager?.onSaveInstanceState() }
    }
    override fun onViewRecycled(holder: Holder) {
        onViewDetachedFromWindow(holder)
        holder.row = null
        clearImages(holder.itemView)
    }

    private fun repairVisible(holder: Holder) {
        val row = holder.row as? PhoneBrowseRow.Video ?: return
        if (holder.job?.isActive == true) return
        holder.job = scope.launch {
            val updated = repair(row.description)
            if (isActive && holder.row === row && updated !== row.description) onRepaired(row.description, updated)
        }
    }

    private fun bindChips(scroller: HorizontalScrollView, row: PhoneBrowseRow.Chips) {
        val strip = scroller.getChildAt(0) as LinearLayout
        val context = scroller.context
        // The chip count is small and changes rarely, so rebuilding beats a nested adapter.
        strip.removeAllViews()
        row.chips.forEachIndexed { index, chip ->
            val selected = chip.title == row.selectedTitle
            strip.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply {
                    if (index > 0) marginStart = dp(8)
                }
                text = chip.title
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(18)
                setPadding(dp(14), 0, dp(14), 0)
                strokeWidth = if (selected) 0 else dp(1)
                setTextColor(context.getColor(
                    if (selected) R.color.md_theme_surface else R.color.md_theme_onSurface))
                backgroundTintList = android.content.res.ColorStateList.valueOf(context.getColor(
                    if (selected) R.color.md_theme_onSurface else android.R.color.transparent))
                setOnClickListener { row.click(chip) }
            })
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        var row: PhoneBrowseRow? = null
        var job: Job? = null
    }

    private inner class ChannelAdapter : ListAdapter<HybridChannel, ChannelHolder>(CHANNEL_DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ChannelHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_subscription_channel, parent, false))
        override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
            val channel = getItem(position)
            holder.title.text = channel.title
            bindChannel(holder.image, channel)
            holder.itemView.setOnClickListener { openChannel(channel) }
        }
        override fun onViewRecycled(holder: ChannelHolder) { holder.image.clearThumbnailBinding() }
    }
    private class ChannelHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.channel_thumbnail)
        val title: TextView = view.findViewById(R.id.channel_title)
    }
    private fun View.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun clearImages(view: View) {
        if (view is ImageView) view.clearThumbnailBinding()
        if (view is ViewGroup) for (i in 0 until view.childCount) clearImages(view.getChildAt(i))
    }
    companion object {
        private const val VIEW_VIDEO_LIST = 0
        private const val VIEW_CHANNELS = 1
        private const val VIEW_HEADER = 2
        private const val VIEW_ACTION = 3
        private const val VIEW_CHIPS = 4
        private const val VIEW_VIDEO_CARD = 5

        private val DIFF = object : DiffUtil.ItemCallback<PhoneBrowseRow>() {
            override fun areItemsTheSame(a: PhoneBrowseRow, b: PhoneBrowseRow) = a.key == b.key
            override fun areContentsTheSame(a: PhoneBrowseRow, b: PhoneBrowseRow) = a == b
        }
        private val CHANNEL_DIFF = object : DiffUtil.ItemCallback<HybridChannel>() {
            override fun areItemsTheSame(a: HybridChannel, b: HybridChannel) = a.channelId == b.channelId
            override fun areContentsTheSame(a: HybridChannel, b: HybridChannel) = a == b
        }
    }
}
