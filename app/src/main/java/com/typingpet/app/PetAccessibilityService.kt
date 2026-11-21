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

        /** 補助判定でコピーとみなすのに必要な、選択の最低継続時間(ミリ秒) */
        private const val COPY_MIN_SELECT_MS = 300L

        /** ノードの親をたどる最大段数 */
        private const val COPY_NODE_SEARCH_DEPTH = 6

        /** メニュー項目を探すときに見るノード数の上限。重くならないための歯止め */
        private const val MENU_SEARCH_LIMIT = 300

        /** コピー後に出る表示を、この時間内は重複して数えない */
        private const val CLIPBOARD_OVERLAY_COOLDOWN_MS = 1200L

        /**
         * 「選択を操作した」文脈とみなす時間窓。
         * 文字を選んでからコピーを押すまでの間隔はこの程度に収まる。
         */
        private const val COPY_CONTEXT_WINDOW_MS = 8000L

        /** コピー後の通知ウィンドウを見分ける手がかり */
        private val CLIPBOARD_WINDOW_HINTS = listOf(
            "clipboard", "copy", "clipper", "clipoverlay"
        )

        /** メニュー判定の結果 */
        private const val MENU_NONE = 0
        private const val MENU_COPY = 1
        private const val MENU_PASTE = 2

        /** メニュー項目のラベルとみなす最大長。これを超える文字列は本文と判断する */
        private const val COPY_LABEL_MAX_LEN = 12

        /**
         * 「コピー」を表すラベル。端末の言語によって変わるので主要言語ぶんを並べる。
         * 比較時は小文字化して前方一致で見る(「コピー」と「コピー...」の揺れを吸収)。
         */
        private val COPY_LABELS = listOf(
            "复制", "复制链接",           // 中国語(簡体字)
            "複製", "拷貝",               // 中国語(繁体字)
            "コピー",                      // 日本語
            "복사",                        // 韓国語
            "copy"                         // 英語
        )

        /** 「貼り付け」を表すラベル */
        private val PASTE_LABELS = listOf(
            "粘贴", "粘貼",               // 中国語
            "貼り付け", "貼付",           // 日本語
            "붙여넣기",                    // 韓国語
            "paste"                        // 英語
        )
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

    /** 直近にコピー完了ウィンドウを拾った時刻 */
    private var lastClipboardOverlayAt = 0L

    /** 直近に「選択を操作した」時刻。SystemUI 浮層と組み合わせてコピーを確定する */
    private var lastCopyContextAt = 0L

    /** いま選択操作の直後か */
    private var recentCopyContext = false

    /** 範囲選択が始まった時刻。コピーの補助判定に使う */
    private var rangeSelectedAt = 0L

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

        // デバッグ用: クリック/ウィンドウ変化の内容を記録しておく。
        // 「コピーが検知できない」ときに、どんなラベルが来ているかを確認するため。
        if (Prefs.getDebugLog(this) &&
            (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            recordDebugEvent(event)
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextChanged(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> onSelectionChanged(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClicked(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onWindowEvent(event)
        }
    }

    // ---------- デバッグ記録 ----------

    private fun recordDebugEvent(event: AccessibilityEvent) {
        try {
            val sb = StringBuilder()
            sb.append(AccessibilityEvent.eventTypeToString(event.eventType))
            val pkg = event.packageName?.toString() ?: ""
            if (pkg.isNotEmpty()) sb.append(" pkg=").append(pkg)
            val cls = event.className?.toString() ?: ""
            if (cls.isNotEmpty()) sb.append(" cls=").append(cls)
            val cd = event.contentDescription?.toString() ?: ""
            if (cd.isNotEmpty()) sb.append(" cd=").append(cd.take(30))
            val tx = event.text?.joinToString("|") ?: ""
            if (tx.isNotEmpty()) sb.append(" text=").append(tx.take(30))

            // ノード側のラベルも見る
            val src = try {
                event.source
            } catch (e: Throwable) {
                null
            }
            if (src != null) {
                try {
                    sb.append(" nodeText=").append((src.text?.toString() ?: "").take(20))
                    sb.append(" nodeCd=").append((src.contentDescription?.toString() ?: "").take(20))
                } finally {
                    try {
                        @Suppress("DEPRECATION")
                        src.recycle()
                    } catch (e: Throwable) {
                    }
                }
            }
            DebugLog.add(this, sb.toString())
        } catch (e: Throwable) {
        }
    }

    // ---------- ウィンドウイベント: コピー後の「コピーしました」表示を拾う ----------

    /**
     * コピーすると、システムやアプリが「コピーしました」という小さな表示を出す。
     * これは別ウィンドウとして現れることがあり、パッケージ名やクラス名に
     * clipboard / copy といった手がかりが入る。
     * その出現をコピーの合図として使う。
     */
    private fun onWindowEvent(event: AccessibilityEvent) {
        if (!OverlayService.watchCopy) return

        val pkg = event.packageName?.toString()?.lowercase() ?: ""
        val cls = event.className?.toString()?.lowercase() ?: ""

        // QQ など自前メニューのアプリでは、コピー後に SystemUI が
        // 「コピーしました」の小さな表示を別ウィンドウとして出す。
        // 実測(Android 17 / HyperOS)では
        //   pkg=com.android.systemui  cls=android.view.View
        // として TYPE_WINDOW_STATE_CHANGED が来る。
        // これが今のところ、クリップボードを読まずにコピーを確定できる唯一の合図。
        if (isSystemUiOverlay(pkg, cls)) {
            // SystemUI のウィンドウは他の用途でも出るので、
            // 「直前に選択メニュー操作があった」ことを条件にする。
            val now = System.currentTimeMillis()
            if (recentCopyContext && now - lastCopyContextAt <= COPY_CONTEXT_WINDOW_MS) {
                if (now - lastClipboardOverlayAt >= CLIPBOARD_OVERLAY_COOLDOWN_MS) {
                    lastClipboardOverlayAt = now
                    recentCopyContext = false
                    cancelPendingDelete()
                    OverlayService.reactCopy()
                }
            }
            return
        }

        // 明示的にクリップボード名を持つウィンドウが来る端末向けの従来判定
        val hint = pkg + " " + cls
        if (CLIPBOARD_WINDOW_HINTS.any { hint.contains(it) }) {
            val now = System.currentTimeMillis()
            if (now - lastClipboardOverlayAt < CLIPBOARD_OVERLAY_COOLDOWN_MS) return
            lastClipboardOverlayAt = now
            cancelPendingDelete()
            OverlayService.reactCopy()
        }
    }

    /** SystemUI が出す小さな表示(コピー完了のトーストなど)か */
    private fun isSystemUiOverlay(pkg: String, cls: String): Boolean {
        if (pkg != "com.android.systemui") return false
        // クラス名は android.view.View や LinearLayout など様々。パッケージだけで判断する。
        return true
    }

    /**
     * 「いま選択メニューを操作した」という文脈を記録する。
     * SystemUI の表示だけでは他の通知と区別できないため、
     * 選択範囲が動いた直後だけコピーと結びつける。
     */
    private fun markCopyContext() {
        recentCopyContext = true
        lastCopyContextAt = System.currentTimeMillis()
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
        // 本筋は「選択メニューのコピーボタンが押された」ことをクリックイベントで見ること
        // (onClicked 側で処理する)。ここでは設定でONのときだけ、
        // 「選択が畳まれた」形からの推定を補助として使う。
        //
        // 補助を使うと、選択をやめただけの場合もコピーと誤判定しやすい。
        // そのため既定では無効にしてあり、必要な人だけ有効化する。
        if (watchCopy && Prefs.getCopyBySelection(this) && wasRange && !isRange) {
            // 文字が減っていれば切り取り。減っていなければコピー。
            val shortened = prevLen >= 0 && lenNow >= 0 && lenNow < prevLen
            // 選択が長く続いた場合だけ拾う(素早いスクロール選択を除外)
            if (!shortened && now - rangeSelectedAt >= COPY_MIN_SELECT_MS) {
                OverlayService.reactCopy()
                return
            }
        }

        // 範囲選択が始まった時刻を記録(補助判定用)
        if (isRange && !wasRange) {
            rangeSelectedAt = now
            // 選択メニュー操作の文脈として記録しておく
            markCopyContext()
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
        // ---------- テキスト選択メニューの「コピー」「貼り付け」を検知 ----------
        // 文字を選ぶとフローティングツールバーが出て、そこに「コピー」「貼り付け」がある。
        // それを押すと TYPE_VIEW_CLICKED が来て、イベントにラベルの文字列が入っている。
        // クリップボードは読めないが、「ユーザーがコピーを押した」ことはこれで直接分かる。
        val actionLabel = clickedActionLabel(event)
        if (actionLabel != null) {
            if (isCopyLabel(actionLabel)) {
                if (OverlayService.watchCopy) {
                    cancelPendingDelete()
                    OverlayService.reactCopy()
                    return
                }
            } else if (isPasteLabel(actionLabel)) {
                if (OverlayService.watchPaste) {
                    OverlayService.reactPaste()
                    return
                }
            }
        }

        // ラベルが取れなかったときの最後の手段:
        // 画面内を探して「コピー」「貼り付け」と書かれたノードを見つけ、
        // クリックされたノードと位置が重なっていればそのボタンだと判断する。
        if (actionLabel == null) {
            when (menuActionAtClickedNode(event)) {
                MENU_COPY -> if (OverlayService.watchCopy) {
                    cancelPendingDelete()
                    OverlayService.reactCopy()
                    return
                }
                MENU_PASTE -> if (OverlayService.watchPaste) {
                    OverlayService.reactPaste()
                    return
                }
                else -> { /* 何もしない */ }
            }
        }

        // ---------- 送信ボタンで入力欄が空になる形の改行 ----------
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
            OverlayService.watchEnter || OverlayService.watchPaste ||
            OverlayService.watchCopy

    /**
     * クリックされた要素のラベルを取り出す。
     *
     * テキスト選択メニューの「コピー」「貼り付け」は、ラベルが
     *  - event.contentDescription
     *  - event.text
     *  - event.source(ノード) の contentDescription / text
     *  - その親ノードの contentDescription / text
     * のどこかに入る。イベント側だけでは空のことが多いので、ノードも辿る。
     */
    private fun clickedActionLabel(event: AccessibilityEvent): String? {
        // 1) イベント自身が持っているラベル
        val cd = event.contentDescription?.toString()?.trim()
        if (!cd.isNullOrEmpty() && cd.length <= COPY_LABEL_MAX_LEN) return cd
        val tx = event.text?.joinToString(" ")?.trim()
        if (!tx.isNullOrEmpty() && tx.length <= COPY_LABEL_MAX_LEN) return tx

        // 2) ノードを辿る。ラベルは子や親に入っていることがある
        val source = try {
            event.source
        } catch (e: Throwable) {
            null
        }
        if (source == null) return null

        try {
            var node: android.view.accessibility.AccessibilityNodeInfo? = source
            var depth = 0
            while (node != null && depth < COPY_NODE_SEARCH_DEPTH) {
                val label = nodeLabel(node)
                if (label != null) return label
                node = try {
                    node.parent
                } catch (e: Throwable) {
                    null
                }
                depth++
            }
        } catch (e: Throwable) {
            // ノードが取れなくなっても致命的ではない
        } finally {
            try {
                @Suppress("DEPRECATION")
                source.recycle()
            } catch (e: Throwable) {
            }
        }
        return null
    }

    /** ノード1つ分のラベル候補(短いものだけ)を返す */
    private fun nodeLabel(node: android.view.accessibility.AccessibilityNodeInfo): String? {
        val cd = node.contentDescription?.toString()?.trim()
        if (!cd.isNullOrEmpty() && cd.length <= COPY_LABEL_MAX_LEN) return cd
        val tx = node.text?.toString()?.trim()
        if (!tx.isNullOrEmpty() && tx.length <= COPY_LABEL_MAX_LEN) return tx
        return null
    }

    /**
     * 画面内のノードを幅優先で探し、「コピー」「貼り付け」と書かれたノードを見つける。
     * クリックされたノードの位置と重なっていれば、そのメニュー項目が押されたと判断する。
     *
     * ラベルがイベントにもノードにも入っていない場合の保険。
     * 探索は深さと個数に上限を設けて、打鍵のたびに走っても重くならないようにする。
     */
    private fun menuActionAtClickedNode(event: AccessibilityEvent): Int {
        val clicked = try {
            event.source
        } catch (e: Throwable) {
            null
        } ?: return MENU_NONE

        try {
            val clickedRect = android.graphics.Rect()
            clicked.getBoundsInScreen(clickedRect)

            // クリックされたノードの祖先をたどり、その配下だけを見る。
            // (画面全体を探すと重いので、まずは近い範囲に絞る)
            var root: android.view.accessibility.AccessibilityNodeInfo? = clicked
            var up = 0
            while (root != null && up < COPY_NODE_SEARCH_DEPTH) {
                root = try {
                    root.parent
                } catch (e: Throwable) {
                    null
                }
                up++
            }
            // 祖先が取れない/浅すぎるときは、クリックされたノード自身を起点にする
            val start = root ?: clicked

            val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            queue.add(start)
            var visited = 0
            while (queue.isNotEmpty() && visited < MENU_SEARCH_LIMIT) {
                val node = queue.removeFirst()
                visited++

                val label = nodeLabel(node)
                if (label != null) {
                    if (isCopyLabel(label) || isPasteLabel(label)) {
                        val r = android.graphics.Rect()
                        node.getBoundsInScreen(r)
                        // クリック位置がそのノードの範囲と重なっているか
                        val hit = r.contains(clickedRect.centerX(), clickedRect.centerY()) ||
                            android.graphics.Rect.intersects(r, clickedRect)
                        if (hit) {
                            return if (isCopyLabel(label)) MENU_COPY else MENU_PASTE
                        }
                    }
                }

                for (i in 0 until node.childCount) {
                    try {
                        node.getChild(i)?.let { queue.add(it) }
                    } catch (e: Throwable) {
                    }
                }
            }
        } catch (e: Throwable) {
            // 探索に失敗しても他の判定は続ける
        } finally {
            try {
                @Suppress("DEPRECATION")
                clicked.recycle()
            } catch (e: Throwable) {
            }
        }
        return MENU_NONE
    }

    /**
     * 「コピー」ボタンらしいラベルか。
     * 端末の言語によって表示が変わるため、主要な言語の表記を並べておく。
     * 長い文字列(本文そのもの)を誤って拾わないよう、短いラベルだけを対象にする。
     */
    private fun isCopyLabel(label: String): Boolean {
        if (label.length > COPY_LABEL_MAX_LEN) return false
        val normalized = label.lowercase().trim()
        return COPY_LABELS.any { normalized == it || normalized.startsWith(it) }
    }

    /** 「貼り付け」ボタンらしいラベルか */
    private fun isPasteLabel(label: String): Boolean {
        if (label.length > COPY_LABEL_MAX_LEN) return false
        val normalized = label.lowercase().trim()
        return PASTE_LABELS.any { normalized == it || normalized.startsWith(it) }
    }

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