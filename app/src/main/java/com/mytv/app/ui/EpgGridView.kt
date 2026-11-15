package com.mytv.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.mytv.app.data.Channel
import com.mytv.app.data.EpgProgram
import kotlin.math.max
import kotlin.math.min

/**
 * TiViMate-style TV guide: a fixed channel gutter on the left, a horizontally
 * scrolling time ruler, and one row of programme blocks per channel.
 *
 * Everything is drawn in one custom view on purpose — a RecyclerView per row
 * would need N synchronous horizontal scrollers, and this keeps 2D panning,
 * fling and the "now" line consistent. Only the visible window is drawn.
 */
class EpgGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Row(val channel: Channel, var programmes: List<EpgProgram> = emptyList())

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** Visible time window: [startTime] + 24h. */
    var startTime: Long = 0L
        private set
    private val windowMinutes = 24 * 60
    var pxPerMinute: Float = dp(4.2f)
        private set
    private val rowHeight = dp(60f)
    private val gutterWidth = dp(110f)
    private val rulerHeight = dp(30f)

    /** Row height in px — lets callers size an overlay to a whole channel row. */
    val rowHeightPx: Float get() = rowHeight

    /** Height of the time ruler in px, above the first channel row. */
    val rulerHeightPx: Float get() = rulerHeight

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            clampScroll()
            invalidate()
        }

    private var scrollX = 0f
    private var scrollY = 0f

    /** Width of any overlay pinned to the right (the preview pane), in px. */
    var rightInset: Float = 0f
        set(value) {
            field = value
            clampScroll()
            invalidate()
        }

    private fun rightEdge(): Float = width - rightInset

    /** Fires when the visible row range changes, so EpgActivity can lazy-load. */
    var onVisibleRangeChanged: ((IntRange) -> Unit)? = null
    var onProgramClick: ((Channel, EpgProgram) -> Unit)? = null
    var onChannelClick: ((Channel) -> Unit)? = null
    var onChannelLongClick: ((Channel) -> Unit)? = null
    var onScrollStart: (() -> Unit)? = null

    /** Channel ids marked as favourites — their gutter cell draws a star. */
    var favoriteIds: Set<String> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    /** Channel currently previewed in the corner window — its row is highlighted. */
    var selectedChannelId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    private var lastVisible: IntRange? = null

    /** Channels whose guide lookup has finished (so we can say "no data"). */
    val loadedChannels = mutableSetOf<String>()

    /** True while the user drags the fast-scroll thumb on the gutter edge. */
    private var scrubbing = false

    /** Set by GestureDetector.onFling so ACTION_UP doesn't fling twice. */
    private var flingStarted = false
    private var velocityTracker: android.view.VelocityTracker? = null

    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** Drag axis for the current gesture: 0 = undecided, 1 = horizontal, 2 = vertical. */
    private var lockAxis = 0
    private var downX = 0f
    private var downY = 0f

    /**
     * Momentum. Vertical flicks get a much bigger multiplier — these lists run to
     * thousands of channels, so a flick has to cover real distance.
     */
    private fun startFling(velocityX: Float, velocityY: Float) {
        scroller.forceFinished(true)
        // Respect the drag's axis lock: a vertical flick shouldn't slide the timeline.
        val vx = if (lockAxis == 2) 0f else velocityX * 1.35f
        val vy = if (lockAxis == 1) 0f else velocityY
        val vertical = kotlin.math.abs(vy) > kotlin.math.abs(vx)
        // Keep a flick worth the same number of *rows* whatever height the grid gets:
        // the portrait preview eats vertical space, so a fixed multiplier crossed far
        // more channels per flick than the list did in its taller layout.
        val visibleRows = ((height - rulerHeight) / rowHeight).coerceAtLeast(1f)
        val vScale = if (vertical && rows.size > 80) {
            (2.6f * (visibleRows / 8f)).coerceIn(1.2f, 2.8f)
        } else {
            1.35f
        }
        scroller.fling(
            scrollX.toInt(), scrollY.toInt(),
            vx.toInt(), (vy * vScale).toInt(),
            0, maxScrollX(), 0, maxScrollY(),
        )
        postOnAnimation(flingRunner)
    }


    // paints ------------------------------------------------------------
    private val bgPaint = Paint().apply { color = Palette.BG }
    private val gutterPaint = Paint().apply { color = Palette.PANEL }
    private val rowAltPaint = Paint().apply { color = 0xFF141420.toInt() }
    private val linePaint = Paint().apply { color = Palette.LINE; strokeWidth = dp(1f) }
    private val gridLinePaint = Paint().apply {
        color = 0x18FFFFFF; strokeWidth = dp(1f)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nowPaint = Paint().apply {
        color = Palette.ACCENTS[0]; strokeWidth = dp(1.5f)
    }
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT; textSize = dp(12.5f)
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.DIM; textSize = dp(10f)
    }
    private val chanPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT; textSize = dp(12f)
    }
    private val groupPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.MUTED; textSize = dp(9.5f)
    }
    private val rulerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.MUTED; textSize = dp(11f)
    }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.ACCENTS[0]; textSize = dp(12f); textAlign = Paint.Align.CENTER
    }
    private val tileTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(11f); textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    /** Drives momentum after a flick. Declared before [gesture] so it can use it. */
    private val flingRunner = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                scrollX = scroller.currX.toFloat()
                scrollY = scroller.currY.toFloat()
                clampScroll()
                notifyVisible()
                invalidate()
                postOnAnimation(this)
            }
        }
    }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            removeCallbacks(flingRunner)
            parent?.requestDisallowInterceptTouchEvent(true)
            lockAxis = 0
            downX = e.x
            downY = e.y
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            // Lock to the axis the finger committed to. Before this a mostly-vertical
            // drag also panned the timeline, so the guide drifted sideways while you
            // were scrolling channels and felt "wonky".
            if (lockAxis == 0) {
                val dx = kotlin.math.abs(e2.x - downX)
                val dy = kotlin.math.abs(e2.y - downY)
                if (dx > touchSlop || dy > touchSlop) lockAxis = if (dx > dy) 1 else 2
            }
            onScrollStart?.invoke()
            if (lockAxis != 2) scrollX += distanceX
            if (lockAxis != 1) scrollY += distanceY
            clampScroll()
            invalidate()
            notifyVisible()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            flingStarted = true
            startFling(velocityX, velocityY)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (e.y < rulerHeight) return
            val rowIndex = ((e.y - rulerHeight + scrollY) / rowHeight).toInt()
            rows.getOrNull(rowIndex)?.let { row ->
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onChannelLongClick?.invoke(row.channel)
            }
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.x < gutterWidth) {
                if (e.y < rulerHeight) return true
                val rowIndex = ((e.y - rulerHeight + scrollY) / rowHeight).toInt()
                rows.getOrNull(rowIndex)?.let { onChannelClick?.invoke(it.channel) }
                return true
            }
            val hit = hitTest(e.x, e.y)
            if (hit != null) {
                onProgramClick?.invoke(hit.first, hit.second)
                return true
            }
            // Nothing under the finger: the channel has no guide data (the row draws
            // "No guide data") or the tap landed in an empty slot. Select the channel
            // anyway — swallowing the tap made the right side of the guide feel dead
            // whenever the provider returned no EPG.
            if (e.y >= rulerHeight) {
                val rowIndex = ((e.y - rulerHeight + scrollY) / rowHeight).toInt()
                rows.getOrNull(rowIndex)?.let { onChannelClick?.invoke(it.channel) }
            }
            return true
        }
    })

    fun begin(nowMillis: Long = System.currentTimeMillis()) {
        // Start a little before "now" so the current programmes are visible.
        val quarter = 30 * 60_000L
        startTime = (nowMillis - 45 * 60_000L) / quarter * quarter
        scrollX = 0f
        scrollToNow()
    }

    /** Centre the timeline on the current programme. */
    fun scrollToNow() {
        val now = System.currentTimeMillis()
        val offsetMin = (now - startTime) / 60_000f
        scrollX = offsetMin * pxPerMinute - dp(40f)
        clampScroll()
        invalidate()
        notifyVisible()
    }

    fun setZoom(pixelsPerMinute: Float) {
        val centerMin = (scrollX + (width - gutterWidth) / 2f) / pxPerMinute
        pxPerMinute = pixelsPerMinute.coerceIn(dp(2f), dp(14f))
        scrollX = centerMin * pxPerMinute - (width - gutterWidth) / 2f
        clampScroll()
        invalidate()
        notifyVisible()
    }

    // ------------------------------------------------------------- geometry ---

    private fun contentWidth() = (rightEdge() - gutterWidth).coerceAtLeast(1f)

    private fun totalWidth() = windowMinutes * pxPerMinute

    private fun maxScrollX() = max(0, (totalWidth() - contentWidth()).toInt())

    private fun maxScrollY() = max(0, (rows.size * rowHeight - (height - rulerHeight)).toInt())

    private fun clampScroll() {
        scrollX = scrollX.coerceIn(0f, maxScrollX().toFloat())
        scrollY = scrollY.coerceIn(0f, maxScrollY().toFloat())
    }

    private fun xOf(timeMs: Long): Float =
        gutterWidth + (timeMs - startTime) / 60_000f * pxPerMinute - scrollX

    private fun timeOf(x: Float): Long =
        startTime + ((x - gutterWidth + scrollX) / pxPerMinute * 60_000f).toLong()

    private fun notifyVisible() {
        if (rows.isEmpty() || height == 0) return
        val first = (scrollY / rowHeight).toInt().coerceIn(0, rows.size - 1)
        val last = ((scrollY + height - rulerHeight) / rowHeight).toInt().coerceIn(0, rows.size - 1)
        val range = first..last
        if (range != lastVisible) {
            lastVisible = range
            onVisibleRangeChanged?.invoke(range)
        }
    }

    private fun hitTest(x: Float, y: Float): Pair<Channel, EpgProgram>? {
        if (x < gutterWidth || y < rulerHeight + dp(4f)) return null
        val rowIndex = ((y - rulerHeight + scrollY) / rowHeight).toInt()
        val row = rows.getOrNull(rowIndex) ?: return null
        val t = timeOf(x)
        return row.programmes.firstOrNull { t >= it.start && t < it.end }?.let { row.channel to it }
    }

    // ------------------------------------------------------------- draw ---

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (rows.isEmpty()) return

        val now = System.currentTimeMillis()
        val firstRow = (scrollY / rowHeight).toInt().coerceAtLeast(0)
        val lastRow = min(rows.size - 1, ((scrollY + height - rulerHeight) / rowHeight).toInt())

        // rows + programme blocks
        for (i in firstRow..lastRow) {
            val top = rulerHeight + i * rowHeight - scrollY
            val bottom = top + rowHeight
            if (i % 2 == 1) canvas.drawRect(gutterWidth, top, rightEdge(), bottom, rowAltPaint)
            canvas.drawLine(gutterWidth, bottom, rightEdge(), bottom, linePaint)
            drawRowBlocks(canvas, rows[i], top, now)
        }

        // channel gutter (drawn after blocks so it covers them when scrolling)
        canvas.drawRect(0f, rulerHeight, gutterWidth, height.toFloat(), gutterPaint)
        for (i in firstRow..lastRow) {
            val row = rows[i]
            val top = rulerHeight + i * rowHeight - scrollY
            if (row.channel.id == selectedChannelId) {
                blockPaint.color = withAlpha(Palette.ACCENTS[0], 0x30)
                rect.set(gutterWidth, top, rightEdge(), top + rowHeight)
                canvas.drawRect(rect, blockPaint)
            }
            drawChannelCell(canvas, row.channel, top)
        }
        canvas.drawLine(gutterWidth, rulerHeight, gutterWidth, height.toFloat(), linePaint)

        drawRuler(canvas, now)
        drawTimeGrid(canvas)

        if (rightInset > 0f) {
            canvas.drawLine(rightEdge(), 0f, rightEdge(), height.toFloat(), linePaint)
        }

        // now line
        val nowX = xOf(now)
        if (nowX in gutterWidth..rightEdge()) {
            canvas.drawLine(nowX, rulerHeight, nowX, height.toFloat(), nowPaint)
        }

        drawScrubber(canvas)
    }

    /** Fast-scroll thumb: drag it to jump through thousands of channels. */
    private fun drawScrubber(canvas: Canvas) {
        if (maxScrollY() <= 0) return
        val pad = dp(6f)
        val trackTop = rulerHeight + pad
        val trackBottom = height - pad
        val track = trackBottom - trackTop
        if (track <= dp(20f)) return
        val frac = (scrollY / maxScrollY()).coerceIn(0f, 1f)
        val thumbH = (track * (contentHeightFraction())).coerceIn(dp(28f), track)
        val top = trackTop + (track - thumbH) * frac

        blockPaint.color = if (scrubbing) Palette.ACCENTS[0] else 0x66FFFFFF
        rect.set(gutterWidth - dp(5f), top, gutterWidth - dp(2f), top + thumbH)
        canvas.drawRoundRect(rect, dp(2f), dp(2f), blockPaint)

        if (scrubbing) {
            val first = (scrollY / rowHeight).toInt() + 1
            val label = "$first / ${rows.size}"
            val w = rulerPaint.measureText(label) + dp(14f)
            val cy = (top + thumbH / 2f).coerceIn(rulerHeight + dp(16f), height - dp(16f))
            rect.set(gutterWidth - dp(10f) - w, cy - dp(13f), gutterWidth - dp(10f), cy + dp(13f))
            blockPaint.color = Palette.ACCENTS[0]
            canvas.drawRoundRect(rect, dp(6f), dp(6f), blockPaint)
            rulerPaint.color = Color.WHITE
            canvas.drawText(label, gutterWidth - dp(10f) - w + dp(7f), cy + dp(4f), rulerPaint)
            rulerPaint.color = Palette.MUTED
        }
    }

    private fun contentHeightFraction(): Float {
        val total = rows.size * rowHeight
        val visible = height - rulerHeight
        if (total <= 0f) return 1f
        return (visible / total).coerceIn(0.04f, 1f)
    }

    private fun isOnScrubber(x: Float): Boolean =
        x >= gutterWidth - dp(34f) && x <= gutterWidth + dp(10f)

    private fun scrubTo(y: Float) {
        val trackTop = rulerHeight + dp(6f)
        val trackBottom = height - dp(6f)
        val frac = ((y - trackTop) / (trackBottom - trackTop)).coerceIn(0f, 1f)
        scrollY = frac * maxScrollY()
        invalidate()
        notifyVisible()
    }

    private fun drawRowBlocks(canvas: Canvas, row: Row, top: Float, now: Long) {
        if (row.programmes.isEmpty()) {
            // Say so instead of showing a blank row: most PPV "Event" channels
            // simply have no guide, which looks like the app failed to load.
            if (row.channel.id in loadedChannels) {
                canvas.drawText(
                    "No guide data",
                    gutterWidth + dp(10f),
                    top + rowHeight / 2f + subPaint.textSize / 3f,
                    subPaint,
                )
            }
            return
        }
        for (p in row.programmes) {
            val left = xOf(p.start)
            val right = xOf(p.end)
            if (right < gutterWidth || left > rightEdge()) continue

            val l = max(left, gutterWidth) + dp(2f)
            val r = min(right, rightEdge()) - dp(2f)
            if (r - l < dp(6f)) continue
            val t = top + dp(4f)
            val b = top + rowHeight - dp(4f)

            val playing = now in p.start until p.end
            val hue = Palette.HUES[(row.channel.id.hashCode() and 0x7fffffff) % Palette.HUES.size]
            blockPaint.color = if (playing) withAlpha(hue, 0xF0) else withAlpha(hue, 0x70)
            rect.set(l, t, r, b)
            canvas.drawRoundRect(rect, dp(6f), dp(6f), blockPaint)

            // Thin progress line along the bottom of the programme that is on now.
            if (playing) {
                val frac = ((now - p.start).toFloat() /
                    (p.end - p.start).coerceAtLeast(1L)).coerceIn(0f, 1f)
                progressPaint.color = Palette.ACCENTS[0]
                rect.set(l, b - dp(3f), l + (r - l) * frac, b)
                canvas.drawRoundRect(rect, dp(2f), dp(2f), progressPaint)
            }

            val pad = dp(8f)
            val textWidth = (r - l) - pad * 2
            if (textWidth > dp(24f)) {
                val title = TextUtils.ellipsize(
                    p.title, titlePaint, textWidth, TextUtils.TruncateAt.END,
                ).toString()
                canvas.drawText(title, l + pad, t + dp(18f), titlePaint)
                if (b - t > dp(34f)) {
                    val sub = "${hhmm(p.start)} – ${hhmm(p.end)}"
                    canvas.drawText(sub, l + pad, t + dp(34f), subPaint)
                }
            }
        }
    }

    private fun drawChannelCell(canvas: Canvas, ch: Channel, top: Float) {
        val pad = dp(8f)
        val tile = dp(30f)
        tilePaint.color = Palette.HUES[
            (ch.id.hashCode() and 0x7fffffff) % Palette.HUES.size
        ]
        rect.set(pad, top + (rowHeight - tile) / 2f, pad + tile, top + (rowHeight + tile) / 2f)
        canvas.drawRoundRect(rect, dp(6f), dp(6f), tilePaint)
        val initials = ch.name.split(" ", "_", "-", "|").filter { it.isNotBlank() }
            .take(2).map { it.first().uppercaseChar() }.joinToString("")
        canvas.drawText(
            initials,
            rect.centerX(),
            rect.centerY() + tileTextPaint.textSize / 3f,
            tileTextPaint,
        )

        val textLeft = pad + tile + dp(7f)
        val favorite = ch.id in favoriteIds
        val usable = gutterWidth - textLeft - dp(4f) - (if (favorite) dp(13f) else 0f)
        if (usable < dp(20f)) return
        val shortName = ch.name.replaceFirst(REGION_PREFIX, "").ifBlank { ch.name }
        val name = TextUtils.ellipsize(shortName, chanPaint, usable, TextUtils.TruncateAt.END).toString()
        canvas.drawText(name, textLeft, top + rowHeight / 2f - dp(2f), chanPaint)
        if (favorite) {
            starPaint.color = Palette.ACCENTS[0]
            canvas.drawText(
                "★",
                gutterWidth - dp(11f),
                top + rowHeight / 2f - dp(1f),
                starPaint,
            )
        }
        val group = TextUtils.ellipsize(
            ch.group.replace("_", " "), groupPaint, usable, TextUtils.TruncateAt.END,
        ).toString()
        canvas.drawText(group, textLeft, top + rowHeight / 2f + dp(13f), groupPaint)
    }

    private fun drawRuler(canvas: Canvas, now: Long) {
        canvas.drawRect(0f, 0f, rightEdge(), rulerHeight, gutterPaint)
        canvas.drawLine(0f, rulerHeight, rightEdge(), rulerHeight, linePaint)

        val stepMin = if (pxPerMinute >= dp(6f)) 15 else 30
        val stepMs = stepMin * 60_000L
        var t = (timeOf(gutterWidth) / stepMs) * stepMs
        var drawn = 0
        while (t < startTime + windowMinutes * 60_000L) {
            val x = xOf(t)
            if (x > rightEdge()) break
            if (x >= gutterWidth) {
                canvas.drawLine(x, rulerHeight - dp(6f), x, rulerHeight, linePaint)
                val label = hhmm(t)
                if (x + dp(5f) + rulerPaint.measureText(label) < rightEdge()) {
                    canvas.drawText(label, x + dp(5f), rulerHeight - dp(10f), rulerPaint)
                    drawn += 1
                }
            }
            t += stepMs
        }
        // "Now" chip on the ruler
        val nowX = xOf(now)
        if (nowX in gutterWidth..rightEdge()) {
            val label = "NOW"
            val w = rulerPaint.measureText(label) + dp(12f)
            rect.set(nowX - w / 2f, dp(3f), nowX + w / 2f, rulerHeight - dp(6f))
            blockPaint.color = Palette.ACCENTS[0]
            canvas.drawRoundRect(rect, dp(5f), dp(5f), blockPaint)
            rulerPaint.color = Color.WHITE
            canvas.drawText(label, nowX - rulerPaint.measureText(label) / 2f, rulerHeight - dp(11f), rulerPaint)
            rulerPaint.color = Palette.MUTED
        }
    }

    /** Vertical half-hour grid lines through the rows, like TiViMate's columns. */
    private fun drawTimeGrid(canvas: Canvas) {
        val stepMin = if (pxPerMinute >= dp(6f)) 15 else 30
        val stepMs = stepMin * 60_000L
        var t = (timeOf(gutterWidth) / stepMs) * stepMs
        val end = startTime + windowMinutes * 60_000L
        while (t < end) {
            val x = xOf(t)
            if (x > rightEdge()) break
            if (x > gutterWidth) {
                canvas.drawLine(x, rulerHeight, x, height.toFloat(), gridLinePaint)
            }
            t += stepMs
        }
    }

    // ------------------------------------------------------------- touch ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (isOnScrubber(event.x)) {
                scrubbing = true
                scroller.forceFinished(true)
                removeCallbacks(flingRunner)
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubTo(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) {
                scrubTo(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (scrubbing) {
                scrubbing = false
                invalidate()
                return true
            }
        }

        // GestureDetector's fling needs dense MOVE sampling, which some input paths
        // don't deliver; track velocity ourselves and fling on release if it didn't.
        velocityTracker?.addMovement(event)
        gesture.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flingStarted = false
                velocityTracker?.recycle()
                velocityTracker = android.view.VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.let { vt ->
                    vt.computeCurrentVelocity(1000)
                    val vx = vt.xVelocity
                    val vy = vt.yVelocity
                    if (!flingStarted && (kotlin.math.abs(vy) > 250f || kotlin.math.abs(vx) > 250f)) {
                        startFling(vx, vy)
                    }
                    vt.recycle()
                }
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat()
            scrollY = scroller.currY.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
            notifyVisible()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampScroll()
        notifyVisible()
        applyGestureExclusion()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyGestureExclusion()
    }

    /**
     * Keep the system back/home edge gestures off the guide: panning the timeline
     * from near a screen edge otherwise triggers "back" and closes the screen
     * mid-scroll (Android caps the exclusion to 200dp per edge, which is plenty
     * for a drag that starts on the grid).
     */
    private fun applyGestureExclusion() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && width > 0) {
            systemGestureExclusionRects =
                listOf(android.graphics.Rect(0, 0, width, height))
        }
    }

    private fun hhmm(ms: Long): String = hhmmFormat.format(ms)
    private val hhmmFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Rows currently on screen (used to load exactly what the user is looking at). */
    fun visibleRows(): IntRange {
        if (rows.isEmpty() || height == 0) return 0..0
        val first = (scrollY / rowHeight).toInt().coerceIn(0, rows.size - 1)
        val last = ((scrollY + height - rulerHeight) / rowHeight).toInt().coerceIn(first, rows.size - 1)
        return first..last
    }

    /** Scroll so [index] sits at the top of the visible rows. */
    fun scrollToRow(index: Int) {
        scrollY = (index * rowHeight).coerceIn(0f, maxScrollY().toFloat())
        invalidate()
        notifyVisible()
    }

    /** Index of the row currently at the top of the grid. */
    val topRow: Int
        get() = (scrollY / rowHeight).toInt()

    /** Jump back to the first channels (used when switching category). */
    fun resetVertical() {
        scrollY = 0f
        lastVisible = null
        invalidate()
        notifyVisible()
    }

    /** Horizontal position of "now", used by the activity for its loading hint. */
    fun channelAtRow(index: Int): Channel? = rows.getOrNull(index)?.channel

    private companion object {
        /** Leading region/country token providers put in front of channel names. */
        val REGION_PREFIX = Regex("^\\s*[A-Za-z0-9]{2,4}\\s*[|•:\\-]\\s*")
    }
}
