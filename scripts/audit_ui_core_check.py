#!/usr/bin/env python3
"""Exercise viewport/image budgets and cancellable HTTP with real OkHttp/coroutines.
No Android runtime or live network is used. This does not replace device/UI tests.
"""
import argparse,glob,os,subprocess,tempfile
from pathlib import Path
RUNNER=r'''import de.uni.youtubeaa.car.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() = runBlocking {
 var count=0
 suspend fun scenario(name:String, block:suspend ()->Unit) { block(); count++; println("PASS $name") }
 scenario("Projected layouts fit low, wide and portrait viewports; controls stay at least 48dp") {
  for (width in listOf(320,480,800,1280)) for (height in listOf(200,320,480,720)) {
   val spec=CarLayoutSpec.forViewport(width,height)
   check(spec.touchDp >= 48 && spec.playDp >= 48 && spec.panelDp <= width)
   check(spec.coverDp == 0 || spec.coverDp + spec.toolbarDp < height)
   check(2*spec.touchDp + spec.playDp + 32 <= width)
  }
  check(CarLayoutSpec.forViewport(320,200).coverDp == 0)
 }
 scenario("Decoded artwork stays inside a bounded size class, including 8K images") {
  for (target in listOf(48,96,160,320,480,640,1024,1920)) {
   val sample=ThumbnailSizing.sampleSize(7680,4320,target)
   check(sample>0 && sample and (sample-1) == 0)
   check(7680/sample < 2*ThumbnailSizing.bucket(target))
   check(7680/sample >= ThumbnailSizing.bucket(target))
   check(ThumbnailSizing.bucket(target) <=1280)
  }
 }
 scenario("Automatic thumbnails prioritize small transfers; explicit high quality and custom URLs survive") {
  val url="https://i.ytimg.com/vi/fixture0001/maxresdefault.jpg"
  check(ThumbnailSizing.candidates(url,"auto",160).first().endsWith("mqdefault.jpg"))
  check(ThumbnailSizing.candidates(url,"max",160).first() == url)
  val custom="https://example.invalid/signed.png?key=x"
  check(ThumbnailSizing.candidates(custom,"auto",160) == listOf(custom))
 }
 val entered=CountDownLatch(1); val release=CountDownLatch(1)
 val http=OkHttpClient.Builder().addInterceptor { chain ->
  val path=chain.request().url.encodedPath
  if (path=="/wait") { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)) }
  Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
   .body("payload".toResponseBody("text/plain".toMediaType())).build()
 }.build()
 try {
  scenario("Async HTTP preserves status and complete response body") {
   val result=http.newCall(Request.Builder().url("https://example.invalid/ok").build()).awaitBytes()
   check(result.isSuccessful && result.bytes.decodeToString()=="payload")
  }
  scenario("Oversized responses fail before entering the bitmap/JSON cache") {
   var rejected=false
   try { http.newCall(Request.Builder().url("https://example.invalid/large").build()).awaitBytes(3) }
   catch (_:IOException) { rejected=true }
   check(rejected)
  }
  scenario("Leaving a view cancels the underlying OkHttp call and rejects late success") {
   val call=http.newCall(Request.Builder().url("https://example.invalid/wait").build())
   var committed=false
   val job=launch(Dispatchers.Default) { call.awaitBytes(); committed=true }
   check(withContext(Dispatchers.IO) { entered.await(5,TimeUnit.SECONDS) })
   job.cancelAndJoin()
   check(call.isCanceled() && !committed)
   release.countDown()
  }
 } finally { release.countDown(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
 println("$count UI/core scenarios passed (no Android runtime)")
}
'''
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--compiler-classpath',required=True);p.add_argument('--test-classpath',required=True);a=p.parse_args()
 def expand(s):return os.pathsep.join(str(Path(f).resolve()) for x in s.split(os.pathsep) for f in (glob.glob(x) if '*' in x else [x]))
 compiler=expand(a.compiler_classpath);cp=compiler+os.pathsep+expand(a.test_classpath)
 root=Path(__file__).resolve().parents[1];base=root/'car/src/main/java/de/uni/youtubeaa/car'
 with tempfile.TemporaryDirectory(prefix='youtube-aa-ui-core-') as d:
  tmp=Path(d);runner=tmp/'Runner.kt';runner.write_text(RUNNER);classes=tmp/'classes';classes.mkdir()
  sources=[base/n for n in ['ThumbnailSizing.kt','CarLayoutSpec.kt','HttpAwait.kt']]
  subprocess.run(['java','-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(classes),*map(str,sources),str(runner)],check=True)
  subprocess.run(['java','-cp',str(classes)+os.pathsep+cp,'RunnerKt'],check=True)
if __name__=='__main__':main()
