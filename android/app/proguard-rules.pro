# OkHttp тянет опциональные классы Conscrypt/BouncyCastle, которых в APK нет.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
