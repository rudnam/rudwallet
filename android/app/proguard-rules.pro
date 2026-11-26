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

# Hilt finds a page's ViewModel by its class name (Dagger's lazy class keys, since #131's
# RecurringViewModel the release build renamed them out of step with Hilt's map). The
# lookup then missed, Android fell back to a no-argument constructor, and opening a page
# crashed: "Cannot create an instance of class u2 … NoSuchMethodException: u2.<init> []"
# (2.1.43 to 2.1.47, tapping an account card or Settings). Keep the names as written.
-keepnames class * extends androidx.lifecycle.ViewModel
