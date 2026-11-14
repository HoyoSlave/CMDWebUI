-repackageclasses
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute

-keep class com.cmdtweaks.bridge.** { *; }
-keep class com.cmdtweaks.services.** { *; }
-keep class com.cmdtweaks.IKsuWebuiStandaloneInterface* { *; }
-keep class rikka.shizuku.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
