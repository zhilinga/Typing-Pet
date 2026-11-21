package com.typingpet.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class PetAccessibilityService : AccessibilityService() {

    companion object {
        /** これ文字数以上が一度に入れば、変換確定ではなく貼り付けとみなす */
        private const val PASTE_ALWAYS_LEN = 12

        /** 貼り付け判定に使う最小文字数(これ未満は1文字入力とみなす) */
        private const val PASTE_MIN_LEN = 2

        /** 前回の入力からこれだけ間が空いていれば、まとめ入力=貼り付けとみなす(ミリ秒) */
        private const val PASTE_IDLE_GAP_MS = 600L
    }

    /** 直近に文字変化を処理した時刻(貼り付け判定用) */
    private var lastTextChangeAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (OverlayService.instance == null && Prefs.getAlwaysOnTop(this)) {
            startService(Intent(this, OverlayService::class.java))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (OverlayService.instance == null) return

        // 表示中のキャラに文字の反応も特殊キーもない / パスワード欄 → 中身は一切見ない
        val maxLen = OverlayService.maxKeyLength
        val watchDelete = OverlayService.watchDelete
        val watchSpace = OverlayService.watchSpace
        val watchEnter = OverlayService.watchEnter
        val watchPaste = OverlayService.watchPaste
        val anySpecial = watchDelete || watchSpace || watchEnter || watchPaste
        if (event.isPassword || (maxLen <= 0 && !anySpecial)) {
            OverlayService.reactIfRunning()
            return
        }

        val after = event.text?.firstOrNull()?.toString()
        val change = after?.let { findChange(event, it) }
        if (after == null || change == null) {
            OverlayService.reactIfRunning()
            return
        }

        // 新しい文字は何も入らず、文字が消えただけ → 「消したとき」
        if (change.end == change.start) {
            if (change.removed > 0 && watchDelete) OverlayService.reactDelete() else OverlayService.reactIfRunning()
            return
        }

        val inserted = after.substring(change.start, change.end)
        val now = System.currentTimeMillis()
        // 最初の入力は「久しぶりのまとめ入力」と区別できないので gap 0 扱い
        val gap = if (lastTextChangeAt == 0L) 0L else now - lastTextChangeAt
        lastTextChangeAt = now

        // 貼り付け: 何も消さずにまとめて複数文字が入った。
        // ただし漢字変換などの確定もまとめて複数文字になるため、
        // 「前回の入力から間が空いている」または「明らかに長い」ときだけ貼り付けとみなす。
        if (watchPaste && change.removed == 0 && isPasteLike(inserted.length, gap)) {
            OverlayService.reactPaste()
            return
        }

        // 1文字だけ入ったときのスペース・改行
        if (inserted.length == 1) {
            when (inserted[0]) {
                ' ', '\u3000' -> if (watchSpace) { OverlayService.reactSpace(); return }
                '\n', '\r' -> if (watchEnter) { OverlayService.reactEnter(); return }
            }
        }

        if (maxLen <= 0) {
            OverlayService.reactIfRunning()
            return
        }

        // 入力位置の直前数文字(登録文字の最大長ぶん)だけを切り出して各キャラに渡す(保存・送信はしない)
        val winStart = maxOf(0, change.start - (maxLen - 1))
        OverlayService.reactText(after.substring(winStart, change.end), change.start - winStart)
    }

    /**
     * 今回の入力を「貼り付け」とみなすか。
     * len: 一度に入った文字数 / gapMs: 前回の文字変化からの経過ミリ秒
     */
    private fun isPasteLike(len: Int, gapMs: Long): Boolean {
        if (len < 2) return false
        if (len >= PASTE_ALWAYS_LEN) return true      // 長すぎれば変換ではありえない → 貼り付け
        if (len < PASTE_MIN_LEN) return false          // 2〜4文字は変換確定のことが多い
        return gapMs >= PASTE_IDLE_GAP_MS              // 入力が止まった後のまとめ入力 = 貼り付け
    }

    /** 今回の変更: 入力された範囲 [start, end) と、消えた文字数 */
    private class Change(val start: Int, val end: Int, val removed: Int)

    /**
     * 今回の変更を求める。
     * 変換中や予測変換でもズレにくいよう、まずは変更前後の文字列の差分から求め、
     * 取れない場合だけ fromIndex/addedCount/removedCount を使う。
     * (「ねむい」→「眠い」のような変換は、新しい文字が入るので「消した」にはならない)
     */
    private fun findChange(event: AccessibilityEvent, after: String): Change? {
        val before = event.beforeText?.toString()
        if (before != null) {
            var prefix = 0
            val maxPrefix = minOf(before.length, after.length)
            while (prefix < maxPrefix && before[prefix] == after[prefix]) prefix++

            var suffix = 0
            val maxSuffix = minOf(before.length - prefix, after.length - prefix)
            while (suffix < maxSuffix &&
                before[before.length - 1 - suffix] == after[after.length - 1 - suffix]
            ) suffix++

            val end = after.length - suffix
            val removed = before.length - prefix - suffix
            if (end > prefix || removed > 0) return Change(prefix, end, removed)
        }

        val from = event.fromIndex
        val count = event.addedCount
        val removed = event.removedCount
        if (from >= 0 && count > 0 && from + count <= after.length) return Change(from, from + count, removed)
        if (from >= 0 && count == 0 && removed > 0) return Change(from, from, removed)
        return null
    }

    override fun onInterrupt() {}
}