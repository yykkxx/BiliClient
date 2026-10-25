# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keeppackagenames org.jsoup.nodes
-keep class tv.danmaku.ijk.media.** {*;}
-keep class com.netease.hearttouch.brotlij.** {*;}

# Android 组件保留
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

# Parcelable 和 Serializable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# JNI 和 Native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# View 构造函数（用于 XML 布局）
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# R 文件中的资源 ID
-keepclassmembers class **.R$* {
    public static <fields>;
}

# 弹幕模型：弹幕库 BiliProtobufDanmakuParser 历史上按字段名反射读取
# （elems / progress / mode / fontsize / color / content）。虽然现在应用层已注入
# 编译期读取器（见 PlayerDanmakuController.installModelReader），这里仍保留字段名
# 作为兜底 —— v5.0.2-alpha3 就是漏了这条，混淆后 elems 变成 a，弹幕整批丢失。
-keepclassmembers class com.RobinNotBad.BiliClient.model.DanmakuElem {
    public <fields>;
}
-keepclassmembers class com.RobinNotBad.BiliClient.model.DmSegMobileReply {
    public <fields>;
}

-keepattributes *Annotation*
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }
# Only required if you use AsyncExecutor
-keepclassmembers class * extends org.greenrobot.eventbus.util.ThrowableFailureEvent {
    <init>(java.lang.Throwable);
}

# 抛出异常时保留代码行号 
-keepattributes SourceFile,LineNumberTable