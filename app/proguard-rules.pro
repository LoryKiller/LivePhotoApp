# --- General Android & Kotlin Rules ---

# Essential for libraries using reflection, annotations, or generics.
-keepattributes *Annotation*, Signature, EnclosingMethod, InnerClasses

# Useful for crash reporting services (like Firebase Crashlytics) to provide readable stack traces.
-keepattributes SourceFile, LineNumberTable

# --- Glide Rules ---

# Glide v4 uses reflection to discover modules. These rules ensure that
# the modules and the generated implementation are not stripped or renamed.
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public class * extends com.bumptech.glide.module.LibraryGlideModule
-keep @com.bumptech.glide.annotation.GlideModule class *

# The generated implementation class might not exist yet if the annotation
# processor hasn't run or if no AppGlideModule is defined.
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl* {
    <init>(...);
}
-dontwarn com.bumptech.glide.GeneratedAppGlideModuleImpl

# --- Media3 / ExoPlayer ---

# Media3 (formerly ExoPlayer) libraries include their own consumer ProGuard rules in the AARs.
# Manual broad -keep rules for 'androidx.media3.**' are typically redundant and
# prevent R8 from effectively shrinking the library code.
# Only add specific rules if you use reflection-based loading for custom components.

# --- ViewBinding ---

# ViewBinding generates standard Java/Kotlin code that is directly referenced by your Activity/Fragment.
# R8 automatically tracks these references and keeps the necessary classes.
# Manual rules for the 'databinding' package are unnecessary.
