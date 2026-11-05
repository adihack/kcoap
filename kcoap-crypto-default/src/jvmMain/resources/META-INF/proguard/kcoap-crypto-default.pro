# R8 and ProGuard rules for apps that use kcoap-crypto-default; the Android Gradle plugin applies them
# from this jar on its own.
#
# cryptography-kotlin's JDK provider (cryptography-provider-jdk 0.6) refers to Bouncy Castle in
# dev.whyoleg.cryptography.providers.jdk.internal.BouncyCastleBridge, which uses it only after Class.forName
# has found org.bouncycastle.jce.ECNamedCurveTable at runtime. An app without Bouncy Castle, that is one
# without kcoap-crypto-bc, never reaches these classes, but R8 fails its release build on them as missing
# (samples/android-consumer, classical flavor). An app with Bouncy Castle has them, and this changes nothing.
-dontwarn org.bouncycastle.crypto.params.**
-dontwarn org.bouncycastle.jcajce.interfaces.**
-dontwarn org.bouncycastle.jce.**
-dontwarn org.bouncycastle.math.ec.**
