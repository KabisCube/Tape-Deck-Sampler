package com.tapedecksampler.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.tapedecksampler.app.MainActivity.Mode
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

class InstrumentView(private val app: MainActivity) : View(app) {

    private class TextBox(val text: String, val bounds: RectF, val screen: Boolean)

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val mono = Typeface.create("monospace", Typeface.NORMAL)
    private val monoBold = Typeface.create("monospace", Typeface.BOLD)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val buttonShape = buttonShape(false)
    private val zoomButtonShape = buttonShape(true)
    private val trackButtonShape = trackButtonShape()
    private val playIcon = playIcon()
    private val zoomArrow = Path()
    private val iconPath = Path()
    private val zoomLight = buttonLight(0xc8efff)
    private val playLight = buttonLight(0xffb45a)
    private var splitGroup = -1
    private var splitIndex = 0
    private var splitFirst = 0
    private var splitStart = 0L
    private var lockGroup = -1
    private var lockUntil = 0L
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var animating = false
    private var wheelDown = false
    private var wheelMoved = false
    private var angle = 0f
    private var accumulated = 0f
    private var mouseAccumulated = 0f
    private var downX = 0f
    private var downY = 0f
    private var held = false
    private var drawingScreen = false
    private var auditText = false
    private val textBoxes = ArrayList<TextBox>()
    private val hatchMatrix = Matrix()
    private var marker: Typeface? = null
    private var insertStart = -1L
    private var carouselStart = 0L
    private var flipStart = -FLIP_MILLIS
    private var insertName: String? = null
    private var ejectName: String? = null
    private var carouselOutName: String? = null
    private var carouselShownName: String? = null
    private var insertColor = 0
    private var ejectColor = 0
    private var carouselOutColor = 0
    private var carouselShownColor = 0
    private var carouselKey = Int.MIN_VALUE
    private var showingBack = false
    private var pressed = -1
    private var pressedTrack = -1
    private var pressedPlay = false
    private var pressedZoom = false
    private var zoomHolding = false
    private var zoomPointerId = -1
    private var wheelPointerId = -1
    private var waveVersion = -1
    private var waveStart = -1
    private var waveEnd = -1
    private var waveTape: Tape? = null
    private var waveTime = 0L
    private var waveform: List<ColoredWaveform.Segment> = emptyList()
    private var waveRegions: SampleRegions? = null
    private var waveRegionsVersion = -1L
    private var blinkStart = -1L
    private var blinkCount = 0
    private var backTap = false
    private var lilGuyBitmap: Bitmap? = null
    private var sneakyBitmap: Bitmap? = null
    private val sleeves = arrayOfNulls<Bitmap>(SLEEVES.size)
    private val sleeveSource = android.graphics.Rect()
    private val sleeveTarget = RectF()
    private var peelStart = -1L
    private var turningNow = false

    private val hold = Runnable {
        if (pressedTrack >= 0) { held = true; app.toggleTrackMute(pressedTrack) }
        else if (pressed >= 0) {
            held = true
            if (app.pageMenu()) app.togglePage(pressed) else app.holdPad(pressed)
        }
        invalidate()
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = app.getString(R.string.app_name) + ". Rotate the wheel to browse or trim. Menus stop at their ends. Select confirms. Left edits the sample start; right edits its end. The center MENU button opens the menu on Home. The bottom of the wheel opens sequence pages. Each pad selects or manages a page there, and holding a pad enables or disables its page. Hold the magnifying glass button to zoom timelines. Tap the left minus or turn counterclockwise to zoom out. Tap the right plus or turn clockwise to zoom in. Rotate the wheel on Home to change tracks. The triangular play/pause button plays or pauses. Left hand mode in System swaps Play/Pause and Zoom. Tap T01 through T08 to select and play tracks. Hold a track button to mute or unmute it. Muted track buttons are red. Pads toggle steps; hold a pad to edit its effects. On the effects page, the wheel's bottom reorders effects, left deletes and right undoes."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun auditText(enabled: Boolean) { auditText = enabled }

    fun textIssues(): ArrayList<String> {
        val issues = ArrayList<String>()
        for (i in textBoxes.indices) {
            val box = textBoxes[i]
            val limit = if (box.screen) RectF(SCREEN_LEFT, SCREEN_TOP, SCREEN_RIGHT, SCREEN_BOTTOM) else RectF(0f, 0f, UI_WIDTH, UI_HEIGHT)
            if (!limit.contains(box.bounds)) issues.add("Clipped: " + box.text + " " + box.bounds)
            for (j in 0 until i) if (RectF.intersects(box.bounds, textBoxes[j].bounds)) issues.add("Overlap: " + box.text + " / " + textBoxes[j].text)
        }
        return issues
    }

    fun resume() { animating = true; requestFocus(); invalidate() }
    fun pause() { animating = false; removeCallbacks(hold) }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (auditText) textBoxes.clear()
        val now = SystemClock.uptimeMillis()
        val back = app.mode == Mode.BACK
        if (back != showingBack) { showingBack = back; flipStart = now }
        val flip = min(1f, (now - flipStart) / FLIP_MILLIS.toFloat())
        val surface = surface(app.darkTheme)
        c.drawColor(surface)
        paint.style = Paint.Style.FILL; paint.alpha = 255
        paint.shader = RadialGradient(width / 2f, height * 0.45f, max(width, height) * 0.75f, tint(surface, WHITE, 0.06f), tint(surface, BLACK, 0.28f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        val bay = app.batteries
        val lidT = now - bay.lidChangedAt
        val lidMoving = lidT < LID_MILLIS
        val fitHeight = UI_HEIGHT
        val contentWidth = (width - paddingLeft - paddingRight).toFloat()
        val contentHeight = (height - paddingTop - paddingBottom).toFloat()
        scale = min(contentWidth / UI_WIDTH, contentHeight / fitHeight)
        offsetX = paddingLeft + (contentWidth - UI_WIDTH * scale) / 2
        offsetY = paddingTop + (contentHeight - fitHeight * scale) / 2
        c.save()
        c.translate(offsetX, offsetY)
        c.scale(scale, scale)
        if (bay.lidOpen && !lidMoving && !back && flip >= 1) { c.save(); lidPose(c, 1f); lid(c, true); c.restore() }
        val travel = bay.lidOpen && !lidMoving && flip < 1
        val firstHalf = flip < 0.5f
        val lidOnTop = travel && (firstHalf != back)
        if (travel && !lidOnTop) travellingLid(c, flip, back)
        turningNow = flip < 1
        if (flip >= 1) {
            restingShadow(c, 0f, UI_WIDTH)
            if (back) back(c)
            else { rect(c, 0f, 0f, UI_WIDTH, UI_HEIGHT, BODY_RADIUS, bodyColor()); front(c, now) }
        } else turning(c, Math.PI * (spring(flip) + (if (back) 0 else 1)), now)
        turningNow = false
        if (lidOnTop) travellingLid(c, flip, back)
        c.restore()
        val moving = flip < 1 || insertStart >= 0 || app.carouselMode() || blinking(now) || clapReady() || lidMoving || batteriesMoving(now) || peelStart >= 0 ||
            app.mode == Mode.PAGE_COPIED && now - app.copiedAt < COPIED_MILLIS + 50
        if (animating && (splitGroup >= 0 || moving)) postInvalidateOnAnimation()
        else if (animating && (app.sequencer.isPlaying() || app.sequencer.isSyncWaiting() || app.sequencer.isPreviewing() || app.mode == Mode.RECORDING || app.mode == Mode.FX_REORDER || app.busy)) postInvalidateDelayed(FRAME_MILLIS)
    }

    private fun bodyColor() = (if (app.darkTheme) DARK_CASES else CASES)[app.batteries.switches]

    private fun turning(c: Canvas, phi: Double, now: Long) {
        val depth = DEVICE_DEPTH
        val cos = cos(phi).toFloat()
        val sin = sin(phi).toFloat()
        val front = cos > 0
        val z = if (front) depth / 2 else -depth / 2
        val side = if (sin > 0) -UI_CENTER_X else UI_CENTER_X
        val face = floatArrayOf(0f, 0f, UI_WIDTH, 0f, UI_WIDTH, UI_HEIGHT, 0f, UI_HEIGHT)
        val quad = FloatArray(8)
        for (i in 0 until 4) {
            val u = face[i * 2]
            val v = face[i * 2 + 1]
            project(quad, i, if (front) u - UI_CENTER_X else UI_CENTER_X - u, v - UI_HEIGHT / 2, z, cos, sin)
        }
        val map = Matrix()
        val edge = floatArrayOf(0f, 0f, depth, 0f, depth, UI_HEIGHT, 0f, UI_HEIGHT)
        val edgeQuad = FloatArray(8)
        for (i in 0 until 4) project(edgeQuad, i, side, edge[i * 2 + 1] - UI_HEIGHT / 2, edge[i * 2] - depth / 2, cos, sin)
        var minX = UI_WIDTH
        var maxX = 0f
        for (i in 0 until 8 step 2) {
            minX = min(minX, min(quad[i], edgeQuad[i])); maxX = max(maxX, max(quad[i], edgeQuad[i]))
        }
        restingShadow(c, minX, maxX)
        val shade = tint(bodyColor(), BLACK, 0.12f + 0.1f * (1 - abs(sin)))
        val slice = FloatArray(8)
        val slices = 56
        for (k in 0 until slices) {
            val sz = -z + (2 * z) * k / slices
            for (i in 0 until 4) project(slice, i, face[i * 2] - UI_CENTER_X, face[i * 2 + 1] - UI_HEIGHT / 2, sz, cos, sin)
            c.save()
            map.setPolyToPoly(face, 0, slice, 0, 4); c.concat(map)
            rect(c, 0f, 0f, UI_WIDTH, UI_HEIGHT, BODY_RADIUS, shade)
            c.restore()
        }
        c.save()
        map.setPolyToPoly(edge, 0, edgeQuad, 0, 4); c.concat(map)
        deviceEdge(c, depth, abs(sin))
        c.restore()
        c.save()
        map.setPolyToPoly(face, 0, quad, 0, 4); c.concat(map)
        if (front) { rect(c, 0f, 0f, UI_WIDTH, UI_HEIGHT, BODY_RADIUS, bodyColor()); front(c, now) }
        else back(c)
        rect(c, 0f, 0f, UI_WIDTH, UI_HEIGHT, BODY_RADIUS, ((1 - abs(cos)) * 0x70).roundToInt() shl 24)
        c.restore()
    }

    private fun restingShadow(c: Canvas, left: Float, right: Float) {
        for (i in 6 downTo 1) {
            val grow = i * 2.2f
            rect(c, left - grow - 5, 10 - grow, right + grow - 5, UI_HEIGHT + grow + 8, 16 + grow, 0x0d000000)
        }
    }

    private fun deviceEdge(c: Canvas, depth: Float, light: Float) {
        val shade = tint(bodyColor(), BLACK, 0.12f + 0.1f * (1 - light))
        c.clipRect(0f, BODY_RADIUS, depth, UI_HEIGHT - BODY_RADIUS)
        rect(c, 0f, 0f, depth, UI_HEIGHT, 0f, shade)
        line(c, depth / 2, 6f, depth / 2, UI_HEIGHT - 6, tint(shade, BLACK, 0.3f), 1.2f)
        line(c, 2f, 10f, 2f, UI_HEIGHT - 10, tint(shade, WHITE, 0.25f), 1f)
        var y = 500f
        while (y < UI_HEIGHT - 20) { rib(c, 4f, depth - 4, y, 8.5f, tint(shade, BLACK, 0.2f), tint(shade, WHITE, 0.18f)); y += 17 }
        rect(c, depth / 2 - 5, 300f, depth / 2 + 5, 360f, 2f, tint(shade, BLACK, 0.35f))
    }

    private fun bevel(c: Canvas) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f
        for (i in 0 until 7) {
            val inset = 0.6f + i
            paint.color = (0x34 - i * 6) shl 24
            c.drawRoundRect(inset, inset, UI_WIDTH - inset, UI_HEIGHT - inset, BODY_RADIUS - i * 0.6f, BODY_RADIUS - i * 0.6f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun front(c: Canvas, now: Long) {
        bevel(c)
        var t = if (insertStart < 0) -1L else now - insertStart
        if (t >= CLOSE_END) { insertStart = -1; t = -1 }
        val hatch = if (t < 0) 0f else hatchAngle(t)
        c.save()
        c.translate(0f, TOP_SHIFT)
        if (hatch > 0) hatchWell(c, t)
        c.save()
        if (hatch > 0) applyHatch(c, hatch)
        hatchPanel(c, hatch)
        screen(c)
        c.restore()
        c.restore()
        c.save()
        c.scale(CLUSTER_SCALE, CLUSTER_SCALE, WHEEL_X, CLUSTER_PIVOT)
        wheel(c)
        cornerButtons(c)
        c.restore()
        tracks(c)
        pads(c)
        c.save()
        val device = Path()
        device.addRoundRect(0f, 0f, UI_WIDTH, UI_HEIGHT, BODY_RADIUS, BODY_RADIUS, Path.Direction.CW)
        c.clipPath(device)
        if (app.secretOn[MainActivity.SECRET_SWEDEN]) sticker(c, 16f, 772f, -10f, null, 911)
        if (app.secretOn[MainActivity.SECRET_PRIDE]) sticker(c, 47f, 829f, 9f, PRIDE_STRIPES, 317)
        if (app.secretOn[MainActivity.SECRET_TRANS]) sticker(c, 351f, 827f, -4f, TRANS_STRIPES, 173)
        c.restore()
        carousel(c, now)
        if (t >= 0) risingCassette(c, t)
    }

    private fun marker(): Typeface {
        marker?.let { return it }
        val loaded = try { Typeface.createFromAsset(context.assets, "fonts/AlbumMarker01-Regular.ttf") } catch (e: Exception) { bold }
        marker = loaded
        return loaded
    }

    fun blinkPad(count: Int) { blinkCount = count; blinkStart = SystemClock.uptimeMillis(); invalidate() }

    private fun blinking(now: Long) = blinkStart >= 0 && now - blinkStart < blinkCount * 2L * BLINK_MILLIS

    private fun blinkLit(now: Long) = blinking(now) && (now - blinkStart) / BLINK_MILLIS % 2 == 0L

    fun insertCassette(name: String?, color: Int, oldName: String?, oldColor: Int) {
        insertName = name; insertColor = color; ejectName = oldName; ejectColor = oldColor
        insertStart = SystemClock.uptimeMillis()
        carouselKey = Int.MIN_VALUE
        invalidate()
    }

    fun cassetteBusy() = insertStart >= 0

    private fun hatchAngle(t: Long): Float = when {
        t < RISE_END -> 0f
        t < OPEN_END -> HATCH_DEGREES * ease((t - RISE_END) / (OPEN_END - RISE_END).toFloat())
        t < LOAD_END -> HATCH_DEGREES
        else -> HATCH_DEGREES * (1 - ease((t - LOAD_END) / (CLOSE_END - LOAD_END).toFloat()))
    }

    private fun applyHatch(c: Canvas, degrees: Float) {
        val rad = Math.toRadians(degrees.toDouble())
        val height = HATCH_B - HATCH_T
        val drop = height * (1 - cos(rad)).toFloat() * 2.6f
        val widen = height * sin(rad).toFloat() * 0.1f
        val from = floatArrayOf(HATCH_L, HATCH_T, HATCH_R, HATCH_T, HATCH_R, HATCH_B, HATCH_L, HATCH_B)
        val to = floatArrayOf(HATCH_L - widen, HATCH_T + drop, HATCH_R + widen, HATCH_T + drop, HATCH_R, HATCH_B, HATCH_L, HATCH_B)
        hatchMatrix.setPolyToPoly(from, 0, to, 0, 4)
        c.concat(hatchMatrix)
    }

    private fun hatchWell(c: Canvas, t: Long) {
        rect(c, HATCH_L, HATCH_T, HATCH_R, HATCH_B, 10f, 0xff16171a.toInt())
        rect(c, HATCH_L + 6, HATCH_T + 4, HATCH_R - 6, HATCH_T + 30, 6f, 0xff22241f.toInt())
        for (i in 0 until 2) circle(c, UI_CENTER_X + (if (i == 0) -SPOOL_GAP else SPOOL_GAP), SLOT_Y - 12, 7f, 0xff3a3c38.toInt())
        var y = SLOT_Y
        var name = ejectName
        var color = ejectColor
        if (t in OPEN_END until EJECT_END) y = SLOT_Y + (OUT_Y - SLOT_Y) * ease((t - OPEN_END) / (EJECT_END - OPEN_END).toFloat())
        else if (t >= EJECT_END) {
            name = insertName; color = insertColor
            y = if (t >= LOAD_END) SLOT_Y else OUT_Y + (SLOT_Y - OUT_Y) * ease((t - EJECT_END) / (LOAD_END - EJECT_END).toFloat())
        }
        cassette(c, UI_CENTER_X, y, color, name, 0f)
    }

    private fun hatchPanel(c: Canvas, degrees: Float) {
        val plate = if (app.darkTheme) 0xff4b4d47.toInt() else 0xffdbd8cf.toInt()
        if (degrees > 0) rect(c, HATCH_L - 1, HATCH_T - 1, HATCH_R + 1, HATCH_B + 2, 11f, 0x40000000)
        rect(c, HATCH_L, HATCH_T, HATCH_R, HATCH_B, 10f, plate)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f; paint.color = if (app.darkTheme) 0xff2c2e29.toInt() else 0xff9c998f.toInt()
        c.drawRoundRect(HATCH_L, HATCH_T, HATCH_R, HATCH_B, 10f, 10f, paint)
        paint.style = Paint.Style.FILL
        topWindow(c)
        for (i in 0 until 2) {
            val x = if (i == 0) 52f else 318f
            rect(c, x, HATCH_B - 3, x + 30, HATCH_B + 1, 1.5f, if (app.darkTheme) 0xff2c2e29.toInt() else 0xffa9a69b.toInt())
        }
        if (degrees > 0) {
            paint.shader = LinearGradient(0f, HATCH_T, 0f, HATCH_T + 60, 0x30000000, 0, Shader.TileMode.CLAMP)
            c.drawRoundRect(HATCH_L, HATCH_T, HATCH_R, HATCH_T + 60, 10f, 10f, paint)
            paint.shader = null
        }
    }

    private fun topWindow(c: Canvas) {
        val left = 27f; val right = 373f; val top = 10f; val bottom = 27f
        val t = if (insertStart < 0) -1L else SystemClock.uptimeMillis() - insertStart
        val seated = t < 0 || t < OPEN_END || t >= LOAD_END
        val color = if (t < 0) app.cassetteColor(app.session) else if (t >= EJECT_END) insertColor else ejectColor
        rect(c, ROW_LEFT, 5f, ROW_RIGHT, 32f, 7f, if (app.darkTheme) 0xff74766a.toInt() else 0xffb2b1a5.toInt())
        rect(c, 20f, 7f, 380f, 30f, 5f, INK)
        rect(c, left, top, right, bottom, 2f, 0xff1b1c19.toInt())
        c.save(); c.clipRect(left, top, right, bottom)
        if (seated) {
            val name = if (t < 0) app.session.name() else if (t >= EJECT_END) insertName else ejectName
            cassette(c, UI_CENTER_X, top + 4 + CASSETTE_H / 2, color, name, 0f)
            rect(c, left, top, right, bottom, 0f, 0x30000000)
        }
        paint.shader = LinearGradient(0f, top, 0f, bottom, 0x30ffffff, 0x08ffffff, Shader.TileMode.CLAMP)
        c.drawRect(left, top, right, bottom, paint)
        paint.shader = null
        paint.strokeWidth = 6f; paint.color = 0x14ffffff
        c.drawLine(250f, bottom + 2, 262f, top - 2, paint)
        c.drawLine(276f, bottom + 2, 282f, top - 2, paint)
        c.restore()
        val title = app.getString(R.string.app_name).uppercase(Locale.US)
        plain(c, title, 37f, 23f, 11f, 0x80000000.toInt(), monoBold)
        text(c, title, LCD_LEFT, 22f, 11f, 0xffeeece4.toInt(), monoBold)
    }

    private fun cassette(c: Canvas, cx: Float, cy: Float, color: Int, name: String?, progress: Float) {
        val left = cx - CASSETTE_W / 2
        val top = cy - CASSETTE_H / 2
        val right = left + CASSETTE_W
        val bottom = top + CASSETTE_H
        val opaque = MainActivity.opaqueCassette(color)
        val vivid = saturated(color)
        val rgb = color and 0xffffff
        val solid = tint(color, BLACK, 0.18f) and 0xffffff
        val dark = tint(color, BLACK, 0.45f) and 0xffffff
        val shell = Path()
        shell.addRoundRect(left, top, right, bottom, 10f, 10f, Path.Direction.CW)
        paint.color = if (opaque) color else (vivid and 0xffffff) or 0x60000000; c.drawPath(shell, paint)
        c.save(); c.clipPath(shell)
        var x = left + 3
        while (x < right) { line(c, x, top, x, bottom, 0x10000000, 0.5f); x += 5 }
        var y = top + 3
        while (y < bottom) { line(c, left, y, right, y, 0x10000000, 0.5f); y += 5 }
        c.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f; paint.color = solid or 0xd8000000.toInt()
        c.drawRoundRect(left + 3.5f, top + 3.5f, right - 3.5f, bottom - 3.5f, 8f, 8f, paint)
        paint.strokeWidth = 2f; paint.color = (vivid and 0xffffff) or 0xd0000000.toInt()
        iconPath.reset()
        iconPath.moveTo(left + 7.5f, bottom - 30); iconPath.lineTo(left + 7.5f, top + 14); iconPath.quadTo(left + 7.5f, top + 7.5f, left + 14, top + 7.5f); iconPath.lineTo(right - 30, top + 7.5f)
        c.drawPath(iconPath, paint)
        paint.strokeWidth = 1.5f; paint.color = dark or 0x90000000.toInt()
        c.drawRoundRect(left, top, right, bottom, 10f, 10f, paint)
        paint.strokeWidth = 2f; paint.color = solid or 0x90000000.toInt()
        c.drawRoundRect(left + 13, top + 13, right - 13, bottom - 40, 6f, 6f, paint)
        paint.style = Paint.Style.FILL
        for (i in 0 until 2) {
            val bx = if (i == 0) left + 28 else right - 28
            val by = bottom - 26
            circle(c, bx, by, 12f, solid or 0x90000000.toInt())
            circle(c, bx, by, 7f, rgb or 0x50000000)
            box(c, bx - 7, by - 7, bx + 7, by + 7, dark or 0x60000000, 1f)
        }
        val spoolY = cy - 12
        val hub = 19f
        val full = 64f
        val packs = floatArrayOf(hub + 2 + (full - hub - 2) * (1 - progress), hub + 2 + (full - hub - 2) * progress)
        val spools = floatArrayOf(cx - SPOOL_GAP, cx + SPOOL_GAP)
        val guideY = bottom - 16
        tapeLine(c, spools[0], spoolY, packs[0], left + 40, guideY, true, 0xe0201810.toInt(), 1.4f)
        tapeLine(c, spools[1], spoolY, packs[1], right - 40, guideY, false, 0xe0201810.toInt(), 1.4f)
        line(c, left + 40, guideY + 4, right - 40, guideY + 4, 0xf0201810.toInt(), 2f)
        for (i in 0 until 2) {
            circle(c, spools[i], spoolY, packs[i], 0xf0141210.toInt())
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.8f; paint.color = 0x30ffffff
            var r = hub + 6
            while (r < packs[i] - 2) { c.drawCircle(spools[i], spoolY, r, paint); r += 7 }
            paint.style = Paint.Style.FILL
        }
        iconPath.reset()
        iconPath.moveTo(cx - 114, bottom - 1); iconPath.lineTo(cx - 100, bottom - 36); iconPath.lineTo(cx + 100, bottom - 36); iconPath.lineTo(cx + 114, bottom - 1); iconPath.close()
        paint.color = solid or 0xc8000000.toInt(); c.drawPath(iconPath, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f; paint.color = dark or 0xa0000000.toInt(); c.drawPath(iconPath, paint); paint.style = Paint.Style.FILL
        for (i in 0 until 2) {
            val hx = if (i == 0) cx - 62 else cx + 62
            circle(c, hx, bottom - 14, 6f, 0xd0101010.toInt())
            rect(c, cx + (if (i == 0) -34 else 22), bottom - 20, cx + (if (i == 0) -22 else 34), bottom - 9, 1.5f, 0xd0101010.toInt())
        }
        rect(c, spools[0] - hub - 6, spoolY - hub - 4, spools[1] + hub + 6, spoolY + hub + 4, 6f, 0x1cffffff)
        box(c, spools[0] - hub - 6, spoolY - hub - 4, spools[1] + hub + 6, spoolY + hub + 4, solid or 0xb0000000.toInt(), 1.6f)
        var tick = cx - 30
        while (tick <= cx + 30) { line(c, tick, spoolY - 4, tick, spoolY + (if (tick == cx) 6 else 2), 0x90ffffff.toInt(), 1f); tick += 10 }
        for (i in 0 until 2) {
            circle(c, spools[i], spoolY, hub, solid or 0xe0000000.toInt())
            circle(c, spools[i], spoolY, hub - 4, 0xffeeeeea.toInt())
            circle(c, spools[i], spoolY, hub - 9, 0xfffafaf8.toInt())
            for (k in 0 until 6) {
                c.save(); c.rotate((k * 60 + (if (i == 0) 15 else 45)).toFloat(), spools[i], spoolY)
                rect(c, spools[i] - 1.6f, spoolY - hub + 9, spools[i] + 1.6f, spoolY - hub + 14, 0.5f, 0xffb8bab8.toInt())
                c.restore()
            }
        }
        val labelLeft = cx - 74
        val labelRight = cx + 74
        val labelTop = top + 16
        val labelBottom = top + 40
        rect(c, labelLeft, labelTop, labelRight, labelBottom, 2f, 0xfff4f0e4.toInt())
        rect(c, labelLeft, labelBottom - 3, labelRight, labelBottom, 0f, rgb or 0xff000000.toInt())
        paint.typeface = marker(); paint.textSize = 17f
        val shown = TextUtils.ellipsize(name ?: "", paint, labelRight - labelLeft - 12, TextUtils.TruncateAt.END).toString()
        paint.color = 0xff1f2a5c.toInt()
        c.drawText(shown, cx - paint.measureText(shown) / 2, labelBottom - 8, paint)
        val screws = arrayOf(floatArrayOf(left + 11, top + 11), floatArrayOf(right - 11, top + 11), floatArrayOf(left + 11, bottom - 11), floatArrayOf(right - 11, bottom - 11), floatArrayOf(cx, bottom - 28))
        for (screw in screws) {
            circle(c, screw[0], screw[1], 4.2f, 0xff1c1c1e.toInt())
            line(c, screw[0] - 2.4f, screw[1], screw[0] + 2.4f, screw[1], 0xff6a6c70.toInt(), 0.9f)
            line(c, screw[0], screw[1] - 2.4f, screw[0], screw[1] + 2.4f, 0xff6a6c70.toInt(), 0.9f)
        }
        paint.color = 0x20ffffff
        iconPath.reset()
        iconPath.moveTo(left + 10, top + 4); iconPath.lineTo(left + 64, top + 4); iconPath.lineTo(left + 4, bottom - 44); iconPath.lineTo(left + 4, top + 20); iconPath.close()
        c.drawPath(iconPath, paint)
    }

    private fun clapReady() = app.mode == Mode.SYNC_CLAP && app.clapClock == null && app.clapStatus.startsWith("READY,")

    private fun tapeLine(c: Canvas, cx: Float, cy: Float, radius: Float, gx: Float, gy: Float, outerLeft: Boolean, color: Int, width: Float) {
        val dx = gx - cx
        val dy = gy - cy
        val distance = hypot(dx, dy)
        val base = atan2(dy.toDouble(), dx.toDouble())
        val spread = acos(min(1f, radius / max(radius, distance)).toDouble())
        val a = base + spread
        val b = base - spread
        val angle = if (outerLeft == (cos(a) < cos(b))) a else b
        line(c, cx + cos(angle).toFloat() * radius, cy + sin(angle).toFloat() * radius, gx, gy, color, width)
    }

    private fun hoverShadow(c: Canvas, cx: Float, cy: Float) {
        rect(c, cx - CASSETTE_W / 2 + 10, cy - CASSETTE_H / 2 + 16, cx + CASSETTE_W / 2 - 4, cy + CASSETTE_H / 2 + 16, 14f, 0x22000000)
        rect(c, cx - CASSETTE_W / 2 + 6, cy - CASSETTE_H / 2 + 11, cx + CASSETTE_W / 2 - 2, cy + CASSETTE_H / 2 + 10, 13f, 0x26000000)
    }

    private fun carousel(c: Canvas, now: Long) {
        if (!app.carouselMode() || insertStart >= 0) { carouselKey = Int.MIN_VALUE; return }
        val name = app.carouselName()
        val color = app.carouselColor()
        val key = app.carouselKey()
        if (key != carouselKey) {
            if (carouselKey != Int.MIN_VALUE && carouselShownName != null) { carouselOutName = carouselShownName; carouselOutColor = carouselShownColor }
            else carouselOutName = null
            carouselKey = key; carouselStart = now
        }
        carouselShownName = name; carouselShownColor = color
        val t = min(1f, (now - carouselStart) / CAROUSEL_MILLIS.toFloat())
        val outgoing = carouselOutName
        if (outgoing != null && t < 1) {
            val x = UI_CENTER_X + CAROUSEL_TRAVEL * ease(t)
            hoverShadow(c, x, CAROUSEL_Y)
            cassette(c, x, CAROUSEL_Y, carouselOutColor, outgoing, 0f)
        }
        val x = UI_CENTER_X - CAROUSEL_TRAVEL + CAROUSEL_TRAVEL * ease(t)
        val bob = sin(now / BOB_MILLIS).toFloat() * BOB_HEIGHT
        hoverShadow(c, x, CAROUSEL_Y)
        cassette(c, x, CAROUSEL_Y + bob, color, name, 0f)
    }

    private fun risingCassette(c: Canvas, t: Long) {
        if (t >= RISE_END) return
        val y = CAROUSEL_Y + (OUT_Y - CAROUSEL_Y) * (t / RISE_END.toFloat()).toDouble().pow(1.7).toFloat()
        cassette(c, UI_CENTER_X, y, insertColor, insertName, 0f)
    }

    private fun tapeFraction(): Float = when (app.mode) {
        Mode.HOME -> { val track = app.sequencer.tracks[app.track]; (track.start + track.end) / 2f / Tape.LENGTH }
        Mode.START -> app.start / Tape.LENGTH.toFloat()
        Mode.END -> app.end / Tape.LENGTH.toFloat()
        Mode.RECORDING -> app.recordPosition / Tape.LENGTH.toFloat()
        Mode.IMPORT_START -> app.importSource?.let { app.importStart / it.frames.toFloat() } ?: 0f
        Mode.IMPORT_END -> app.importSource?.let { app.importEnd / it.frames.toFloat() } ?: 0f
        else -> app.cursor / Tape.LENGTH.toFloat()
    }

    private fun showsTapeIcon() = !app.busy && (app.mode == Mode.HOME || app.isTimelineMode())

    private fun tapeIcon(c: Canvas, right: Float, top: Float, position: Float) {
        val fraction = max(0f, min(1f, position))
        val left = right - 34
        val bottom = top + 19
        val cy = top + 9.5f
        val hub = 1.5f
        val full = 6f
        box(c, left, top, right, bottom, LCD, 1.2f)
        val a = hub + (full - hub) * (1 - fraction)
        val b = hub + (full - hub) * fraction
        val guide = bottom - 2.5f
        circle(c, left + 10, cy, a, LCD)
        circle(c, right - 10, cy, b, LCD)
        line(c, left + 10 - a, cy, left + 3.5f, guide, LCD, 1f)
        line(c, right - 10 + b, cy, right - 3.5f, guide, LCD, 1f)
        line(c, left + 3.5f, guide, right - 3.5f, guide, LCD, 1f)
    }

    private fun batteryMeter(c: Canvas) {
        val bay = app.batteries
        val color = if (bay.low()) ORANGE else LCD
        val left = 339f; val right = 360f; val top = 51f; val bottom = 60f
        box(c, left, top, right, bottom, color, 1.2f)
        rect(c, right + 0.6f, top + 2.5f, right + 2.6f, bottom - 2.5f, 0.5f, color)
        val fill = (right - left - 3) * bay.level()
        if (fill > 0) rect(c, left + 1.5f, top + 1.5f, left + 1.5f + fill, bottom - 1.5f, 0.5f, color)
    }

    private fun sticker(c: Canvas,x: Float, y: Float, angle: Float, stripes: IntArray?, seed: Long) {
        val random = Random(seed)
        val width = 84f
        val height = 52f
        val left = x - width / 2
        val top = y - height / 2
        val right = left + width
        val bottom = top + height
        c.save()
        c.rotate(angle, x, y)
        val shape = Path()
        shape.addRoundRect(left, top, right, bottom, 3f, 3f, Path.Direction.CW)
        val corner = random.nextInt(4)
        val tear = 11 + random.nextFloat() * 9
        val cornerX = if (corner % 2 == 0) left else right
        val cornerY = if (corner < 2) top else bottom
        val sx = if (corner % 2 == 0) 1f else -1f
        val sy = if (corner < 2) 1f else -1f
        val rip = Path()
        rip.moveTo(cornerX - sx * 2, cornerY - sy * 2)
        rip.lineTo(cornerX + sx * tear, cornerY - sy * 2)
        val teeth = 6
        for (i in 1 until teeth) {
            val f = i / teeth.toFloat()
            val jitter = (random.nextFloat() - 0.5f) * 3.2f
            rip.lineTo(cornerX + sx * (tear * (1 - f) + jitter), cornerY + sy * (tear * 0.8f * f + jitter))
        }
        rip.lineTo(cornerX - sx * 2, cornerY + sy * tear * 0.8f)
        rip.close()
        val residue = Path(rip)
        shape.op(rip, Path.Op.DIFFERENCE)
        paint.color = 0x38f6f4ec
        c.save(); c.clipRect(left, top, right, bottom); c.drawPath(residue, paint); c.restore()
        c.save(); c.translate(0.6f, 1.4f); paint.color = 0x33000000; c.drawPath(shape, paint); c.restore()
        c.save()
        c.clipPath(shape)
        rect(c, left, top, right, bottom, 0f, 0xfff6f4ec.toInt())
        if (stripes == null) {
            rect(c, left + 3, top + 3, right - 3, bottom - 3, 0f, 0xff3f86c4.toInt())
            val bar = (height - 6) * 0.2f
            val crossX = left + 3 + (width - 6) * 5 / 16f
            val crossY = top + 3 + (height - 6) * 0.4f
            rect(c, crossX, top + 3, crossX + bar, bottom - 3, 0f, 0xfff2cf4a.toInt())
            rect(c, left + 3, crossY, right - 3, crossY + bar, 0f, 0xfff2cf4a.toInt())
        } else {
            val stripe = (height - 6) / stripes.size
            for (i in stripes.indices) {
                rect(c, left + 3, top + 3 + i * stripe, right - 3, top + 3 + (i + 1) * stripe + (if (i < stripes.size - 1) 0.4f else 0f), 0f, stripes[i])
            }
        }
        rect(c, left, top, right, bottom, 0f, 0x1effffff)
        c.save(); c.rotate(-angle, x, y); bevel(c); c.restore()
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 6) {
            val ax = left + random.nextFloat() * width
            val ay = top + random.nextFloat() * height
            val dir = random.nextDouble() * Math.PI
            val length = 8 + random.nextFloat() * 26
            line(c, ax, ay, ax + cos(dir).toFloat() * length, ay + sin(dir).toFloat() * length, if (i % 3 == 0) 0x28000000 else 0x66ffffff, 0.45f + random.nextFloat() * 0.5f)
        }
        for (i in 0 until 10) circle(c, left + random.nextFloat() * width, top + random.nextFloat() * height, 0.4f + random.nextFloat() * 0.9f, 0x50ffffff)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.4f; paint.color = 0x40ffffff
        c.drawPath(shape, paint)
        paint.style = Paint.Style.FILL
        c.restore()
        c.restore()
    }

    private fun back(c: Canvas) {
        val body = bodyColor()
        val deep = tint(body, BLACK, 0.16f)
        val light = tint(body, WHITE, 0.35f)
        rect(c, 0f, 0f, UI_WIDTH, UI_HEIGHT, BODY_RADIUS, body)
        bevel(c)
        embossed(c, "TAPE DECK SAMPLER", UI_CENTER_X, 50f, 21f, body)
        recess(c, 46f, 70f, 354f, 476f, 10f, deep, light)
        val left = 58f; val top = 82f; val right = 342f; val bottom = 464f
        rect(c, left + 1, top + 2, right + 1, bottom + 2, 4f, 0x30000000)
        rect(c, left, top, right, bottom, 4f, 0xffefefea.toInt())
        rect(c, left, top, right, bottom, 4f, 0x10b8bcc4)
        val ink = 0xff1b1c1e.toInt()
        val sans = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        val sansBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val x = left + 14
        text(c, "Tape Deck Sampler", x, top + 30, 20f, ink, sansBold)
        text(c, "Designed by KabisCube", x, top + 48, 12f, ink, sans)
        line(c, x, top + 58, right - 14, top + 58, ink, 1f)
        val specs = arrayOf(
            "Model: TDS-20    Portable tape sampler",
            "Input: 5V ⎓ 2.0A   USB-C",
            "Output: 48 000 Hz ⎓ 16-bit mono PCM",
            "Tape: 20 s / 960 000 frames",
            "Tracks: 8 × 16 steps × 16 pages",
            "Tempo: 40–240 BPM",
            "Sync: Clap & Sync / no network")
        for (i in specs.indices) text(c, specs[i], x, top + 76 + i * 15, 11f, ink, sans)
        val symbols = top + 196
        binSymbol(c, x + 10, symbols, ink)
        box(c, x + 30, symbols - 9, x + 48, symbols + 9, ink, 1.3f)
        box(c, x + 34, symbols - 5, x + 44, symbols + 5, ink, 1.3f)
        line(c, x + 58, symbols - 3, x + 78, symbols - 3, ink, 1.6f)
        for (i in 0 until 3) line(c, x + 58 + i * 8, symbols + 3, x + 62 + i * 8, symbols + 3, ink, 1.6f)
        line(c, x, top + 214, right - 14, top + 214, ink, 1f)
        text(c, "USAGE LOG", x, top + 232, 12f, ink, sansBold)
        val stats = app.stats()
        for (i in stats.indices) {
            val y = top + 250 + i * 16
            text(c, stats[i][0], x, y, 11f, ink, sans)
            paint.typeface = monoBold; paint.textSize = 11f
            text(c, stats[i][1], right - 14 - paint.measureText(stats[i][1]), y, 11f, ink, monoBold)
            dotted(c, x + 4 + measure(stats[i][0], sans, 11f), right - 20 - measure(stats[i][1], monoBold, 11f), y - 3, ink)
        }
        val serial = String.format(Locale.US, "S/N TDS-%08X", (app.stickerSeed xor (app.stickerSeed ushr 32)).toInt())
        val barTop = bottom - 40
        val bars = Random(app.stickerSeed)
        var bx = x
        while (bx < x + 150) {
            val w = 0.8f + bars.nextInt(3) * 0.9f
            rect(c, bx, barTop, bx + w, barTop + 22, 0f, ink)
            bx += w + 0.9f + bars.nextInt(3) * 0.9f
        }
        text(c, serial, x, bottom - 8, 9f, ink, mono)
        rightText(c, "MADE FOR FRIENDS", right - 14, bottom - 8, 9f, ink, sansBold)
        var y = 500f
        while (y < 846) {
            val beside = y + RIB_PITCH / 2 > COVER_T - 4 && y < COVER_B + 4
            rib(c, 4f, if (beside) COVER_L - 8 else 396f, y, RIB_PITCH / 2, deep, light)
            if (beside) rib(c, COVER_R + 8, 396f, y, RIB_PITCH / 2, deep, light)
            y += RIB_PITCH
        }
        rect(c, COVER_L - 3, COVER_T - 3, COVER_R + 3, COVER_B + 3, 12f, tint(deep, BLACK, 0.35f))
        val now = SystemClock.uptimeMillis()
        val bay = app.batteries
        val lidT = now - bay.lidChangedAt
        val lidMoving = lidT < LID_MILLIS
        val open = bay.lidOpen || lidMoving
        if (open) compartment(c, body) else lid(c, false)
        val screws = arrayOf(floatArrayOf(34f, 34f), floatArrayOf(366f, 34f), floatArrayOf(30f, 836f), floatArrayOf(370f, 836f), floatArrayOf(200f, 488f))
        for (screw in screws) if (screw[1] > 495) circle(c, screw[0], screw[1], 17f, body)
        for (i in screws.indices) recessedScrew(c, screws[i][0], screws[i][1], body, i)
        if (app.secretOn[MainActivity.SECRET_LIL_GUY]) stickerImage(c, false, LIL_X, LIL_Y, 84f, -7f)
        val peelT = if (peelStart < 0) -1L else now - peelStart
        if (peelT >= PEEL_DONE) peelStart = -1
        if (app.secretOn[MainActivity.SECRET_SNEAKY] && peelStart < 0) stickerImage(c, true, SNEAKY_X, SNEAKY_Y, 80f, 6f)
        peel(c, body, peelSize(peelT))
        if (peelStart >= 0) flyingSneaky(c, peelT)
        if (open) for (slot in 0 until BatteryBay.SLOTS) if (bay.installed[slot] && batterySettled(slot, now)) battery(c, slot, now)
        if ((lidMoving || bay.lidOpen) && !turningNow) {
            val f = softSpring(min(1f, lidT / LID_MILLIS.toFloat()))
            c.save(); lidPose(c, if (bay.lidOpen) f else 1 - f); lid(c, true); c.restore()
        }
        if (open) for (slot in 0 until BatteryBay.SLOTS) if (!bay.installed[slot] || !batterySettled(slot, now)) battery(c, slot, now)
    }

    private fun stickerImage(c: Canvas, sneaky: Boolean, x: Float, y: Float, size: Float, angle: Float) {
        val bitmap = if (sneaky) sneakyBitmap ?: BitmapFactory.decodeResource(resources, R.drawable.sneaky_guy).also { sneakyBitmap = it }
        else lilGuyBitmap ?: BitmapFactory.decodeResource(resources, R.drawable.lil_guy).also { lilGuyBitmap = it }
        val h = size * bitmap.height / bitmap.width
        c.save(); c.rotate(angle, x, y)
        paint.isFilterBitmap = true; paint.color = 0x30000000
        c.save(); c.translate(0.8f, 1.6f)
        paint.colorFilter = android.graphics.PorterDuffColorFilter(0x30000000, android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(bitmap, null, RectF(x - size / 2, y - h / 2, x + size / 2, y + h / 2), paint)
        paint.colorFilter = null
        c.restore()
        paint.color = WHITE
        c.drawBitmap(bitmap, null, RectF(x - size / 2, y - h / 2, x + size / 2, y + h / 2), paint)
        c.restore()
    }

    private fun peelSize(t: Long): Float = when {
        t < 0 -> PEEL_IDLE
        t < PEEL_OPEN_MS -> PEEL_IDLE + (PEEL_OPEN - PEEL_IDLE) * ease(t / PEEL_OPEN_MS.toFloat())
        t < PEEL_CLOSE_MS -> PEEL_OPEN
        else -> PEEL_OPEN + (PEEL_IDLE - PEEL_OPEN) * ease((t - PEEL_CLOSE_MS) / (PEEL_DONE - PEEL_CLOSE_MS).toFloat())
    }

    private fun peel(c: Canvas, body: Int, size: Float) {
        val r = LABEL_R; val t = LABEL_T
        iconPath.reset(); iconPath.moveTo(r - size, t); iconPath.lineTo(r, t); iconPath.lineTo(r, t + size); iconPath.close()
        paint.color = tint(body, BLACK, 0.3f); c.drawPath(iconPath, paint)
        iconPath.reset(); iconPath.moveTo(r - size, t); iconPath.lineTo(r - size + 2, t + size + 3); iconPath.lineTo(r + 1, t + size + 1); iconPath.close()
        paint.color = 0x30000000; c.drawPath(iconPath, paint)
        iconPath.reset(); iconPath.moveTo(r - size, t); iconPath.lineTo(r, t + size); iconPath.lineTo(r - size, t + size); iconPath.close()
        paint.color = WHITE; paint.shader = LinearGradient(r - size / 2, t + size / 2, r - size, t + size, 0xfff4f3ee.toInt(), 0xffcfcdc4.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(iconPath, paint); paint.shader = null
        line(c, r - size, t, r, t + size, 0x40ffffff, 1f)
    }

    private fun flyingSneaky(c: Canvas, t: Long) {
        if (t < PEEL_OPEN_MS) return
        val startX = LABEL_R - 18; val startY = LABEL_T + 18
        val dropX = LABEL_R - 34; val dropY = LABEL_T + 96
        if (t < PEEL_FALL_MS) {
            val f = (t - PEEL_OPEN_MS) / (PEEL_FALL_MS - PEEL_OPEN_MS).toFloat()
            val g = f * f
            stickerImage(c, true, startX + (dropX - startX) * f, startY + (dropY - startY) * g, 30f + 20f * f, -25f * f)
            return
        }
        val f = ease((t - PEEL_FALL_MS) / (PEEL_DONE - PEEL_FALL_MS).toFloat())
        val arc = sin(Math.PI * f).toFloat() * 30
        stickerImage(c, true, dropX + (SNEAKY_X - dropX) * f + arc, dropY + (SNEAKY_Y - dropY) * f - arc, 50f + 30f * f + arc * 0.4f, -25f + 31f * f)
    }

    fun peelTap(x: Float, y: Float): Boolean {
        if (app.secretUnlocked[MainActivity.SECRET_SNEAKY] || peelStart >= 0) return false
        if (x < LABEL_R - 44 || x > LABEL_R + 8 || y < LABEL_T - 8 || y > LABEL_T + 44) return false
        peelStart = SystemClock.uptimeMillis()
        app.findSneaky()
        invalidate()
        return true
    }

    private fun travellingLid(c: Canvas, flip: Float, toBack: Boolean) {
        val firstHalf = flip < 0.5f
        val f = softSpring(if (firstHalf) flip * 2 else flip * 2 - 1)
        val from: FloatArray
        val to: FloatArray
        if (toBack) { from = if (firstHalf) FRONT_REST else LID_OUT; to = if (firstHalf) LID_OUT else BACK_REST }
        else { from = if (firstHalf) BACK_REST else LID_OUT; to = if (firstHalf) LID_OUT else FRONT_REST }
        val cx = (COVER_L + COVER_R) / 2
        val cy = (COVER_T + COVER_B) / 2
        c.save()
        c.translate(from[0] + (to[0] - from[0]) * f - cx, from[1] + (to[1] - from[1]) * f - cy)
        c.rotate(from[2] + (to[2] - from[2]) * f, cx, cy)
        lid(c, true)
        c.restore()
    }

    private fun lidPose(c: Canvas, f: Float) {
        val cx = (COVER_L + COVER_R) / 2
        val cy = (COVER_T + COVER_B) / 2
        val lift = 1 + 0.06f * sin(Math.PI * f).toFloat()
        c.translate((REST_X - cx) * f, (REST_Y - cy) * f)
        c.rotate(REST_ANGLE * f, cx, cy)
        c.scale(lift, lift, cx, cy)
    }

    private fun lid(c: Canvas, loose: Boolean) {
        val body = bodyColor()
        val deep = tint(body, BLACK, 0.16f)
        val light = tint(body, WHITE, 0.35f)
        if (loose) {
            rect(c, COVER_L + 2, COVER_T + 7, COVER_R + 6, COVER_B + 10, 12f, 0x30000000)
            rect(c, COVER_L - 1, COVER_T - 4, COVER_R + 1, COVER_B + 2, 11f, tint(deep, BLACK, 0.2f))
        }
        rect(c, COVER_L, COVER_T, COVER_R, COVER_B, 10f, body)
        line(c, COVER_L + 10, COVER_T + 1, COVER_R - 10, COVER_T + 1, light, 1f)
        var y = COVER_T + 44
        while (y + RIB_PITCH / 2 < COVER_B - 6) { rib(c, COVER_L + 10, COVER_R - 10, y, RIB_PITCH / 2, deep, light); y += RIB_PITCH }
        rect(c, 176f, COVER_T - 3, 224f, COVER_T + 26, 4f, tint(deep, BLACK, 0.35f))
        rect(c, 179f, COVER_T - 1, 221f, COVER_T + 23, 3f, tint(body, WHITE, 0.06f))
        line(c, 182f, COVER_T + 1, 218f, COVER_T + 1, light, 1f)
        for (i in 0 until 3) line(c, 188f + i * 12, COVER_T + 7, 188f + i * 12, COVER_T + 17, deep, 2.2f)
    }

    private fun slotY(slot: Int) = COVER_T + 60 + slot * SLOT_PITCH

    private fun compartment(c: Canvas, body: Int) {
        val floor = tint(body, BLACK, 0.34f)
        rect(c, COVER_L, COVER_T, COVER_R, COVER_B, 10f, tint(body, BLACK, 0.55f))
        rect(c, COVER_L + 5, COVER_T + 5, COVER_R - 5, COVER_B - 5, 7f, floor)
        paint.shader = LinearGradient(0f, COVER_T + 5, 0f, COVER_T + 34, 0x60000000, 0, Shader.TileMode.CLAMP)
        c.drawRect(COVER_L + 5, COVER_T + 5, COVER_R - 5, COVER_T + 34, paint); paint.shader = null
        val now = SystemClock.uptimeMillis()
        rect(c, WALL_L, slotY(0) - SLOT_PITCH / 2 - 2, WALL_R, slotY(BatteryBay.SLOTS - 1) + SLOT_PITCH / 2 + 2, 8f, tint(floor, BLACK, 0.18f))
        for (slot in 1 until BatteryBay.SLOTS) {
            val edge = slotY(slot) - SLOT_PITCH / 2
            line(c, WALL_L + 6, edge, WALL_R - 6, edge, tint(floor, WHITE, 0.1f), 1.4f)
        }
        for (slot in 0 until BatteryBay.SLOTS) {
            val cy = slotY(slot)
            val plusRight = slot % 2 == 0
            centeredText(c, "−", if (plusRight) BATTERY_L + 30 else BATTERY_R - 30, cy, 18f, tint(floor, WHITE, 0.18f), monoBold)
            centeredText(c, "+", if (plusRight) BATTERY_R - 30 else BATTERY_L + 30, cy, 18f, tint(floor, WHITE, 0.18f), monoBold)
            c.save()
            if (!plusRight) c.scale(-1f, 1f, BATTERY_CX, cy)
            spring(c, cy, app.batteries.installed[slot] || batteryPoses(slot, now).any { it.dx == 0f })
            flap(c, cy)
            c.restore()
            if (slot >= SWITCH_SLOT) caseSwitch(c, slot - SWITCH_SLOT, cy)
        }
    }

    private fun spring(c: Canvas, cy: Float, compressed: Boolean) {
        rect(c, WALL_L - 2, cy - 17, WALL_L + 2, cy + 17, 1.5f, METAL_DARK)
        val reach = if (compressed) BATTERY_L else BATTERY_L + 18
        val coils = 6
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        for (pass in 0 until 2) {
            paint.color = if (pass == 0) METAL_DARK else METAL; paint.strokeWidth = if (pass == 0) 2.6f else 1.8f
            val shift = if (pass == 0) 0.8f else 0f
            iconPath.reset(); iconPath.moveTo(WALL_L + 2 + shift, cy + 14)
            for (k in 0 until coils) {
                val x = WALL_L + 2 + (reach - WALL_L - 2) * (k + 1) / coils + shift
                iconPath.lineTo(x - (reach - WALL_L - 2) / coils / 2, cy - 14)
                iconPath.lineTo(x, cy + 14)
            }
            c.drawPath(iconPath, paint)
        }
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
        rect(c, reach - 2, cy - 12, reach + 1, cy + 12, 1f, METAL)
    }

    private fun flap(c: Canvas, cy: Float) {
        rect(c, WALL_R - 3, cy - 18, WALL_R + 2, cy + 18, 1.5f, METAL_DARK)
        iconPath.reset()
        iconPath.moveTo(WALL_R - 1, cy - 15); iconPath.lineTo(BATTERY_R + 1, cy - 8); iconPath.lineTo(BATTERY_R + 1, cy + 8); iconPath.lineTo(WALL_R - 1, cy + 15); iconPath.close()
        paint.color = WHITE
        paint.shader = LinearGradient(BATTERY_R, 0f, WALL_R, 0f, METAL, METAL_DARK, Shader.TileMode.CLAMP)
        c.drawPath(iconPath, paint); paint.shader = null
        line(c, BATTERY_R + 1.5f, cy - 7, BATTERY_R + 1.5f, cy + 7, 0xfff4f5f7.toInt(), 1f)
    }

    private fun caseSwitch(c: Canvas, index: Int, cy: Float) {
        val on = app.batteries.switches and (1 shl index) != 0
        val color = SWITCH_COLORS[index]
        rect(c, SWITCH_L - 2, cy - 10, SWITCH_R + 2, cy + 10, 4f, 0xff151618.toInt())
        rect(c, SWITCH_L, cy - 8, SWITCH_R, cy + 8, 3f, 0xff3a3c40.toInt())
        val knob = if (on) SWITCH_R - 15 else SWITCH_L + 1
        rect(c, knob, cy - 7, knob + 14, cy + 7, 2.5f, color)
        rect(c, knob, cy - 7, knob + 14, cy - 3, 2.5f, tint(color, WHITE, 0.3f))
        line(c, knob + 4, cy - 4, knob + 4, cy + 4, 0x50000000, 1.2f)
        line(c, knob + 9, cy - 4, knob + 9, cy + 4, 0x50000000, 1.2f)
    }

    private class Pose(val dx: Float, val dy: Float, val angle: Float, val brand: Int)

    private fun batteryPoses(slot: Int, now: Long): List<Pose> {
        val bay = app.batteries
        val t = now - bay.changedAt[slot]
        val brand = bay.brand[slot]
        val pop = POPPED[slot]
        if (bay.installed[slot]) {
            if (t >= INSERT_MILLIS) return listOf(Pose(0f, 0f, 0f, brand))
            val f = 1 - softSpring(t / INSERT_MILLIS.toFloat())
            val from = if (bay.fresh[slot]) pop else floatArrayOf(LOOSE[slot][0] - BATTERY_CX, LOOSE[slot][1] - slotY(slot), LOOSE[slot][2])
            return listOf(Pose(from[0] * f, from[1] * f, from[2] * f, brand))
        }
        val leaving = bay.leavingBrand[slot]
        if (leaving < 0) {
            val loose = LOOSE[slot]
            val f = if (t >= POP_MILLIS) 1f else softSpring(t / POP_MILLIS.toFloat())
            val lift = sin(Math.PI * f).toFloat() * 40
            return listOf(Pose((loose[0] - BATTERY_CX) * f + lift, (loose[1] - slotY(slot)) * f, loose[2] * f, brand))
        }
        val poses = ArrayList<Pose>()
        if (t < FLY_MILLIS) {
            val f = (t / FLY_MILLIS.toFloat()).pow(2)
            poses.add(Pose(OFF_DX * f, -40 * f, 30 * f, leaving))
        }
        val arrive = t - FLY_MILLIS
        if (arrive >= 0) {
            val f = if (arrive >= ARRIVE_MILLIS) 1f else softSpring(arrive / ARRIVE_MILLIS.toFloat())
            poses.add(Pose(OFF_DX + (pop[0] - OFF_DX) * f, 30 + (pop[1] - 30) * f, -20 + (pop[2] + 20) * f, brand))
        }
        return poses
    }

    private fun batterySettled(slot: Int, now: Long): Boolean {
        val bay = app.batteries
        val t = now - bay.changedAt[slot]
        return when {
            bay.installed[slot] -> t >= INSERT_MILLIS
            bay.leavingBrand[slot] >= 0 -> t >= FLY_MILLIS + ARRIVE_MILLIS
            else -> t >= POP_MILLIS
        }
    }

    private fun batteriesMoving(now: Long) = (0 until BatteryBay.SLOTS).any { !batterySettled(it, now) }

    private fun battery(c: Canvas, slot: Int, now: Long) {
        val cy = slotY(slot)
        for (pose in batteryPoses(slot, now)) {
            c.save()
            c.translate(BATTERY_CX + pose.dx, cy + pose.dy)
            c.rotate(pose.angle)
            if (pose.dx != 0f || pose.dy != 0f) rect(c, -BATTERY_HALF + 5, -BATTERY_RADIUS + 8, BATTERY_HALF + 3, BATTERY_RADIUS + 10, 6f, 0x40000000)
            batteryBody(c, pose.brand, slot % 2 == 0, app.batteries.roll[slot])
            c.restore()
        }
    }

    private fun batteryBody(c: Canvas, brand: Int, plusRight: Boolean, roll: Float) {
        val r = BATTERY_RADIUS
        val half = BATTERY_HALF
        c.save()
        if (!plusRight) c.rotate(180f)
        paint.color = WHITE; paint.style = Paint.Style.FILL
        val nub = r * 0.34f
        paint.shader = LinearGradient(0f, -nub, 0f, nub, METAL_STOPS, METAL_POSITIONS, Shader.TileMode.CLAMP)
        c.drawRoundRect(half - 5, -nub, half, nub, 1.5f, 1.5f, paint)
        paint.shader = LinearGradient(0f, -r, 0f, r, METAL_STOPS, METAL_POSITIONS, Shader.TileMode.CLAMP)
        c.drawRect(-half, -r + 0.5f, -half + 3, r - 0.5f, paint)
        c.drawRect(half - 9, -r + 1, half - 5, r - 1, paint)
        paint.shader = null
        val sleeve = sleeves[brand] ?: BitmapFactory.decodeResource(resources, SLEEVES[brand]).also { sleeves[brand] = it }
        paint.isFilterBitmap = true
        val strip = 2 * r / ROLL_STRIPS
        for (k in 0 until ROLL_STRIPS) {
            val top = -r + k * strip
            val v0 = wrap(top, r, roll)
            var v1 = wrap(top + strip, r, roll)
            if (v1 < v0) v1 = 2f
            val copy = if (v0 >= 1f) 1f else 0f
            val end = if (v1 > copy + 1f) copy + 1f else v1
            sleeveSource.set(0, ((v0 - copy) * sleeve.height).toInt(), sleeve.width, max(((v0 - copy) * sleeve.height).toInt() + 1, ((end - copy) * sleeve.height).roundToInt()))
            sleeveTarget.set(-half + 3, top, half - 9, top + strip + 0.6f)
            c.drawBitmap(sleeve, sleeveSource, sleeveTarget, paint)
        }
        c.restore()
        paint.color = WHITE
        paint.shader = LinearGradient(0f, -r, 0f, r, CYLINDER_STOPS, CYLINDER_POSITIONS, Shader.TileMode.CLAMP)
        c.drawRect(if (plusRight) -half else -half + 9, -r, if (plusRight) half - 9 else half, r, paint)
        paint.shader = null
        line(c, -half + 6, -r * 0.56f, half - 12, -r * 0.56f, 0x90ffffff.toInt(), 2.2f)
        line(c, -half + 10, -r * 0.62f, -half + 60, -r * 0.62f, 0x60ffffff, 1.2f)
    }

    private fun wrap(y: Float, r: Float, roll: Float): Float {
        val angle = Math.toDegrees(kotlin.math.asin((y / r).coerceIn(-1f, 1f).toDouble())).toFloat() + roll
        return ((angle + 90) % 360 + 360) % 360 / 180f
    }

    private fun onBattery(x: Float, y: Float, slot: Int, pose: Pose): Boolean {
        val rad = Math.toRadians(-pose.angle.toDouble())
        val dx = x - BATTERY_CX - pose.dx
        val dy = y - slotY(slot) - pose.dy
        val lx = dx * cos(rad) - dy * sin(rad)
        val ly = dx * sin(rad) + dy * cos(rad)
        return abs(lx) <= BATTERY_HALF + 6 && abs(ly) <= BATTERY_RADIUS + 6
    }

    fun backTap(x: Float, y: Float) {
        val bay = app.batteries
        val now = SystemClock.uptimeMillis()
        if (peelTap(x, y)) return
        if (now - bay.lidChangedAt < LID_MILLIS) return
        val inCover = x in COVER_L..COVER_R && y in COVER_T..COVER_B
        if (!bay.lidOpen) { if (inCover) app.batteryLid() else app.cancel(); return }
        val rad = Math.toRadians(-REST_ANGLE.toDouble())
        val lx = (x - REST_X) * cos(rad) - (y - REST_Y) * sin(rad)
        val ly = (x - REST_X) * sin(rad) + (y - REST_Y) * cos(rad)
        for (slot in BatteryBay.SLOTS - 1 downTo 0) {
            if (!batterySettled(slot, now) || bay.installed[slot]) continue
            if (onBattery(x, y, slot, batteryPoses(slot, now).last())) { app.batteryPress(slot); return }
        }
        if (abs(lx) <= (COVER_R - COVER_L) / 2 && abs(ly) <= (COVER_B - COVER_T) / 2) { app.batteryLid(); return }
        for (slot in 0 until BatteryBay.SLOTS) {
            if (!batterySettled(slot, now)) continue
            val cy = slotY(slot)
            if (bay.installed[slot]) {
                if (onBattery(x, y, slot, Pose(0f, 0f, 0f, 0))) { app.batteryPress(slot); return }
            } else if (slot >= SWITCH_SLOT && x in SWITCH_L - 8..SWITCH_R + 8 && abs(y - cy) <= 16) { app.caseSwitch(slot - SWITCH_SLOT); return }
        }
        if (inCover) return
        app.cancel()
    }

    private fun rib(c: Canvas, from: Float, to: Float, y: Float, height: Float, deep: Int, light: Int) {
        rect(c, from, y, to, y + height, 1.5f, deep)
        line(c, from + 1, y + 0.8f, to - 1, y + 0.8f, tint(deep, BLACK, 0.25f), 1.4f)
        line(c, from + 1, y + height, to - 1, y + height, light, 1f)
    }

    private fun plain(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, font: Typeface) {
        paint.color = color; paint.textSize = size; paint.typeface = font; paint.style = Paint.Style.FILL
        c.drawText(value, x, y, paint)
    }

    private fun measure(value: String, font: Typeface, size: Float): Float { paint.typeface = font; paint.textSize = size; return paint.measureText(value) }

    private fun dotted(c: Canvas, from: Float, to: Float, y: Float, color: Int) {
        var x = from + 2
        while (x < to) { circle(c, x, y, 0.6f, (color and 0xffffff) or 0x80000000.toInt()); x += 4 }
    }

    private fun binSymbol(c: Canvas, x: Float, y: Float, ink: Int) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f; paint.color = ink
        stroke(c, x, y, 1f, true, -5f, -6f, 5f, -6f, 4f, 7f, -4f, 7f)
        paint.style = Paint.Style.FILL
        line(c, x - 7, y - 7.5f, x + 7, y - 7.5f, ink, 1.4f)
        line(c, x - 8, y - 10, x + 8, y + 9, ink, 1.3f)
        line(c, x + 8, y - 10, x - 8, y + 9, ink, 1.3f)
    }

    private fun embossed(c: Canvas, value: String, x: Float, y: Float, size: Float, body: Int) {
        paint.typeface = monoBold; paint.textSize = size
        val w = paint.measureText(value)
        plain(c, value, x - w / 2, y + 0.8f, size, tint(body, WHITE, 0.4f), monoBold)
        text(c, value, x - w / 2, y, size, tint(body, BLACK, 0.22f), monoBold)
    }

    private fun recess(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float, deep: Int, light: Int) {
        rect(c, left, top, right, bottom, radius, light)
        rect(c, left, top, right - 1.5f, bottom - 1.5f, radius, tint(deep, BLACK, 0.25f))
        rect(c, left + 2.5f, top + 3, right - 1, bottom - 1, radius - 1, deep)
    }

    private fun recessedScrew(c: Canvas, x: Float, y: Float, body: Int, index: Int) {
        circle(c, x, y + 0.8f, 11f, tint(body, WHITE, 0.35f))
        circle(c, x, y, 11f, tint(body, BLACK, 0.45f))
        paint.shader = RadialGradient(x, y - 3, 11f, 0xff141414.toInt(), tint(body, BLACK, 0.3f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, 10f, paint); paint.shader = null
        circle(c, x, y + 1.6f, 4.6f, 0xff5a5b57.toInt())
        circle(c, x, y + 1.2f, 4.2f, 0xff86877f.toInt())
        c.save(); c.rotate(SCREW_ANGLES[index % SCREW_ANGLES.size] * 2, x, y + 1.2f)
        line(c, x - 2.8f, y + 1.2f, x + 2.8f, y + 1.2f, 0xff2d2e2b.toInt(), 1f)
        line(c, x, y - 1.6f, x, y + 4, 0xff2d2e2b.toInt(), 1f)
        c.restore()
    }

    private fun screen(c: Canvas) {
        rect(c, ROW_LEFT, 34f, ROW_RIGHT, 235f, 7f, if (app.darkTheme) 0xff74766a.toInt() else 0xffb2b1a5.toInt())
        rect(c, 20f, 36f, 380f, 231f, 5f, INK)
        rect(c, 27f, 43f, 373f, 224f, 2f, LCD_DARK)
        if (!app.powered()) return
        c.save(); c.clipRect(SCREEN_LEFT, SCREEN_TOP, SCREEN_RIGHT, SCREEN_BOTTOM)
        drawingScreen = true
        text(c, String.format(Locale.US, "%03d BPM", app.sequencer.bpm()), LCD_LEFT, 61f, LCD_TEXT, LCD, mono)
        paint.textSize = 10f; paint.typeface = monoBold
        val meter = app.batteries.useBatteries
        val headerWidth = (if (app.sequencer.isPlaying()) 213f else 234f) - (if (meter) 30f else 0f)
        val header = TextUtils.ellipsize(app.syncHeader(), paint, headerWidth, TextUtils.TruncateAt.END).toString()
        text(c, header, 130f, 61f, 10f, if (app.mode == Mode.RECORDING) ORANGE else LCD, monoBold)
        val pageRight = if (meter) 333f else 363f
        if (app.sequencer.isPlaying() && app.sequencer.playingPage() >= 0) rect(c, pageRight - 10, 50f, pageRight, 60f, 1f, app.sequencer.page(app.sequencer.playingPage()).color)
        if (meter) batteryMeter(c)
        line(c, 35f, 69f, 365f, 69f, 0xff42503b.toInt(), 1f)
        if (app.busy) {
            text(c, "WORKING", LCD_LEFT, 108f, 22f, LCD, mono)
            wrapped(c, app.message, LCD_LEFT, 138f, 38, 11f, LCD)
        } else when (app.mode) {
            Mode.HOME -> home(c)
            Mode.PAGES -> {
                if (app.pagePrompt) {
                    text(c, MainActivity.PAGE_ACTIONS[app.selection].uppercase(Locale.US), LCD_LEFT, LCD_TITLE_Y, LCD_TITLE_TEXT, LCD, mono)
                    text(c, app.pagePromptText(), LCD_LEFT, 150f, 20f, LCD, monoBold)
                } else menu(c, MainActivity.PAGE_ACTIONS)
                val hint = when {
                    !app.pagePrompt -> "PRESS: OPEN PAGE  HOLD: ON / OFF"
                    app.selection == 1 -> if (app.swapSource < 0) "PRESS FIRST PAGE" else "SWAP " + (app.swapSource + 1) + " / PRESS SECOND"
                    else -> "PRESS A PAD"
                }
                text(c, hint, LCD_LEFT, LCD_HINT_Y, LCD_TEXT, LCD, mono)
            }
            Mode.PAGE_COPIED ->
                if (SystemClock.uptimeMillis() - app.copiedAt < COPIED_MILLIS) value(c, "COPY PAGE", String.format(Locale.US, "COPIED %02d", app.pageClipboardSource + 1), "READY TO PASTE")
                else value(c, "PASTE PAGE", "SELECT PAD", String.format(Locale.US, "TO PASTE PAGE %02d TO", app.pageClipboardSource + 1))
            Mode.PAGE_PASTE -> value(c, "PASTE PAGE", String.format(Locale.US, "PASTE %02d → %02d?", app.pageClipboardSource + 1, app.pasteTarget + 1), "REPLACES ITS PATTERN AND EFFECTS")
            Mode.PAGE_RESET -> value(c, "RESET PAGE", String.format(Locale.US, "RESET PAGE %02d?", app.pasteTarget + 1), "CLEARS ITS PATTERN AND EFFECTS")
            Mode.MENU -> menu(c, MainActivity.MENU)
            Mode.ADD_SAMPLE -> menu(c, MainActivity.ADD_SAMPLE)
            Mode.DEVICE_RECORD -> deviceRecording(c)
            Mode.SYSTEM -> menu(c, app.systemItems())
            Mode.EXPORT -> menu(c, MainActivity.EXPORT_ITEMS)
            Mode.EXPORT_LOOPS -> {
                menu(c, app.exportLoopItems())
                wrapped(c, "Some pads only play on later loops.", LCD_LEFT, 160f, 38, 11f, LCD)
            }
            Mode.BACK -> {}
            Mode.SYNC -> {
                menu(c, app.syncItems())
                text(c, app.syncStatus(), LCD_LEFT, 173f, LCD_TITLE_TEXT, LCD, monoBold)
                if (app.clapClock != null) {
                    text(c, "KEEP PHONES CLOSE / USE SAME BPM", LCD_LEFT, 193f, LCD_TEXT, LCD, mono)
                    text(c, "BPM CHANGES REJOIN THE NEXT LOOP", LCD_LEFT, LCD_HINT_Y, LCD_SMALL_TEXT, LCD, mono)
                }
            }
            Mode.SYNC_CLAP -> {
                wrapped(c, app.clapStatus, LCD_LEFT, 100f, 29, if (app.clapClock != null) 18f else 15f, LCD)
                if (clapReady()) {
                    val t = (0.5 - 0.5 * cos(SystemClock.uptimeMillis() * 2 * Math.PI / PULSE_MILLIS)).toFloat()
                    plain(c, "READY,", LCD_LEFT, 100f, 15f, lerpColor(0xffaee08a.toInt(), 0xff5cff72.toInt(), t), mono)
                }
                if (app.clapClock == null) {
                    wrapped(c, "Keep phones close. Use the same BPM. Wait until all phones are ready, then clap once to sync everyone.", LCD_LEFT, 150f, 40, 10f, LCD)
                    text(c, "SELECT: CONTINUE / RETRY  RETURN: EXIT", LCD_LEFT, LCD_HINT_Y, LCD_SMALL_TEXT, LCD, mono)
                }
            }
            Mode.PROJECTS -> menu(c, MainActivity.PROJECT_ITEMS)
            Mode.PROJECT_OPEN, Mode.PROJECT_EDIT -> tapeBrowser(c, app.mode == Mode.PROJECT_OPEN)
            Mode.PROJECT_EDIT_MENU -> menu(c, MainActivity.PROJECT_EDIT_ITEMS)
            Mode.PROJECT_COLOR -> colorPicker(c)
            Mode.PROJECT_DELETE -> {
                text(c, "DELETE TAPE?", LCD_LEFT, 99f, 17f, ORANGE, monoBold)
                wrapped(c, "Erases " + app.carouselName() + " with its sample tape and sequences.", LCD_LEFT, 128f, 38, 11f, LCD)
                text(c, "SELECT: DELETE  RETURN: KEEP", LCD_LEFT, LCD_HINT_Y, LCD_TEXT, LCD, mono)
            }
            Mode.EFFECTS -> if (app.effectGroup < 0) effectStack(c) else menu(c, app.effectItems(), EFFECT_COLORS[app.effectGroup], null)
            Mode.FX_ADD -> menu(c, app.addItems(), LCD, app.addGroups())
            Mode.FX_REORDER -> effectStack(c)
            Mode.VIBRATION -> value(c, "VIBRATION INTENSITY", if (app.vibrationIntensity == 0) "OFF" else app.vibrationIntensity.toString() + "%", "TURN TO FEEL / SELECT TO SAVE")
            Mode.SENSITIVITY -> value(c, "WHEEL SENSITIVITY", app.wheelSensitivity.toString() + "%", "${MainActivity.MIN_WHEEL}–${MainActivity.MAX_WHEEL}% / SELECT TO SAVE")
            Mode.TEMPO -> menu(c, app.tempoItems())
            Mode.BPM -> value(c, "SEQUENCER TEMPO", app.sequencer.bpm().toString(), if (app.clapClock != null) "BPM / CHANGE REJOINS NEXT LOOP" else "BPM / ${Sequencer.MIN_BPM}–${Sequencer.MAX_BPM}")
            Mode.SWING -> value(c, "SIXTEENTH NOTE SWING", app.sequencer.swing().toString() + "%", Sequencer.MIN_SWING.toString() + "-" + Sequencer.MAX_SWING + "% / ${Sequencer.DEFAULT_SWING}% STRAIGHT")
            Mode.FX_VALUE -> {
                val accent = if (app.effectGroup >= 0) EFFECT_COLORS[app.effectGroup] else LCD
                if (shifting()) shiftTimeline(c, accent)
                else {
                    value(c, MainActivity.FX_LABELS[app.effectParameter], app.effectDisplay(app.effectParameter, app.effectValue).uppercase(Locale.US), app.effectHint(app.effectParameter), accent)
                    effectVisual(c, accent)
                }
            }
            Mode.IMPORT_START, Mode.IMPORT_END -> sourceTimeline(c)
            else -> timeline(c)
        }
        if (showsTapeIcon()) tapeIcon(c, 364f, 200f, tapeFraction())
        drawingScreen = false
        c.restore()
    }

    private fun colorPicker(c: Canvas) {
        text(c, "TAPE COLOR", LCD_LEFT, LCD_TITLE_Y, LCD_TITLE_TEXT, LCD, mono)
        rightText(c, MainActivity.CASSETTE_NAMES[app.selection].uppercase(Locale.US), LCD_RIGHT, LCD_TITLE_Y, LCD_TITLE_TEXT, LCD, monoBold)
        val size = 20f
        val gap = 4f
        var index = 0
        for (row in MainActivity.CASSETTE_ROWS.indices) {
            val count = MainActivity.CASSETTE_ROWS[row]
            val left = 36f
            val y = 108 + row * (size + gap)
            for (k in 0 until count) {
                val x = left + k * (size + gap)
                if (index == app.selection) rect(c, x - 2.5f, y - 2.5f, x + size + 2.5f, y + size + 2.5f, 3f, LCD)
                rect(c, x, y, x + size, y + size, 2f, MainActivity.CASSETTE_COLORS[index])
                if (index == app.selection) box(c, x, y, x + size, y + size, LCD_DARK, 1.2f)
                index++
            }
        }
        text(c, "TURN: PICK  SELECT: " + (if (app.colorForNew) "LOAD NEW TAPE" else "SAVE"), LCD_LEFT, LCD_HINT_Y, LCD_TEXT, LCD, mono)
    }

    private fun tapeBrowser(c: Canvas, open: Boolean) {
        val list = app.browseList()
        if (list.isEmpty()) return
        val index = min(app.selection, list.size - 1)
        val loaded = list[index].id == app.session.id
        text(c, if (open) "OPEN TAPE" else "EDIT TAPE", LCD_LEFT, LCD_TITLE_Y, LCD_TITLE_TEXT, LCD, mono)
        rightText(c, String.format(Locale.US, "%d / %d", index + 1, list.size), LCD_RIGHT, LCD_TITLE_Y, LCD_TEXT, LCD, mono)
        text(c, app.carouselName().uppercase(Locale.US), LCD_LEFT, 140f, 20f, LCD, monoBold)
        if (loaded) text(c, "IN THE DECK", LCD_LEFT, 164f, LCD_TEXT, ORANGE, monoBold)
        text(c, if (open) "TURN: BROWSE  SELECT: LOAD" else "TURN: BROWSE  SELECT: EDIT", LCD_LEFT, LCD_HINT_Y, LCD_TEXT, LCD, mono)
    }

    private fun home(c: Canvas) {
        val track = app.sequencer.tracks[app.track]
        text(c, "TRACK " + (app.track + 1), LCD_LEFT, LCD_TITLE_Y, 14f, LCD, monoBold)
        rightText(c, if (track.mute) "MUTED" else "SAMPLE SLICE", LCD_RIGHT, LCD_TITLE_Y, LCD_SMALL_TEXT, if (track.mute) ORANGE else LCD, mono)
        val span = app.timelineSpan().toInt()
        val from = app.homeTimelineStart()
        wave(c, from, from + span, track.start, track.end, 109f, 179f)
        text(c, MainActivity.seconds(track.start.toLong()) + " → " + MainActivity.seconds(track.end.toLong()) + " s", LCD_LEFT, LCD_HINT_Y, LCD_TITLE_TEXT, LCD, mono)
    }

    private fun menu(c: Canvas, items: Array<String>, accent: Int = LCD, groups: IntArray? = null) {
        val first = max(0, min(app.selection - MENU_ROWS / 2, items.size - MENU_ROWS))
        for (i in first until min(items.size, first + MENU_ROWS)) {
            val y = MENU_TOP + (i - first) * MENU_PITCH
            val selected = i == app.selection
            val left = if (groups != null) STACK_LEFT else 34f
            val right = if (groups != null) STACK_LEFT + STACK_WIDTH else 366f
            if (selected) rect(c, left, y - 13, right, y + 4, if (groups != null) STACK_RADIUS else 1f, accent)
            text(c, (if (selected) "> " else "  ") + items[i], left + 5, y, 13f, if (selected) LCD_DARK else accent, if (selected) monoBold else mono)
            if (groups != null && i < groups.size) effectIcon(c, groups[i], right - STACK_CELL / 2, y - 4.5f, if (selected) LCD_DARK else EFFECT_COLORS[groups[i]], 0.75f)
        }
    }

    private fun effectStack(c: Canvas) {
        val stack = app.effectStack()
        val ordering = app.mode == Mode.FX_REORDER
        val rows = stack.size + (if (ordering) 0 else 1)
        val first = stackFirst(rows)
        val split = if (ordering || splitGroup < 0) 1f else (SystemClock.uptimeMillis() - splitStart) / SPLIT_MILLIS.toFloat()
        if (split >= 1) splitGroup = -1
        val splitting = splitGroup >= 0
        var closing = max(0f, (split - SPLIT_PAUSE) / (1 - SPLIT_PAUSE))
        closing = 1 - (1 - closing) * (1 - closing)
        if (splitting) { c.save(); c.clipRect(SCREEN_LEFT, 72f, SCREEN_RIGHT, 200f) }
        val phase = SystemClock.uptimeMillis() * 2 * Math.PI / PULSE_MILLIS
        for (i in first until min(rows, first + STACK_ROWS)) {
            var y = STACK_TOP + (i - first) * STACK_PITCH
            if (splitting) {
                val from = STACK_TOP + ((if (i < splitIndex) i else i + 1) - splitFirst) * STACK_PITCH
                y = from + (y - from) * closing
            }
            val selected = i == app.selection
            if (i == stack.size) { addRow(c, y, selected, if (stack.isNotEmpty()) ADD_INSET else if (splitting) ADD_INSET * (1 - closing) else 0f); continue }
            val group = stack[i]
            val movable = app.canReorder(group)
            val held = group == app.effectHeld
            val sway = if (ordering && movable && !held) Math.round(sin(phase + i * 0.9) * 2) * 2f else 0f
            effectRow(c, STACK_LEFT + sway, y, group, i, selected, held)
            if (ordering && !movable) lockIcon(c, STACK_LEFT - 12, y + STACK_HEIGHT / 2, EFFECT_COLORS[group], 1f)
        }
        if (splitting) { splitRow(c, split); c.restore() }
        if (ordering) {
            val movable = app.selection < stack.size && app.canReorder(stack[app.selection])
            if (app.effectHeld >= 0 || movable) text(c, if (app.effectHeld >= 0) "TURN: MOVE  SELECT: DROP" else "SELECT: PICK UP", LCD_LEFT, 216f, LCD_TEXT, LCD, mono)
            rightText(c, "RETURN", 345f, LCD_FOOTER_Y, 10f, LCD, mono)
            wheelGlyph(c, 357f, LCD_FOOTER_Y - FOOTER_GLYPH_RISE, -90f, LCD)
            val notice = if (app.selection < stack.size && stack[app.selection] == lockGroup) lockNotice() else null
            if (notice != null) lockPopup(c, MainActivity.lockTitle(lockGroup), notice)
        }
    }

    fun effectLocked(group: Int) {
        lockGroup = group
        lockUntil = SystemClock.uptimeMillis() + LOCK_NOTICE_MILLIS
        invalidate()
    }

    fun lockNotice(): String? {
        if (lockGroup < 0 || SystemClock.uptimeMillis() >= lockUntil) return null
        return if (Sequencer.Effects.movable(lockGroup)) MainActivity.ALONE_REASON else MainActivity.FX_LOCK_REASONS[lockGroup]
    }

    private fun lockPopup(c: Canvas, title: String, notice: String) {
        paint.typeface = mono; paint.textSize = 9f
        val popupWidth = max(paint.measureText(notice), paint.measureText(title) * 1.12f) + 34
        val left = UI_CENTER_X - popupWidth / 2
        val right = UI_CENTER_X + popupWidth / 2
        rect(c, left, 140f, right, 180f, STACK_RADIUS, LCD_DARK)
        box(c, left, 140f, right, 180f, LCD, 1.2f)
        lockIcon(c, left + 12, 154f, LCD, 1f)
        text(c, title, left + 24, 157f, 10f, LCD, monoBold)
        text(c, notice, left + 24, 172f, 9f, LCD, mono)
    }

    private fun lockIcon(c: Canvas, x: Float, y: Float, color: Int, s: Float) {
        rect(c, x - 4 * s, y - 1 * s, x + 4 * s, y + 5 * s, 0.5f, color)
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.3f
        stroke(c, x, y, s, false, -2.5f, -1f, -2.5f, -4.5f, 2.5f, -4.5f, 2.5f, -1f)
        paint.style = Paint.Style.FILL
    }

    private fun stackFirst(rows: Int) = max(0, min(app.selection - 1, rows - STACK_ROWS))

    fun effectDeleting(row: Int) {
        val stack = app.effectStack()
        splitGroup = stack[row]; splitIndex = row
        splitFirst = stackFirst(stack.size + 1)
        splitStart = SystemClock.uptimeMillis()
    }

    fun cancelEffectDeleting() { splitGroup = -1 }

    private fun splitRow(c: Canvas, t: Float) {
        val y = STACK_TOP + (splitIndex - splitFirst) * STACK_PITCH
        val bottom = y + STACK_HEIGHT
        val middle = y + STACK_HEIGHT / 2
        val seam = STACK_LEFT + STACK_WIDTH - STACK_CELL
        val travel = 260 * t * t
        splitHalf(c, -travel, STACK_LEFT - 4, seam, y)
        splitHalf(c, travel, seam, STACK_LEFT + STACK_WIDTH + 4, y)
        if (t < 0.35f) line(c, seam, y - 4, seam, bottom + 4, tint(EFFECT_COLORS[splitGroup], WHITE, 0.5f), 3 * (1 - t / 0.35f))
        val color = EFFECT_COLORS[splitGroup]
        val size = 3 * (1 - t)
        for (i in 0 until 8) {
            val sparkX = seam + (i - 3.5f) * 26 * t
            val sparkY = middle + (if (i % 2 == 0) -1 else 1) * (10 + i % 3 * 7) * t * 2 + 30 * t * t
            rect(c, sparkX - size / 2, sparkY - size / 2, sparkX + size / 2, sparkY + size / 2, 0f, color)
        }
    }

    private fun splitHalf(c: Canvas, offset: Float, left: Float, right: Float, y: Float) {
        c.save()
        c.translate(offset, 0f)
        c.clipRect(left, y - 1, right, y + STACK_HEIGHT + 1)
        effectRow(c, STACK_LEFT, y, splitGroup, splitIndex, true, false, true)
        c.restore()
    }

    private fun effectRow(c: Canvas, x: Float, y: Float, group: Int, index: Int, selected: Boolean, held: Boolean, solid: Boolean = false) {
        val color = EFFECT_COLORS[group]
        val ink = if (selected) LCD_DARK else color
        val right = x + STACK_WIDTH
        val bottom = y + STACK_HEIGHT
        val cell = right - STACK_CELL
        if (selected) {
            rect(c, x, y, right, bottom, STACK_RADIUS, color)
            if (!solid) rect(c, cell, y + 1, right - 1, bottom - 1, 1f, LCD_DARK)
        } else {
            box(c, x, y, right, bottom, color, 1.2f)
            c.save(); c.clipRect(cell, y, right, bottom)
            rect(c, x, y, right, bottom, STACK_RADIUS, color)
            c.restore()
        }
        if (held) box(c, x - 3, y - 2, right + 3, bottom + 2, 0xfff6ffd2.toInt(), 1f)
        text(c, String.format(Locale.US, "%02d", index + 1), x + 6, y + 16, 8f, ink, mono)
        text(c, MainActivity.FX_NAMES[group].uppercase(Locale.US), x + 24, y + 17, 12f, ink, monoBold)
        effectIcon(c, group, right - STACK_CELL / 2, y + STACK_HEIGHT / 2, if (selected && !solid) color else LCD_DARK, 1f)
    }

    private fun addRow(c: Canvas, y: Float, selected: Boolean, inset: Float) {
        val x = STACK_LEFT + inset
        val right = STACK_LEFT + STACK_WIDTH
        val bottom = y + STACK_HEIGHT
        val cell = right - STACK_CELL
        if (selected) rect(c, x, y, right, bottom, STACK_RADIUS, LCD)
        else {
            box(c, x, y, right, bottom, LCD, 1.2f)
            c.save(); c.clipRect(cell, y, right, bottom)
            rect(c, x, y, right, bottom, STACK_RADIUS, LCD)
            c.restore()
        }
        if (selected) line(c, cell, y, cell, bottom, LCD_DARK, 1.2f)
        text(c, "ADD EFFECT", STACK_LEFT + 24 + inset / 2, y + 17, 12f, if (selected) LCD_DARK else LCD, monoBold)
        val middleX = right - STACK_CELL / 2
        val middleY = y + STACK_HEIGHT / 2
        line(c, middleX - 6, middleY, middleX + 6, middleY, LCD_DARK, 2.4f)
        line(c, middleX, middleY - 6, middleX, middleY + 6, LCD_DARK, 2.4f)
    }

    private fun wheelGlyph(c: Canvas, x: Float, y: Float, direction: Float, color: Int) {
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f
        c.drawCircle(x, y, 6.5f, paint)
        paint.style = Paint.Style.FILL
        c.drawArc(x - 6.5f, y - 6.5f, x + 6.5f, y + 6.5f, direction - 45, 90f, true, paint)
        circle(c, x, y, 2.4f, LCD_DARK)
    }

    private fun box(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int, width: Float) {
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = width
        c.drawRoundRect(left, top, right, bottom, STACK_RADIUS, STACK_RADIUS, paint)
        paint.style = Paint.Style.FILL
    }

    private fun stroke(c: Canvas, x: Float, y: Float, scale: Float, closed: Boolean, vararg points: Float) {
        iconPath.reset()
        iconPath.moveTo(x + points[0] * scale, y + points[1] * scale)
        for (i in 2 until points.size step 2) iconPath.lineTo(x + points[i] * scale, y + points[i + 1] * scale)
        if (closed) iconPath.close()
        c.drawPath(iconPath, paint)
    }

    private fun effectIcon(c: Canvas, group: Int, x: Float, y: Float, color: Int, s: Float) {
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f; paint.strokeCap = Paint.Cap.SQUARE
        when (group) {
            Sequencer.Effects.SAMPLE -> {
                stroke(c, x, y, s, false, -6f, -6f, -1f, 0f, -6f, 6f)
                stroke(c, x, y, s, false, 1f, -6f, 6f, 0f, 1f, 6f)
            }
            Sequencer.Effects.WOBBLE -> stroke(c, x, y, s, false, -8f, 0f, -6.5f, -4f, -4f, -5.5f, -1.5f, -4f, 0f, 0f, 1.5f, 4f, 4f, 5.5f, 6.5f, 4f, 8f, 0f)
            Sequencer.Effects.CHANCE -> {
                stroke(c, x, y, s, true, -6.5f, -6.5f, 6.5f, -6.5f, 6.5f, 6.5f, -6.5f, 6.5f)
                paint.style = Paint.Style.FILL
                c.drawRect(x - 4 * s, y - 4 * s, x - 1.5f * s, y - 1.5f * s, paint)
                c.drawRect(x - 1.25f * s, y - 1.25f * s, x + 1.25f * s, y + 1.25f * s, paint)
                c.drawRect(x + 1.5f * s, y + 1.5f * s, x + 4 * s, y + 4 * s, paint)
                paint.style = Paint.Style.STROKE
            }
            Sequencer.Effects.EVERY -> {
                stroke(c, x, y, s, false, -7f, 5f, -7f, 2f)
                stroke(c, x, y, s, false, -2.5f, 5f, -2.5f, 2f)
                stroke(c, x, y, s, false, 2f, 5f, 2f, 2f)
                stroke(c, x, y, s, false, 6.5f, 5f, 6.5f, -6f)
            }
            Sequencer.Effects.PITCH -> {
                stroke(c, x, y, s, false, 0f, -6f, 0f, 6f)
                stroke(c, x, y, s, false, -4f, -3f, 0f, -7f, 4f, -3f)
                stroke(c, x, y, s, false, -4f, 3f, 0f, 7f, 4f, 3f)
            }
            Sequencer.Effects.AUTOTUNE -> stroke(c, x, y, s, false, -7f, 6f, -3f, 6f, -3f, 2f, 1f, 2f, 1f, -2f, 5f, -2f, 5f, -6f, 7f, -6f)
            Sequencer.Effects.HIGH_PASS -> stroke(c, x, y, s, false, -7f, 6f, 0f, -4f, 8f, -4f)
            Sequencer.Effects.LOW_PASS -> stroke(c, x, y, s, false, -8f, -4f, 0f, -4f, 7f, 6f)
            Sequencer.Effects.DELAY -> {
                stroke(c, x, y, s, false, -6f, -6f, -6f, 6f)
                stroke(c, x, y, s, false, -1f, -4f, -1f, 4f)
                stroke(c, x, y, s, false, 3f, -2.5f, 3f, 2.5f)
                stroke(c, x, y, s, false, 7f, -1f, 7f, 1f)
            }
            Sequencer.Effects.DISTORTION -> stroke(c, x, y, s, false, -8f, 5f, -5f, -5f, -1f, -5f, 2f, 5f, 6f, 5f, 8f, -5f)
            Sequencer.Effects.ENVELOPE -> stroke(c, x, y, s, false, -8f, 6f, -4f, -6f, -1f, 0f, 4f, 0f, 8f, 6f)
            Sequencer.Effects.REVERB -> {
                stroke(c, x, y, s, true, -7f, -6f, 7f, -6f, 7f, 6f, -7f, 6f)
                stroke(c, x, y, s, true, -3.5f, -2.5f, 3.5f, -2.5f, 3.5f, 2.5f, -3.5f, 2.5f)
            }
            Sequencer.Effects.NUDGE -> {
                stroke(c, x, y, s, false, -7f, -6f, -7f, 6f)
                stroke(c, x, y, s, false, -4f, 0f, 7f, 0f)
                stroke(c, x, y, s, false, 3f, -4f, 7f, 0f, 3f, 4f)
            }
            Sequencer.Effects.SWING -> {
                stroke(c, x, y, s, false, -8f, 6f, 8f, 6f)
                stroke(c, x, y, s, false, -6f, 6f, -6f, -2f)
                stroke(c, x, y, s, false, 2f, 6f, 2f, -6f)
                stroke(c, x, y, s, false, 6f, 6f, 6f, -2f)
            }
            Sequencer.Effects.VOLUME -> stroke(c, x, y, s, true, -8f, 6f, 8f, 6f, 8f, -6f)
            Sequencer.Effects.SHIFT -> {
                stroke(c, x, y, s, false, -4f, -7f, -7f, -7f, -7f, 7f, -4f, 7f)
                stroke(c, x, y, s, false, 4f, -7f, 7f, -7f, 7f, 7f, 4f, 7f)
                stroke(c, x, y, s, false, -3f, 0f, 3f, 0f)
                stroke(c, x, y, s, false, 1f, -2f, 3f, 0f, 1f, 2f)
            }
            else -> {
                stroke(c, x, y, s, true, -7f, -6f, 7f, -6f, 7f, 6f, -7f, 6f)
                paint.style = Paint.Style.FILL
                c.drawRect(x, y - 6 * s, x + 7 * s, y + 6 * s, paint)
            }
        }
        paint.strokeCap = Paint.Cap.BUTT; paint.style = Paint.Style.FILL
    }

    private fun value(c: Canvas, title: String, value: String, unit: String, accent: Int = LCD) {
        text(c, title, LCD_LEFT, LCD_TITLE_Y, LCD_TITLE_TEXT, accent, mono)
        var size = if (value.length > LONG_VALUE_CHARS) LONG_VALUE_TEXT else VALUE_TEXT
        if (app.mode == Mode.FX_VALUE && hasVisual(app.effectParameter)) {
            paint.typeface = monoBold; paint.textSize = size
            val room = VISUAL_LEFT - LCD_LEFT - 10
            val width = paint.measureText(value)
            if (width > room) size *= room / width
        }
        text(c, value, LCD_LEFT, 158f, size, accent, monoBold)
        text(c, unit, LCD_LEFT, 202f, LCD_TEXT, accent, mono)
    }

    private fun hasVisual(parameter: Int) = parameter !in intArrayOf(MainActivity.FX_SPEED, MainActivity.FX_NUDGE, MainActivity.FX_TRIM_START, MainActivity.FX_TRIM_END, MainActivity.FX_WOBBLE_MODE)

    private fun effectVisual(c: Canvas, accent: Int) {
        val parameter = app.effectParameter
        if (!hasVisual(parameter)) return
        val fx = app.effectControls()
        val l = VISUAL_LEFT; val r = VISUAL_RIGHT; val t = VISUAL_TOP; val b = VISUAL_BOTTOM
        val w = r - l; val h = b - t
        val faint = tint(accent, LCD_DARK, 0.65f)
        paint.strokeCap = Paint.Cap.ROUND
        when (parameter) {
            MainActivity.FX_HIGH_PASS, MainActivity.FX_LOW_PASS -> {
                val high = parameter == MainActivity.FX_HIGH_PASS
                val cutoff = if (high) fx.highPass else fx.lowPass
                for (decade in intArrayOf(100, 1000, 10000)) {
                    val x = l + w * (ln(decade / 20f) / ln(1000f))
                    line(c, x, t, x, b, faint, 1f)
                }
                curve(c, accent, true) { u ->
                    if (cutoff <= 0) 1f else {
                        val ratio = 20f * 1000f.pow(u) / cutoff
                        val gain = if (high) ratio / sqrt(1 + ratio * ratio) else 1 / sqrt(1 + ratio * ratio)
                        1 + 20 * log10(max(gain, 1e-4f)) / VISUAL_DB
                    }
                }
                if (cutoff > 0) {
                    val x = l + w * (ln(cutoff / 20f) / ln(1000f)).coerceIn(0f, 1f)
                    line(c, x, t - 3, x, b, accent, 1.2f)
                }
            }
            MainActivity.FX_DELAY_TIME, MainActivity.FX_DELAY_FEEDBACK, MainActivity.FX_DELAY_MIX -> {
                val bpm = app.sequencer.bpm()
                val echoes = fx.delayEchoes.coerceIn(1, TrackEffects.MAX_ECHOES)
                val gap = TrackEffects.delayFrames(fx.delayBeat, bpm).toFloat()
                val beat = Tape.RATE * 60f / bpm
                val span = max(gap * (echoes + 1), beat * 4)
                var tick = 0f
                while (tick <= span) { val x = l + 2 + (w - 4) * tick / span; line(c, x, b - 4, x, b, faint, 1f); tick += beat }
                line(c, l, b, r, b, faint, 1f)
                line(c, l + 2, b, l + 2, t, accent, 3f)
                for (k in 1..echoes) {
                    val x = l + 2 + (w - 4) * k * gap / span
                    val level = TrackEffects.echoGain(k, echoes) * fx.delayMix / 100f
                    line(c, x, b, x, b - h * level, accent, 3f)
                }
            }
            MainActivity.FX_DISTORTION, MainActivity.FX_DISTORTION_MIX -> {
                val cx = l + w / 2; val cy = (t + b) / 2; val half = min(w, h) / 2
                line(c, cx - half, cy, cx + half, cy, faint, 1f)
                line(c, cx, t, cx, b, faint, 1f)
                line(c, cx - half, cy + half, cx + half, cy - half, faint, 1f)
                val type = max(1, fx.distortion)
                val mix = fx.distortionMix / 100f
                val path = Path()
                for (i in 0..VISUAL_POINTS) {
                    val x = -1 + 2f * i / VISUAL_POINTS
                    val y = (x + (TrackEffects.shape(x, type, fx.drive) - x) * mix).coerceIn(-1f, 1f)
                    val px = cx + x * half; val py = cy - y * half
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                stroke(c, path, accent, 2f)
            }
            MainActivity.FX_REVERB -> {
                val amount = fx.reverb / 100f
                line(c, l, b, r, b, faint, 1f)
                line(c, l + 2, b, l + 2, t, accent, 3f)
                val random = Random(7)
                var x = l + 7
                while (x < r) {
                    val level = amount * exp(-(x - l) / w * 3.2f) * (0.45f + 0.55f * random.nextFloat())
                    line(c, x, b, x, b - h * level, accent, 1.6f)
                    x += 3.5f
                }
            }
            MainActivity.FX_ATTACK, MainActivity.FX_DECAY, MainActivity.FX_SUSTAIN, MainActivity.FX_RELEASE -> {
                val total = max(1f, (fx.attack + fx.decay + fx.release).toFloat())
                val span = w * 0.72f
                val xa = l + span * fx.attack / total
                val xd = xa + span * fx.decay / total
                val xs = xd + w * 0.28f
                val ys = b - h * fx.sustain / 100f
                line(c, l, b, r, b, faint, 1f)
                stroke(c, Path().apply { moveTo(l, b); lineTo(xa, t); lineTo(xd, ys); lineTo(xs, ys); lineTo(r, b) }, accent, 2f)
                val (from, to) = when (parameter) {
                    MainActivity.FX_ATTACK -> l to xa
                    MainActivity.FX_DECAY -> xa to xd
                    MainActivity.FX_SUSTAIN -> xd to xs
                    else -> xs to r
                }
                rect(c, from, b + 3, max(to, from + 2), b + 5, 1f, accent)
            }
            MainActivity.FX_PITCH -> {
                val shifted = 2f.pow(fx.pitch / 24f)
                line(c, l, (t + b) / 2, r, (t + b) / 2, faint, 0.6f)
                curve(c, faint, false) { u -> 0.5f + 0.4f * sin(u * 4 * PI.toFloat()) }
                curve(c, accent, false) { u -> 0.5f + 0.4f * sin(u * 4 * PI.toFloat() * shifted) }
            }
            MainActivity.FX_AUTO_KEY, MainActivity.FX_AUTO_SCALE -> keyboard(c, fx.autoKey, fx.autoScale, accent, faint)
            MainActivity.FX_AUTO_STRENGTH, MainActivity.FX_AUTO_RETUNE, MainActivity.FX_AUTO_MIX -> {
                for (k in 0..3) line(c, l, t + h * k / 3f, r, t + h * k / 3f, faint, 0.6f)
                val sung = { u: Float -> 0.6f + 1.9f * u + 0.28f * sin(u * 6 * PI.toFloat()) }
                curve(c, faint, false) { u -> sung(u) / 3f }
                val follow = if (fx.autoRetune == 0) 1f else 1 - exp(-VISUAL_AUTOTUNE_MS / VISUAL_POINTS / fx.autoRetune)
                var out = Math.round(sung(0f)).toFloat()
                val path = Path()
                for (i in 0..VISUAL_POINTS) {
                    val u = i / VISUAL_POINTS.toFloat()
                    val sang = sung(u)
                    out += (Math.round(sang) - out) * follow
                    val y = sang + (out - sang) * fx.autoStrength / 100f * fx.autoMix / 100f
                    val px = l + w * u; val py = b - h * (y / 3f).coerceIn(0f, 1f)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                stroke(c, path, accent, 2f)
            }
            MainActivity.FX_WOBBLE_RATE, MainActivity.FX_WOBBLE_DEPTH -> {
                val hertz = fx.wobbleRate / MainActivity.WOBBLE_RATE_PER_HZ
                val depth = fx.wobbleDepth / 100f
                line(c, l, (t + b) / 2, r, (t + b) / 2, faint, 0.6f)
                curve(c, accent, false) { u -> 0.5f + 0.48f * depth * sin(u * 2 * PI.toFloat() * hertz) }
            }
            MainActivity.FX_CHANCE -> {
                val order = intArrayOf(5, 12, 1, 9, 14, 3, 7, 10, 0, 15, 6, 2, 11, 8, 13, 4)
                val lit = Math.round(fx.chance * 16 / 100f)
                for (i in 0 until 16) {
                    val x = l + w * (i % 8 + 0.5f) / 8; val y = t + h * (i / 8 + 0.5f) / 2
                    circle(c, x, y, 4.5f, if (order.indexOf(i) < lit) accent else faint)
                }
            }
            MainActivity.FX_EVERY -> {
                val cells = 8
                val y0 = (t + b) / 2 - 8; val y1 = (t + b) / 2 + 8
                for (i in 0 until cells) {
                    val x0 = l + w * i / cells + 1.5f; val x1 = l + w * (i + 1) / cells - 1.5f
                    if ((i + 1) % max(1, fx.every) == 0) rect(c, x0, y0, x1, y1, 2f, accent) else box(c, x0, y0, x1, y1, faint, 1f)
                }
            }
            MainActivity.FX_SWING -> {
                val pair = w / 4
                for (i in 0 until 8) {
                    val x = l + 3 + pair * (i / 2) + if (i % 2 == 0) 0f else pair * app.effectValue / 100f
                    line(c, x, b, x, if (i % 2 == 0) t + 6 else t + h * 0.45f, if (i % 2 == 0) faint else accent, 3f)
                }
            }
            MainActivity.FX_VOLUME -> {
                rect(c, l, b - 10, r, b, 2f, faint)
                rect(c, l, b - 10, l + w * app.effectValue / 100f, b, 2f, accent)
            }
        }
        paint.strokeCap = Paint.Cap.BUTT; paint.style = Paint.Style.FILL
    }

    private fun curve(c: Canvas, color: Int, fill: Boolean, y: (Float) -> Float) {
        val path = Path()
        for (i in 0..VISUAL_POINTS) {
            val u = i / VISUAL_POINTS.toFloat()
            val px = VISUAL_LEFT + (VISUAL_RIGHT - VISUAL_LEFT) * u
            val py = VISUAL_BOTTOM - (VISUAL_BOTTOM - VISUAL_TOP) * y(u).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        if (fill) {
            val area = Path(path).apply { lineTo(VISUAL_RIGHT, VISUAL_BOTTOM); lineTo(VISUAL_LEFT, VISUAL_BOTTOM); close() }
            paint.color = color; paint.alpha = 60; paint.style = Paint.Style.FILL
            c.drawPath(area, paint)
            paint.alpha = 255
        }
        stroke(c, path, color, 2f)
    }

    private fun stroke(c: Canvas, path: Path, color: Int, width: Float) {
        paint.color = color; paint.strokeWidth = width; paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    private fun keyboard(c: Canvas, key: Int, scale: Int, accent: Int, faint: Int) {
        val whites = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val blacks = intArrayOf(1, 3, -1, 6, 8, 10)
        val l = VISUAL_LEFT; val t = VISUAL_TOP; val b = VISUAL_BOTTOM
        val keyWidth = (VISUAL_RIGHT - VISUAL_LEFT) / whites.size
        val inScale = { note: Int -> PitchCorrector.nearestNote(note.toDouble(), key, scale) == note }
        for (i in whites.indices) {
            val note = whites[i]
            val x0 = l + keyWidth * i + 1; val x1 = l + keyWidth * (i + 1) - 1
            if (inScale(note)) rect(c, x0, t, x1, b, 2f, accent) else box(c, x0, t, x1, b, faint, 1f)
            if (note == key) circle(c, (x0 + x1) / 2, b - 7, 3f, LCD_DARK)
        }
        for (i in blacks.indices) {
            val note = blacks[i]
            if (note < 0) continue
            val cx = l + keyWidth * (i + 1)
            val x0 = cx - keyWidth * 0.32f; val x1 = cx + keyWidth * 0.32f; val y1 = t + (b - t) * 0.58f
            rect(c, x0 - 1.5f, t, x1 + 1.5f, y1 + 1.5f, 2f, LCD_DARK)
            if (inScale(note)) rect(c, x0, t, x1, y1, 2f, accent) else box(c, x0, t, x1, y1, faint, 1f)
            if (note == key) circle(c, cx, y1 - 6, 2.5f, LCD_DARK)
        }
    }

    private fun timeline(c: Canvas) {
        var a = app.start
        var b = app.end
        var position = app.cursor
        when (app.mode) {
            Mode.RECORD_POSITION -> { a = position; b = Tape.LENGTH }
            Mode.RECORDING -> { a = app.recordStart; position = app.recordPosition; b = position }
            Mode.IMPORT_POSITION -> { a = position; b = min(Tape.LENGTH, a + (app.imported?.size ?: 0)) }
            else -> {}
        }
        val span = app.timelineSpan().toInt()
        var from = max(0, min(Tape.LENGTH - span, position - span / 2))
        if (app.mode == Mode.START || app.mode == Mode.END) from = TimelineWindow.start(Tape.LENGTH.toLong(), span.toLong(), position.toLong(), a.toLong(), b.toLong()).toInt()
        wave(c, from, from + span, a, b, 83f, 148f)
        if (app.mode == Mode.IMPORT_POSITION) {
            text(c, MainActivity.seconds(a.toLong()) + " → " + MainActivity.seconds(b.toLong()) + " s", LCD_LEFT, 187f, 13f, app.importColor, monoBold)
            text(c, "LENGTH " + MainActivity.seconds((b - a).toLong()) + "s / OVERWRITE", LCD_LEFT, LCD_HINT_Y, LCD_TEXT, LCD, mono)
        } else {
            text(c, MainActivity.seconds(position.toLong()) + " s", LCD_LEFT, LCD_VALUE_Y, 20f, LCD, monoBold)
            text(c, "RANGE " + MainActivity.seconds(a.toLong()) + " → " + MainActivity.seconds(b.toLong()) + " s", LCD_LEFT, LCD_HINT_Y, LCD_SMALL_TEXT, LCD, mono)
        }
        rightText(c, (if (app.fine) "FINE " else "STEP ") + app.timelineIncrement() * 1000 / Tape.RATE + " ms", LCD_RIGHT, 187f, LCD_SMALL_TEXT, LCD, mono)
    }

    private fun shifting() = app.mode == Mode.FX_VALUE && (app.effectParameter == MainActivity.FX_TRIM_START || app.effectParameter == MainActivity.FX_TRIM_END)

    private fun shiftTimeline(c: Canvas, accent: Int) {
        val track = app.sequencer.tracks[app.track]
        val controls = app.effectControls()
        val a = Sequencer.sliceStart(track, controls)
        val b = Sequencer.sliceEnd(track, controls)
        val margin = MainActivity.MAX_SHIFT_MS * (Tape.RATE / 1000)
        val from = max(0, track.start - margin)
        val to = min(Tape.LENGTH, track.end + margin)
        text(c, MainActivity.FX_LABELS[app.effectParameter], LCD_LEFT, LCD_TITLE_Y, LCD_TITLE_TEXT, accent, mono)
        wave(c, from, to, a, b, 104f, 152f)
        val window = (to - from).toFloat()
        for (edge in intArrayOf(track.start, track.end)) {
            val x = WAVE_LEFT + WAVE_WIDTH * (edge - from) / window
            var y = 104f
            while (y < 152f) { line(c, x, y, x, min(152f, y + 3), tint(accent, LCD_DARK, 0.4f), 1f); y += 6 }
        }
        text(c, app.effectDisplay(app.effectParameter, app.effectValue).uppercase(Locale.US), LCD_LEFT, 186f, 20f, accent, monoBold)
        rightText(c, MainActivity.seconds(a.toLong()) + " → " + MainActivity.seconds(b.toLong()) + " s", LCD_RIGHT, 186f, LCD_SMALL_TEXT, accent, mono)
        text(c, app.effectHint(app.effectParameter), LCD_LEFT, 205f, LCD_TEXT, accent, mono)
    }

    private fun sourceTimeline(c: Canvas) {
        val choosingStart = app.mode == Mode.IMPORT_START
        val source = app.importSource ?: return
        text(c, app.importName, LCD_LEFT, 88f, LCD_SMALL_TEXT, LCD, mono)
        val position = if (choosingStart) app.importStart else app.importEnd
        val span = app.timelineSpan()
        val from = TimelineWindow.start(source.frames, span, position, app.importStart, app.importEnd)
        val to = from + span
        val sourcePeaks = source.wave.waveform(from, to, WAVE_BINS)
        val left = WAVE_LEFT; val width = WAVE_WIDTH; val top = 99f; val bottom = 148f; val middle = (top + bottom) / 2
        val a = left + width * (app.importStart - from) / span
        val b = left + width * (app.importEnd - from) / span
        c.save(); c.clipRect(left - 3, top, left + width + 3, bottom)
        rect(c, max(left, a), top, min(left + width, b), bottom, 0f, 0xff29434b.toInt())
        for (i in sourcePeaks.indices) {
            val x = left + width * i / sourcePeaks.size
            val height = max(0.6f, sourcePeaks[i] * 23)
            line(c, x, middle - height, x, middle + height, 0xff8ad9ec.toInt(), 1.1f)
        }
        selectionEdges(c, a, b, top, bottom, choosingStart)
        c.restore()
        text(c, axisTime(from, span), left, 159f, 7f, LCD, mono)
        rightText(c, axisTime(to, span), LCD_RIGHT, 159f, 7f, LCD, mono)
        text(c, MainActivity.seconds(app.importStart) + " → " + MainActivity.seconds(app.importEnd) + " s", LCD_LEFT, LCD_VALUE_Y, 13f, LCD, monoBold)
        text(c, "LENGTH " + MainActivity.seconds(app.importEnd - app.importStart) + "s", LCD_LEFT, LCD_HINT_Y, LCD_TEXT, LCD, mono)
        rightText(c, "STEP " + MainActivity.seconds(app.timelineIncrement()) + "s", LCD_RIGHT, LCD_VALUE_Y, LCD_SMALL_TEXT, LCD, mono)
    }

    private fun wave(c: Canvas, windowStart: Int, windowEnd: Int, a: Int, b: Int, top: Float, bottom: Float) {
        val now = SystemClock.uptimeMillis()
        val changed = waveVersion != app.waveVersion || waveRegions !== app.regions || waveRegionsVersion != app.regions.revision()
        if (waveTape !== app.tape || waveStart != windowStart || waveEnd != windowEnd || (changed && (app.mode != Mode.RECORDING || now - waveTime > WAVE_REFRESH_MILLIS))) {
            val currentRegions = app.regions
            val regions: ArrayList<SampleRegions.Region>
            synchronized(currentRegions) { regions = currentRegions.snapshot(); waveRegionsVersion = currentRegions.revision() }
            waveRegions = currentRegions
            waveform = ColoredWaveform.build(app.tape, windowStart, windowEnd, WAVE_BINS, regions)
            waveTape = app.tape
            waveVersion = app.waveVersion
            waveStart = windowStart
            waveEnd = windowEnd
            waveTime = now
        }
        val left = WAVE_LEFT
        val width = WAVE_WIDTH
        val window = (windowEnd - windowStart).toFloat()
        val x1 = left + width * (a - windowStart) / window
        val x2 = left + width * (b - windowStart) / window
        c.save()
        c.clipRect(left - 3, top, left + width + 3, bottom)
        if (app.mode == Mode.HOME || app.isTimelineMode() || shifting()) {
            val selectionLeft = max(left, x1)
            val selectionRight = min(left + width, x2)
            if (selectionRight > selectionLeft) rect(c, selectionLeft, top, selectionRight, bottom, 0f,
                if (app.mode == Mode.HOME) 0xff566b3c.toInt() else 0xff39452e.toInt())
        }
        val center = (top + bottom) / 2
        line(c, left, center, left + width, center, 0xff49563e.toInt(), 0.7f)
        for (segment in waveform) {
            val from = left + width * (segment.start - windowStart) / window
            val to = left + width * (segment.end - windowStart) / window
            val height = max(0.6f, segment.peak * (bottom - top) * WAVE_HEIGHT)
            rect(c, from, center - 0.6f, to, center + 0.6f, 0f, segment.color)
            line(c, (from + to) / 2, center - height, (from + to) / 2, center + height, segment.color, min(1.1f, to - from))
        }
        val incomingWave = app.importWave
        if (app.mode == Mode.IMPORT_POSITION && incomingWave != null) {
            val incoming = incomingWave.waveform(0, (app.imported?.size ?: 0).toLong(), WAVE_BINS)
            c.save(); c.clipRect(x1, top, x2, bottom)
            rect(c, x1, center - 0.6f, x2, center + 0.6f, 0f, app.importColor)
            for (i in incoming.indices) {
                val from = x1 + (x2 - x1) * i / incoming.size
                val to = x1 + (x2 - x1) * (i + 1) / incoming.size
                val height = max(0.6f, incoming[i] * (bottom - top) * INCOMING_WAVE_HEIGHT)
                line(c, (from + to) / 2, center - height, (from + to) / 2, center + height, app.importColor, min(1.1f, to - from))
            }
            c.restore()
        }
        if (app.isTimelineMode()) selectionEdges(c, x1, x2, top, bottom, app.mode != Mode.END && app.mode != Mode.RECORDING)
        if (shifting()) selectionEdges(c, x1, x2, top, bottom, app.effectParameter == MainActivity.FX_TRIM_START)
        c.restore()
        text(c, axisTime(windowStart.toLong(), (windowEnd - windowStart).toLong()), left, bottom + 10, 7f, 0xff8e9e7c.toInt(), mono)
        rightText(c, axisTime(windowEnd.toLong(), (windowEnd - windowStart).toLong()), left + width, bottom + 10, 7f, 0xff8e9e7c.toInt(), mono)
    }

    private fun selectionEdges(c: Canvas, start: Float, end: Float, top: Float, bottom: Float, choosingStart: Boolean) {
        line(c, start - 1.5f, top, start - 1.5f, bottom, if (choosingStart) ORANGE else LCD, 1f)
        line(c, end + 1.5f, top, end + 1.5f, bottom, if (choosingStart) LCD else ORANGE, 1f)
    }

    private fun axisTime(frame: Long, span: Long): String =
        String.format(Locale.US, if (span < Tape.RATE) "%.3fs" else "%.1fs", frame / Tape.RATE.toDouble())

    private fun wheel(c: Canvas) {
        circle(c, WHEEL_X, WHEEL_Y + 5, WHEEL_RADIUS + 4, if (app.darkTheme) 0xff343630.toInt() else 0xffb2b0a5.toInt())
        circle(c, WHEEL_X, WHEEL_Y + 1, WHEEL_RADIUS + 3, 0xff30332e.toInt())
        paint.shader = LinearGradient(46f, WHEEL_Y - WHEEL_RADIUS, 354f, WHEEL_Y + WHEEL_RADIUS, 0xff55584e.toInt(), 0xff292d27.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(WHEEL_X, WHEEL_Y, WHEEL_RADIUS - 2, paint); paint.shader = null
        for (i in 0 until WHEEL_TICKS) {
            val rad = Math.toRadians(i * 360.0 / WHEEL_TICKS)
            val sx = sin(rad).toFloat(); val cy = cos(rad).toFloat()
            line(c, WHEEL_X + sx * (WHEEL_RADIUS - 9), WHEEL_Y + cy * (WHEEL_RADIUS - 9), WHEEL_X + sx * (WHEEL_RADIUS - 5), WHEEL_Y + cy * (WHEEL_RADIUS - 5), 0xff7c8071.toInt(), 0.7f)
        }
        circle(c, WHEEL_X, WHEEL_Y + 4, WHEEL_SELECT_RADIUS + 1, 0xff171c17.toInt())
        circle(c, WHEEL_X, WHEEL_Y, WHEEL_SELECT_RADIUS, 0xffd2ad59.toInt())
        val recordingScreen = app.mode == Mode.RECORD_POSITION || app.mode == Mode.RECORDING || app.mode == Mode.DEVICE_RECORD
        val stackPage = app.effectStackPage()
        val ordering = app.mode == Mode.FX_REORDER
        val held = app.effectHeld >= 0
        val label = 0xffd3d7c7.toInt()
        val dim = 0xff6d7066.toInt()
        val confirming = app.mode == Mode.PAGE_PASTE || app.mode == Mode.PAGE_RESET
        if (!zoomHolding) {
            val center = when {
                app.mode == Mode.HOME -> "MENU"
                recordingScreen -> "RECORD"
                ordering -> if (held) "DROP" else if (selectedMovable()) "MOVE" else "LOCKED"
                confirming -> "CONFIRM"
                app.mode == Mode.PAGE_COPIED -> ""
                else -> "SELECT"
            }
            centeredText(c, center, WHEEL_X, WHEEL_Y, 16f, INK, monoBold)
            if (app.mode != Mode.HOME) centeredText(c, if (confirming) "CANCEL" else "RETURN", WHEEL_X, WHEEL_Y - WHEEL_LABEL_OFFSET, WHEEL_LABEL_TEXT, label, monoBold)
            if (app.canOpenPages()) centeredText(c, "PAGES", WHEEL_X, WHEEL_Y + WHEEL_LABEL_OFFSET, WHEEL_LABEL_TEXT, label, monoBold)
            if (stackPage) centeredText(c, "ORDER", WHEEL_X, WHEEL_Y + WHEEL_LABEL_OFFSET, WHEEL_LABEL_TEXT, if (app.effectStack().isNotEmpty()) label else dim, monoBold)
        }
        when {
            zoomHolding -> zoomWheelOverlay(c)
            stackPage -> {
                centeredText(c, "DELETE", WHEEL_X - WHEEL_LABEL_OFFSET, WHEEL_Y, WHEEL_LABEL_TEXT, if (app.selection < app.effectStack().size) label else dim, monoBold)
                centeredText(c, "UNDO", WHEEL_X + WHEEL_LABEL_OFFSET, WHEEL_Y, WHEEL_LABEL_TEXT, if (app.canUndoEffect()) label else dim, monoBold)
            }
            ordering -> if (held) {
                centeredText(c, "UP", WHEEL_X - WHEEL_LABEL_OFFSET, WHEEL_Y, WHEEL_LABEL_TEXT, label, monoBold)
                centeredText(c, "DOWN", WHEEL_X + WHEEL_LABEL_OFFSET, WHEEL_Y, WHEEL_LABEL_TEXT, label, monoBold)
            }
            !app.busy && app.mode in arrayOf(Mode.HOME, Mode.START, Mode.END, Mode.IMPORT_START, Mode.IMPORT_END) -> {
                sliceIcon(c, WHEEL_X - WHEEL_LABEL_OFFSET, WHEEL_Y, true); sliceIcon(c, WHEEL_X + WHEEL_LABEL_OFFSET, WHEEL_Y, false)
            }
        }
    }

    private fun zoomWheelOverlay(c: Canvas) {
        val color = 0xffd3d7c7.toInt()
        curvedArrow(c, -103f, -40f, color)
        curvedArrow(c, -77f, 40f, color)
        paint.strokeCap = Paint.Cap.ROUND
        line(c, WHEEL_X - 117, WHEEL_Y, WHEEL_X - 91, WHEEL_Y, color, 3f)
        line(c, WHEEL_X + 91, WHEEL_Y, WHEEL_X + 117, WHEEL_Y, color, 3f)
        line(c, WHEEL_X + WHEEL_LABEL_OFFSET, WHEEL_Y - 13, WHEEL_X + WHEEL_LABEL_OFFSET, WHEEL_Y + 13, color, 3f)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun curvedArrow(c: Canvas, start: Float, sweep: Float, color: Int) {
        val radius = WHEEL_LABEL_OFFSET.toFloat()
        paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.strokeCap = Paint.Cap.ROUND
        c.drawArc(WHEEL_X - radius, WHEEL_Y - radius, WHEEL_X + radius, WHEEL_Y + radius, start, sweep, false, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
        val angle = Math.toRadians((start + sweep).toDouble())
        val tipX = WHEEL_X + radius * cos(angle).toFloat()
        val tipY = WHEEL_Y + radius * sin(angle).toFloat()
        val direction = sign(sweep)
        val dx = -sin(angle).toFloat() * direction
        val dy = cos(angle).toFloat() * direction
        zoomArrow.reset(); zoomArrow.moveTo(tipX, tipY)
        zoomArrow.lineTo(tipX - dx * 16 - dy * 7, tipY - dy * 16 + dx * 7)
        zoomArrow.lineTo(tipX - dx * 16 + dy * 7, tipY - dy * 16 - dx * 7)
        zoomArrow.close(); c.drawPath(zoomArrow, paint)
    }

    private fun sliceIcon(c: Canvas, x: Float, y: Float, start: Boolean) {
        val color = 0xffd3d7c7.toInt()
        for (i in SLICE_WAVEFORM.indices) {
            val bar = x - 12 + i * 4
            line(c, bar, y - SLICE_WAVEFORM[i], bar, y + SLICE_WAVEFORM[i], color, 2f)
        }
        val boundary = x + if (start) -18 else 18
        line(c, boundary, y - 14, boundary, y + 14, color, 2.5f)
    }

    private fun buttonShadow(c: Canvas, shape: Path, x: Float, y: Float) {
        c.save()
        c.translate(x, y + BUTTON_DEPTH + BUTTON_BOTTOM_RIM + 1)
        paint.style = Paint.Style.FILL_AND_STROKE; paint.strokeWidth = 2f
        paint.color = 0x12000000; c.drawPath(shape, paint)
        c.translate(0f, -1f)
        paint.color = 0x24000000; c.drawPath(shape, paint)
        c.restore()
        paint.style = Paint.Style.FILL
    }

    private fun cornerButton(c: Canvas, x: Float, y: Float, color: Int, pressed: Boolean, mirrored: Boolean) {
        val shape = if (mirrored) zoomButtonShape else buttonShape
        buttonShadow(c, shape, x, y)
        c.save()
        c.translate(x, y)
        c.save()
        c.translate(0f, BUTTON_DEPTH + BUTTON_BOTTOM_RIM - 1)
        paint.color = if (app.darkTheme) 0xff2c2e2a.toInt() else 0xffa5a196.toInt()
        paint.style = Paint.Style.FILL_AND_STROKE; paint.strokeWidth = 2f
        c.drawPath(shape, paint)
        c.restore()
        paint.style = Paint.Style.FILL
        c.save()
        c.translate(0f, BUTTON_DEPTH)
        paint.color = tint(color, BLACK, 0.35f)
        c.drawPath(shape, paint)
        c.restore()
        c.translate(0f, if (pressed) PRESS_DEPTH else 0f)
        paint.shader = LinearGradient(0f, -32f, 0f, 54f, tint(color, WHITE, 0.10f), tint(color, BLACK, 0.08f), Shader.TileMode.CLAMP)
        c.drawPath(shape, paint)
        paint.shader = null
        c.restore()
    }

    private fun cornerButtons(c: Canvas) {
        val left = app.leftHandMode
        val playX = playX()
        val zoomX = zoomX()
        cornerButton(c, playX, PLAY_Y, PLAY_COLOR, pressedPlay, left)
        cornerButton(c, zoomX, ZOOM_Y, ZOOM_COLOR, pressedZoom, !left)
        val active = app.sequencer.isPlaying() || app.sequencer.isSyncWaiting() || app.sequencer.isPreviewing()
        if (active) buttonGlow(c, playX, PLAY_Y + (if (pressedPlay) PRESS_DEPTH else 0f), if (left) zoomButtonShape else buttonShape, playLight)
        c.save()
        c.translate(playX + (if (left) -CORNER_FACE_X else CORNER_FACE_X), PLAY_Y + CORNER_FACE_Y + (if (pressedPlay) PRESS_DEPTH else 0f))
        c.rotate(if (left) -45f else 45f)
        paint.color = INK
        if (active) {
            rect(c, -9.5f, -12f, -2.5f, 12f, 1f, INK)
            rect(c, 2.5f, -12f, 9.5f, 12f, 1f, INK)
        } else c.drawPath(playIcon, paint)
        c.restore()
        if (app.hasTimelineZoom()) buttonGlow(c, zoomX, ZOOM_Y + (if (pressedZoom) PRESS_DEPTH else 0f), if (left) buttonShape else zoomButtonShape, zoomLight)
        zoomIcon(c, zoomX + (if (left) CORNER_FACE_X else -CORNER_FACE_X), ZOOM_Y + CORNER_FACE_Y + (if (pressedZoom) PRESS_DEPTH else 0f))
    }

    private fun deviceRecording(c: Canvas) {
        if (DeviceCaptureService.active()) {
            text(c, "RECORDING DEVICE AUDIO", LCD_LEFT, LCD_TITLE_Y, 13f, ORANGE, monoBold)
            text(c, MainActivity.seconds(DeviceCaptureService.frames().toLong()) + " s", LCD_LEFT, 139f, 25f, LCD, monoBold)
            text(c, "SWITCH APPS TO RECORD / MAX ${Tape.SECONDS}s", LCD_LEFT, 176f, LCD_TEXT, LCD, mono)
            text(c, "RECORD OR PLAY: STOP AND TRIM", LCD_LEFT, 207f, LCD_TEXT, LCD, mono)
        } else {
            text(c, "RECORD DEVICE", LCD_LEFT, LCD_TITLE_Y, 17f, LCD, monoBold)
            wrapped(c, "Records the audio on your device, including other apps.", LCD_LEFT, 125f, 39, 11f, LCD)
            text(c, "RECORD: START", LCD_LEFT, 207f, LCD_TEXT, LCD, mono)
        }
    }

    private fun tracks(c: Canvas) {
        for (i in 0 until Sequencer.TRACKS) {
            val x = ROW_LEFT + TRACK_WIDTH / 2 + i * TRACK_PITCH
            val selected = i == app.track
            val muted = app.sequencer.tracks[i].mute
            val depression = if (pressedTrack == i) PRESS_DEPTH else 0f
            val left = x - TRACK_WIDTH / 2 + 1
            val right = x + TRACK_WIDTH / 2 - 1
            val top = TRACK_Y - TRACK_HEIGHT / 2
            val height = TRACK_HEIGHT
            val base = (if (muted) (if (selected) 0xffe95d65 else 0xffbf414c) else (if (selected) 0xff9edacb else 0xff799fad)).toInt()
            buttonShadow(c, trackButtonShape, left, top)
            raisedButton(c, left, top, right - left, height, base, tint(base, WHITE, if (selected) 0.22f else 0.10f), if (selected) base else tint(base, BLACK, 0.08f), depression)
            centeredText(c, String.format(Locale.US, "T%02d", i + 1), x, top + (height - BUTTON_DEPTH) / 2 + depression, 10f, INK, monoBold)
        }
    }

    private fun raisedButton(c: Canvas, x: Float, y: Float, width: Float, height: Float, base: Int, top: Int, bottom: Int, depression: Float) {
        rect(c, x - 1, y + 1, x + width + 1, y + height + BUTTON_BOTTOM_RIM, 6f, if (app.darkTheme) 0xff2c2e2a.toInt() else 0xffa5a196.toInt())
        rect(c, x, y + 3, x + width, y + height, 5f, tint(base, BLACK, 0.35f))
        paint.shader = LinearGradient(x, y, x, y + height - BUTTON_DEPTH, top, bottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(x, y + depression, x + width, y + height - BUTTON_DEPTH + depression, 5f, 5f, paint)
        paint.shader = null
    }

    private fun padGlow(c: Canvas, x: Float, y: Float, color: Int) {
        val faceHeight = PAD_HEIGHT - BUTTON_DEPTH
        c.save()
        c.clipRect(x, y, x + PAD_WIDTH, y + faceHeight)
        c.translate(x + PAD_WIDTH / 2, y + faceHeight / 2)
        paint.shader = padLight(glowColor(color))
        c.drawRoundRect(-PAD_WIDTH / 2, -faceHeight / 2, PAD_WIDTH / 2, faceHeight / 2, 5f, 5f, paint)
        paint.shader = null
        c.restore()
    }

    private fun pads(c: Canvas) {
        val bits: Int
        synchronized(app.sequencer) { bits = app.sequencer.tracks[app.track].steps }
        val pages = app.pageMenu()
        val playhead = if (app.sequencer.playingPage() == app.sequencer.selectedPage()) app.sequencer.step() else -1
        for (i in 0 until Sequencer.STEPS) {
            val x = ROW_LEFT + 1 + (i % PAD_COLUMNS) * PAD_COLUMN_PITCH
            val y = PAD_TOP + (i / PAD_COLUMNS) * PAD_PITCH
            val page = app.sequencer.page(if (pages) i else app.sequencer.selectedPage())
            val on = if (pages) page.enabled else bits and (1 shl i) != 0
            val playing = !pages && i == playhead
            val editing = !pages && app.editingStep(i)
            val base = page.color
            val top = tint(base, WHITE, if (editing) 0.40f else if (on) 0.22f else 0.10f)
            val bottom = if (editing) tint(base, WHITE, 0.22f) else if (on) base else tint(base, BLACK, 0.08f)
            val depression = if (pressed == i) PRESS_DEPTH else 0f
            raisedButton(c, x, y, PAD_WIDTH, PAD_HEIGHT, base, top, bottom, depression)
            val marked = pages && (i == app.swapSource || i == app.pasteTarget || app.pageCopying() && i == app.pageClipboardSource)
            if (playing && on || marked || i == 0 && blinkLit(SystemClock.uptimeMillis())) padGlow(c, x, y + depression, base)
            text(c, String.format(Locale.US, "%02d", i + 1), x + 12, y + 37 + depression, 26f, INK, bold)
            val custom = !pages && app.sequencer.tracks[app.track].padEffects[i] != null
            val bar = when {
                playing -> 0xfff6ffd2.toInt()
                custom -> 0xff8ad9ec.toInt()
                on -> tint(base, WHITE, 0.65f)
                else -> tint(base, BLACK, 0.4f)
            }
            rect(c, x + 45, y + 7 + depression, x + 71, y + 11 + depression, 1f, bar)
            if (editing) centeredText(c, "FX", x + 65, y + 32 + depression, 10f, INK, monoBold)
        }
    }

    private fun buttonGlow(c: Canvas, x: Float, y: Float, shape: Path, light: RadialGradient) {
        c.save()
        c.translate(x, y)
        c.clipPath(shape)
        paint.shader = light
        c.drawCircle(0f, CORNER_FACE_Y, GLOW_RADIUS, paint)
        paint.shader = null
        c.restore()
    }

    private fun zoomIcon(c: Canvas, x: Float, y: Float) {
        c.save(); c.scale(1.35f, 1.35f, x, y)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.2f; paint.color = INK
        c.drawCircle(x - 2, y - 2, 6f, paint)
        line(c, x + 2, y + 2, x + 8, y + 8, INK, 2.2f)
        paint.style = Paint.Style.FILL
        c.restore()
    }

    private fun padAt(x: Float, y: Float): Int {
        if (x < ROW_LEFT || x > ROW_RIGHT || y < PAD_TOP || y >= PAD_TOP + PAD_PITCH * PAD_ROWS) return -1
        val col = ((x - ROW_LEFT) / PAD_COLUMN_PITCH).toInt()
        val row = ((y - PAD_TOP) / PAD_PITCH).toInt()
        return if (col < PAD_COLUMNS && row < PAD_ROWS && (x - ROW_LEFT) % PAD_COLUMN_PITCH <= PAD_WIDTH + 2 && (y - PAD_TOP) % PAD_PITCH <= PAD_HEIGHT) row * PAD_COLUMNS + col else -1
    }

    private fun trackAt(x: Float, y: Float): Int {
        if (x < ROW_LEFT || x > ROW_RIGHT || y < TRACK_Y - TRACK_HEIGHT / 2 || y > TRACK_Y + TRACK_HEIGHT / 2 + BUTTON_BOTTOM_RIM) return -1
        val track = ((x - ROW_LEFT) / TRACK_PITCH).toInt()
        return if (track < Sequencer.TRACKS && (x - ROW_LEFT) % TRACK_PITCH <= TRACK_WIDTH) track else -1
    }

    private fun cornerAt(x: Float, y: Float, centerX: Float, centerY: Float, mirrored: Boolean): Boolean {
        val dx = (x - centerX) * (if (mirrored) -1 else 1)
        val dy = y - centerY
        return dx >= CORNER_HIT_LEFT && dx <= CORNER_HIT_RIGHT && dy >= CORNER_HIT_TOP && dy <= CORNER_HIT_BOTTOM && hypot(PLAY_X + dx - WHEEL_X, PLAY_Y + dy - WHEEL_Y) >= CORNER_RADIUS - TOUCH_SLOP_INNER
    }

    fun playX() = if (app.leftHandMode) ZOOM_X else PLAY_X
    fun zoomX() = if (app.leftHandMode) PLAY_X else ZOOM_X

    private fun playAt(x: Float, y: Float) = cornerAt(x, y, playX(), PLAY_Y, app.leftHandMode)
    private fun zoomAt(x: Float, y: Float) = cornerAt(x, y, zoomX(), ZOOM_Y, !app.leftHandMode)

    private fun zoomWheelTap(x: Float, y: Float) {
        if (!zoomHolding || !app.hasTimelineZoom() || !wheelDown || wheelMoved || hypot(x - downX, y - downY) >= TAP_DISTANCE) return
        val dx = x - WHEEL_X
        val dy = y - WHEEL_Y
        val radius = hypot(dx, dy)
        if (abs(dx) <= abs(dy) || radius <= WHEEL_SELECT_RADIUS + TOUCH_SLOP_INNER || radius >= WHEEL_RADIUS + TOUCH_SLOP_OUTER) return
        app.zoomByWheel(if (dx < 0) -1 else 1)
        performClick()
    }

    fun zoomHeld() = zoomHolding

    private fun selectedMovable(): Boolean {
        val stack = app.effectStack()
        return app.selection < stack.size && app.canReorder(stack[app.selection])
    }

    private fun turnWheel(ticks: Int) {
        if (insertStart >= 0 || app.mode == Mode.BACK || !app.powered()) return
        if (zoomHolding) { if (app.hasTimelineZoom()) app.zoomByWheel(ticks) }
        else app.wheel(ticks)
    }

    private fun releaseAll() {
        wheelDown = false; pressed = -1; pressedTrack = -1; pressedPlay = false; pressedZoom = false
        zoomHolding = false; zoomPointerId = -1; wheelPointerId = -1
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var pointerIndex = 0
        if (event.actionMasked == MotionEvent.ACTION_MOVE && zoomHolding && wheelPointerId >= 0) {
            pointerIndex = max(0, event.findPointerIndex(wheelPointerId))
        }
        val px = (event.getX(pointerIndex) - offsetX) / scale
        val py = (event.getY(pointerIndex) - offsetY) / scale
        val x = wheelX(px)
        val y = wheelY(py)
        val radius = hypot(x - WHEEL_X, y - WHEEL_Y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (insertStart >= 0 || app.mode == Mode.BACK || !app.powered()) {
                    backTap = insertStart < 0 && (app.mode != Mode.BACK || SystemClock.uptimeMillis() - flipStart >= FLIP_MILLIS)
                    return true
                }
                backTap = false
                pressedTrack = trackAt(px, py)
                pressedPlay = playAt(x, y)
                pressedZoom = zoomAt(x, y)
                zoomHolding = pressedZoom && app.hasTimelineZoom()
                zoomPointerId = if (pressedZoom) event.getPointerId(0) else -1
                wheelPointerId = -1
                downX = x; downY = y; held = false
                wheelDown = !pressedPlay && !pressedZoom && radius > WHEEL_SELECT_RADIUS + TOUCH_SLOP_INNER && radius < WHEEL_RADIUS + TOUCH_SLOP_OUTER
                wheelMoved = false
                angle = atan2(y - WHEEL_Y, x - WHEEL_X)
                accumulated = 0f
                pressed = padAt(px, py)
                if (pressed >= 0 || pressedTrack >= 0) postDelayed(hold, HOLD_MILLIS)
                parent.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (zoomHolding && wheelPointerId < 0) {
                val index = event.actionIndex
                wheelPointerId = event.getPointerId(index)
                val wx = wheelX((event.getX(index) - offsetX) / scale)
                val wy = wheelY((event.getY(index) - offsetY) / scale)
                downX = wx; downY = wy
                angle = atan2(wy - WHEEL_Y, wx - WHEEL_X)
                accumulated = 0f; wheelMoved = false
                val pointerRadius = hypot(wx - WHEEL_X, wy - WHEEL_Y)
                wheelDown = pointerRadius > WHEEL_SELECT_RADIUS + TOUCH_SLOP_INNER && pointerRadius < WHEEL_RADIUS + TOUCH_SLOP_OUTER
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val liftedId = event.getPointerId(index)
                if (liftedId == wheelPointerId) {
                    zoomWheelTap(wheelX((event.getX(index) - offsetX) / scale), wheelY((event.getY(index) - offsetY) / scale))
                    wheelPointerId = -1; wheelDown = false
                }
                if (liftedId == zoomPointerId) {
                    zoomPointerId = -1; pressedZoom = false; zoomHolding = false; wheelDown = false; held = true; removeCallbacks(hold)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (wheelDown) {
                    val next = atan2(y - WHEEL_Y, x - WHEEL_X)
                    var delta = next - angle
                    if (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
                    if (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
                    accumulated += delta
                    app.wheelTurned(delta.toDouble())
                    angle = next
                    if (abs(accumulated) > WHEEL_DEAD_ZONE) wheelMoved = true
                    val step = WHEEL_STEP * 100 / app.wheelSensitivity
                    val ticks = (accumulated / step).toInt()
                    if (ticks != 0) {
                        wheelMoved = true
                        turnWheel(ticks)
                        accumulated -= ticks * step
                        invalidate()
                    }
                } else if (hypot(x - downX, y - downY) > DRAG_CANCEL_DISTANCE) { pressed = -1; pressedTrack = -1; removeCallbacks(hold) }
            }
            MotionEvent.ACTION_UP -> {
                if (backTap) {
                    backTap = false
                    if (app.mode == Mode.BACK) backTap(px, py) else app.showBack()
                    invalidate(); return true
                }
                if (insertStart >= 0 || app.mode == Mode.BACK || !app.powered()) return true
                removeCallbacks(hold)
                zoomWheelTap(x, y)
                val still = hypot(x - downX, y - downY) < TAP_DISTANCE
                if (!zoomHolding && wheelDown && !wheelMoved && still) {
                    if (abs(y - WHEEL_Y) >= abs(x - WHEEL_X)) {
                        if (y < WHEEL_Y) { if (app.mode != Mode.HOME) app.wheelMenu() }
                        else app.wheelPages()
                    } else app.wheelSide(if (x < WHEEL_X) -1 else 1)
                }
                if (!held && !zoomHolding && !wheelDown && still) {
                    when {
                        pressed >= 0 && padAt(px, py) == pressed -> app.pad(pressed)
                        pressedPlay && playAt(x, y) -> app.wheelPlay()
                        pressedTrack >= 0 && trackAt(px, py) == pressedTrack -> app.selectTrack(pressedTrack)
                        radius <= WHEEL_SELECT_RADIUS + TOUCH_SLOP_INNER -> app.center()
                        py >= FINE_TOUCH_TOP + TOP_SHIFT && py < SCREEN_BOTTOM + TOP_SHIFT && px > SCREEN_LEFT && px < SCREEN_RIGHT -> app.toggleFine()
                    }
                    performClick()
                }
                releaseAll()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(hold); releaseAll() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (scroll != 0f) {
                mouseAccumulated -= scroll * SCROLL_TICKS_PER_NOTCH * app.wheelSensitivity / 100
                val ticks = mouseAccumulated.toInt()
                if (ticks != 0) {
                    app.wheelTurned((ticks * WHEEL_STEP).toDouble())
                    turnWheel(ticks)
                    mouseAccumulated -= ticks
                }
                invalidate()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!app.powered() && app.mode != Mode.BACK) {
            if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ESCAPE) app.showBack()
            return true
        }
        val sideKeys = app.mode == Mode.HOME || app.isTimelineMode() || app.effectSideKeys()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (!zoomHolding) app.wheel(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!zoomHolding) { if (app.mode == Mode.HOME) app.wheelPages() else app.wheel(1) }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                if (zoomHolding) { if (app.hasTimelineZoom()) app.zoomByWheel(direction) }
                else if (sideKeys) app.wheelSide(direction) else app.wheel(direction)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (!zoomHolding && event.repeatCount == 0) app.confirm()
            KeyEvent.KEYCODE_ESCAPE -> if (event.repeatCount == 0) app.cancel()
            KeyEvent.KEYCODE_P -> if (event.repeatCount == 0) app.previewTrack()
            KeyEvent.KEYCODE_SPACE -> if (event.repeatCount == 0) app.wheelPlay()
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_8 -> app.selectTrack(keyCode - KeyEvent.KEYCODE_1)
            else -> return super.onKeyDown(keyCode, event)
        }
        invalidate()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> turnWheel(1)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> turnWheel(-1)
            AccessibilityNodeInfo.ACTION_CLICK -> if (!app.powered()) app.showBack() else if (!zoomHolding) app.center()
            else -> return super.performAccessibilityAction(action, arguments)
        }
        invalidate()
        return true
    }

    private fun wrapped(c: Canvas, value: String, x: Float, y: Float, length: Int, size: Float, color: Int) {
        var rowY = y
        val row = StringBuilder()
        for (word in value.split(" ")) {
            if (row.length + word.length + 1 > length) {
                text(c, row.toString(), x, rowY, size, color, mono)
                rowY += 15
                row.setLength(0)
            }
            if (row.isNotEmpty()) row.append(' ')
            row.append(word)
        }
        if (row.isNotEmpty()) text(c, row.toString(), x, rowY, size, color, mono)
    }

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, font: Typeface?) {
        paint.color = color; paint.textSize = size; paint.typeface = font; paint.style = Paint.Style.FILL
        val width = (if (drawingScreen) LCD_RIGHT + 1 else UI_WIDTH - 1) - x
        val shown = TextUtils.ellipsize(value, paint, max(0f, width), TextUtils.TruncateAt.END).toString()
        c.drawText(shown, x, y, paint)
        if (auditText && shown.isNotBlank()) {
            val metrics = paint.fontMetrics
            textBoxes.add(TextBox(shown, RectF(x, y + metrics.ascent, x + paint.measureText(shown), y + metrics.descent), drawingScreen))
        }
    }

    private fun rightText(c: Canvas, value: String, right: Float, y: Float, size: Float, color: Int, font: Typeface?) {
        paint.typeface = font; paint.textSize = size
        text(c, value, right - paint.measureText(value), y, size, color, font)
    }

    private fun centeredText(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, font: Typeface?) {
        paint.typeface = font
        paint.textSize = size
        val metrics = paint.fontMetrics
        text(c, value, x - paint.measureText(value) / 2, y - (metrics.ascent + metrics.descent) / 2, size, color, font)
    }

    private fun rect(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Int) {
        paint.color = color; paint.style = Paint.Style.FILL
        c.drawRoundRect(left, top, right, bottom, radius, radius, paint)
    }

    private fun circle(c: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.color = color; paint.style = Paint.Style.FILL; c.drawCircle(x, y, radius, paint)
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        paint.color = color; paint.strokeWidth = width; c.drawLine(x1, y1, x2, y2, paint)
    }

    private fun tint(color: Int, target: Int, amount: Float) = Color.rgb(
        (Color.red(color) * (1 - amount) + Color.red(target) * amount).roundToInt(),
        (Color.green(color) * (1 - amount) + Color.green(target) * amount).roundToInt(),
        (Color.blue(color) * (1 - amount) + Color.blue(target) * amount).roundToInt())

    companion object {
        private const val WHITE = 0xffffffff.toInt()
        private const val BLACK = 0xff000000.toInt()
        private const val DARK_BODY = 0xff444641.toInt()
        private val BODY = Color.rgb(213, 210, 200)
        private val CASES = intArrayOf(0xff8f8e8a.toInt(), 0xffd9a59b.toInt(), 0xffaecba0.toInt(), 0xffe0d29a.toInt(), 0xffa4b8d6.toInt(), 0xffc2a8d6.toInt(), 0xff9fcac6.toInt(), BODY)
        private val DARK_CASES = intArrayOf(0xff232323.toInt(), 0xff6e3d38.toInt(), 0xff3e5a3b.toInt(), 0xff6b6136.toInt(), 0xff3a4a6a.toInt(), 0xff58406a.toInt(), 0xff35605e.toInt(), DARK_BODY)
        private val SWITCH_COLORS = intArrayOf(0xffe0453c.toInt(), 0xff3fbf5a.toInt(), 0xff6fb3ff.toInt())
        private const val COVER_L = 63f
        private const val COVER_R = 337f
        private const val COVER_T = 506f
        private const val COVER_B = 836f
        private const val WALL_L = 71f
        private const val WALL_R = 329f
        private const val RIB_PITCH = 17f
        private const val SLOT_PITCH = 70f
        private const val BATTERY_L = 81f
        private const val BATTERY_R = 319f
        private const val BATTERY_CX = (BATTERY_L + BATTERY_R) / 2
        private const val BATTERY_HALF = (BATTERY_R - BATTERY_L) / 2
        private const val BATTERY_RADIUS = 34.5f
        private const val SWITCH_L = 126f
        private const val SWITCH_R = 158f
        private const val SWITCH_SLOT = BatteryBay.SLOTS - BatteryBay.SWITCHES
        private val POPPED = arrayOf(floatArrayOf(90f, -6f, 1f), floatArrayOf(84f, -2f, -1f), floatArrayOf(96f, 2f, 1f), floatArrayOf(86f, 6f, -1f))
        private const val METAL = 0xffc9ccd1.toInt()
        private const val METAL_DARK = 0xff7d8188.toInt()
        private val METAL_STOPS = intArrayOf(0xff8a8e95.toInt(), 0xfff2f3f5.toInt(), 0xffc3c7cd.toInt(), 0xff6f737a.toInt())
        private val METAL_POSITIONS = floatArrayOf(0f, 0.25f, 0.55f, 1f)
        private const val OFF_DX = 520f
        private const val LID_MILLIS = 900L
        private const val INSERT_MILLIS = 520L
        private const val POP_MILLIS = 780L
        private val LOOSE = arrayOf(floatArrayOf(215f, 150f, -4f), floatArrayOf(245f, 236f, 3f), floatArrayOf(205f, 322f, -3f), floatArrayOf(238f, 408f, 4f))
        private const val FLY_MILLIS = 360L
        private const val ARRIVE_MILLIS = 720L
        private const val LABEL_R = 342f
        private const val LABEL_T = 82f
        private const val LIL_X = 290f
        private const val LIL_Y = 128f
        private const val SNEAKY_X = 292f
        private const val SNEAKY_Y = 210f
        private const val PEEL_IDLE = 14f
        private const val PEEL_OPEN = 62f
        private const val PEEL_OPEN_MS = 260L
        private const val PEEL_FALL_MS = 620L
        private const val PEEL_CLOSE_MS = 760L
        private const val PEEL_DONE = 1160L
        private val SLEEVES = intArrayOf(R.drawable.battery_tapecell, R.drawable.battery_claptron, R.drawable.battery_loopjuice, R.drawable.battery_hiss, R.drawable.battery_rewinder, R.drawable.battery_wowflutter,
            R.drawable.battery_bside, R.drawable.battery_spoolboy, R.drawable.battery_padpower, R.drawable.battery_kabiscell, R.drawable.battery_doubletime, R.drawable.battery_voltowl)
        private const val ROLL_STRIPS = 40
        private val CYLINDER_STOPS = intArrayOf(0x60000000, 0x10ffffff, 0x80ffffff.toInt(), 0x18ffffff, 0, 0x38000000, 0x90000000.toInt())
        private val CYLINDER_POSITIONS = floatArrayOf(0f, 0.12f, 0.22f, 0.34f, 0.5f, 0.75f, 1f)
        private const val REST_X = 107f
        private const val REST_Y = 282f
        private const val REST_ANGLE = -5f
        private val BACK_REST = floatArrayOf(REST_X, REST_Y, REST_ANGLE)
        private val FRONT_REST = BACK_REST
        private val LID_OUT = floatArrayOf(-175f, REST_Y + 30, -14f)
        private val INK = Color.rgb(41, 43, 39)
        private val ORANGE = Color.rgb(235, 82, 49)
        private val LCD = Color.rgb(190, 211, 148)
        private const val LCD_DARK = 0xff101a16.toInt()
        private const val LCD_DIM = 0xff4d5a45.toInt()
        private val EFFECT_COLORS = longArrayOf(0xff8ad9ec, 0xffc79be0, 0xff7fd0a8, 0xff7fa8e8, 0xffeb7a5a, 0xffe8c15a, 0xffd68f9f, 0xffd9d9c8, 0xff9fc7c0, 0xfff6a6a6, 0xfff08fd0, 0xffb4e06a, 0xffa9a4ee, 0xffe8d0a0, 0xff9fe0d0, 0xfff0b060).map { it.toInt() }.toIntArray()
        private const val STACK_ROWS = 4
        private val TRANS_STRIPES = longArrayOf(0xff5bcefa, 0xfff5a9b8, 0xffffffff, 0xfff5a9b8, 0xff5bcefa).map { it.toInt() }.toIntArray()
        private val PRIDE_STRIPES = longArrayOf(0xffec5b5b, 0xffffae58, 0xfffff070, 0xff5cae72, 0xff6890e6, 0xffa064b0).map { it.toInt() }.toIntArray()
        private const val GLOW_RADIUS = 48f
        private val GLOW_ALPHAS = intArrayOf(0x80, 0x63, 0x4f, 0x33, 0x1a, 0x05, 0)
        private val GLOW_STOPS = floatArrayOf(0f, 0.4f, 0.52f, 0.65f, 0.77f, 0.9f, 1f)
        private const val SPLIT_MILLIS = 340L
        private const val LOCK_NOTICE_MILLIS = 1500L
        private const val STACK_LEFT = 112f
        private const val STACK_WIDTH = 176f
        private const val STACK_TOP = 77f
        private const val STACK_HEIGHT = 25f
        private const val STACK_PITCH = 28f
        private const val STACK_CELL = 34f
        private const val STACK_RADIUS = 2f
        private const val ADD_INSET = 24f
        private const val HATCH_L = 10f
        private const val HATCH_R = 390f
        private const val HATCH_T = -1f
        private const val HATCH_B = 243f
        private const val HATCH_DEGREES = 20f
        private const val CASSETTE_W = 300f
        private const val CASSETTE_H = 188f
        private const val DEVICE_DEPTH = 97f
        private const val SPOOL_GAP = 60f
        private const val CAROUSEL_Y = 429f
        private const val CAROUSEL_TRAVEL = 420f
        private const val BOB_MILLIS = 520.0
        private const val BOB_HEIGHT = 2.5f
        private const val SLOT_Y = 140f
        private const val OUT_Y = -150f
        private const val RISE_END = 450L
        private const val OPEN_END = 700L
        private const val EJECT_END = 1000L
        private const val LOAD_END = 1350L
        private const val CLOSE_END = 1600L
        private const val CAROUSEL_MILLIS = 320L
        private const val FLIP_MILLIS = 800L
        private const val COPIED_MILLIS = 1500L
        private const val BLINK_MILLIS = 170L
        private val SCREW_ANGLES = floatArrayOf(-14f, 11f, 24f, -26f)
        private val SLICE_WAVEFORM = floatArrayOf(3f, 7f, 10f, 5f, 9f, 6f, 2f)
        const val UI_WIDTH = 400f
        const val UI_CENTER_X = UI_WIDTH / 2
        const val WHEEL_X = UI_CENTER_X
        const val WHEEL_Y = 687f
        const val UI_HEIGHT = 860f
        private const val BODY_RADIUS = 16f
        private const val FRAME_MILLIS = 33L
        private const val SCREEN_LEFT = 32f
        private const val SCREEN_TOP = 46f
        private const val SCREEN_RIGHT = 368f
        private const val SCREEN_BOTTOM = 222f
        private const val LCD_LEFT = 36f
        private const val LCD_RIGHT = 363f
        private const val LCD_TITLE_Y = 96f
        private const val LCD_VALUE_Y = 188f
        private const val LCD_HINT_Y = 213f
        private const val LCD_SMALL_TEXT = 9f
        private const val LCD_TEXT = 10f
        private const val LCD_TITLE_TEXT = 12f
        private const val WHEEL_LABEL_OFFSET = 104
        private const val WHEEL_TICKS = 60
        private const val WHEEL_LABEL_TEXT = 15f
        private const val PAD_COLUMNS = 4
        private const val PAD_ROWS = Sequencer.STEPS / PAD_COLUMNS
        private const val PRESS_DEPTH = 2f
        private const val PLAY_COLOR = 0xffef3546.toInt()
        private const val ZOOM_COLOR = 0xff67bde8.toInt()
        private const val CORNER_HIT_LEFT = -54
        private const val CORNER_HIT_RIGHT = 38
        private const val CORNER_HIT_TOP = -36
        private const val CORNER_HIT_BOTTOM = 58
        private const val WAVE_LEFT = 37f
        private const val WAVE_WIDTH = 326f
        private const val WAVE_BINS = 160
        private const val WAVE_REFRESH_MILLIS = 100
        private const val WAVE_HEIGHT = 0.46f
        private const val INCOMING_WAVE_HEIGHT = 0.44f
        private const val LONG_VALUE_CHARS = 7
        private const val LONG_VALUE_TEXT = 31f
        private const val VALUE_TEXT = 46f
        private const val LCD_FOOTER_Y = 216f
        private const val VISUAL_LEFT = 254f
        private const val VISUAL_RIGHT = 360f
        private const val VISUAL_TOP = 110f
        private const val VISUAL_BOTTOM = 166f
        private const val VISUAL_POINTS = 64
        private const val VISUAL_DB = 30f
        private const val VISUAL_AUTOTUNE_MS = 900f
        private const val FOOTER_GLYPH_RISE = 4f
        private const val PULSE_MILLIS = 1800
        private const val MENU_ROWS = 7
        private const val MENU_TOP = 93f
        private const val MENU_PITCH = 20
        private const val SPLIT_PAUSE = 0.35f
        private const val TOUCH_SLOP_INNER = 4
        private const val TOUCH_SLOP_OUTER = 6
        private const val HOLD_MILLIS = 450L
        private const val WHEEL_DEAD_ZONE = 0.04f
        private const val DRAG_CANCEL_DISTANCE = 18
        private const val TAP_DISTANCE = 20
        private const val FINE_TOUCH_TOP = 163
        private const val SCROLL_TICKS_PER_NOTCH = 0.35f
        const val WHEEL_RADIUS = 154f
        const val WHEEL_SELECT_RADIUS = 52f
        private const val WHEEL_STEP = 0.36f
        const val PAD_TOP = 307f
        const val PAD_PITCH = 60f
        const val PAD_HEIGHT = 52f
        const val TRACK_Y = 277f
        const val TOP_SHIFT = 11f
        const val CLUSTER_SCALE = 0.95f
        const val CLUSTER_PIVOT = 846f
        const val PLAY_X = 348f
        const val PLAY_Y = 564f
        const val ZOOM_X = 52f
        const val ZOOM_Y = 564f
        private const val CORNER_RADIUS = 172f
        private const val BUTTON_DEPTH = 5f
        private const val BUTTON_BOTTOM_RIM = 2f
        private const val TRACK_HEIGHT = 34f
        private const val CORNER_FACE_X = 0f
        private const val CORNER_FACE_Y = -1.5f
        private const val ROW_LEFT = 16f
        private const val ROW_RIGHT = 384f
        private const val TRACK_WIDTH = (ROW_RIGHT - ROW_LEFT - 7 * 4) / 8
        private const val TRACK_PITCH = TRACK_WIDTH + 4
        private const val PAD_WIDTH = (ROW_RIGHT - ROW_LEFT - 3 * 6) / 4 - 2
        private const val PAD_COLUMN_PITCH = PAD_WIDTH + 8

        @JvmStatic fun surface(dark: Boolean) = if (dark) 0xff2b2b2c.toInt() else 0xffbba383.toInt()
        @JvmStatic fun wheelX(x: Float) = WHEEL_X + (x - WHEEL_X) / CLUSTER_SCALE
        @JvmStatic fun wheelY(y: Float) = CLUSTER_PIVOT + (y - CLUSTER_PIVOT) / CLUSTER_SCALE
        @JvmStatic fun deviceX(x: Float) = WHEEL_X + (x - WHEEL_X) * CLUSTER_SCALE
        @JvmStatic fun deviceY(y: Float) = CLUSTER_PIVOT + (y - CLUSTER_PIVOT) * CLUSTER_SCALE

        private fun project(out: FloatArray, index: Int, x: Float, y: Float, z: Float, cos: Float, sin: Float) {
            val distance = 1500f
            val xr = x * cos + z * sin
            val zr = -x * sin + z * cos
            val s = (distance - DEVICE_DEPTH / 2) / (distance - zr)
            out[index * 2] = 200 + xr * s
            out[index * 2 + 1] = UI_HEIGHT / 2 + y * s
        }

        private fun softSpring(t: Float): Float = 1 - (Math.exp(-6.0 * t) * cos(5.5 * t)).toFloat()

        private fun spring(t: Float): Float = 1 - (Math.exp(-7.0 * t) * cos(8.5 * t)).toFloat()

        private fun ease(t: Float): Float {
            val v = max(0f, min(1f, t))
            return v * v * (3 - 2 * v)
        }

        private fun lerpColor(from: Int, to: Int, t: Float) = Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).roundToInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).roundToInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).roundToInt())

        private fun saturated(color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[1] = min(1f, hsv[1] * 1.6f + 0.12f)
            hsv[2] = min(1f, hsv[2] * 1.08f)
            return Color.HSVToColor(hsv)
        }

        private fun glowColor(color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            val toward = ((60 - hsv[0]) % 360 + 540) % 360 - 180
            hsv[0] = (hsv[0] + max(-25f, min(25f, toward)) + 360) % 360
            hsv[1] *= 0.7f
            hsv[2] = min(1f, hsv[2] * 1.1f + 0.15f)
            return Color.HSVToColor(hsv) and 0xffffff
        }

        private fun glow(color: Int) = IntArray(GLOW_ALPHAS.size) { GLOW_ALPHAS[it] shl 24 or color }
        private fun padLight(color: Int) = RadialGradient(0f, 0f, PAD_WIDTH * 0.62f, glow(color), GLOW_STOPS, Shader.TileMode.CLAMP)
        private fun buttonLight(color: Int) = RadialGradient(0f, CORNER_FACE_Y, GLOW_RADIUS, glow(color), GLOW_STOPS, Shader.TileMode.CLAMP)

        private fun buttonShape(mirrored: Boolean): Path {
            val centerX = WHEEL_X - PLAY_X
            val centerY = WHEEL_Y - PLAY_Y
            val fillet = 8f
            val shape = Path()
            shape.moveTo(-44.12f, -32f)
            shape.lineTo(23f, -32f)
            shape.quadTo(36f, -32f, 33.8f, -19.2f)
            shape.lineTo(22.4f, 47f)
            shape.arcTo(14.5f - fillet, 45.6f - fillet, 14.5f + fillet, 45.6f + fillet, 10f, 144.5f, false)
            shape.arcTo(centerX - CORNER_RADIUS, centerY - CORNER_RADIUS, centerX + CORNER_RADIUS, centerY + CORNER_RADIUS, -25.46f, -29.3f, false)
            shape.arcTo(-44.12f - fillet, -24 - fillet, -44.12f + fillet, -24 + fillet, 125.24f, 144.76f, false)
            shape.close()
            if (mirrored) shape.transform(Matrix().apply { setScale(-1f, 1f) })
            return shape
        }

        private fun playIcon() = Path().apply {
            moveTo(-7f, -12f); lineTo(-7f, 12f); lineTo(14f, 0f); close()
        }

        private fun trackButtonShape() = Path().apply {
            addRoundRect(0f, 0f, TRACK_WIDTH - 2, TRACK_HEIGHT - BUTTON_DEPTH, 5f, 5f, Path.Direction.CW)
        }
    }
}
