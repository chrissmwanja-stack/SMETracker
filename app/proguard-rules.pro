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

# --- Readable stack traces ---
# Crashlytics uploads the mapping file to de-obfuscate on its dashboard, but
# keeping this locally means logcat/bugreport traces stay readable too.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Firestore POJO mapping (critical) ---
# Every class in data.remote.model (RemoteSale, RemoteCustomer, SaleFinancials,
# InventoryCost, Business, BusinessMember, PhoneIndexEntry, etc.) is passed
# directly to Firestore's .set(...) and read back via .toObject(...). Both
# directions rely on runtime reflection over field names, so without these
# rules R8 will rename/strip fields and sync will silently break in release
# builds only - debug builds never re-obfuscate, so this bug class won't show
# up until a real Play Store build is tested against real Firestore data.
-keep class com.vestateck.smetracker.data.remote.model.** { *; }
-keepclassmembers class com.vestateck.smetracker.data.remote.model.** {
    <init>(...);
}

# @DocumentId and any Firestore @PropertyName annotations must survive so the
# POJO mapper can find them via reflection at runtime.
-keepattributes *Annotation*

-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
}

# Enums referenced inside the models above (ExpenseStatus, MemberRole) -
# Firestore resolves these via valueOf(String) reflectively too.
-keepclassmembers enum com.vestateck.smetracker.data.remote.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Kotlin metadata - needed for Firestore's Kotlin-aware reflection (data class
# component functions, nullability, default values) to resolve correctly.
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**