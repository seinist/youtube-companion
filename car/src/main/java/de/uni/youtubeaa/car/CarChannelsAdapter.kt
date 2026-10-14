package de.uni.youtubeaa.car

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.uni.youtubeaa.car.hybrid.HybridChannel
import kotlinx.coroutines.CoroutineScope

internal class CarChannelsAdapter(private val scope: CoroutineScope, private val openChannel: (HybridChannel) -> Unit) :
    ListAdapter<HybridChannel, CarChannelsAdapter.Holder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_car_channel, parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = getItem(position)
        holder.title.text = channel.title
        holder.image.bindThumbnail(scope, channel.thumbnailUrl, targetPx = (64 * holder.itemView.resources.displayMetrics.density).toInt())
        holder.itemView.setOnClickListener { openChannel(channel) }
    }
    override fun onViewRecycled(holder: Holder) { holder.image.clearThumbnailBinding() }
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.car_channel_image)
        val title: TextView = view.findViewById(R.id.car_channel_title)
    }
    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<HybridChannel>() {
            override fun areItemsTheSame(a: HybridChannel, b: HybridChannel) = a.channelId == b.channelId
            override fun areContentsTheSame(a: HybridChannel, b: HybridChannel) = a == b
        }
    }
}
