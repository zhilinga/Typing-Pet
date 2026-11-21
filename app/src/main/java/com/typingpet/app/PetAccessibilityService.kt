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
         * 選択範囲を置換する形の貼り付け(何かが消えたと同時に入る)を
         * 貼り付けとみなす文字数。変換確定は普通 1〜4 文字なので、それより明確に多くする。
         */
        private const val PASTE_REPLACE_LEN = 8

        /** 直前の状態を忘れるまでの時間。別の入力欄に移ったと判断する */
        private const val STATE_RESET_MS = 4000L

        /**
         * 入力欄が空になったとき、「削除」と確定するまでの待ち時間。
         * 送信ボタンは「文字を消す」→「空の入力欄をクリック」の順に来ることが多く、
         * すぐ削除と決めると送信が削除として反応してしまう。
         * この時間だけ待って、その間にクリックが来なければ削除とみなす。
         */
        private const val CLEAR_CONFIRM_MS = 220L

        /** 拼音らしさを判定するときに見る、入力位置より前の文字数 */
        private const val PINYIN_LOOKBEHIND = 24
    }

    /** 貼り付け判定に使う最小文字数(設定値を反映) */
    private fun pasteMinLen(): Int = Prefs.getPasteMinLen(this)

    /**
     * いま入力中の文字列が「拼音の入力途中」らしいか。
     *
     * 一部の入力法は拼音を打つときに「字母＋スペース」をまとめて入れるため、
     * 2文字以上が一度に入った = 貼り付け、と単純に判定すると毎回貼り付けになる。
     * 入力位置の直前を見て、ラテン小文字とスペースだけが並んでいるなら
     * 拼音の入力途中と判断して、貼り付け扱いにしない。
     *
     * 例: "ni hao " の直後 → 拼音らしい / "Hello world" の直後 → 単語間の空白であり得るが
     *     大文字や記号が混ざるので拼音とは判定しにくい
     */
    private fun looksLikePinyinInput(text: String, insertAt: Int, inserted: String): Boolean {
        // 今回入った文字に、拼音として不自然なもの(漢字・記号・大文字)が混ざっていたら拼音ではない
        if (inserted.any { it.isUpperCase() }) return false
        if (!inserted.all { it.isLetter() || it == ' ' || it == '\u3000' || it == '\'' }) return false

        val from = maxOf(0, insertAt - PINYIN_LOOKBEHIND)
        val window = text.substring(from, insertAt)
        if (window.isEmpty()) {
            // 直前が空 = 入力欄の先頭。1〜3文字のラテン小文字だけなら拼音の打ち始め
            val core = inserted.trim()
            return core.isNotEmpty() && core.length <= 3 && core.all { it in 'a'..'z' }
        }

        // 直前のウィンドウがラテン小文字・スペース・アポストロフィのみで構成されているか
        val tail = window.takeLast(PINYIN_LOOKBEHIND)
        val onlyPinyinChars = tail.all {
            it in 'a'..'z' || it == ' ' || it == '\u3000' || it == '\''
        }
        if (!onlyPinyinChars) return false

        // 連続した空白だけをまとめて入れた場合は拼音とは限らない(整形貼り付けの可能性)
        val insertedTrim = inserted.trim()
        if (insertedTrim.isEmpty()) return false

        // 英単語を打っているだけの可能性はあるが、この判定は
        // 「貼り付けと誤認して毎回反応する」のを防ぐことを優先する。
        // 誤って貼り付けが反応しない場合は、設定のしきい値を下げれば拾える。
        return true
    }

    /** テキスト変化が来ないアプリ向け: 前回のカーソル位置と文字数 */
    private var lastSelStart = -1
    private var lastSelEnd = -1
    private var lastTextLength = -1

    /** 最後に「内容が空でない入力欄」を見た時刻。送信によるクリア判定に使う */
    private var lastNonEmptyAt = 0L

    /** 「空になった = 削除」を確定させるための保留処理 */
    private val clearHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingDelete: Runnable? = null

    /** 保留中の削除を取り消す(送信と判明したとき) */
    private fun cancelPendingDelete() {
        pendingDelete?.let { clearHandler.removeCallbacks(it) }
        pendingDelete = null
    }

    /**
     * 入力欄が空になったときの処理。
     * すぐには削除と決めず、少し待ってから削除を確定する。
     * その間に送信(空の入力欄のクリック)が来たら、そちらが改行として反応し、
     * ここで予約した削除は取り消される。
     */
    private fun deferClearReaction(watchDelete: Boolean, watchEnter: Boolean) {
        cancelPendingDelete()
        if (!watchDelete) {
            // 削除の反応がなければ、改行の判定だけ待てばよい
            if (!watchEnter) OverlayService.reactIfRunning()
            return
        }
        val runnable = Runnable {
            pendingDelete = null
            OverlayService.reactDelete()
        }
        pendingDelete = runnable
        clearHandler.postDelayed(runnable, CLEAR_CONFIRM_MS)
    }

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

        // 新しい文字は何も入らず、文字が消えただけ
        if (change.end == change.start) {
            // 入力欄が「全部空になった」場合は、送信ボタンによる消去かもしれない。
            // すぐ削除と決めずに少し待ち、その間に空欄クリック(=送信)が来たら
            // そちらを改行として扱い、この削除は取り消す。
            if (after.isEmpty()) {
                cancelPendingDelete()
                if (watchDelete || watchEnter) {
                    deferClearReaction(watchDelete, watchEnter)
                } else {
                    OverlayService.reactIfRunning()
                }
                return
            }
            if (change.removed > 0 && watchDelete) OverlayService.reactDelete() else OverlayService.reactIfRunning()
            return
        }

        // 何かが入った → 保留中の「削除」は誤りだった可能性が高いので取り消す
        cancelPendingDelete()

        val inserted = after.substring(change.start, change.end)

        // 貼り付け判定。アクセシビリティイベントに「貼り付け」を示す情報は無いので、
        // 「一度に入った文字数」と「同時に消えた文字数」から推定する。
        //  - 変換確定(拼音→漢字): 拼音が消えて漢字が入る → removed > 0、入るのは数文字
        //  - 貼り付け:           消さずにまとめて入る   → removed == 0、入るのは数文字以上
        //  - 選択範囲を置換して貼り付け: removed > 0 だが入る文字数が明らかに多い
        //
        // ただし「拼音の入力中にスペースを挟む」入力法があり、その場合は
        // 「字母＋スペース」の2文字が毎回まとめて入る。これを貼り付けと誤認しないよう、
        // 入力中の文字列(直前の数文字＋今回の入力)が拼音らしいときは貼り付けとしない。
        if (watchPaste) {
            val needLen = pasteMinLen()
            val pasted = if (change.removed == 0) {
                inserted.length >= needLen
            } else {
                inserted.length >= maxOf(PASTE_REPLACE_LEN, needLen)
            }
            if (pasted && !looksLikePinyinInput(after, change.start, inserted)) {
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
     * 選択範囲の変化から、コピーと打鍵を推定する。
     *
     * コピーについて:
     *   Android 10 以降、バックグラウンドのアプリはクリップボードを読めない
     *   (getPrimaryClip / addPrimaryClipChangedListener は空を返すか発火しない)。
     *   そのため「クリップボードが変わった」を直接知ることはできない。
     *   代わりに選択範囲の形を見る: 文字を選ぶと範囲ありになり、
     *   コピーすると範囲が畳まれてキャレットに戻る。この遷移をコピーとみなす。
     *   文字が減っていれば切り取りなのでコピー扱いにしない。
     *
     * 打鍵について:
     *   WebView などは文字変化を送らずカーソル位置だけ送る。カーソルの前後移動で推定する。
     *
     * 選択範囲の大きさ(=何文字目から何文字目か)だけを見て、入力内容は一切読まない。
     */
    private fun onSelectionChanged(event: AccessibilityEvent) {
        // この推定はアプリによっては二重反応するので、設定でONにした時だけ動かす
        val fallback = Prefs.getSelectionFallback(this)
        val watchCopy = OverlayService.watchCopy
        // パスワード欄は見ない
        if (event.isPassword) return
        if (!fallback && !watchCopy) return
        if (OverlayService.maxKeyLength <= 0 && !anySpecialWatched()) return

        val start = event.fromIndex
        val end = event.toIndex
        if (start < 0 || end < 0) return

        val now = System.currentTimeMillis()
        val prevStart = lastSelStart
        val prevEnd = lastSelEnd
        val prevLen = lastTextLength

        // 選択イベント時点の文字数。text が取れないアプリもあるので、取れたときだけ更新する
        val textNow = try {
            event.text?.firstOrNull()?.toString()
        } catch (e: Throwable) {
            null
        }
        val lenNow = textNow?.length ?: prevLen
        if (textNow != null) lastTextLength = lenNow

        lastSelStart = start
        lastSelEnd = end

        val wasRange = prevStart >= 0 && prevEnd > prevStart
        val isRange = end > start

        // ---------- コピーの検知 ----------
        // Android 10 以降、バックグラウンドのアプリはクリップボードを読めない。
        // そのため「コピーされたこと」を直接は知れない。代わりに選択の形を見る:
        //   長押しで文字を選ぶ → 選択が「範囲あり」になる
        //   「コピー」を押す   → 選択が畳まれてキャレットに戻ることが多い
        // この「範囲あり → 範囲なし」の遷移を、コピーとみなす。
        // 選択の大きさだけを見て、文字の中身は一切読まない。
        if (watchCopy && wasRange && !isRange) {
            // 文字が減っていれば切り取り。減っていなければコピー。
            val shortened = prevLen >= 0 && lenNow >= 0 && lenNow < prevLen
            if (!shortened) {
                OverlayService.reactCopy()
                return
            }
        }

        // 以下はカーソル移動からの入力推定。設定でONのときだけ
        if (!fallback) return

        // 初回・入力欄の切り替え直後は判断材料がないので何もしない
        if (prevStart < 0) return

        // 範囲選択が始まっただけのときは打鍵とみなさない
        if (isRange) return

        // 選択確定後のカーソル位置変化。前回が範囲選択中なら比較しない
        if (wasRange) return

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

        val text = event.text?.joinToString("") ?: ""
        val now = System.currentTimeMillis()

        // クリック時に中身がある入力欄 → 記録だけ
        if (text.isNotEmpty()) {
            lastNonEmptyAt = now
            return
        }

        // 空の入力欄がクリックされた。
        // 直前に中身があった(送信で消された)か、直前に「空になった」保留があるなら
        // 送信ボタンと判断して改行を出し、予約済みの削除を取り消す。
        val hadContent = lastNonEmptyAt > 0 && now - lastNonEmptyAt <= STATE_RESET_MS
        val clearJustHappened = pendingDelete != null
        if (hadContent || clearJustHappened) {
            cancelPendingDelete()
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