# アクセシビリティサービスはクラス名を AndroidManifest から参照される。
# 難読化するとシステムがサービスを見つけられず、入力検知が丸ごと止まる。
-keep class com.typingpet.app.PetAccessibilityService { *; }
-keep class com.typingpet.app.OverlayService { *; }
-keep class com.typingpet.app.MainActivity { *; }

# PetView は WindowManager に直接 addView するので、リフレクション相当の扱いになる
-keep class com.typingpet.app.PetView { *; }

# バックアップの zip 読み書きで型名を使う箇所があるため、モデルは残す
-keep class com.typingpet.app.Backup { *; }
-keep class com.typingpet.app.Backup$Result { *; }

# ImageLoader / PetFrame 系はインタフェース経由で呼ぶ。実装クラス名は残す
-keep interface com.typingpet.app.PetFrame { *; }
-keep class * implements com.typingpet.app.PetFrame { *; }

# Kotlin のメタデータ(リフレクション用)
-keep class kotlin.Metadata { *; }

# 行番号は残す(クラッシュ時の解析用)。ソースファイル名は隠す
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
