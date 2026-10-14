#!/usr/bin/env python3
"""Compile actual core Kotlin sources and test them with deterministic platform doubles.

Usage: python3 scripts/audit_core_check.py --compiler-classpath '/path/to/kotlin/jars/*'
Requires JDK 17+, Kotlin compiler/stdlib and kotlinx-coroutines on that classpath.
This is NOT an Android, APK, R8, network or device test.
"""
import argparse
from pathlib import Path
import glob
import os
import subprocess
import tempfile

SOURCES = {
    'Media.kt': '''package android.support.v4.media
class MediaDescriptionCompat(val mediaId: String? = null)
''',
    'Context.kt': '''package android.content
open class Context
''',
    'Loop.kt': '''package android.os
class Looper { companion object {
    private val main = Looper()
    fun getMainLooper() = main
    fun myLooper() = main
} }
class Handler(looper: Looper) {
    fun post(action: Runnable): Boolean { action.run(); return true }
    fun postDelayed(action: Runnable, delay: Long): Boolean { pending += action; return true }
    fun removeCallbacks(action: Runnable) { pending.remove(action) }
    companion object {
        val pending = mutableListOf<Runnable>()
        fun expireAll() { val copy = pending.toList(); pending.clear(); copy.forEach { it.run() } }
    }
}
''',
    'Projection.kt': '''package de.uni.youtubeaa.car
object ProjectionLauncher {
    var result = true
    fun requestOpen(
        context: android.content.Context,
        bringToFront: Boolean = false,
        onFinished: ((Boolean) -> Unit)? = null
    ) { onFinished?.invoke(result) }
}
object HeadUnitDiagnostics {
    fun event(tag: String, message: String) = Unit
    fun warning(tag: String, message: String) = Unit
    fun error(tag: String, message: String, throwable: Throwable? = null) = Unit
}
''',
    'Json.kt': '''package org.json
// Map/array doubles: parser selection is tested; JSON byte decoding is not.
class JSONObject {
    private val fields = linkedMapOf<String, Any?>()
    fun put(key: String, value: Any?): JSONObject { fields[key] = value; return this }
    fun opt(key: String): Any? = fields[key]
    fun optJSONObject(key: String) = fields[key] as? JSONObject
    fun optJSONArray(key: String) = fields[key] as? JSONArray
    fun optString(key: String) = fields[key] as? String ?: ""
    fun optBoolean(key: String) = fields[key] == true
    fun keys() = fields.keys.iterator()
}
class JSONArray {
    private val values = mutableListOf<Any?>()
    fun put(value: Any?): JSONArray { values += value; return this }
    fun length() = values.size
    fun opt(i: Int): Any? = values.getOrNull(i)
    fun optJSONObject(i: Int) = opt(i) as? JSONObject
}
''',
    'Profile.kt': '''package de.uni.youtubeaa.car.hybrid
class YtTvAuthClient { data class ChannelProfile(val title: String?, val avatarUrl: String?) }
''',
    'Test.java': '''package org.junit;
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface Test {}
''',
    'After.java': '''package org.junit;
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface After {}
''',
    'Assert.java': '''package org.junit;
public class Assert {
 public static void assertEquals(Object a,Object b) {if(!java.util.Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
 public static void assertEquals(long a,long b) {if(a!=b)throw new AssertionError(a+" != "+b);}
 public static void assertTrue(boolean b) {if(!b)throw new AssertionError("expected true");}
 public static void assertFalse(boolean b) {if(b)throw new AssertionError("expected false");}
 public static void assertNull(Object v) {if(v!=null)throw new AssertionError("expected null: "+v);}
 public static void assertSame(Object a,Object b) {if(a!=b)throw new AssertionError("not identical");}
}
''',
    'Runner.kt': '''import de.uni.youtubeaa.car.*
import de.uni.youtubeaa.car.hybrid.AccountProfileParser
import android.support.v4.media.MediaDescriptionCompat
import org.json.JSONObject
import org.json.JSONArray

private var count = 0
private fun scenario(name: String, block: () -> Unit) {
    block(); count++; println("PASS $name")
}
private class Receiver : RemoteCastBus.Receiver {
    var calls = 0
    val callbacks = mutableMapOf<Long, () -> Unit>()
    val cancelled = mutableListOf<Long>()
    var accepts = true
    var lastId = 0L
    var foreground = true
    override fun castVideo(video: MediaDescriptionCompat, queue: List<MediaDescriptionCompat>,
        startPositionMs: Long, requestId: Long, onStarted: () -> Unit): RemoteCastBus.Acceptance {
        calls++; lastId = requestId
        if (accepts) callbacks[requestId] = onStarted
        return if (accepts) RemoteCastBus.Acceptance.ACCEPTED else RemoteCastBus.Acceptance.PARK_BLOCKED
    }
    override fun cancelCast(requestId: Long) { cancelled += requestId }
    override fun isInForeground() = foreground
}
private fun castScenario(name: String, block: (Receiver) -> Unit) = scenario(name) {
    val receiver = Receiver()
    RemoteCastBus.register(receiver)
    try { block(receiver) } finally {
        RemoteCastBus.cancelPending(); RemoteCastBus.unregister(receiver)
        android.os.Handler.expireAll()
    }
}
fun main() {
    for (klass in listOf(PlaybackOwnershipTest::class.java, NowPlayingBusTest::class.java, CarLipSyncTest::class.java, SessionUpdatesTest::class.java)) {
        for (method in klass.declaredMethods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }) {
            val test = klass.getDeclaredConstructor().newInstance()
            scenario(method.name) {
                try { method.invoke(test) }
                finally { klass.declaredMethods.filter { it.isAnnotationPresent(org.junit.After::class.java) }.forEach { it.invoke(test) } }
            }
        }
    }
    val video = MediaDescriptionCompat("abcdefghijk")
    castScenario("Repeated delivery waits for actual readiness") { r ->
        var ready = 0
        RemoteCastBus.cast(video, onStarted = { ready++ })
        repeat(4) { RemoteCastBus.retryPendingIfAny() }
        check(r.calls == 1 && ready == 0 && RemoteCastBus.hasPendingCast())
        r.callbacks.getValue(r.lastId)()
        check(ready == 1 && !RemoteCastBus.hasPendingCast())
        android.os.Handler.expireAll()
        check(ready == 1)
    }
    castScenario("Duplicate cast for the same video joins the existing request") { r ->
        var first = 0; var duplicate = 0
        RemoteCastBus.cast(video, onStarted = { first++ })
        val original = r.lastId
        RemoteCastBus.cast(video, onStarted = { duplicate++ })
        check(r.calls == 1 && r.lastId == original && original !in r.cancelled)
        r.callbacks.getValue(original)()
        check(first == 1 && duplicate == 0 && !RemoteCastBus.hasPendingCast())
    }
    castScenario("Timeout cancels target before invoking fallback once") { r ->
        var failed = 0; var ready = 0
        RemoteCastBus.cast(video, onProjectionFailed = { check(r.cancelled.isNotEmpty()); failed++ }, onStarted = { ready++ })
        val id = r.lastId
        android.os.Handler.expireAll()
        r.callbacks.getValue(id)()
        RemoteCastBus.projectionFailed(id)
        check(failed == 1 && ready == 0 && !RemoteCastBus.hasPendingCast())
    }
    castScenario("Explicit cancellation never starts fallback later") { r ->
        var failed = 0; var ready = 0
        RemoteCastBus.cast(video, onProjectionFailed = { failed++ }, onStarted = { ready++ })
        val id = r.lastId
        RemoteCastBus.cancelPending()
        r.callbacks.getValue(id)()
        android.os.Handler.expireAll()
        check(failed == 0 && ready == 0)
    }
    castScenario("Disconnect while preparing fails pending cast") { r ->
        var failed = 0
        RemoteCastBus.cast(video, onProjectionFailed = { failed++ })
        RemoteCastBus.unregister(r)
        check(failed == 1 && !RemoteCastBus.hasPendingCast())
    }
    castScenario("Park rejection is immediate and leaves no pending cast") { r ->
        r.accepts = false
        check(RemoteCastBus.cast(video) == RemoteCastBus.CastResult.ParkBlocked)
        check(!RemoteCastBus.hasPendingCast())
    }
    castScenario("Blank media IDs never reach receiver") { r ->
        check(RemoteCastBus.cast(MediaDescriptionCompat(" ")) == RemoteCastBus.CastResult.Rejected)
        check(r.calls == 0)
    }
    scenario("Missing receiver and missing launch context invokes fallback") {
        var failed = 0
        RemoteCastBus.cast(video, onProjectionFailed = { failed++ })
        check(failed == 1 && !RemoteCastBus.hasPendingCast())
    }
    scenario("Active account name takes precedence over other account rows") {
        val root = JSONObject().put("activeAccountHeaderRenderer", JSONObject()
            .put("accountName", JSONObject().put("simpleText", "Seinist")))
            .put("accountItemRenderer", JSONObject().put("isSelected", true)
                .put("accountName", JSONObject().put("simpleText", "Other")))
        check(AccountProfileParser.parse(root)?.title == "Seinist")
    }
    scenario("Selected account handles runs and protocol-relative avatar") {
        val account = JSONObject().put("isSelected", true)
            .put("accountName", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "My ")).put(JSONObject().put("text", "Channel"))))
            .put("accountPhoto", JSONObject().put("thumbnails", JSONArray().put(JSONObject().put("url", "//example.test/avatar"))))
        val result = AccountProfileParser.parse(JSONObject().put("accountItemRenderer", account))
        check(result?.title == "My Channel" && result.avatarUrl == "https://example.test/avatar")
    }
    scenario("Unselected accounts and arbitrary channel names are not the signed-in user") {
        check(AccountProfileParser.parse(JSONObject().put("accountItemRenderer", JSONObject().put("accountName", "Other"))) == null)
        check(AccountProfileParser.parse(JSONObject().put("channelRenderer", JSONObject().put("title", "Other"))) == null)
    }
    scenario("Legacy placeholder is missing data, not a username") {
        check(AccountProfileParser.displayName("YouTube-Konto verknüpft") == null)
        check(AccountProfileParser.displayName("  ") == null)
        check(AccountProfileParser.displayName("  Seinist  ") == "Seinist")
    }
    println("$count scenarios passed (platform doubles; no Android runtime)")
}
'''
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler-classpath', required=True)
    args = parser.parse_args()
    cp = os.pathsep.join(str(Path(p).resolve()) for part in args.compiler_classpath.split(os.pathsep)
                         for p in (glob.glob(part) if '*' in part else [part]))
    root = Path(__file__).resolve().parents[1]
    base = root / 'car/src/main/java/de/uni/youtubeaa/car'
    test = root / 'car/src/test/java/de/uni/youtubeaa/car'
    production = [base / x for x in ['VideoSessionBridge.kt', 'MediaSessionCommandPolicy.kt', 'NowPlayingBus.kt', 'RemoteCastBus.kt', 'hybrid/AccountProfileParser.kt', 'CarLipSync.kt', 'SessionMetadataKey.kt']]
    tests = [test / x for x in ['PlaybackOwnershipTest.kt', 'NowPlayingBusTest.kt', 'CarLipSyncTest.kt', 'SessionUpdatesTest.kt']]
    with tempfile.TemporaryDirectory(prefix='youtube-aa-core-') as tmp:
        tmp = Path(tmp)
        for name, source in SOURCES.items():
            (tmp / name).write_text(source)
        classes = tmp / 'classes'
        classes.mkdir()
        subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', str(classes),
                        *map(str, tmp.glob('*.java'))], check=True)
        subprocess.run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                        '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp + os.pathsep + str(classes),
                        '-d', str(classes), *map(str, production + tests + list(tmp.glob('*.kt')))], check=True)
        subprocess.run(['java', '-cp', str(classes) + os.pathsep + cp, 'RunnerKt'], check=True)


if __name__ == '__main__':
    main()
