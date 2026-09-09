# Keep rules for the release build.
#
# NOTE: `isMinifyEnabled` is currently **false** — see the investigation at the bottom of this
# file. These rules are kept because they are correct and were validated, so that re-enabling
# minification later starts from a known position rather than from scratch.
#
# The point of the release variant is NOT size. It is that the debug variant is `debuggable`, so
# the process is jdwp-attachable by anything with adb — and both target devices necessarily have
# USB debugging enabled, because Shizuku requires it. A release build closes that regardless of
# whether R8 runs.

# ---- Shizuku ----------------------------------------------------------------------------
# Shizuku.newProcess is @hidden, private static, and reached ONLY by getDeclaredMethod from
# core/Elevated.kt. The shizuku-api AAR ships a ZERO-BYTE proguard.txt, so nothing keeps it.
#
# Without this rule the failure is silent and total: R8 renames the method, the reflective lookup
# throws NoSuchMethodException, Elevated's runCatching swallows it, and every elevated tool
# returns "shizuku exec failed: null". Nothing crashes, and all 121 unit tests still pass, because
# they run against unminified classes.
-keepclassmembers class rikka.shizuku.Shizuku {
    private static ** newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
# The returned ShizukuRemoteProcess is cast to java.lang.Process and its streams are used.
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }

# ---- Netty and Ktor's Netty engine (loaded only when the TLS toggle is on) ----------------
-keep class io.ktor.server.netty.** { *; }
-keep class io.netty.channel.ChannelHandler$Sharable { *; }
-keepclassmembernames class io.netty.** { *; }
-keep class io.netty.util.internal.** { *; }
# Channels are constructed reflectively by ReflectiveChannelFactory.
-keep class io.netty.channel.socket.nio.NioServerSocketChannel { <init>(); }
-keep class io.netty.channel.socket.nio.NioSocketChannel { <init>(); }

# Netty compiles against optional native/TLS/logging backends absent on Android — warnings about
# code that is never reached, not missing dependencies.
-dontwarn io.netty.internal.tcnative.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn org.bouncycastle.openssl.**
-dontwarn org.bouncycastle.asn1.pkcs.PrivateKeyInfo
-dontwarn org.bouncycastle.operator.InputDecryptorProvider
-dontwarn org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
-dontwarn reactor.blockhound.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn org.osgi.annotation.bundle.Export
-dontwarn java.lang.management.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# ---- shrink, but do not rename -----------------------------------------------------------
# Renaming buys ~99 KB and costs readable logcat plus honest class names in
# `t::class.java.simpleName`, which server/Mcp.kt puts into the JSON-RPC error body an MCP client
# reads. Obfuscated, a tool failure reports "Internal error: a: null" to the model.
-dontobfuscate
-keepattributes *Annotation*,SourceFile,LineNumberTable

# Deliberately NOT set: isShrinkResources. Resource shrinking plus a notification- and
# PendingIntent-driven approval flow is a classic source of stripped-drawable crashes, and the
# approval prompt is the app's core safety control.

# ==========================================================================================
# WHY MINIFICATION IS OFF
# ==========================================================================================
# R8 silently breaks HTTPS, and no combination of keep rules tried here fixed it.
#
# Symptom, measured on the K70 against the minified build: the FIRST TLS request succeeds, every
# one after it hangs, while the UI and the audit log both report the server as running. Netty
# fails on its event loop after `start()` has already returned, so nothing is caught and nothing
# reaches the app:
#
#   io.netty.channel.ChannelPipelineException: io.ktor.server.netty.NettyChannelInitializer is
#   not a @Sharable handler, so can't be added or removed multiple times
#
# `ChannelHandlerAdapter.isSharable()` reads `getClass().isAnnotationPresent(Sharable.class)`, so
# the marker has to survive on the concrete class. In the release DEX that class carries no
# annotations at all.
#
# Tried, and none of it restored the annotation:
#   * -keepattributes *Annotation*        redundant — proguard-android-optimize.txt already keeps
#                                         RuntimeVisibleAnnotations. The APK came out
#                                         byte-identical, which is how the redundancy showed up.
#   * -keep class io.ktor.server.netty.** { *; }      class kept; annotation still gone
#   * -keep class io.netty.channel.ChannelHandler$Sharable { *; }
#   * -dontoptimize                       4.17 MB instead of 2.99 MB, same failure — so it is not
#                                         the optimizer
#   * -keep @io.netty.channel.ChannelHandler$Sharable class * { *; }
#                                         fails the BUILD: drags in Netty's marshalling and
#                                         protobuf codecs, whose optional deps are absent
#
# The trade being refused: minification takes the APK from 14.6 MB to 2.9 MB and saves about a
# second per adb install. TLS is an advertised feature that would break after exactly one request,
# in a way no test can see and the app's own status display actively denies. That is not a good
# trade for a personal, adb-installed app.
#
# To revisit: a newer Ktor may annotate these handlers differently, or R8 may stop dropping the
# marker. The decisive check takes two minutes — enable the TLS toggle and make TWO requests. One
# request always succeeds, so a single smoke test proves nothing.
