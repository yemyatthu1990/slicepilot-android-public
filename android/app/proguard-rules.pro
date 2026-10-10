-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

-keep class world.yemyat.slicepilot.NativeLib { *; }
-keep interface world.yemyat.slicepilot.NativeLib$SliceCallback { *; }

-keep class world.yemyat.slicepilot.MainActivity { *; }
-keep class world.yemyat.slicepilot.SliceForegroundService { *; }

-keep class androidx.lifecycle.** { *; }
-keep class androidx.lifecycle.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.platform.** { *; }

-keep class io.netty.** { *; }
-keep class com.hivemq.** { *; }
-keep class io.agora.** { *; }
-keep class org.jctools.** { *; }
-keep class io.reactivex.** { *; }

-dontwarn io.netty.**
-dontwarn com.hivemq.**
-dontwarn io.agora.**
-dontwarn org.jctools.**
-dontwarn io.reactivex.**
