package de.uni.youtubeaa.car

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/** A video cover stays 16:9 as the projected grid changes width. */
class VideoThumbnailView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * 9L / 16L).toInt())
    }
}
