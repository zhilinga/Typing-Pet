package com.typingpet.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View

/**
 * GIF / アニメーションWebP / APNG のような「時間で絵が変わる画像」。
 *
 * Android 9 以降は ImageDecoder が返す AnimatedImageDrawable をそのまま使う。
 * コマ送りは Drawable 自身の時計で進むので、invalidate を受け取れるよう
 * 描画先の View を Callback として渡す。
 */
class AnimatedFrame private constructor(
    private val rawDrawable: Drawable,
    override val width: Int,
    override val height: Int
) : PetFrame {

    /**
     * 型を Drawable で受けて中身だけ AnimatedImageDrawable として使う。
     * こうしておくと、API 28 未満でこのクラスが読み込まれても
     * 存在しないクラスを解決しにいかない。
     */
    private val drawable: AnimatedImageDrawable get() = rawDrawable as AnimatedImageDrawable

    private val bounds = Rect()
    private var host: View? = null

    /** Drawable 側の都合で再描画を要求してくるので、それを View に伝える */
    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            host?.invalidate()
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            host?.postDelayed(what, `when` - android.os.SystemClock.uptimeMillis())
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            host?.removeCallbacks(what)
        }
    }

    override val isAnimated: Boolean get() = true

    /** 自前でコマ送りする必要はない(AnimatedImageDrawable が自分で進む) */
    override val needsManualTick: Boolean get() = false

    /** サムネイル用。1コマ目を1枚のビットマップに描き出す */
    override val preview: Bitmap? by lazy {
        try {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val keep = Rect(drawable.bounds)
            drawable.bounds = Rect(0, 0, width, height)
            drawable.draw(c)
            drawable.bounds = keep
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    override fun draw(canvas: Canvas, dst: RectF, paint: Paint, timeMs: Long) {
        bounds.set(
            dst.left.toInt(), dst.top.toInt(),
            dst.right.toInt(), dst.bottom.toInt()
        )
        drawable.bounds = bounds
        drawable.draw(canvas)
    }

    /** 描画先の View を覚えておく(再描画要求の宛先) */
    override fun attachHost(view: View?) {
        if (host === view) return
        host = view
        if (view != null) {
            drawable.callback = callback
            try {
                if (!drawable.isRunning) drawable.start()
            } catch (e: Throwable) {
            }
        } else {
            try {
                if (drawable.isRunning) drawable.stop()
            } catch (e: Throwable) {
            }
            drawable.callback = null
        }
    }

    companion object {
        /**
         * ImageDecoder が返した Drawable を PetFrame に変換する。
         * アニメーションでなければ null を返す(呼び出し側が静止画として扱う)。
         */
        fun create(drawable: Drawable, width: Int, height: Int): PetFrame? {
            if (width <= 0 || height <= 0) return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            if (drawable !is AnimatedImageDrawable) return null

            return try {
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                AnimatedFrame(drawable, width, height)
            } catch (e: Throwable) {
                null
            }
        }
    }
}