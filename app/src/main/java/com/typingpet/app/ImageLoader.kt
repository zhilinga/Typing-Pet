package com.typingpet.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.view.View
import android.graphics.ImageDecoder

/**
 * 表示用の1枚(静止画)または動画。
 * 静止画も動画も同じ扱いにして、PetView はこれを描くだけでよい。
 */
interface PetFrame {
    val width: Int
    val height: Int

    /** 時間で絵が変わるか */
    val isAnimated: Boolean

    /**
     * 表示側が自前で再描画ループを回す必要があるか。
     * Movie のように「描くたびに時刻を渡す」方式なら true、
     * AnimatedImageDrawable のように自分で時計を持つなら false。
     */
    val needsManualTick: Boolean

    /** サムネイル用(動画は1コマ目) */
    val preview: Bitmap?

    /**
     * dst の矩形いっぱいに描く。
     * timeMs は「この図を表示し始めてからの経過時間」。
     */
    fun draw(canvas: Canvas, dst: RectF, paint: Paint, timeMs: Long)

    /** 画面に出す View を教える(自分で時計を持つ動画が再描画要求を送る先) */
    fun attachHost(view: View?) {}
}

/** 静止画 */
class StaticFrame(private val bmp: Bitmap) : PetFrame {
    override val width: Int get() = bmp.width
    override val height: Int get() = bmp.height
    override val isAnimated: Boolean get() = false
    override val needsManualTick: Boolean get() = false
    override val preview: Bitmap get() = bmp

    override fun draw(canvas: Canvas, dst: RectF, paint: Paint, timeMs: Long) {
        canvas.drawBitmap(bmp, null, dst, paint)
    }
}

/**
 * GIF。Android 9 未満でも動かせるよう Movie で描く。
 * Movie は元画像の座標系で描くので、canvas を拡大縮小して合わせる。
 * 時刻は呼び出し側から渡されるので、表示側が再描画を回す必要がある。
 */
class MovieFrame(
    private val movie: Movie,
    private val maxPx: Int
) : PetFrame {

    private val srcW: Int = movie.width().coerceAtLeast(1)
    private val srcH: Int = movie.height().coerceAtLeast(1)

    /** 大きすぎる GIF をそのままの解像度で持たないための倍率 */
    private val scale: Float = run {
        val longest = maxOf(srcW, srcH)
        if (longest > maxPx && maxPx > 0) maxPx.toFloat() / longest else 1f
    }

    override val width: Int get() = (srcW * scale).toInt().coerceAtLeast(1)
    override val height: Int get() = (srcH * scale).toInt().coerceAtLeast(1)
    override val isAnimated: Boolean get() = movie.duration() > 0
    override val needsManualTick: Boolean get() = isAnimated
    override val preview: Bitmap? by lazy { firstFrame() }

    private fun firstFrame(): Bitmap? = try {
        val w = width
        val h = height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        movie.setTime(0)
        c.scale(w.toFloat() / srcW, h.toFloat() / srcH)
        movie.draw(c, 0f, 0f)
        bmp
    } catch (e: Throwable) {
        null
    }

    override fun draw(canvas: Canvas, dst: RectF, paint: Paint, timeMs: Long) {
        val dur = movie.duration()
        movie.setTime(if (dur > 0) (timeMs % dur).toInt() else 0)

        canvas.save()
        canvas.clipRect(dst)
        canvas.translate(dst.left, dst.top)
        canvas.scale(dst.width() / srcW, dst.height() / srcH)
        movie.draw(canvas, 0f, 0f, paint)
        canvas.restore()
    }
}

/** 画像を必要なサイズまで縮小して読み込む(枚数が増えてもメモリを食いすぎないように) */
object ImageLoader {

    fun load(context: Context, uriStr: String, reqPx: Int): Bitmap? = try {
        val uri = Uri.parse(uriStr)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            while (bounds.outWidth / (sample * 2) >= reqPx && bounds.outHeight / (sample * 2) >= reqPx) {
                sample *= 2
            }
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }

    /**
     * 静止画も動画(GIF / アニメーションWebP)も読む。
     * 動画として読めなければ静止画として読む。
     */
    fun loadFrame(context: Context, uriStr: String, reqPx: Int): PetFrame? {
        val uri = try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            return null
        }

        val frame = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                loadModern(context, uri, reqPx)
            } else {
                loadLegacy(context, uri, reqPx)
            }
        } catch (e: Throwable) {
            null
        }
        if (frame != null) return frame

        // 動画として読めなかった → 静止画として読む
        return load(context, uriStr, reqPx)?.let { StaticFrame(it) }
    }

    /** Android 9 以降は ImageDecoder が GIF / アニメーションWebP / APNG をまとめて扱える */
    @Suppress("NewApi")
    private fun loadModern(context: Context, uri: Uri, reqPx: Int): PetFrame? {
        val cr = context.contentResolver
        val source = ImageDecoder.createSource(cr, uri)

        // 目标尺寸在解码回调里算：那里能拿到原始宽高，且能直接 setTargetSize
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val sw = info.size.width
            val sh = info.size.height
            if (sw > 0 && sh > 0 && reqPx > 0) {
                val longest = maxOf(sw, sh)
                if (longest > reqPx) {
                    val tw = (sw.toLong() * reqPx / longest).toInt().coerceAtLeast(1)
                    val th = (sh.toLong() * reqPx / longest).toInt().coerceAtLeast(1)
                    decoder.setTargetSize(tw, th)
                }
            }
            // ソフトウェアビットマップにしておく(縮小描画とプレビュー生成で扱いやすい)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

        val dw = drawable.intrinsicWidth
        val dh = drawable.intrinsicHeight

        return when (drawable) {
            is BitmapDrawable -> drawable.bitmap?.let { StaticFrame(it) }
            else -> AnimatedFrame.create(
                drawable,
                if (dw > 0) dw else 1,
                if (dh > 0) dh else 1
            )
        }
    }

    /** Android 8 以前は GIF だけ Movie で読む(アニメーションWebP は非対応) */
    @Suppress("DEPRECATION")
    private fun loadLegacy(context: Context, uri: Uri, reqPx: Int): PetFrame? {
        val isGif = try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val head = ByteArray(6)
                val n = ins.read(head)
                n >= 6 && (String(head, 0, 6, Charsets.US_ASCII) == "GIF87a" ||
                    String(head, 0, 6, Charsets.US_ASCII) == "GIF89a")
            } ?: false
        } catch (e: Exception) {
            false
        }

        if (isGif) {
            val movie = try {
                context.contentResolver.openInputStream(uri)?.use { Movie.decodeStream(it) }
            } catch (e: Throwable) {
                null
            }
            if (movie != null && movie.width() > 0 && movie.height() > 0) {
                return MovieFrame(movie, if (reqPx > 0) reqPx else 260)
            }
        }
        return null
    }
}