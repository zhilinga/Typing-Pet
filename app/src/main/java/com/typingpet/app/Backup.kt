package com.typingpet.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 設定と画像をまるごと1つのファイルに書き出す / 読み戻す。
 *
 * 端末を替えたときや、アプリを入れ直したときに、キャラ・イラスト・文字の反応を
 * 一から作り直さなくて済むようにするためのもの。
 *
 * 形式: zip
 *   settings.json  … SharedPreferences の中身(JSON) + 形式バージョン
 *   images/<name>  … filesDir/images 配下の画像ファイル
 */
object Backup {

    private const val FORMAT_VERSION = 1
    private const val ENTRY_SETTINGS = "settings.json"
    private const val ENTRY_IMAGES = "images/"
    private const val PREF_FILE = "typing_pet_prefs"

    /** 書き出したファイルの名前(呼び出し側が保存先に使う) */
    fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(now))
        return "TypingPet_backup_$stamp.zip"
    }

    class Result(val ok: Boolean, val message: String, val imageCount: Int = 0)

    /**
     * 設定と画像を zip に書き出す。
     * 書き出し先は SAF で選んでもらった Uri をそのまま受け取る。
     */
    fun export(context: Context, target: Uri): Result {
        return try {
            val settings = JSONObject()

            // SharedPreferences をそのまま JSON 化する
            val sp = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val prefsObj = JSONObject()
            sp.all.forEach { (key, value) ->
                when (value) {
                    is String -> prefsObj.put(key, value)
                    is Int -> prefsObj.put(key, value)
                    is Long -> prefsObj.put(key, value)
                    is Float -> prefsObj.put(key, value.toDouble())
                    is Boolean -> prefsObj.put(key, value)
                    is Set<*> -> prefsObj.put(key, JSONArray(value.filterNotNull()))
                    else -> { /* 想定外の型は飛ばす */ }
                }
            }

            settings.put("format", FORMAT_VERSION)
            settings.put("appVersion", appVersion(context))
            settings.put("exportedAt", System.currentTimeMillis())
            settings.put("prefs", prefsObj)

            val imageDir = File(context.filesDir, "images")
            val imageFiles = imageDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val names = JSONArray()
            imageFiles.forEach { names.put(it.name) }
            settings.put("images", names)

            val out = context.contentResolver.openOutputStream(target)
                ?: return Result(false, "open_failed")
            out.use { os ->
                ZipOutputStream(os.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(ENTRY_SETTINGS))
                    zip.write(settings.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    imageFiles.forEach { f ->
                        zip.putNextEntry(ZipEntry(ENTRY_IMAGES + f.name))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            Result(true, "ok", imageFiles.size)
        } catch (e: Throwable) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * zip から設定と画像を読み戻す。
     * 画像はいったん別名で展開し、成功したら差し替える(失敗しても元を壊さない)。
     */
    fun import(context: Context, source: Uri): Result {
        var settingCount = 0
        var imageCount = 0
        return try {
            val imageDir = File(context.filesDir, "images")
            if (!imageDir.exists()) imageDir.mkdirs()

            // 先に一時フォルダへ展開する
            val tmpDir = File(context.cacheDir, "restore_${System.currentTimeMillis()}")
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            tmpDir.mkdirs()

            var settingsJson: String? = null
            val input = context.contentResolver.openInputStream(source)
                ?: return Result(false, "open_failed")

            input.use { ins ->
                ZipInputStream(ins.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (entry.isDirectory) {
                            // ディレクトリは作らない(必要なファイルだけ拾う)
                        } else if (name == ENTRY_SETTINGS) {
                            settingsJson = zip.readBytes().toString(Charsets.UTF_8)
                        } else if (name.startsWith(ENTRY_IMAGES)) {
                            val fileName = name.removePrefix(ENTRY_IMAGES)
                            // パス抜けを防ぐ
                            if (fileName.isNotEmpty() &&
                                !fileName.contains('/') &&
                                !fileName.contains('\\') &&
                                fileName != "." && fileName != ".."
                            ) {
                                val out = File(tmpDir, fileName)
                                out.outputStream().use { zip.copyTo(it) }
                                imageCount++
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            val json = settingsJson ?: return Result(false, "settings_missing").also {
                tmpDir.deleteRecursively()
            }

            val root = JSONObject(json)
            val version = root.optInt("format", 0)
            if (version < 1) {
                tmpDir.deleteRecursively()
                return Result(false, "bad_format")
            }

            val prefsObj = root.optJSONObject("prefs") ?: JSONObject()
            val sp = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val editor = sp.edit()
            // 既存の値は残しつつ、バックアップにある値を上書きする
            for (key in prefsObj.keys()) {
                when (val v = prefsObj.get(key)) {
                    is String -> { editor.putString(key, v); settingCount++ }
                    is Int -> { editor.putInt(key, v); settingCount++ }
                    is Long -> { editor.putLong(key, v); settingCount++ }
                    is Double -> { editor.putFloat(key, v.toFloat()); settingCount++ }
                    is Boolean -> { editor.putBoolean(key, v); settingCount++ }
                    is JSONArray -> {
                        val set = HashSet<String>()
                        for (i in 0 until v.length()) set.add(v.optString(i, ""))
                        editor.putStringSet(key, set)
                        settingCount++
                    }
                }
            }
            editor.apply()

            // 展開した画像を images へ移す(同名は上書き)
            tmpDir.listFiles()?.forEach { f ->
                try {
                    val dst = File(imageDir, f.name)
                    if (dst.exists()) dst.delete()
                    f.renameTo(dst)
                } catch (e: Throwable) {
                    // 1枚失敗しても続ける
                }
            }
            tmpDir.deleteRecursively()

            // 反映
            Prefs.setTriggers(context, Prefs.getTriggers(context))
            OverlayService.refreshIfRunning()

            Result(true, "ok", imageCount)
        } catch (e: Throwable) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}