package com.leaptv.jump

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 「跳一跳」风格的 TV 游戏视图。
 *
 * 核心玩法:
 *  - 等距(isometric)视角下的方块平台。
 *  - 玩家按住屏幕(或遥控器 OK 键)蓄力,松开起跳。
 *  - 蓄力时间决定跳跃距离,落到下一块平台上得分(正中额外加分)。
 *  - 跳空则掉落,游戏结束。
 */
class GameView(context: Context) : View(context) {

    private enum class State { READY, CHARGING, JUMPING, FALLING, GAME_OVER }

    private data class Block(val wx: Float, val wy: Float, val half: Float, val color: Int)

    /** 得分飘字:+1/+2 上飘淡出。 */
    private data class ScorePop(
        val wx: Float,
        val wy: Float,
        val text: String,
        val startMs: Long
    )

    private val handler = Handler(Looper.getMainLooper())
    private var lastFrameMs = 0L
    private val sound = SoundEngine()

    private var state = State.READY
    private var score = 0

    // 世界坐标(连续单位)。当前平台、下一个平台、所有平台。
    private var current: Block = Block(0f, 0f, 0.7f, 0)
    private var next: Block? = null
    private val blocks = mutableListOf<Block>()

    // 玩家世界坐标与屏幕竖直偏移(正值为向上)。
    private var playerWX = 0f
    private var playerWY = 0f
    private var playerVy = 0f

    // 蓄力
    private var chargeStartMs = 0L
    private var chargeRatio = 0f

    // 跳跃
    private var jumpFromX = 0f
    private var jumpFromY = 0f
    private var jumpToX = 0f
    private var jumpToY = 0f
    private var jumpDist = 0f
    private var jumpDirX = 0f
    private var jumpDirY = 0f
    private var jumpStartMs = 0L

    // 掉落
    private var fallStartMs = 0L

    // 得分飘字
    private var scorePop: ScorePop? = null

    // 相机(平滑跟随当前平台)
    private var camWX = 0f
    private var camWY = 0f

    // 投影与尺寸
    private var screenW = 0
    private var screenH = 0
    private var tileHalfW = 0f
    private var tileHalfH = 0f
    private var blockThickness = 0f
    private var jumpHeightPx = 0f

    private val palette = intArrayOf(
        0xFFF46B3D.toInt(), 0xFF3DB2E0.toInt(), 0xFF6BCB77.toInt(),
        0xFFFFD93D.toInt(), 0xFFA66BFF.toInt(), 0xFFFF8FB1.toInt(),
        0xFF4D96FF.toInt(), 0xFF00C2A8.toInt()
    )
    private var colorIndex = 0

    private val blockPaint = Paint()
    private val sidePaint = Paint()
    private val shadowPaint = Paint()
    private val playerPaint = Paint()
    private val playerOutlinePaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint()
    private val barBgPaint = Paint()
    private val targetPaint = Paint()
    private val scorePopPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bgGradient: LinearGradient? = null
    private val bgPaint = Paint()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0xFF232637.toInt())

        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true

        barBgPaint.color = 0x55FFFFFF.toInt()
        targetPaint.style = Paint.Style.STROKE
        targetPaint.strokeWidth = 4f
        targetPaint.color = 0xAAFFFFFF.toInt()

        scorePopPaint.textAlign = Paint.Align.CENTER
        scorePopPaint.isFakeBoldText = true

        playerPaint.color = 0xFF2B2D42.toInt()
        playerOutlinePaint.style = Paint.Style.STROKE
        playerOutlinePaint.strokeWidth = 2f
        playerOutlinePaint.color = 0xFFFFFFFF.toInt()

        resetGame()
    }

    private fun resetGame() {
        state = State.READY
        score = 0
        colorIndex = 0
        camWX = 0f
        camWY = 0f
        current = Block(0f, 0f, 0.7f, palette[0])
        blocks.clear()
        blocks.add(current)
        playerWX = current.wx
        playerWY = current.wy
        playerVy = 0f
        next = null
        spawnNext()
    }

    private fun nextColor(): Int {
        val c = palette[colorIndex % palette.size]
        colorIndex++
        return c
    }

    private fun spawnNext() {
        // 只向前(屏幕上方)跳:等距坐标下,「前」对应 wy 减小
        // (即世界坐标 y 轴负方向),再随机向左前/右前偏移。
        val dirs = arrayOf(
            floatArrayOf(-0.6f, -0.8f), floatArrayOf(0.6f, -0.8f),
            floatArrayOf(-0.2f, -0.98f), floatArrayOf(0.2f, -0.98f)
        )
        val dir = dirs[Random.nextInt(4)]
        val gap = Random.nextFloat() * (GAP_MAX - GAP_MIN) + GAP_MIN
        val half = HALF_SIZES[Random.nextInt(HALF_SIZES.size)]
        val wx = current.wx + dir[0] * gap
        val wy = current.wy + dir[1] * gap
        next = Block(wx, wy, half, nextColor())
        blocks.add(next!!)
    }

    private fun startCharge() {
        when (state) {
            State.READY -> {
                state = State.CHARGING
                chargeStartMs = System.currentTimeMillis()
                chargeRatio = 0f
                sound.setCharge(0.01f)
                val n = next ?: return
                // 从玩家当前实际位置出发,指向下一方块中心。
                val dx = n.wx - playerWX
                val dy = n.wy - playerWY
                val len = hypot(dx, dy)
                if (len == 0f) return
                jumpDirX = dx / len
                jumpDirY = dy / len
            }
            State.GAME_OVER -> resetGame()
            else -> Unit
        }
    }

    private fun releaseCharge() {
        if (state != State.CHARGING) return
        val dist = chargeRatio * MAX_JUMP_DIST
        jumpDist = dist
        jumpFromX = playerWX
        jumpFromY = playerWY
        jumpToX = jumpFromX + jumpDirX * dist
        jumpToY = jumpFromY + jumpDirY * dist
        jumpStartMs = System.currentTimeMillis()
        sound.stopCharge()
        sound.playSwoosh()
        state = State.JUMPING
    }

    private fun resolveJump() {
        val n = next ?: return
        // 落点相对下一平台中心的偏差(有符号):起跳点到平台中心的距离 vs 实际跳出的距离。
        val err = jumpDist - distanceBetweenPlayerAndBlock(n)
        if (abs(err) <= n.half * 0.98f) {
            val perfect = abs(err) <= n.half * 0.45f
            score += if (perfect) 2 else 1
            scorePop = ScorePop(
                jumpToX, jumpToY,
                if (perfect) "+2" else "+1",
                System.currentTimeMillis()
            )
            sound.playLand()
            current = n
            // 玩家停在真实落点(可能偏边缘或正中心),而非强制居中。
            playerWX = jumpToX
            playerWY = jumpToY
            playerVy = 0f
            spawnNext()
            state = State.READY
        } else {
            sound.stopCharge()
            state = State.FALLING
            fallStartMs = System.currentTimeMillis()
        }
    }

    private fun distanceBetweenPlayerAndBlock(b: Block): Float =
        hypot(b.wx - jumpFromX, b.wy - jumpFromY)

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt = if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(1L, 50L)
            lastFrameMs = now
            update(dt, now)
            invalidate()
            handler.postDelayed(this, 16L)
        }
    }

    private fun update(dt: Long, now: Long) {
        // 相机平滑跟随当前平台。
        val k = 0.08f
        camWX += (current.wx - camWX) * k
        camWY += (current.wy - camWY) * k

        when (state) {
            State.READY -> Unit
            State.CHARGING -> {
                val elapsed = now - chargeStartMs
                chargeRatio = (elapsed.toFloat() / MAX_CHARGE_MS).coerceIn(0f, 1f)
                sound.setCharge(chargeRatio)
            }
            State.JUMPING -> {
                val t = ((now - jumpStartMs).toFloat() / JUMP_DURATION).coerceIn(0f, 1f)
                playerWX = jumpFromX + (jumpToX - jumpFromX) * t
                playerWY = jumpFromY + (jumpToY - jumpFromY) * t
                playerVy = sin(PI.toFloat() * t) * jumpHeightPx
                if (t >= 1f) resolveJump()
            }
            State.FALLING -> {
                val t = ((now - fallStartMs).toFloat() / FALL_DURATION).coerceIn(0f, 1f)
                playerVy = -t * screenH * 0.6f
                if (t >= 1f) state = State.GAME_OVER
            }
            State.GAME_OVER -> sound.stopCharge()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isActionKey(keyCode)) {
            if (event.repeatCount == 0) startCharge()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isActionKey(keyCode)) {
            releaseCharge()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isActionKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startCharge()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseCharge()
        }
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        lastFrameMs = 0L
        sound.start()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        sound.release()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        screenW = w
        screenH = h
        tileHalfW = w / 8.0f
        tileHalfH = tileHalfW / 2.0f
        blockThickness = tileHalfW * 0.32f
        jumpHeightPx = tileHalfW * 0.55f
        bgGradient = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0xFF2E3150.toInt(), 0xFF1A1C2C.toInt(), Shader.TileMode.CLAMP
        )
    }

    // 世界坐标 -> 屏幕坐标(等距投影)。
    private fun projectX(wx: Float, wy: Float): Float =
        screenW / 2f + ((wx - camWX) - (wy - camWY)) * tileHalfW

    private fun projectY(wx: Float, wy: Float): Float =
        screenH * 0.45f + ((wx - camWX) + (wy - camWY)) * tileHalfH

    override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)

        // 由远及近绘制平台(painter's algorithm)。
        val sorted = blocks.sortedBy { it.wx + it.wy }
        for (b in sorted) drawBlock(canvas, b)

        drawPlayer(canvas)
        drawScorePop(canvas)
        drawHud(canvas)
    }

    private fun drawBackground(canvas: Canvas) {
        bgPaint.shader = bgGradient
        canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), bgPaint)
    }

    private fun drawBlock(canvas: Canvas, b: Block) {
        val cx = projectX(b.wx, b.wy)
        val cy = projectY(b.wx, b.wy)
        val hw = b.half * tileHalfW
        val hh = b.half * tileHalfH

        val topX = cx
        val topY = cy - hh
        val rightX = cx + hw
        val rightY = cy
        val bottomX = cx
        val bottomY = cy + hh
        val leftX = cx - hw
        val leftY = cy
        val t = blockThickness

        // 右前侧面(较亮)。
        sidePaint.color = shade(b.color, 0.72f)
        val rf = Path()
        rf.moveTo(rightX, rightY)
        rf.lineTo(bottomX, bottomY)
        rf.lineTo(bottomX, bottomY + t)
        rf.lineTo(rightX, rightY + t)
        rf.close()
        canvas.drawPath(rf, sidePaint)

        // 左前侧面(较暗)。
        sidePaint.color = shade(b.color, 0.56f)
        val lf = Path()
        lf.moveTo(leftX, leftY)
        lf.lineTo(bottomX, bottomY)
        lf.lineTo(bottomX, bottomY + t)
        lf.lineTo(leftX, leftY + t)
        lf.close()
        canvas.drawPath(lf, sidePaint)

        // 顶面。
        blockPaint.color = b.color
        val top = Path()
        top.moveTo(topX, topY)
        top.lineTo(rightX, rightY)
        top.lineTo(bottomX, bottomY)
        top.lineTo(leftX, leftY)
        top.close()
        canvas.drawPath(top, blockPaint)
    }

    private fun drawPlayer(canvas: Canvas) {
        val sx = projectX(playerWX, playerWY)
        val sy = projectY(playerWX, playerWY)

        // 地面阴影,随高度收缩。
        val ratio = (playerVy / (jumpHeightPx + 1f)).coerceIn(0f, 1f)
        val shadowScale = 0.4f + 0.6f * (1f - ratio)
        shadowPaint.color = 0x33000000
        val shadowRadius = tileHalfW * 0.16f * shadowScale
        canvas.drawOval(
            sx - shadowRadius, sy - shadowRadius * 0.5f,
            sx + shadowRadius, sy + shadowRadius * 0.5f, shadowPaint
        )

        val baseY = sy - playerVy
        val bodyR = tileHalfW * 0.16f
        val bodyH = tileHalfW * 0.32f
        val headR = bodyR * 0.7f

        playerPaint.color = 0xFF2B2D42.toInt()
        val body = RectF(sx - bodyR, baseY - bodyH, sx + bodyR, baseY)
        canvas.drawRoundRect(body, bodyR, bodyR, playerPaint)
        canvas.drawCircle(sx, baseY - bodyH - headR * 0.6f, headR, playerPaint)
        canvas.drawRoundRect(body, bodyR, bodyR, playerOutlinePaint)
        canvas.drawCircle(sx, baseY - bodyH - headR * 0.6f, headR, playerOutlinePaint)
    }

    private fun drawScorePop(canvas: Canvas) {
        val pop = scorePop ?: return
        val elapsed = System.currentTimeMillis() - pop.startMs
        val duration = 700f
        if (elapsed >= duration) {
            scorePop = null
            return
        }
        val t = elapsed / duration
        // 世界坐标上飘:等距里「向上」是 wy 减小,叠加屏幕 y 的额外偏移让动画更明显。
        val sx = projectX(pop.wx, pop.wy)
        val sy = projectY(pop.wx, pop.wy) - tileHalfW * 0.5f - t * tileHalfW * 0.6f
        scorePopPaint.textSize = tileHalfW * (0.45f + 0.15f * (1f - t))
        scorePopPaint.color = if (pop.text == "+2") 0xFFFFD93D.toInt() else Color.WHITE
        scorePopPaint.alpha = (255 * (1f - t)).toInt().coerceIn(0, 255)
        canvas.drawText(pop.text, sx, sy, scorePopPaint)
        scorePopPaint.alpha = 255
    }

    private fun drawHud(canvas: Canvas) {
        textPaint.textSize = tileHalfW * 0.6f
        canvas.drawText("得分 $score", screenW / 2f, tileHalfW * 0.9f, textPaint)

        if (state == State.CHARGING) {
            drawPowerBar(canvas)
            // 预估落点指示圈。
            val dist = chargeRatio * MAX_JUMP_DIST
            val tx = current.wx + jumpDirX * dist
            val ty = current.wy + jumpDirY * dist
            val sx = projectX(tx, ty)
            val sy = projectY(tx, ty)
            val r = tileHalfW * 0.14f
            canvas.drawCircle(sx, sy, r, targetPaint)
        }

        if (state == State.READY && score == 0) {
            textPaint.textSize = tileHalfW * 0.4f
            canvas.drawText(
                "按住屏幕或 OK 键蓄力,松开起跳",
                screenW / 2f, screenH * 0.8f, textPaint
            )
        }

        if (state == State.GAME_OVER) {
            canvas.drawColor(0xAA000000)
            textPaint.textSize = tileHalfW * 0.8f
            canvas.drawText("游戏结束", screenW / 2f, screenH * 0.4f, textPaint)
            textPaint.textSize = tileHalfW * 0.5f
            canvas.drawText("得分 $score", screenW / 2f, screenH * 0.48f, textPaint)
            canvas.drawText("按住屏幕或 OK 键重新开始", screenW / 2f, screenH * 0.56f, textPaint)
        }
    }

    private fun drawPowerBar(canvas: Canvas) {
        val w = screenW * 0.4f
        val h = tileHalfW * 0.35f
        val left = screenW / 2f - w / 2f
        val top = screenH * 0.86f
        canvas.drawRoundRect(left, top, left + w, top + h, h / 2f, h / 2f, barBgPaint)

        val fill = w * chargeRatio
        val hue = 120f * (1f - chargeRatio) // 绿 -> 红
        barPaint.color = Color.HSVToColor(0xFF, floatArrayOf(hue, 1f, 1f))
        canvas.drawRoundRect(left, top, left + fill, top + h, h / 2f, h / 2f, barPaint)
    }

    private fun shade(color: Int, factor: Float): Int {
        val r = ((color shr 16) and 0xFF) * factor
        val g = ((color shr 8) and 0xFF) * factor
        val b = (color and 0xFF) * factor
        return (0xFF000000.toInt() or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt())
    }

    companion object {
        private const val MAX_CHARGE_MS = 1400L
        private const val JUMP_DURATION = 360L
        private const val FALL_DURATION = 650L
        private const val GAP_MIN = 1.8f
        private const val GAP_MAX = 2.8f
        private const val MAX_JUMP_DIST = 3.6f
        private val HALF_SIZES = floatArrayOf(0.5f, 0.7f, 0.9f)
    }
}
