# Don't obfuscate code
-dontobfuscate

# Our code
-keep class com.limelight.binding.input.evdev.* {*;}

# Moonlight common
-keep class com.limelight.nvstream.jni.* {*;}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
# MoonCast: X25519 for identity transfer (the TV's receiver key). The provider loads these by name, so R8 removed them
# and a release-built TV could not make its key: "identityKey" went out empty and no identity could be sent to it.
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**

# MoonCast: chiaki-ng's native code (PS5) reads these classes' fields and calls their methods by name, so code
# shrinking must keep all of them. Without this a release build dropped ConnectInfo.psnToken and .videoProfile.
-keep class com.metallic.chiaki.** { *; }
# Google's WebRTC (Xbox) calls back into its Java classes from native code too
-keep class org.webrtc.** { *; }
