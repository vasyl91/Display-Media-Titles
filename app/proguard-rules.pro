# Project specific R8 rules.
#
# AGP 9 enables android.r8.strictFullModeForKeepRules by default: "-keep class A" no longer keeps the
# default constructor implicitly, so every class that is instantiated reflectively keeps <init>()
# explicitly below.
#
# Removed on purpose:
#  - "-assumenosideeffects class kotlin.jvm.internal.Intrinsics { ... }": it deleted Kotlin null
#    checks and lateinit checks, turning clear exceptions into silent corruption / later crashes.
#  - "-optimizationpasses", "-dontpreverify", "-verbose": ignored by R8.
#  - "-keep class vasyl.titles.widget.** { *; }": replaced by the targeted Glance rules below.

# Readable stack traces (the original source file name is hidden).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Remove Compose runtime tracing calls.
-assumenosideeffects public class androidx.compose.runtime.ComposerKt {
    boolean isTraceInProgress();
    void traceEventStart(int,int,int,java.lang.String);
    void traceEventStart(int,java.lang.String);
    void traceEventEnd();
}

# Glance stores class names in RemoteViews / action parameters and creates the instances reflectively.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { <init>(); }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { <init>(); }
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# AppFilter.loadByName() instantiates the class named in R.string.app_filter_class via reflection.
# Keeps every AppFilter implementation (in any package) with its name and no-arg constructor.
-keep class * extends vasyl.titles.excludeapps.AppFilter { <init>(); }
