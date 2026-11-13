#!/usr/bin/env python3
"""Compile the real request lifecycle and GPG streams against small Android stubs."""
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCES = {
    "Context.kt": '''package android.content
open class Context {
  companion object { const val NOTIFICATION_SERVICE = "notification"; const val MODE_PRIVATE = 0 }
  open fun getString(id: Int) = "resource-$id"
  open fun getSystemService(name: String): Any = android.app.Notifications.manager
  open fun startActivity(intent: Intent) {}
  val cacheDir = java.io.File(System.getProperty("java.io.tmpdir"))
  fun getSharedPreferences(name: String, mode: Int) = Preferences()
}
class Preferences { fun getLong(name: String, fallback: Long) = 123456789L }
abstract class BroadcastReceiver { abstract fun onReceive(context: Context, intent: Intent) }
class Intent {
  var action: String? = null
  var flags = 0
  val extras = mutableMapOf<String, Any?>()
  constructor()
  constructor(context: Context, type: Class<*>)
  fun putExtra(key: String, value: Any?): Intent { extras[key] = value; return this }
  fun getIntExtra(key: String, fallback: Int) = extras[key] as? Int ?: fallback
  fun getLongExtra(key: String, fallback: Long) = extras[key] as? Long ?: fallback
  fun getByteArrayExtra(key: String) = extras[key] as? ByteArray
  fun getLongArrayExtra(key: String) = extras[key] as? LongArray
  @Suppress("UNCHECKED_CAST") fun getStringArrayExtra(key: String) = extras[key] as? Array<String>
  @Suppress("UNCHECKED_CAST") fun <T> getParcelableExtra(key: String): T? = extras[key] as? T
  companion object { const val FLAG_ACTIVITY_NEW_TASK = 1 }
}
''',
    "Service.kt": '''package android.app
import android.content.Context
import android.content.Intent
open class Service: Context() {
  val mainLooper: Any = Any()
  val javaClass: Class<*> get() = this::class.java
  open fun onBind(intent: Intent): android.os.IBinder? = null
  open fun onCreate() {}
  open fun onStartCommand(intent: Intent, flags: Int, id: Int) = 0
  open fun onDestroy() {}
  fun stopSelf() {}
  fun startForeground(id: Int, notification: Any) {}
  companion object { const val START_NOT_STICKY = 2 }
}
open class Activity: Context()
class PendingIntent {
  companion object {
    const val FLAG_UPDATE_CURRENT = 1
    fun getActivity(c: Context, p: Int, i: Intent, f: Int) = PendingIntent()
    fun getService(c: Context, p: Int, i: Intent, f: Int) = PendingIntent()
  }
}
class NotificationChannel(id: String, name: String, importance: Int)
object Notifications { val manager = NotificationManager() }
class NotificationManager {
  val active = java.util.concurrent.ConcurrentHashMap<Int, Any>()
  fun notify(id: Int, value: Any) { active[id] = value }
  fun cancel(id: Int) { active.remove(id) }
  fun createNotificationChannel(channel: NotificationChannel) {}
  companion object { const val IMPORTANCE_MIN = 1; const val IMPORTANCE_HIGH = 4 }
}
''',
    "Os.kt": '''package android.os
interface IBinder
interface Parcelable
class Handler(loop: Any) { fun post(action: () -> Unit) { action() } }
object Build {
  object VERSION { const val SDK_INT = 36 }
  object VERSION_CODES { const val O = 26; const val Q = 29 }
}
''',
    "Notification.kt": '''package androidx.core.app
import android.content.Context
class NotificationCompat {
  class Builder(context: Context, channel: String) {
    fun setPriority(v: Int) = this
    fun setSmallIcon(v: Int) = this
    fun setContentTitle(v: String) = this
    fun setContentText(v: String) = this
    fun setStyle(v: Any) = this
    fun setAutoCancel(v: Boolean) = this
    fun setOngoing(v: Boolean) = this
    fun setContentIntent(v: Any) = this
    fun addAction(v: Int, title: String, p: Any) = this
    fun build(): Any = this
  }
  class BigTextStyle { fun bigText(v: String) = this }
  companion object { const val PRIORITY_HIGH = 1; const val PRIORITY_MIN = -2 }
}
''',
    "Resources.kt": '''package org.ddosolitary.okcagent
class IntentRunnerActivity {
  companion object {
    const val ACTION_RUN_PENDING_INTENT = "run"
    const val ACTION_FINISH = "finish"
    const val EXTRA_API_INTENT = "api"
    const val EXTRA_CALLBACK_INTENT = "callback"
    const val EXTRA_RESULT_INTENT = "result"
  }
}
class ErrorDialogActivity { companion object { const val EXTRA_ERROR_MESSAGE = "error" } }
object R {
  object string {
    const val channel_id_auth = 1; const val notification_auth_title = 2
    const val notification_auth_content = 3; const val error_api = 4
    const val channel_id_service = 5; const val text_terminate = 6
    const val channel_service = 7; const val channel_auth = 8
    const val channel_id_error = 9; const val channel_error = 10; const val text_error = 11
    const val pref_main = 12; const val key_gpg_key = 13; const val provider_package_id = 14
    const val notification_gpg_title = 15; const val notification_gpg_content = 16
    const val error_incompatible_gpg_proto_ver = 17
  }
  object drawable { const val ic_key = 1; const val ic_stop = 2; const val ic_error = 3 }
  object integer { const val notification_id_gpg = 50 }
}
''',
    "RuntimeTest.kt": '''import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.Notifications
import org.ddosolitary.okcagent.*
import org.ddosolitary.okcagent.gpg.*
import java.net.ServerSocket
import java.net.Socket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TestService: AgentService() {
  val waiting = CountDownLatch(1)
  val done = java.util.concurrent.ConcurrentHashMap<Int, CountDownLatch>()
  val outcomes = java.util.concurrent.ConcurrentHashMap<Int, String>()
  override fun getErrorMessage(intent: Intent): String? = "provider error"
  override fun runAgent(port: Int, intent: Intent) {
    try {
      Socket("127.0.0.1", port).use { socket ->
        watchClientDisconnect(socket).use {
          callApi({ request ->
            if (request.getIntExtra("mode", 0) == 0) Intent().putExtra("result_code", 1)
            else {
              waiting.countDown()
              Intent().putExtra("result_code", 2).putExtra("intent", PendingIntent())
            }
          }, intent, port, null)
          outcomes[port] = "success"
        }
      }
    } catch (_: InterruptedException) { outcomes[port] = "canceled" }
      catch (error: Exception) { outcomes[port] = "error: $error" }
    finally { checkThreadExit(port); done[port]!!.countDown() }
  }
  fun start(port: Int, mode: Int) {
    done[port] = CountDownLatch(1)
    onStartCommand(Intent().apply { action = ACTION_RUN_AGENT; putExtra(EXTRA_PROXY_PORT, port); putExtra("mode", mode) }, 0, 1)
  }
}

fun main() {
  val service = TestService()
  ServerSocket(0).use { pending ->
    service.start(pending.localPort, 1)
    val connection = pending.accept()
    check(service.waiting.await(2, TimeUnit.SECONDS))
    ServerSocket(0).use { immediate ->
      service.start(immediate.localPort, 0)
      immediate.accept().use {
        check(service.done[immediate.localPort]!!.await(2, TimeUnit.SECONDS)) { "Pending UI blocked independent request" }
        check(service.outcomes[immediate.localPort] == "success")
      }
    }
    connection.close()
    check(service.done[pending.localPort]!!.await(2, TimeUnit.SECONDS)) { "Client disconnect did not cancel UI wait" }
    check(service.outcomes[pending.localPort] == "canceled")
    check(!Notifications.manager.active.containsKey(pending.localPort))
  }

  fun parse(vararg args: String) = GpgArguments.parse(Context(), args.toList())
  parse("--encrypt", "-r", "user@example.com").validate()
  parse("--status-fd=2", "-bsau", "user@example.com").validate()
  parse("--backup", "--backup-secret", "0123456789ABCDEF").validate()
  check(parse("--export", "--", "--list-secret-keys").arguments == listOf("--list-secret-keys"))
  check(parse("--export", "--output=").options["output"] == "")
  check(parse("-ea", "-rfirst@example.com", "--recipient=second@example.com").options["recipient"] == "first@example.com\\u0000second@example.com")
  for (args in listOf(listOf("--list-secret-keys"), listOf("--gen-key"), listOf("--encrypt", "--hidden-recipient=x"), listOf("--passphrase=x", "--decrypt"), listOf("--sign"), listOf("--decrypt", "--export"), listOf("--metadata", "--export"), listOf("--decrypt", "--status-fd=1"), listOf("--export", "--output"))) {
    check(runCatching { parse(*args.toTypedArray()).validate() }.isFailure) { "Accepted unsupported arguments $args" }
  }

  val rejectedString = java.io.ByteArrayOutputStream()
  check(runCatching { writeString(rejectedString, "x".repeat(65536)) }.isFailure)
  check(rejectedString.size() == 0)
  val expected = ByteArray(256) { it.toByte() }
  ServerSocket(0).use { server ->
    val sender = thread {
      server.accept().use { socket ->
        val input = DataInputStream(socket.getInputStream())
        check(input.readUnsignedByte() == 1)
        check(input.readUTF() == "-")
        val output = DataOutputStream(socket.getOutputStream())
        output.writeShort(expected.size); output.write(expected); output.writeShort(0)
      }
    }
    GpgInputWrapper(server.localPort, "-", null).use { source ->
      val replay = source.getAutoReopenStream()
      check(replay.read(ByteArray(0)) == 0)
      check(replay.read(ByteArray(4)) == 4)
      replay.close()
      check(replay.readBytes().contentEquals(expected))
      replay.close()
      check(replay.readBytes().contentEquals(expected))
      replay.close()
    }
    sender.join(2000); check(!sender.isAlive)
  }
  ServerSocket(0).use { server ->
    val sender = thread {
      server.accept().use { socket ->
        val input = DataInputStream(socket.getInputStream())
        input.readUnsignedByte(); input.readUTF()
        DataOutputStream(socket.getOutputStream()).writeShort(5)
      }
    }
    GpgInputWrapper(server.localPort, "-", null).use {
      check(runCatching { it.read() }.exceptionOrNull() is EOFException)
    }
    sender.join(2000)
  }
  ServerSocket(0).use { server ->
    val receiver = thread {
      server.accept().use { socket ->
        val input = DataInputStream(socket.getInputStream())
        check(input.readUnsignedByte() == 2); check(input.readUTF() == "-")
        val data = ByteArray(input.readUnsignedShort()); input.readFully(data)
        check(data.contentEquals(expected)); check(input.readUnsignedShort() == 0)
      }
    }
    GpgOutputWrapper(server.localPort, "-").use { it.write(ByteArray(0)); it.write(expected) }
    receiver.join(2000); check(!receiver.isAlive)
  }
  ServerSocket(0).use { server ->
    val ready = CountDownLatch(1)
    val canceled = java.util.concurrent.CompletableFuture<Boolean>()
    val worker = thread {
      try {
        java.net.Socket("127.0.0.1", server.localPort).use { socket ->
          org.ddosolitary.okcagent.ssh.ClientInputStream(socket).use { input ->
            val bytes = ByteArray(256)
            DataInputStream(input).readFully(bytes)
            check(bytes.contentEquals(expected))
            ready.countDown()
            Thread.sleep(10000)
          }
        }
        canceled.complete(false)
      } catch (_: InterruptedException) { canceled.complete(true) }
    }
    server.accept().use { socket ->
      socket.getOutputStream().write(expected)
      check(ready.await(2, TimeUnit.SECONDS))
    }
    check(canceled.get(2, TimeUnit.SECONDS)) { "SSH disconnect did not interrupt authorization" }
    worker.join(2000)
  }
  val receiver = GpgProxyReceiver()
  val legacy = Intent().putExtra("org.ddosolitary.okcagent.extra.GPG_PROTO_VER", 1).putExtra(AgentService.EXTRA_PROXY_PORT, 42)
  receiver.onReceive(Context(), legacy)
  check(androidx.core.content.ContextCompat.starts == 0) { "Legacy protocol was accepted" }
  receiver.onReceive(Context(), Intent().putExtra("org.ddosolitary.okcagent.extra.GPG_PROTO_VER", 2).putExtra(AgentService.EXTRA_PROXY_PORT, 42))
  check(androidx.core.content.ContextCompat.starts == 1)
  gpgServiceTests()
  println("Agent lifecycle, GPG API mappings, argument rejection, input replay and binary framing tests passed")
}
''',
}

import runpy
SOURCES.update(runpy.run_path(str(ROOT / "tests/gpg-service-stubs.py"))["SOURCES"])

with tempfile.TemporaryDirectory() as directory:
    directory = pathlib.Path(directory)
    stubs = []
    for name, content in SOURCES.items():
        source = directory / name
        source.write_text(content)
        stubs.append(str(source))
    base = ROOT / "app/src/main/java/org/ddosolitary/okcagent"
    actual = [base / name for name in ["AgentService.kt", "Utils.kt", "GpgProxyReceiver.kt", "gpg/GpgAgentService.kt", "gpg/GpgArguments.kt", "gpg/GpgInputWrapper.kt", "gpg/GpgOutputWrapper.kt", "ssh/ClientInputStream.kt"]]
    import re
    api = (ROOT / "libraries/openpgp-api/src/main/java/org/openintents/openpgp/util/OpenPgpApi.java").read_text()
    constants = re.findall(r'public static final String ([A-Z_]+) = "([^"\n]*)";', api)
    java = directory / "OpenPgpApi.java"
    java.write_text('package org.openintents.openpgp.util; public class OpenPgpApi {\n' + '\n'.join('public static final String %s = "%s";' % item for item in constants) + '\n}')
    classes = directory / "classes"
    classes.mkdir()
    subprocess.run(["javac", "-d", str(classes), str(java)], check=True)
    archive = directory / "tests.jar"
    subprocess.run(["kotlinc", *map(str, actual), *stubs, "-classpath", str(classes), "-nowarn", "-include-runtime", "-d", str(archive)], check=True)
    subprocess.run(["jar", "uf", str(archive), "-C", str(classes), "."], check=True)
    subprocess.run(["java", "-jar", str(archive)], check=True, timeout=30)
