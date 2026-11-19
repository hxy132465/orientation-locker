# 保留 Shizuku 与反射调用的隐藏 API 符号
-keep class rikka.shizuku.** { *; }
-keep class android.view.IWindowManager { *; }
-keep class android.view.IWindowManager$Stub { *; }
-keepclassmembers class * {
    public void freezeRotation(int);
    public void thawRotation();
}
-dontwarn rikka.shizuku.**
