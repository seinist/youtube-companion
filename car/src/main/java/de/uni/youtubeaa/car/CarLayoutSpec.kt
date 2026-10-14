package de.uni.youtubeaa.car

/** Uses the projected view's size, independent of the phone's screen configuration. */
internal data class CarLayoutSpec(
    val touchDp: Int, val playDp: Int, val toolbarDp: Int,
    val coverDp: Int, val panelDp: Int, val compact: Boolean
) {
    companion object {
        fun forViewport(widthDp: Int, heightDp: Int): CarLayoutSpec {
            val width = widthDp.coerceAtLeast(1)
            val height = heightDp.coerceAtLeast(1)
            val compact = height < 360 || width < 560
            val cover = if (width < 520 || height < 240) 0
                else minOf(192, height - 100, width / 4).coerceAtLeast(0)
            val panel = if (width < 480) width else (width * 0.48f).toInt().coerceIn(320, 440)
            return CarLayoutSpec(if (compact) 48 else 56, if (compact) 56 else 72,
                if (compact) 56 else 64, cover, minOf(width, panel), compact)
        }
    }
}
