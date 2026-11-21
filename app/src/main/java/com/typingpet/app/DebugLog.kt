package com.typingpet.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「コピーが検知できない」といった問題を調べるための記録。
 *
 * アクセシビリティで実際にどんなイベントが来ているかを残す。
 * 設定でONにしたときだけ動き、記録は端末内のアプリ専用領域に留まる(外部送信しない)。
 */
object DebugLog {

    private const val FILE_NAME = "detect_debug.log"
    private const val MAX_BYTES = 256 * 1024L
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(context: Context, line: String) {
        try {
            val f = File(context.filesDir, FILE_NAME)
            // 大きくなりすぎたら古い方を捨てる
            if (f.exists() && f.length() > MAX_BYTES) {
                val keep = f.readLines().takeLast(200)
                f.writeText(keep.joinToString("\n") + "\n")
            }
            val stamp = timeFormat.format(Date())
            f.appendText("$stamp  $line\n")
        } catch (e: Throwable) {
            // 記録できなくても本来の動作には影響させない
        }
    }

    /** 記録の全文を返す */
    @Synchronized
    fun read(context: Context): String {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (f.exists()) f.readText() else ""
        } catch (e: Throwable) {
            ""
        }
    }

    /** 記録を消す */
    @Synchronized
    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Throwable) {
        }
    }

    /** 直近の記録だけ返す(画面表示用) */
    fun tail(context: Context, lines: Int = 120): String {
        val all = read(context)
        if (all.isEmpty()) return ""
        return all.lines().takeLast(lines).joinToString("\n")
    }
}