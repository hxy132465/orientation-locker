# ---------------------------------------------------------------------------
# R8 规则
# ---------------------------------------------------------------------------

# Shizuku：通过 binder + 类名/IPC 传递，不能被重命名或裁剪
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# HiddenApiBypass：内部用 Unsafe / 反射访问 ART 结构，字段名必须保留
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

# 反射读取系统类的 freezeRotation/thawRotation 签名（系统类不在本包内，
# 保留规则主要用于 -keepclassmembers 的匹配语义，防止自身包装层被内联掉）
-keep class android.view.IWindowManager { *; }
-keep class android.view.IWindowManager$Stub { *; }
-keepclassmembers class * {
    public void freezeRotation(int);
    public void thawRotation();
}

# LockStateRepository.Mode 通过 Mode.valueOf(String) 反序列化持久化值
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 清单里声明的组件（TileService / Service / Receiver / Provider）由 AAPT 生成的
# keep 规则兜底；这里显式再保一层构造器与生命周期方法，避免被合并后系统实例化失败。
-keep public class * extends android.app.Service { public <init>(...); }
-keep public class * extends android.content.BroadcastReceiver { public <init>(...); }
-keep public class * extends android.content.ContentProvider { public <init>(...); }
-keep public class * extends android.service.quicksettings.TileService { *; }
