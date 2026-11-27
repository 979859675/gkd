# http://developer.android.com/guide/developing/tools/proguard.html

-dontwarn **

# LSPosed（libxposed Modern API）模块入口：仅由 META-INF/xposed/java_init.list 按名字加载。
-keep,allowoptimization public class li.gkd.app.xposed.** { public <init>(); }
-adaptresourcefilecontents META-INF/xposed/java_init.list
-dontwarn io.github.libxposed.**
