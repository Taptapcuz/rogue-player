package com.rogueplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.rogueplayer.R
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Transformer-style boot screen for Rogue Player: the actual letter glyphs —
 * rendered in Saira Condensed italic and filled with fire — fly in one by one
 * from across the void, spinning slowly, and lock into place to spell
 * "ROGUE PLAYER". Corner flame columns and rising smoke burn beneath the word
 * while a slanted loading bar fills with live status text.
 *
 * The two lines (ROGUE / PLAYER) use the real typeface (bundled Saira Condensed
 * ExtraBold + a skew for the italic cut); each letter is placed by its true
 * kerning advance so the assembled word sets exactly like the font.
 *
 * Time is driven by Choreographer frame callbacks with a Handler end cut. The
 * only external contract is [start], whose callback fires at handoff time.
 */
class SplashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        const val DURATION_MS = 4200L

        val VOID = Color.rgb(2, 3, 10)
        val STEEL = Color.rgb(138, 151, 173)
        val WHITE_HOT = Color.rgb(240, 248, 255)

        val LINE1 = "ROGUE"
        val LINE2 = "PLAYER"
    }

    // Boot-screen theme color (from Settings): the flame/glow/ice set is derived
    // from it so every neon element follows the chosen boot color on launch.
    private val FLAME: Int = UiSettings.bootColor(context)
    private val GLOW: Int = tint(FLAME, 0.55f)
    private val ICE: Int = tint(FLAME, 0.87f)
    private val GLEAM: Int = tint(FLAME, 0.40f)   // (reserved; not currently drawn)

    /** Blend [c] toward white by [t] (0 same, 1 white) — keeps the neon read. */
    private fun tint(c: Int, t: Float): Int = Color.argb(
        255,
        (Color.red(c) + (255 - Color.red(c)) * t).toInt().coerceIn(0, 255),
        (Color.green(c) + (255 - Color.green(c)) * t).toInt().coerceIn(0, 255),
        (Color.blue(c) + (255 - Color.blue(c)) * t).toInt().coerceIn(0, 255),
    )

    /** Linear color blend [a]→[b] by [t] (0 = a, 1 = b). */
    private fun lerp(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(a) + (Color.red(b) - Color.red(a)) * tt).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * tt).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * tt).toInt(),
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var runStartMs = 0L
    private var running = false
    private var endRunnable: Runnable? = null

    private var w = 0
    private var h = 0
    private var nowMs = 0f

    private val flames = ArrayList<Ember>()
    private val smoke = ArrayList<Ember>()
    private val letters = ArrayList<Letter>()
    private val orbs = ArrayList<Orb>()
    private val fx = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var assembleTo = 0f
    private val frameCb = Choreographer.FrameCallback { advanceNow() }

    /** Target slot for one letter (true font-kerned position). */
    private class Slot(val ch: Char, val cx: Float, val cy: Float, val size: Float)

    /** A letter mid-flight: s = start, t = target, a0 = initial spin. */
    private class Letter(
        private val slot: Slot,
        var sx: Float, var sy: Float,
        val delay: Float, val dur: Float, val a0: Float,
    ) {
        var x = sx; var y = sy; var px = sx; var py = sy
        var rot = a0
        val ch: Char get() = slot.ch
        val tx: Float get() = slot.cx
        val ty: Float get() = slot.cy
        val size: Float get() = slot.size
    }

    /** One fire/smoke particle. Colors lerp hot → deep over its life. */
    private class Ember(
        var x: Float, var y: Float,
        var vy: Float,
        var life: Float, var max: Float,
        var size: Float,
        var r0: Int, var g0: Int, var b0: Int, var a0: Float,
        var id: Int, var side: Int, var ph: Float,
    )

    /** A fast fiery orb (half-count of the old swarm) streaking to the word. */
    private class Orb(
        var sx: Float, var sy: Float,
        val tx: Float, val ty: Float,
        val delay: Float, val dur: Float,
    ) {
        var x = sx; var y = sy; var px = sx; var py = sy
    }

    /** Bounding box (view coords) of the assembled word — orbs gather inside it. */
    private val blockRect = RectF()

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        super.onSizeChanged(nw, nh, ow, oh)
        if (nw == 0 || nh == 0) return
        w = nw; h = nh

        // Size: fit the longer line (PLAYER) to ~82% width so both lines are big.
        // The measuring paint must carry the same italic skew that renders it, else
        // the skewed glyphs overhang the fitted width and clip at the right edge.
        val type = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 1f; textSkewX = -0.18f; typeface = typeface()
        }
        val fit = nw * 0.82f
        val size = fit / type.measureText(LINE2)
        type.textSize = size

        val radius = sqrt((w / 2f) * (w / 2f) + (h / 2f) * (h / 2f)).toFloat()
        val vc = nh * 0.46f
        letters.clear()

        fun addLine(line: String, lineY: Float) {
            val totalW = type.measureText(line)
            val startX = (nw - totalW) / 2f
            var i = 0
            while (i < line.length) {
                val x0 = type.measureText(line.substring(0, i))
                val cw = type.measureText(line[i].toString())
                val cx = startX + x0 + cw / 2f
                val cy = lineY
                val a = (i / 12f) * Math.PI * 2.0 + i * 1.7
                val r = radius * (0.95f + (i % 5) * 0.05f)
                letters.add(Letter(
                    Slot(line[i], cx, cy, size),
                    w / 2f + (cos(a).toFloat() * r),
                    h / 2f + (sin(a).toFloat() * r) * 0.8f,
                    delay = 150f + i * 150f,
                    dur = 950f,
                    a0 = (if (i % 2 == 0) -1 else 1) * (70f + (i % 3) * 22f),
                ))
                i++
            }
        }
        addLine(LINE1, vc - size * 0.62f)
        addLine(LINE2, vc + size * 0.78f)

        // Bounding box of the assembled word — orbs gather inside it.
        var bx0 = Float.MAX_VALUE; var bx1 = -Float.MAX_VALUE
        var by0 = Float.MAX_VALUE; var by1 = -Float.MAX_VALUE
        for (l in letters) {
            if (l.tx < bx0) bx0 = l.tx; if (l.tx > bx1) bx1 = l.tx
            if (l.ty < by0) by0 = l.ty; if (l.ty > by1) by1 = l.ty
        }
        blockRect.set(bx0 - size * 0.5f, by0 - size * 0.8f, bx1 + size * 0.5f, by1 + size * 0.8f)

        // Half-count fast orbs covering the whole screen (original 350+i*0.6ms stagger).
        orbs.clear()
        val count = 450
        for (i in 0 until count) {
            val tx = Math.random().toFloat() * nw
            val ty = Math.random().toFloat() * nh
            val a = (i / count.toFloat()) * Math.PI * 2.0 + (i % 3) * 2.4
            val r = sqrt((w / 2f) * (w / 2f) + (h / 2f) * (h / 2f)).toFloat() * (0.9f + (i % 5) * 0.06f)
            orbs.add(Orb(
                w / 2f + (cos(a).toFloat() * r),
                h / 2f + (sin(a).toFloat() * r),
                tx, ty,
                delay = 350f + (i % count).toFloat() * 0.6f,
                dur = 780f + (i % 7) * 42f,
            ))
        }

        assembleTo = 150f + letters.size * 150f + 950f
    }

    private fun typeface(): Typeface =
        ResourcesCompat.getFont(context, R.font.sairacond_extrabold)!!

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSkewX = -0.18f
    }

    // ---------------------------------------------------------------- loop ---

    private fun advanceNow() {
        if (!running) return
        nowMs = (SystemClock.uptimeMillis() - runStartMs).toFloat()
        stepLetters()
        stepOrbs()
        stepAmbient()
        invalidate()
        if (nowMs < DURATION_MS) Choreographer.getInstance().postFrameCallback(frameCb)
    }

    /** Move the half-count fast orbs toward their slots in the word. */
    private fun stepOrbs() {
        for (o in orbs) {
            val end = o.delay + o.dur
            val k = when {
                nowMs < o.delay -> 0f
                nowMs >= end -> 1f
                else -> (nowMs - o.delay) / o.dur
            }
            if (k == 0f) { o.px = o.sx; o.py = o.sy; o.x = o.sx; o.y = o.sy; continue }
            if (k >= 1f) { o.px = o.x; o.py = o.y; o.x = o.tx; o.y = o.ty; continue }

            val e = if (k < .5f) 2f * k * k else 1f - (-2f * k + 2f) * (-2f * k + 2f) / 2f
            o.px = o.x; o.py = o.y
            o.x = o.sx + (o.tx - o.sx) * e
            o.y = o.sy + (o.ty - o.sy) * e

            // light swirl as they streak in
            if (k < 0.85f) {
                val amp = 50f * (1f - k)
                val ox = o.ty - o.sy; val oy = o.sx - o.tx
                val len = sqrt(ox * ox + oy * oy)
                if (len > 0f) {
                    o.x += ox / len * sin(k * 3.5f) * amp
                    o.y += oy / len * sin(k * 3.5f) * amp
                }
            }
        }
    }

    private fun kOf(l: Letter): Float {
        val end = l.delay + l.dur
        return when {
            nowMs < l.delay -> 0f
            nowMs >= end -> 1f
            else -> (nowMs - l.delay) / l.dur
        }
    }

    private fun stepLetters() {
        for (l in letters) {
            val k = kOf(l)
            if (k == 0f) { l.px = l.sx; l.py = l.sy; l.x = l.sx; l.y = l.sy; l.rot = l.a0; continue }
            if (k >= 1f) { l.px = l.x; l.py = l.y; l.x = l.tx; l.y = l.ty; l.rot = 0f; continue }

            val e = if (k < .5f) 2f * k * k else 1f - (-2f * k + 2f) * (-2f * k + 2f) / 2f
            l.px = l.x; l.py = l.y
            l.x = l.sx + (l.tx - l.sx) * e
            l.y = l.sy + (l.ty - l.sy) * e

            // swirl orthogonal to the flight path, settling out near the end
            if (k < 0.82f) {
                val amp = 40f * (1f - k)
                val ox = l.ty - l.sy; val oy = l.sx - l.tx
                val len = sqrt(ox * ox + oy * oy)
                if (len > 0f) {
                    l.x += ox / len * sin(k * 3.2f) * amp
                    l.y += oy / len * sin(k * 3.2f) * amp
                }
            }
            // slow rotation, eases down to aligned (0°) by the time it locks
            l.rot = l.a0 * (1f - e)
        }
    }

    // ------------------------------------------------------------- ambient ---

    private fun stepAmbient() {
        val t = (nowMs / 1100f).coerceIn(0f, 1f)
        val n = (0.5f + 1.2f * t).toInt()
        for (i in 0 until n) {
            spawnFlame(t)
            if (Math.random() < 0.3 * t) spawnSmoke()
        }

        var i = flames.size - 1
        while (i >= 0) {
            val p = flames[i]
            p.life++
            p.y += p.vy; p.vy *= 1.03f
            p.x += sin(p.life * 0.09f + p.ph) * 0.6f - p.side * 0.01f * (1f - p.life / p.max)
            if (p.life >= p.max) flames.removeAt(i)
            i--
        }
        i = smoke.size - 1
        while (i >= 0) {
            val p = smoke[i]
            p.life++
            p.y -= 0.35f
            p.x += sin(p.life * 0.02f + p.ph) * 0.5f - p.side * 0.08f
            if (p.life >= p.max) smoke.removeAt(i)
            i--
        }
        while (flames.size > 150) flames.removeAt(0)
        while (smoke.size > 60) smoke.removeAt(0)
    }

    private fun spawnFlame(ramp: Float) {
        val side = if (Math.random() < 0.5) -1 else 1
        val base = if (side < 0) w * 0.14f else w * 0.86f
        flames.add(Ember(
            base + (Math.random() - 0.5).toFloat() * w * 0.13f,
            h * 0.94f,
            -(0.7f + Math.random().toFloat() * 1.6f),
            0f, 60f + Math.random().toFloat() * 70f,
            (22f + Math.random().toFloat() * 34f) * (h / 600f + 0.5f),
            Color.red(FLAME), Color.green(FLAME), Color.blue(FLAME), 0.5f * ramp,
            0, side, (Math.random() * 6.28).toFloat(),
        ))
    }

    private fun spawnSmoke() {
        val side = if (Math.random() < 0.5) -1 else 1
        val base = if (side < 0) w * 0.14f else w * 0.86f
        smoke.add(Ember(
            base + (Math.random() - 0.5).toFloat() * w * 0.3f,
            h * 0.8f,
            0f, 0f, 200f + Math.random().toFloat() * 130f,
            50f + Math.random().toFloat() * 90f,
            Color.red(FLAME) / 2, Color.green(FLAME) / 2, Color.blue(FLAME) / 2, 0.13f,
            1, side, (Math.random() * 6.28).toFloat(),
        ))
    }

    private fun ambientPreload() {
        if (w == 0 || h == 0) return
        for (i in 0 until 44) spawnFlame(1f)
        for (i in 0 until 16) spawnSmoke()
        var n = 0
        var idx = 0
        while (idx < flames.size && n < 30) {
            flames[idx].life = 24f + idx * 0.4f; idx++; n++
        }
    }

    // --------------------------------------------------------------- draw ---

    private val floorRect = RectF()
    private var fireText = LinearGradient(0f, 0f, 0f, 1f,
        intArrayOf(FLAME, FLAME, tint(FLAME, 0.18f)),
        floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)

    override fun onDraw(canvas: Canvas) {
        if (w == 0 || h == 0) return
        val t = (nowMs / DURATION_MS).coerceIn(0f, 1f)
        val first = letters.firstOrNull()
        val blockHalf = (first?.size?.times(1.1f) ?: 0f)
        val blockTop = h * 0.46f - blockHalf
        fireText = LinearGradient(0f, blockTop, 0f, blockTop + (h * 0.5f),
            intArrayOf(FLAME, FLAME, tint(FLAME, 0.18f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)

        // void + lower glow
        canvas.drawRect(0f, h * 0.5f, w.toFloat(), h.toFloat(), fx.apply {
            shader = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.argb(70, Color.red(FLAME) / 4, Color.green(FLAME) / 4, Color.blue(FLAME) / 4)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        })
        fx.shader = null

        floorRect.set(0f, h * 0.93f, w.toFloat(), h.toFloat())
        canvas.drawRect(floorRect, fx.apply {
            shader = LinearGradient(0f, h * 0.93f, 0f, h.toFloat(),
                intArrayOf(Color.argb(95, Color.red(FLAME), Color.green(FLAME), Color.blue(FLAME)), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        })
        fx.shader = null
        canvas.drawRect(0f, h * 0.93f, w.toFloat(), h * 0.93f + 1f,
            fx.apply { color = Color.argb(60, Color.red(GLOW), Color.green(GLOW), Color.blue(GLOW)) })

        drawAmbient(canvas)
        drawOrbs(canvas)
        drawLetters(canvas)
        drawLoader(canvas, t)
    }

    /** Off-screen glow + flying trays for the ambient flames and smoke. */
    private fun drawAmbient(canvas: Canvas) {
        for (p in smoke) {
            val k = (p.life / p.max).coerceIn(0f, 1f)
            val a = sin(k * Math.PI.toFloat()).toFloat() * p.a0
            if (a <= 0f) continue
            val r = p.size * (1f + k)
            paintGlow(canvas, p.x, p.y, r, Color.argb((a * 255).toInt(), p.r0, p.g0, p.b0), 2)
        }
        for (p in flames) {
            val k = (p.life / p.max).coerceIn(0f, 1f)
            if (k >= 1f) continue
            val al = (1f - k) * p.a0
            if (al <= 0f) continue
            val r = p.size * (1f - k * 0.7f)
            if (r <= 0f) continue
            val core = Color.argb((al * 255).toInt(), p.r0, p.g0, p.b0)
            val mid = Color.argb((al * 160).toInt(), p.r0 * 30 / 100, p.g0 * 130 / 190, p.b0)
            val outer = Color.argb((al * 85).toInt(), Color.red(FLAME) / 3, Color.green(FLAME) / 3, Color.blue(FLAME) / 3)
            canvas.drawLine(p.x, p.y + r, p.x, p.y - r * 1.6f,
                fx.apply {
                    shader = null; style = Paint.Style.STROKE; strokeWidth = r * 0.9f
                    strokeCap = Paint.Cap.ROUND; color = outer
                })
            fx.style = Paint.Style.FILL
            canvas.drawCircle(p.x, p.y - r * 0.3f, r, fx.apply { color = outer })
            canvas.drawCircle(p.x, p.y - r * 0.3f, r * 0.55f, fx.apply { color = mid })
            canvas.drawCircle(p.x, p.y - r * 0.3f, r * 0.26f, fx.apply { color = core })
        }
    }

    /** The half-count fast fire orbs streaking into the word (behind the letters).
     *  Each orb dissolves as it arrives, so the field never piles up. */
    private fun drawOrbs(canvas: Canvas) {
        // backstop global fade once the name locks in
        val fade = 1f - ((nowMs - assembleTo) / 360f).coerceIn(0f, 1f)
        if (fade <= 0f) return

        val orbR = 2.4f * resources.displayMetrics.density
        fx.style = Paint.Style.FILL
        for (o in orbs) {
            val end = o.delay + o.dur
            val k = when {
                nowMs < o.delay -> 0f
                nowMs >= end -> 1f
                else -> (nowMs - o.delay) / o.dur
            }
            if (k <= 0f) continue

            // dissolve on arrival: alpha ramps to 0 over the last ~18% of the flight
            val arrive = ((k - 0.82f) / 0.18f).coerceIn(0f, 1f)
            val a = fade * (1f - arrive)

            // trail streak
            if (a > 0f && (o.px != o.x || o.py != o.y)) {
                val trail = LinearGradient(o.px, o.py, o.x, o.y,
                    Color.argb((120 * a).toInt(), Color.red(ICE), Color.green(ICE), Color.blue(ICE)),
                    Color.argb(0, Color.red(FLAME), Color.green(FLAME), Color.blue(FLAME)), Shader.TileMode.CLAMP)
                canvas.drawLine(o.px, o.py, o.x, o.y, fx.apply {
                    shader = trail; style = Paint.Style.STROKE; strokeWidth = 2f
                    strokeCap = Paint.Cap.ROUND
                })
                fx.shader = null
            }
            // hot core + tight flame halo (kept small so letters stay readable)
            val core = if (k < 1f) WHITE_HOT else ICE
            val halo = tint(FLAME, 0.4f)
            fx.style = Paint.Style.FILL
            canvas.drawCircle(o.x, o.y, orbR * 1.8f, fx.apply { color = argb((46 * a).toInt(), halo) })
            canvas.drawCircle(o.x, o.y, orbR * 1.15f, fx.apply { color = argb((110 * a).toInt(), halo) })
            canvas.drawCircle(o.x, o.y, orbR * 0.55f, fx.apply { color = argb((255 * a).toInt(), core) })
        }
    }

    private fun drawLetters(canvas: Canvas) {
        val sw = shineX()
        val wave = neonWave()          // looping blue pulse rippling through the name
        val neon = FLAME   // bold, full-saturation wave, not a white-diluted tint
        val front = colorFront()       // traveling TRUE-color strike band
        for (idx in letters.indices) {
            val l = letters[idx]
            val k = kOf(l)
            if (k <= 0f) continue

            // How lit this letter is by the passing color front: 0 far ahead (muted),
            // ramps to 1 as the true color hits it and stays 1 behind it.
            val band = l.size * 1.9f
            val hit = if (front == Float.MAX_VALUE) 1f
                      else (1f - (front - l.x) / band).coerceIn(0f, 1f)

            // Bold neon pulsation: the glow flickers in strength and reach, offset
            // per letter so the name ripples. Another wave of bright blue surges
            // through the word on a loop once it's assembled.
            val ph = idx * 0.85f
            val pulse = 0.5f + 0.5f * sin(nowMs * 0.012f + ph)
            val onWave = k >= 1f && wave > 0f &&
                kotlin.math.abs(l.x - wave) <= l.size * 0.95f
            val lit = sw > 0f && l.x >= sw - 70f && l.x <= sw + 70f

            // glow reaches wider + hotter when the wave is passing or it's pulsing
            val boost = if (onWave) 1.35f else 1f
            val glowR = l.size * (0.02f + 0.04f * pulse) * boost
            val glowA = (44f * (0.35f + 0.75f * pulse) * boost * (1f - k * 0.5f)).toInt()
            val glowCol = if (onWave) neon else tint(FLAME, 0.25f)

            canvas.save()
            canvas.translate(l.x, l.y)
            canvas.rotate(l.rot)

            drawGlyph(canvas, l, glowR, argb(glowA.coerceAtLeast(0), glowCol))
            if (onWave) {
                // neon fill flushed over the letter as the surge passes
                canvas.drawText(l.ch.toString(), 0f, 0f,
                    Paint(basePaint).apply {
                        textSize = l.size
                        color = if (lit) tint(FLAME, 0.30f) else tint(FLAME, 0.55f)
                        shader = null; typeface = typeface()
                    })
            } else {
                // TRUE-color strike: letters ahead of the front sit muted (STEEL), they
                // snap to the full boot color as the wave hits them, and stay lit behind it.
                val fill = lerp(STEEL, FLAME, hit)
                canvas.drawText(l.ch.toString(), 0f, 0f,
                    Paint(basePaint).apply {
                        textSize = l.size; color = fill; shader = null; typeface = typeface()
                    })
                // bright white glint at the hit edge — kept thin so the true color stays visible
                val st = kotlin.math.sin((1f - hit) * Math.PI.toFloat()).toFloat()
                val strikeA = (st * 150f * hit).toInt().coerceAtLeast(0)
                if (strikeA > 20) canvas.drawText(l.ch.toString(), 0f, 0f,
                    Paint(basePaint).apply {
                        textSize = l.size; color = argb(strikeA, Color.WHITE)
                        shader = null; typeface = typeface()
                    })
            }
            // hot pulsing core pass for that electric read
            if (!lit) {
                canvas.drawText(l.ch.toString(), l.size * 0.02f, l.size * 0.02f,
                    Paint(basePaint).apply {
                        textSize = l.size * (0.90f + 0.08f * pulse)
                        color = if (onWave) tint(FLAME, 0.55f) else tint(FLAME, 0.35f)
                        typeface = typeface()
                    })
            }
            // per-letter color sparkle: a bright boot-color sheen pops each glyph in turn
            val tw = 0.5f + 0.5f * kotlin.math.sin(nowMs * 0.010f + idx * 1.31f).toFloat()
            val sheen = (tw * 130f * hit).toInt().coerceAtLeast(0)
            canvas.drawText(l.ch.toString(), 0f, 0f,
                Paint(basePaint).apply {
                    textSize = l.size
                    color = argb(sheen, FLAME)   // true, full boot color (not a washed tint)
                    shader = null; typeface = typeface()
                })
            // thin white gloss over struck letters — just a top specular rim, so the
            // true color body stays clearly visible beneath it
            if (hit > 0.3f) {
                val gloss = LinearGradient(0f, -l.size * 0.9f, 0f, -l.size * 0.25f,
                    intArrayOf(argb(180, Color.WHITE), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                canvas.drawText(l.ch.toString(), 0f, 0f,
                    Paint(basePaint).apply {
                        textSize = l.size; shader = gloss; typeface = typeface()
                    })
            }
            canvas.restore()
        }
    }

    /** Traveling band of TRUE color that sweeps across the assembled word and
     *  "hits" each letter left→right on a loop (MAX = not assembled yet). */
    private fun colorFront(): Float {
        if (nowMs < assembleTo) return Float.MAX_VALUE
        val p = ((nowMs - assembleTo) % 3400f) / 3400f
        return blockRect.left + (p - 0.12f) * blockRect.width() * 1.35f
    }

    /** A looping neon-blue surge that sweeps across the assembled name. */
    private fun neonWave(): Float {
        if (nowMs < assembleTo) return -1f
        val p = ((nowMs - assembleTo) % 2600f) / 2600f
        return blockRect.left + p * blockRect.width()
    }

    /** Draw the letter silhouetted in a soft glow (all four diagonals). */
    private fun drawGlyph(canvas: Canvas, l: Letter, off: Float, c: Int) {
        val p = Paint(basePaint).apply { textSize = l.size; color = c; typeface = typeface() }
        val o = off.coerceAtLeast(1.5f)
        canvas.drawText(l.ch.toString(), o, o, p)
        canvas.drawText(l.ch.toString(), -o, o, p)
        canvas.drawText(l.ch.toString(), o, -o, p)
        canvas.drawText(l.ch.toString(), -o, -o, p)
        canvas.drawText(l.ch.toString(), 0f, o, p)
        canvas.drawText(l.ch.toString(), 0f, -o, p)
    }

    private fun shineX(): Float {
        val from = assembleTo; val to = assembleTo + 900f
        if (nowMs < from || nowMs > to) return -1f
        val p = (nowMs - from) / (to - from)
        return w * (0.16f + 0.68f * p)
    }

    private fun drawLoader(canvas: Canvas, t: Float) {
        if (t < 0.16f) return
        val al = ((t - 0.16f) / 0.30f).coerceIn(0f, 1f)
        val prog = ((t - 0.16f) / 0.84f).coerceIn(0f, 1f)

        val bw = w * 0.6f
        val bh = 12f * resources.displayMetrics.density
        val bx = (w - bw) / 2f
        val by = h * 0.81f
        val slant = 10f * resources.displayMetrics.density

        val track = Path().apply {
            moveTo(bx + slant, by); lineTo(bx + bw, by); lineTo(bx + bw - slant, by + bh)
            lineTo(bx, by + bh); close()
        }
        canvas.save()
        canvas.clipPath(track)
        canvas.drawColor(Color.argb((70 * al).toInt(), 2, 3, 10))
        val fillW = bw * prog
        if (fillW > 0) {
            canvas.drawRect(bx, by, bx + fillW, by + bh, fx.apply {
                shader = LinearGradient(bx, 0f, bx + bw, 0f,
                    intArrayOf(FLAME, GLOW, ICE), floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP)
            })
            fx.shader = null
        }
        canvas.restore()
        canvas.drawPath(track, fx.apply {
            shader = null; style = Paint.Style.STROKE; strokeWidth = 1.6f
            color = Color.argb((120 * al).toInt(), 138, 151, 173)
        })

        val status = statusFor(prog)
        canvas.drawText(status, w / 2f, by + bh + 34f * resources.displayMetrics.density,
            fx.apply {
                shader = null; style = Paint.Style.FILL; textAlign = Paint.Align.CENTER
                color = if (prog >= 1f) ICE else STEEL
                typeface = Typeface.create(ResourcesCompat.getFont(context, R.font.saira_medium),
                    Typeface.NORMAL)
                textSize = 14f * resources.displayMetrics.density
                letterSpacing = 0.08f
            })
    }

    private fun statusFor(prog: Float): String = when {
        prog >= 1f -> "Welcome"
        nowMs >= assembleTo + 500f -> "Almost there…"
        nowMs >= assembleTo -> "Fetching channels…"
        nowMs >= 1300f -> "Loading playlists…"
        nowMs >= 450f -> "Assembling Rogue…"
        else -> "Starting up…"
    }

    private fun paintGlow(canvas: Canvas, cx: Float, cy: Float, r: Float, c: Int, layers: Int) {
        val alpha = Color.alpha(c)
        for (i in 0 until layers) {
            canvas.drawCircle(cx, cy, r * (layers - i) / layers,
                fx.apply { shader = null; color = Color.argb(
                    (alpha / (i + 1)).coerceAtLeast(1), Color.red(c), Color.green(c), Color.blue(c)) })
        }
    }

    private fun argb(a: Int, c: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    init {
        setBackgroundColor(VOID)
    }

    /** Raise the first frame and schedule the handoff callback. */
    fun start(onDone: () -> Unit) {
        if (running) return
        running = true
        ambientPreload()
        runStartMs = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(frameCb)
        endRunnable = Runnable {
            if (running) { running = false; onDone() }
        }
        mainHandler.postAtTime(endRunnable!!, runStartMs + DURATION_MS)
    }

    override fun onDetachedFromWindow() {
        running = false
        endRunnable?.let { mainHandler.removeCallbacks(it) }
        Choreographer.getInstance().removeFrameCallback(frameCb)
        super.onDetachedFromWindow()
    }
}
