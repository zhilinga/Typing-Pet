package com.typingpet.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 入力の検知。
 *
 * アプリによって通知の出し方が違うので、複数の手がかりを組み合わせる:
 *  1. TYPE_VIEW_TEXT_CHANGED      … 普通の入力欄。文字の増減が分かる
 *  2. TYPE_VIEW_TEXT_SELECTION_CHANGED … WebView や一部の自前入力欄は
 *     文字変化を送らずカーソル位置の変化だけを送る。これで打鍵を推定する
 *  3. TYPE_VIEW_CLICKED           … IME の「送信/検索」で入力欄が空になる形の改行を拾う
 *
 * 入力内容は保存も送信もしない。パスワード欄(isPassword)は一切見ない。
 */
class PetAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * 貼り付けとみなす最小文字数。
         * 1文字だけの貼り付けは普通の入力と区別できないので対象外。
         */
        private const val PASTE_MIN_LEN = 2

        /**
         * 選択範囲を置換する形の貼り付け(何かが消えたと同時に入る)を
         * 貼り付けとみなす文字数。変換確定は普通 1〜4 文字なので、それより明確に多くする。
         */
        private const val PASTE_REPLACE_LEN = 8

        /** 直前の状態を忘れるまでの時間。別の入力欄に移ったと判断する */
        private const val STATE_RESET_MS = 4000L
    }

    /** テキスト変化が来ないアプリ向け: 前回のカーソル位置と文字数 */
    private var lastSelStart = -1
    private var lastSelEnd = -1
    private var lastTextLength = -1

    /** 最後に「内容が空でない入力欄」を見た時刻。送信によるクリア判定に使う */
    private var lastNonEmptyAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (OverlayService.instance == null && Prefs.getAlwaysOnTop(this)) {
            startService(Intent(this, OverlayService::class.java))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (OverlayService.instance == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextChanged(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> onSelectionChanged(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClicked(event)
        }
    }

    // ---------- 通常の文字変化 ----------

    private fun onTextChanged(event: AccessibilityEvent) {
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

        rememberTextLength(after.length, event.isPassword)

        // 新しい文字は何も入らず、文字が消えただけ → 「消したとき」
        if (change.end == change.start) {
            if (change.removed > 0 && watchDelete) OverlayService.reactDelete() else OverlayService.reactIfRunning()
            return
        }

        val inserted = after.substring(change.start, change.end)

        // 貼り付け判定。アクセシビリティイベントに「貼り付け」を示す情報は無いので、
        // 「一度に入った文字数」と「同時に消えた文字数」から推定する。
        //  - 変換確定(拼音→漢字): 拼音が消えて漢字が入る → removed > 0、入るのは数文字
        //  - 貼り付け:           消さずにまとめて入る   → removed == 0、入るのは2文字以上
        //  - 選択範囲を置換して貼り付け: removed > 0 だが入る文字数が明らかに多い
        if (watchPaste) {
            val pasted = if (change.removed == 0) {
                inserted.length >= PASTE_MIN_LEN
            } else {
                inserted.length >= PASTE_REPLACE_LEN
            }
            if (pasted) {
                OverlayService.reactPaste()
                return
            }
        }

        // 1文字だけ入ったときのスペース・改行
        if (inserted.length == 1) {
            when (inserted[0]) {
                ' ', '\u3000' -> if (watchSpace) { OverlayService.reactSpace(); return }
                '\n', '\r' -> if (watchEnter) { OverlayService.reactEnter(); return }
            }
        }
        // 改行がまとめて入る形(貼り付け以外では稀)
        if (inserted.length > 1 && inserted.all { it == '\n' || it == '\r' }) {
            if (watchEnter) { OverlayService.reactEnter(); return }
        }

        if (maxLen <= 0) {
            OverlayService.reactIfRunning()
            return
        }

        // 入力位置の直前数文字(登録文字の最大長ぶん)だけを切り出して各キャラに渡す(保存・送信はしない)
        val winStart = maxOf(0, change.start - (maxLen - 1))
        OverlayService.reactText(after.substring(winStart, change.end), change.start - winStart)
    }

    // ---------- 文字変化を送らないアプリ向け: カーソル位置の変化で打鍵を推定 ----------

    /**
     * WebView や一部の自前入力欄は TYPE_VIEW_TEXT_CHANGED を送らないが、
     * カーソル位置の変化(TYPE_VIEW_TEXT_SELECTION_CHANGED)は送ってくる。
     * ここでは「カーソルが後ろに動いた = 何か入力された」とだけ判断して反応させる。
     * 入力内容は読まない(text を参照しない)ので、文字そのものは一切扱わない。
     */
    private fun onSelectionChanged(event: AccessibilityEvent) {
        // この推定はアプリによっては二重反応するので、設定でONにした時だけ動かす
        if (!Prefs.getSelectionFallback(this)) return
        // パスワード欄は見ない
        if (event.isPassword) return
        if (OverlayService.maxKeyLength <= 0 && !anySpecialWatched()) return

        val start = event.fromIndex
        val end = event.toIndex
        if (start < 0 || end < 0) return

        val now = System.currentTimeMillis()
        val prevStart = lastSelStart
        val prevEnd = lastSelEnd
        lastSelStart = start
        lastSelEnd = end

        // 初回・入力欄の切り替え直後は判断材料がないので何もしない
        if (prevStart < 0) return

        // カーソルが後ろへ動いた → 文字が入った可能性が高い
        if (start > prevStart || end > prevEnd) {
            lastNonEmptyAt = now
            // 文字種は分からないので、文字反応は「入力があったこと」だけを伝える
            if (OverlayService.maxKeyLength > 0) OverlayService.reactIfRunning()
            return
        }

        // カーソルが前へ動いた → 削除された可能性
        if (start < prevStart && OverlayService.watchDelete) {
            OverlayService.reactDelete()
            return
        }
    }

    // ---------- 送信ボタンで入力欄が空になる形の改行 ----------

    /**
     * 単一行の入力欄では、IME の「送信/検索」を押しても改行文字は入らず、
     * 入力欄が空になって閉じる。これを「改行」として扱う。
     * 直前まで内容があり、急に空になった瞬間だけを対象にするので、
     * 手で全消しした場合と区別しにくい点は残る(そのため短い時間窓で判定する)。
     */
    private fun onClicked(event: AccessibilityEvent) {
        if (!OverlayService.watchEnter) return
        if (!Prefs.getEnterOnSubmit(this)) return
        if (event.isPassword) return

        val text = event.text?.joinToString("") ?: return
        val now = System.currentTimeMillis()

        // クリック時に中身がある入力欄 → 記録だけ
        if (text.isNotEmpty()) {
            lastNonEmptyAt = now
            return
        }

        // 空の入力欄がクリックされた。直前に中身があったなら送信で消えた可能性が高い
        if (lastNonEmptyAt > 0 && now - lastNonEmptyAt <= STATE_RESET_MS) {
            lastNonEmptyAt = 0
            OverlayService.reactEnter()
        }
    }

    private fun anySpecialWatched(): Boolean =
        OverlayService.watchDelete || OverlayService.watchSpace ||
            OverlayService.watchEnter || OverlayService.watchPaste

    /** 入力欄の文字数を覚えておく(送信クリア判定用) */
    private fun rememberTextLength(len: Int, isPassword: Boolean) {
        lastTextLength = len
        if (!isPassword && len > 0) lastNonEmptyAt = System.currentTimeMillis()
    }

    /** 今回の変更: 入力された範囲 [start, end) と、消えた文字数 */
    private class Change(val start: Int, val end: Int, val removed: Int)

    /**
     * 今回の変更を求める。
     * 変換中や予測変換でもズレにくいよう、まずは変更前後の文字列の差分から求め、
     * 取れない場合だけ fromIndex/addedCount/removedCount を使う。
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