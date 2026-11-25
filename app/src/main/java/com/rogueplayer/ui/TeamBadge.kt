package com.rogueplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/**
 * Sport-appropriate team badge, tinted from our own palette with the team abbreviation on it.
 *
 * A helmet only makes sense for football — basketball gets a ball with seams, baseball a ball
 * with stitching, hockey a puck and stick. All of them are shapes we draw ourselves: no club
 * logo, no official colours. Authored in a 32x32 box and scaled to the view.
 */
class TeamBadge(
    context: Context,
    private val abbr: String,
    private val hue: Int,
    private val league: String,
) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = hue
    }

    /** True when this badge draws the team abbreviation itself. */
    val showLabel: Boolean get() = !(isHockey || isBaseball)

    /** Darker version of the shell, for the puck's edge. */
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        val r = (((hue shr 16) and 0xFF) * 0.5).toInt()
        val g = (((hue shr 8) and 0xFF) * 0.5).toInt()
        val b = ((hue and 0xFF) * 0.5).toInt()
        color = android.graphics.Color.rgb(r, g, b)
    }

    /** Ink for anything drawn ON the shape: light hues need dark ink, dark hues light ink. */
    private val ink = run {
        val r = (hue shr 16) and 0xFF
        val g = (hue shr 8) and 0xFF
        val b = hue and 0xFF
        val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        if (lum > 0.60) 0xFF0B0B10.toInt() else 0xFFFFFFFF.toInt()
    }

    private val stickPaintBack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ink
        alpha = 140
    }
    private val stickPaintFront = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ink
        alpha = 225
    }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ink
        alpha = 200
    }
    private val abbrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val shell = Path()
    private val oval = RectF()

    init {
        shell.moveTo(5f, 17f)
        shell.cubicTo(5f, 10f, 10f, 5f, 17f, 5f)
        shell.cubicTo(24f, 5f, 28f, 9f, 28f, 15f)
        shell.cubicTo(28f, 18f, 26f, 20f, 23f, 21f)
        shell.lineTo(14f, 21.5f)
        shell.cubicTo(10f, 21.5f, 8f, 20.5f, 5f, 17f)
        shell.close()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = minOf(w, h) / 32f
        canvas.save()
        canvas.translate((w - 32f * s) / 2f, (h - 32f * s) / 2f)
        canvas.scale(s, s)

        val ball = isBall
        if (isFootball) drawHelmet(canvas) else drawBall(canvas, hockey = isHockey)

        val label = abbr.take(3)
        if (showLabel && label.isNotBlank()) {
            abbrPaint.textSize = when {
                isHockey && label.length > 2 -> 7.2f
                isHockey -> 8.2f
                label.length > 2 -> 8.4f
                else -> 9.6f
            }
            val cy = when {
                isFootball -> 13.5f
                isHockey -> 18.4f
                else -> 16f
            }
            val y = cy - (abbrPaint.descent() + abbrPaint.ascent()) / 2f
            canvas.drawText(
                TextUtils.ellipsize(label, abbrPaint, 16f, TextUtils.TruncateAt.END).toString(),
                16f, y, abbrPaint,
            )
        }
        canvas.restore()
    }

    private val isFootball: Boolean
        get() = league.uppercase().let { it == "NFL" || it == "NCAAF" || it.contains("FOOTBALL") }

    private val isHockey: Boolean
        get() = league.uppercase().let { it == "NHL" || it.contains("HOCKEY") }

    private val isBall: Boolean
        get() = !isFootball

    private fun drawHelmet(canvas: Canvas) {
        canvas.drawPath(shell, fill)
        line.strokeWidth = 2.2f
        canvas.drawLine(24f, 15f, 29f, 15f, line)
        canvas.drawLine(23.5f, 18.5f, 29.5f, 18.5f, line)
        canvas.drawLine(21.5f, 21.5f, 26f, 21.5f, line)
    }

    private fun drawBall(canvas: Canvas, hockey: Boolean) {
        // Bigger, better-read glyphs: an outlined ball with real seams, a stitched baseball,
        // and a puck seen edge-on with the stick behind it. Details stay at 3+ units so they
        // survive a 34dp tile.
        line.strokeWidth = 1.5f
        line.alpha = 235

        when {
            hockey -> {
                // Crossed sticks, hockey-issue shape: shaft into an angled blade, then a
                // hooked toe — the hook is what stops it reading as a plain X at 34dp.
                oval.set(5f, 5f, 27f, 27f)
                canvas.drawOval(oval, fill)
                line.strokeWidth = 1.2f
                canvas.drawOval(oval, line)

                fun stick(x0: Float, y0: Float, x1: Float, y1: Float, bx: Float, by: Float,
                          hx: Float, hy: Float, paint: Paint) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeWidth = 2.2f
                    canvas.drawLine(x0, y0, x1, y1, paint)
                    paint.strokeWidth = 3.6f
                    canvas.drawLine(x1, y1, bx, by, paint)
                    paint.strokeWidth = 3.2f
                    canvas.drawLine(bx, by, hx, hy, paint)
                }
                stick(22f, 4.5f, 14f, 17.5f, 6f, 19f, 5f, 23f, stickPaintBack)
                stick(10f, 4.5f, 18f, 17.5f, 26f, 19f, 27f, 23f, stickPaintFront)
            }
            isBaseball -> {
                // Just the bat: no disc, no ring — the glyph itself, in the team's hue.
                val batPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    color = hue
                }
                batPaint.strokeWidth = 2.6f
                canvas.drawLine(8f, 23.5f, 15f, 16.5f, batPaint)
                batPaint.strokeWidth = 4.8f
                canvas.drawLine(15f, 16.5f, 24.5f, 7f, batPaint)
                fill.alpha = 255
                oval.set(5.2f, 22.2f, 9.6f, 26.6f)
                canvas.drawOval(oval, fill)
                oval.set(22.4f, 4.0f, 27.0f, 8.6f)
                canvas.drawOval(oval, fill)
            }
            else -> {
                oval.set(5f, 5f, 27f, 27f)
                canvas.drawOval(oval, fill)
                canvas.drawOval(oval, line)
                // equator + the two side meridians give the classic basketball read
                canvas.drawLine(5.8f, 16f, 26.2f, 16f, line)
                val l = Path().apply {
                    moveTo(9f, 7.6f)
                    cubicTo(14.5f, 12f, 14.5f, 20f, 9f, 24.4f)
                }
                val r = Path().apply {
                    moveTo(23f, 7.6f)
                    cubicTo(17.5f, 12f, 17.5f, 20f, 23f, 24.4f)
                }
                canvas.drawPath(l, line)
                canvas.drawPath(r, line)
            }
        }
    }

    private val isBaseball: Boolean
        get() = league.uppercase().let { it == "MLB" || it.contains("BASEBALL") }
}
