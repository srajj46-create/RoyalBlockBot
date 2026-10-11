package com.bot.royalblock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot

class BotService : AccessibilityService() {

    // ---- Tunables (fractions of screen) ----
    private val bx0 = 0.15f; private val bx1 = 0.87f   // board interior x
    private val by0 = 0.31f; private val by1 = 0.67f   // board interior y
    private val SW = 270                                // analysis width

    @Volatile private var running = false
    @Volatile private var alive = true
    private var ow = 1; private var oh = 1
    private var btn: TextView? = null

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        addButton()
        Thread { loop() }.start()
    }

    override fun onDestroy() { alive = false; super.onDestroy() }

    private fun addButton() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val t = TextView(this).apply {
            text = "▶"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA555555"))
        }
        t.setOnClickListener {
            running = !running
            t.text = if (running) "■" else "▶"
            t.setBackgroundColor(Color.parseColor(if (running) "#AAFF8800" else "#AA555555"))
        }
        val p = WindowManager.LayoutParams(150, 150, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.BOTTOM or Gravity.START; p.x = 20; p.y = 120
        wm.addView(t, p); btn = t
    }

    // ---------- screen / gesture helpers ----------
    private fun shot(): Bitmap? {
        val latch = CountDownLatch(1); var out: Bitmap? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                try {
                    val b = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    out = b?.copy(Bitmap.Config.ARGB_8888, false)
                    r.hardwareBuffer.close()
                } catch (_: Throwable) {}
                latch.countDown()
            }
            override fun onFailure(code: Int) { latch.countDown() }
        })
        latch.await(3, TimeUnit.SECONDS)
        val o = out ?: return null
        ow = o.width; oh = o.height
        return Bitmap.createScaledBitmap(o, SW, (SW.toFloat() * o.height / o.width).toInt(), true)
    }

    private fun tap(fx: Float, fy: Float) {
        val p = Path().apply { moveTo(fx * ow, fy * oh) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        val l = CountDownLatch(1)
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { l.countDown() }
            override fun onCancelled(d: GestureDescription?) { l.countDown() }
        }, null)
        l.await(2, TimeUnit.SECONDS)
    }

    private class Img(val b: Bitmap) {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        private val hsv = FloatArray(3)
        fun at(fx: Float, fy: Float): Int =
            px[(fy * h).toInt().coerceIn(0, h - 1) * w + (fx * w).toInt().coerceIn(0, w - 1)]
        fun hsvOf(c: Int): FloatArray { Color.colorToHSV(c, hsv); return hsv }
        fun lum(c: Int) = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
    }

    // ---------- state detection ----------
    private enum class St { BLACK, DIM, GAME, OTHER }

    private fun state(im: Img): St {
        var dark = 0
        for (c in im.px) if (maxOf(Color.red(c), Color.green(c), Color.blue(c)) < 40) dark++
        if (dark.toFloat() / im.px.size > 0.35f) return St.BLACK
        val m = im.at(0.02f, 0.40f)
        val hv = im.hsvOf(m)
        if (im.lum(m) < 120) return St.DIM
        if (hv[0] in 195f..225f && hv[1] in 0.2f..0.65f && hv[2] > 0.7f) return St.GAME
        return St.OTHER
    }

    /** centroid of pixels matching colour rule inside y-range, or null */
    private fun find(im: Img, y0: Float, y1: Float, rule: (FloatArray) -> Boolean): Pair<Float, Float>? {
        var sx = 0L; var sy = 0L; var n = 0
        for (y in (y0 * im.h).toInt() until (y1 * im.h).toInt()) for (x in 0 until im.w) {
            if (rule(im.hsvOf(im.px[y * im.w + x]))) { sx += x; sy += y; n++ }
        }
        if (n < im.w * im.h * 0.012) return null
        return Pair(sx.toFloat() / n / im.w, sy.toFloat() / n / im.h)
    }
    private val blue = { v: FloatArray -> v[0] in 215f..235f && v[1] > 0.7f && v[2] > 0.75f }
    private val green = { v: FloatArray -> v[0] in 95f..130f && v[1] > 0.7f && v[2] > 0.65f }

    // ---------- puzzle ----------
    private fun isEmptyCell(im: Img, c: Int): Boolean {
        val v = im.hsvOf(c)
        return v[0] in 190f..220f && v[1] in 0.22f..0.52f && v[2] in 0.6f..0.9f
    }

    private fun candidates(im: Img): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        val cols = 14; val rows = 12
        for (r in 0 until rows) for (c in 0 until cols) {
            val fx = bx0 + (bx1 - bx0) * (c + 0.5f) / cols
            val fy = by0 + (by1 - by0) * (r + 0.5f) / rows
            val col = im.at(fx, fy)
            if (!isEmptyCell(im, col) && im.hsvOf(col)[1] > 0.0f) out.add(Pair(fx, fy))
        }
        return out
    }

    private fun boardDiff(a: Img, b: Img): Float {
        var ch = 0; var n = 0
        var y = (by0 * a.h).toInt()
        while (y < (by1 * a.h).toInt()) {
            var x = (bx0 * a.w).toInt()
            while (x < (bx1 * a.w).toInt()) {
                val p = a.px[y * a.w + x]; val q = b.px[y * b.w + x]
                if (abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q)) > 60) ch++
                n++; x += 2
            }
            y += 2
        }
        return ch.toFloat() / n
    }

    private val failed = ArrayList<Pair<Float, Float>>()
    private var lastProgress = System.currentTimeMillis()
    private var otherSince = 0L

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    private fun loop() {
        while (alive) {
            if (!running) { sleep(400); continue }
            val im = shot()?.let { Img(it) } ?: run { sleep(500); null } ?: continue
            when (state(im)) {
                St.BLACK -> {
                    // result screen: Continue (blue) -> never the green "AD x2" button
                    val t = find(im, 0.72f, 0.92f, blue) ?: find(im, 0.55f, 0.92f, green)
                    if (t != null) { tap(t.first, t.second); lastProgress = now(); failed.clear() }
                    sleep(1200)
                }
                St.DIM -> {
                    // popup (Freeze / reward): Claim (green) first
                    val t = find(im, 0.45f, 0.92f, green) ?: find(im, 0.60f, 0.92f, blue)
                    if (t != null) { tap(t.first, t.second); failed.clear() } else tap(0.5f, 0.85f)
                    sleep(1200)
                }
                St.GAME -> { otherSince = 0; playStep(im) }
                St.OTHER -> {
                    if (otherSince == 0L) otherSince = now()
                    // unknown screen (ad / splash): after 8s try the close X corners
                    if (now() - otherSince > 8000) {
                        tap(0.93f, 0.05f); sleep(600); tap(0.07f, 0.05f)
                        otherSince = now()
                    }
                    sleep(1000)
                }
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun playStep(before: Img) {
        val all = candidates(before)
        val cand = all.firstOrNull { p -> failed.none { hypot(it.first - p.first, it.second - p.second) < 0.06f } }
        if (cand == null) {
            // nothing to try: either level finished (win screen soon) or all failed -> reset memory
            if (all.isNotEmpty() && now() - lastProgress > 4000) failed.clear()
            sleep(800); return
        }
        tap(cand.first, cand.second)
        sleep(800)
        val after = shot()?.let { Img(it) } ?: return
        if (boardDiff(before, after) > 0.015f) {
            failed.clear(); lastProgress = now(); sleep(900)   // block moved: let animation finish
        } else failed.add(cand)
    }
}
