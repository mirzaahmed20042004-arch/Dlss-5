# DLSS-5 release rules
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keep class com.dlss5.optimizer.core.DlssOverlayService { *; }
-keep class com.dlss5.optimizer.network.SyncWorker { *; }
-keep class com.dlss5.optimizer.core.BootRestoreReceiver { *; }
-dontwarn org.jetbrains.annotations.**
