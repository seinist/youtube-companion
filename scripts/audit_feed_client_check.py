#!/usr/bin/env python3
"""Compile the real InnerTube client with real OkHttp/JSON and platform/session doubles.
All requests are intercepted locally; no live YouTube account or server is used.
Requires the Kotlin compiler, coroutines, OkHttp 5.4.0, Okio and org.json JARs.
"""
import argparse
import glob
import os
from pathlib import Path
import subprocess
import tempfile

DOUBLES = {
'Uri.kt': '''package android.net
class Uri private constructor(val value: String) { companion object { fun parse(s: String) = Uri(s) } }
''',
'Media.kt': '''package android.support.v4.media
class MediaDescriptionCompat(val mediaId: String?, val title: CharSequence?, val subtitle: CharSequence?, val iconUri: android.net.Uri?) {
 class Builder {
  private var id: String? = null; private var title: CharSequence? = null
  private var subtitle: CharSequence? = null; private var uri: android.net.Uri? = null
  fun setMediaId(v: String?) = apply { id=v }; fun setTitle(v: CharSequence?) = apply { title=v }
  fun setSubtitle(v: CharSequence?) = apply { subtitle=v }; fun setIconUri(v: android.net.Uri?) = apply { uri=v }
  fun build() = MediaDescriptionCompat(id,title,subtitle,uri)
 }
}
''',
'Metadata.kt': '''package de.uni.youtubeaa.car
fun android.support.v4.media.MediaDescriptionCompat.Builder.setChannelId(value: String?) = this
''',
'Session.kt': '''package de.uni.youtubeaa.car.hybrid
data class TestTokens(val refreshToken: String = "test-account", val accessToken: String = "test-access") {
 fun isAccessExpired() = false
}
class YtSessionStore {
 @JvmField var tokens = TestTokens()
 fun getTokens(): TestTokens? = tokens
 fun saveIfCurrent(expected: String, value: TestTokens): Boolean {
  if (tokens.refreshToken != expected) return false
  tokens=value; return true
 }
}
class YtTvAuthClient {
 data class ChannelProfile(val title: String?, val avatarUrl: String?)
 suspend fun refresh(tokens: TestTokens) = tokens
}
''',
'Runner.kt': '''import de.uni.youtubeaa.car.hybrid.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject

fun page(id: String, next: String? = null, visitor: String? = null): String =
 JSONObject().put("contents", org.json.JSONArray().apply {
  put(JSONObject().put("videoRenderer", JSONObject().put("videoId", id).put("title", JSONObject().put("simpleText", id))))
  if (next != null) put(JSONObject().put("continuationItemViewModel", JSONObject().put("continuationCommand", JSONObject().put("token", next))))
 }).apply { if (visitor != null) put("responseContext", JSONObject().put("visitorData", visitor)) }.toString()

fun client(store: YtSessionStore, respond: (Request, JSONObject) -> Pair<Int,String>): InnerTubeClient {
 val http = OkHttpClient.Builder().addInterceptor { chain ->
  val request = chain.request()
  val buffer = Buffer(); request.body!!.writeTo(buffer)
  val (code, body) = respond(request, JSONObject(buffer.readUtf8()))
  Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
   .body(body.toResponseBody("application/json".toMediaType())).build()
 }.build()
 return InnerTubeClient(store, YtTvAuthClient(), http)
}
fun main() = runBlocking {
 var count = 0
 suspend fun scenario(name: String, block: suspend () -> Unit) { block(); count++; println("PASS $name") }
 scenario("Android/TV merge, client-specific visitor context and incremental continuation") {
  val store = YtSessionStore()
  val requests = mutableListOf<String>()
  val api = client(store) { request, json ->
   val context = json.getJSONObject("context").getJSONObject("client")
   val android = context.getString("clientName") == "ANDROID"
   val token = json.optString("continuation")
   requests += "${if(android) "A" else "T"}:$token"
   check(request.header("Authorization") == "Bearer test-access")
   if (token == "a1") {
    check(context.getString("visitorData") == "visitor-A")
    check(request.header("X-Goog-Visitor-Id") == "visitor-A")
   }
   200 to when {
    !android -> page("tvVideo0001")
    token.isEmpty() -> page("newVideo001", "a1", "visitor-A")
    token == "a1" -> page("newVideo002", "a2")
    else -> page("newVideo003")
   }
  }
  val first = api.getSubscriptionFeed()
  check(first.items.map { it.mediaId } == listOf("newVideo001", "newVideo002", "tvVideo0001"))
  check(first.hasMore && !first.partial)
  val more = api.getSubscriptionFeed(loadMore = true)
  check(more.items.map { it.mediaId } == listOf("newVideo001", "newVideo002", "tvVideo0001", "newVideo003"))
  check(!more.hasMore && more.fetchedAtMs == first.fetchedAtMs)
  check(requests == listOf("A:", "A:a1", "T:", "A:a2"))
 }
 scenario("Partial HTTP failure retains both clients' items and retries") {
  var offline = true
  val api = client(YtSessionStore()) { _, json ->
   val android = json.getJSONObject("context").getJSONObject("client").getString("clientName") == "ANDROID"
   when {
    !android -> 200 to page("tvVideo0001")
    !json.has("continuation") -> 200 to page("newVideo001", "retry")
    offline -> 503 to "{}"
    else -> 200 to page("newVideo002")
   }
  }
  val first = api.getSubscriptionFeed()
  check(first.items.size == 2 && first.partial && first.hasMore)
  offline = false
  val fixed = api.getSubscriptionFeed(loadMore = true)
  check(fixed.items.size == 3 && !fixed.partial && !fixed.hasMore)
 }
 scenario("Explicit refresh restarts at the head of the feed") {
  var current = "newVideo001"
  val api = client(YtSessionStore()) { _, _ -> 200 to page(current) }
  check(api.getSubscriptionFeed().items.single().mediaId == current)
  current = "newVideo002"
  check(api.getSubscriptionFeed().items.single().mediaId == current)
 }
 scenario("Account change during request cannot commit the previous account's feed") {
  val store = YtSessionStore()
  var switch = true
  val api = client(store) { _, _ ->
   if (switch) { store.tokens = TestTokens(refreshToken = "new-test-account"); switch = false }
   200 to page("newVideo009")
  }
  var rejected = false
  try { api.getSubscriptionFeed() } catch (_: IllegalStateException) { rejected = true }
  check(rejected)
  check(api.getSubscriptionFeed().items.single().mediaId == "newVideo009")
 }
 println("$count client scenarios passed (intercepted HTTP; platform/session doubles)")
}
'''
}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler-classpath', required=True)
    parser.add_argument('--test-classpath', required=True)
    args = parser.parse_args()
    def expand(value):
        return os.pathsep.join(str(Path(p).resolve()) for part in value.split(os.pathsep)
                               for p in (glob.glob(part) if '*' in part else [part]))
    compiler = expand(args.compiler_classpath)
    cp = compiler + os.pathsep + expand(args.test_classpath)
    root = Path(__file__).resolve().parents[1]
    base = root / 'car/src/main/java/de/uni/youtubeaa/car/hybrid'
    production = [base / name for name in ['InnerTubeFeedParser.kt', 'FeedPager.kt', 'InnerTubeClient.kt', 'AccountProfileParser.kt']]
    production.append(base.parent / 'HttpAwait.kt')
    with tempfile.TemporaryDirectory(prefix='youtube-aa-feed-client-') as tmp:
        tmp = Path(tmp)
        for name, text in DOUBLES.items():
            (tmp / name).write_text(text)
        classes = tmp / 'classes'
        classes.mkdir()
        subprocess.run(['java', '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                        '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp,
                        '-d', str(classes), *map(str, production + list(tmp.glob('*.kt')))], check=True)
        subprocess.run(['java', '-cp', str(classes) + os.pathsep + cp, 'RunnerKt'], check=True)

if __name__ == '__main__':
    main()
