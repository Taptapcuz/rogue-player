package com.rogueplayer.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import com.rogueplayer.data.Entry

/** FrameLayout whose height is always [ratio] × its measured width (poster art). */
class AspectRatioFrame(context: Context, private val ratio: Float) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((w * ratio).toInt(), MeasureSpec.EXACTLY),
        )
    }
}

/**
 * Plex-style poster tile: rounded 2:3 cover art (initials as the loading /
 * missing-art fallback) with the title and a "year · category" line beneath.
 * [compact] renders the same art as a short horizontal row (search results).
 */
class PosterCell(context: Context, private val compact: Boolean = false) : LinearLayout(context) {

    private val frame = AspectRatioFrame(context, POSTER_RATIO)
    private val poster = ImageView(context)
    private val fallback = TextView(context)
    private val title = TextView(context)
    private val sub = TextView(context)

    init {
        orientation = if (compact) HORIZONTAL else VERTICAL
        gravity = if (compact) Gravity.CENTER_VERTICAL else Gravity.TOP
        setPadding(
            dp(if (compact) 6 else 5), dp(if (compact) 5 else 5),
            dp(if (compact) 10 else 5), dp(if (compact) 5 else 9),
        )

        fallback.apply {
            textSize = 20f
            setTextColor(Color.argb(235, 255, 255, 255))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        poster.scaleType = ImageView.ScaleType.CENTER_CROP
        frame.clipToOutline = true
        // fallback sits under the art so it shows while loading and on failure
        frame.addView(fallback, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(poster, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        addView(frame, if (compact) {
            LayoutParams(dp(54), dp(81))
        } else {
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        title.apply {
            textSize = if (compact) 14f else 12.5f
            setTextColor(Palette.TEXT)
            setTypeface(null, Typeface.BOLD)
            maxLines = if (compact) 1 else 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(if (compact) 12 else 2), dp(if (compact) 0 else 7), dp(2), 0)
        }
        sub.apply {
            textSize = if (compact) 11.5f else 10.5f
            setTextColor(Palette.DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(if (compact) 12 else 2), dp(2), dp(2), 0)
        }
        val text = LinearLayout(context).apply { orientation = VERTICAL }
        text.addView(title, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        text.addView(sub, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(text, if (compact) {
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        } else {
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    /** Bind [e]; [hue] fills the tile behind missing/low-res art. */
    fun bind(e: Entry, hue: Int) {
        frame.background = GradientDrawable().apply {
            setColor(hue)
            cornerRadius = dp(9).toFloat()
        }
        val art = e.logo
        if (art.isNullOrBlank()) {
            poster.visibility = GONE
            poster.setImageDrawable(null)
        } else {
            poster.visibility = VISIBLE
            poster.load(art) {
                crossfade(true)
                listener(
                    onError = { _, result ->
                        android.util.Log.w("PosterCell", "poster failed: $art (${result.throwable.message})")
                    },
                )
            }
        }
        fallback.text = initials(Titles.clean(e.name))

        title.text = Titles.clean(e.name)
        val bits = listOfNotNull(
            Titles.year(e.name),
            e.group.takeIf { it.isNotBlank() }?.let { Titles.clean(it) },
        )
        sub.text = bits.joinToString(" · ")
        sub.visibility = if (sub.text.isBlank()) GONE else VISIBLE
    }

    private fun initials(s: String): String =
        s.split(" ", "_", "-", ":").filter { it.isNotBlank() }.take(2)
            .map { it.first().uppercaseChar() }.joinToString("")

    private fun dp(n: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics,
    ).toInt()

    companion object {
        /** Plex/IMDb poster aspect: height = 1.5 × width. */
        const val POSTER_RATIO = 1.5f
    }
}
