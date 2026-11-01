package com.uminocelo.candle

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Builds the focusable tiles used in every home-screen row. */
class Tiles(private val context: Context) {
    private val res = context.resources
    private val tileWidth = res.getDimensionPixelSize(R.dimen.tile_width)
    private val tileHeight = res.getDimensionPixelSize(R.dimen.tile_height)
    private val gap = res.getDimensionPixelSize(R.dimen.tile_gap)
    private val iconSize = res.getDimensionPixelSize(R.dimen.tile_icon)

    fun text(label: CharSequence, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.text))
            textSize = 18f
            setPadding(gap, gap, gap, gap)
            styleAsTile(onClick)
        }

    /** An app tile: its TV banner when it has one, otherwise icon and label. */
    fun app(label: String, banner: Drawable?, icon: Drawable?, onClick: () -> Unit): View =
        if (banner != null) {
            ImageView(context).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = label
                val inset = gap / 3
                setPadding(inset, inset, inset, inset)
                styleAsTile(onClick)
            }
        } else {
            text(label, onClick).apply {
                textSize = 14f
                icon?.setBounds(0, 0, iconSize, iconSize)
                setCompoundDrawables(null, icon, null, null)
                compoundDrawablePadding = gap / 2
            }
        }

    private fun View.styleAsTile(onClick: () -> Unit) {
        layoutParams = LinearLayout.LayoutParams(tileWidth, tileHeight).apply { marginEnd = gap }
        background = context.getDrawable(R.drawable.tile_background)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
        setOnFocusChangeListener { view, focused ->
            val scale = if (focused) FOCUSED_SCALE else 1f
            view.animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_ANIMATION_MS).start()
        }
    }

    private companion object {
        const val FOCUSED_SCALE = 1.08f
        const val FOCUS_ANIMATION_MS = 120L
    }
}

fun ViewGroup.replaceChildren(views: List<View>) {
    removeAllViews()
    views.forEach(::addView)
}
