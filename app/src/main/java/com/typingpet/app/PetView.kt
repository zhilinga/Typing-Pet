package com.typingpet.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * タイピングに反応するペット。
 * - 待機中: 待機イラストからランダムで1枚
 * - 入力中: 1番目→2番目→…の順に切り替え。各番号では登録された別パターンからランダムで1枚
 * - 文字で切り替え: 登録文字が入力されたら、その画像からランダムで1枚
 * 入力が IDLE_REVERT_DELAY_MS 止まると待機に戻り、次は1番目から始まる。
 * 画像が1枚もなければ内蔵イラスト(4色 x 表情)で動く。
 *
 * GIF / アニメーションWebP も同じ枠に入れられる。動画を表示している間は
 * 毎フレーム再描画してコマを進める。
 */
class PetView(context: Context) : View(context) {

    companion object {
        private const val IDLE_REVERT_DELAY_MS = 700L
        private const val BOUNCE_DURATION_MS = 420L

        /** 動画を出している間の再描画間隔(約30fps) */
        private const val ANIM_FRAME_MS = 33L
    }

    var preset = 0
        set(value) { field = value; invalidate() }

    /** 揺れの強さ 0(なし・画像だけ切り替え)〜3(大きく弾む) */
    var shakeLevel = 3

    /** 足元の影を表示するか */
    var showShadow = true
        set(value) { field = value; invalidate() }

    private var idleFrames: List<PetFrame> = emptyList()
    private var steps: List<List<PetFrame>> = emptyList()
    private var triggerFrames: List<List<PetFrame>> = emptyList()

    private var stepIndex = -1
    private var idleFrame: PetFrame? = null
    private var shown: PetFrame? = null
    private var builtInFace = 0

    /** 表示中の絵を出し始めた時刻(動画のコマ送りに使う) */
    private var shownSince = 0L

    /** 動画を出している間だけ回す再描画用 */
    private val animHandler = Handler(Looper.getMainLooper())
    private val animTick = object : Runnable {
        override fun run() {
            invalidate()
            animHandler.postDelayed(this, ANIM_FRAME_MS)
        }
    }

    private var scaleXAnim = 1f
    private var scaleYAnim = 1f
    private var bounceAnimator: ValueAnimator? = null

    private val revertHandler = Handler(Looper.getMainLooper())
    private val revertRunnable = Runnable { goIdle() }

    private val presetColors = listOf(
        "#FF9D6C" to "#7CC9A9",
        "#6CA8FF" to "#FFD76C",
        "#FF8FC7" to "#B98FFF",
        "#8FD9A8" to "#FFB26C"
    )

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cheekPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A2E2C"); style = Paint.Style.STROKE
        strokeWidth = 8f; strokeCap = Paint.Cap.ROUND
    }
    private val inkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A2E2C") }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#14000000") }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#59FFFFFF") }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()

    /**
     * 画像一式を差し替える(設定変更時にOverlayServiceから呼ばれる)。
     * triggers は Prefs.getTriggers と同じ並びにすること(番号で対応づけるため)。
     */
    fun setImages(idle: List<PetFrame>, steps: List<List<PetFrame>>, triggers: List<List<PetFrame>>) {
        this.idleFrames = idle
        this.steps = steps.filter { it.isNotEmpty() }
        this.triggerFrames = triggers
        goIdle()
    }

    /** 表示する絵を差し替え、動画ならコマ送りを開始する */
    private fun show(frame: PetFrame?) {
        if (shown !== frame) {
            shown?.attachHost(null)
            shownSince = System.currentTimeMillis()
        }
        shown = frame
        frame?.attachHost(this)
        syncAnimation()
        invalidate()
    }

    /** 自前でコマ送りが必要な動画(Movie)のときだけ再描画ループを回す */
    private fun syncAnimation() {
        animHandler.removeCallbacks(animTick)
        if (shown?.needsManualTick == true) animHandler.postDelayed(animTick, ANIM_FRAME_MS)
    }

    private fun goIdle() {
        revertHandler.removeCallbacks(revertRunnable)
        stepIndex = -1
        builtInFace = 0
        idleFrame = idleFrames.randomOrNull() ?: steps.firstOrNull()?.randomOrNull()
        show(idleFrame)
    }

    /**
     * 打鍵イベントごとに呼ぶ。trigger に「文字で切り替え」の番号を渡すと、その画像からランダムで表示する。
     */
    fun react(trigger: Int = -1) {
        revertHandler.removeCallbacks(revertRunnable)

        if (steps.isNotEmpty()) stepIndex = (stepIndex + 1) % steps.size
        builtInFace = (builtInFace % 3) + 1 // 内蔵イラストは入力中 表情1〜3を循環

        val triggerFrame = if (trigger >= 0) triggerFrames.getOrNull(trigger)?.randomOrNull() else null
        val stepFrame = steps.getOrNull(stepIndex)?.randomOrNull()

        show(triggerFrame ?: stepFrame ?: idleFrame)

        revertHandler.postDelayed(revertRunnable, IDLE_REVERT_DELAY_MS)

        if (shakeLevel > 0) playBounce()
    }

    /** 縦に潰れて横に伸び、オーバーシュートしながら戻る「もちもち」アニメ */
    private fun playBounce() {
        bounceAnimator?.cancel()

        val squish = when (shakeLevel) { 1 -> 0.95f; 2 -> 0.90f; else -> 0.82f }
        val stretch = 1f + (1f - squish) * 0.7f
        val tension = when (shakeLevel) { 1 -> 1.4f; 2 -> 1.8f; else -> 2.4f }

        bounceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BOUNCE_DURATION_MS
            interpolator = OvershootInterpolator(tension)
            addUpdateListener {
                val t = it.animatedValue as Float
                scaleYAnim = squish + (1f - squish) * t
                scaleXAnim = stretch + (1f - stretch) * t
                invalidate()
            }
        }
        bounceAnimator?.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        revertHandler.removeCallbacks(revertRunnable)
        animHandler.removeCallbacks(animTick)
        shown?.attachHost(null)
        bounceAnimator?.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        shown?.attachHost(this)
        syncAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        canvas.save()
        canvas.scale(scaleXAnim, scaleYAnim, cx, cy + height * 0.16f)

        val frame = shown
        if (frame != null) drawFrame(canvas, cx, cy, frame) else drawBuiltIn(canvas, cx, cy)

        canvas.restore()
    }

    private fun drawFrame(canvas: Canvas, cx: Float, cy: Float, frame: PetFrame) {
        val size = minOf(width, height).toFloat()
        val longest = maxOf(frame.width, frame.height).coerceAtLeast(1)
        val scale = size / longest
        val w = frame.width * scale
        val h = frame.height * scale

        if (showShadow) {
            canvas.drawOval(
                RectF(cx - w * 0.4f, cy + h * 0.42f, cx + w * 0.4f, cy + h * 0.5f),
                shadowPaint
            )
        }

        dstRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        frame.draw(canvas, dstRect, framePaint, System.currentTimeMillis() - shownSince)
    }

    private fun drawBuiltIn(canvas: Canvas, cx: Float, cy: Float) {
        val r = minOf(width, height) * 0.34f
        val colors = presetColors[preset.coerceIn(0, presetColors.size - 1)]
        bodyPaint.color = Color.parseColor(colors.first)
        cheekPaint.color = Color.parseColor(colors.second)

        if (showShadow) canvas.drawOval(RectF(cx - r * 0.8f, cy + r * 1.05f, cx + r * 0.8f, cy + r * 1.3f), shadowPaint)

        canvas.drawCircle(cx - r * 0.75f, cy - r * 0.85f, r * 0.28f, bodyPaint)
        canvas.drawCircle(cx + r * 0.75f, cy - r * 0.85f, r * 0.28f, bodyPaint)
        canvas.drawCircle(cx - r * 0.75f, cy - r * 0.85f, r * 0.14f, cheekPaint)
        canvas.drawCircle(cx + r * 0.75f, cy - r * 0.85f, r * 0.14f, cheekPaint)

        canvas.drawCircle(cx, cy, r, bodyPaint)

        canvas.drawCircle(cx - r * 0.62f, cy + r * 0.18f, r * 0.14f, highlightPaint)
        canvas.drawCircle(cx + r * 0.62f, cy + r * 0.18f, r * 0.14f, highlightPaint)

        drawFace(canvas, cx, cy, r)
    }

    private fun drawFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val eyeY = cy - r * 0.12f
        val eyeDx = r * 0.45f
        val eyeR = r * 0.12f
        val mouthY = cy + r * 0.42f

        when (builtInFace % 4) {
            0 -> {
                canvas.drawCircle(cx - eyeDx, eyeY, eyeR, inkFill)
                canvas.drawCircle(cx + eyeDx, eyeY, eyeR, inkFill)
                canvas.drawArc(RectF(cx - r * 0.28f, mouthY - r * 0.1f, cx + r * 0.28f, mouthY + r * 0.18f), 20f, 140f, false, inkPaint)
            }
            1 -> {
                canvas.drawCircle(cx - eyeDx, eyeY, eyeR * 1.3f, inkFill)
                canvas.drawCircle(cx + eyeDx, eyeY, eyeR * 1.3f, inkFill)
                canvas.drawOval(RectF(cx - r * 0.16f, mouthY - r * 0.14f, cx + r * 0.16f, mouthY + r * 0.14f), inkFill)
            }
            2 -> {
                canvas.drawArc(RectF(cx - eyeDx - eyeR, eyeY - eyeR, cx - eyeDx + eyeR, eyeY + eyeR), 200f, 140f, false, inkPaint)
                canvas.drawArc(RectF(cx + eyeDx - eyeR, eyeY - eyeR, cx + eyeDx + eyeR, eyeY + eyeR), 200f, 140f, false, inkPaint)
                canvas.drawArc(RectF(cx - r * 0.3f, mouthY - r * 0.16f, cx + r * 0.3f, mouthY + r * 0.24f), 15f, 150f, false, inkPaint)
            }
            3 -> {
                canvas.drawCircle(cx - eyeDx, eyeY, eyeR, inkFill)
                canvas.drawArc(RectF(cx + eyeDx - eyeR, eyeY - eyeR * 0.4f, cx + eyeDx + eyeR, eyeY + eyeR * 0.4f), 200f, 140f, false, inkPaint)
                canvas.drawArc(RectF(cx - r * 0.26f, mouthY - r * 0.08f, cx + r * 0.26f, mouthY + r * 0.2f), 20f, 140f, false, inkPaint)
            }
        }
    }
}