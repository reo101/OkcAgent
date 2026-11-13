"""Android/provider stubs and API-mapping assertions for test-agent-runtime.py."""
SOURCES = {
    "ContextCompat.kt": '''package androidx.core.content
object ContextCompat {
  var starts = 0
  fun startForegroundService(context: android.content.Context, intent: android.content.Intent) { starts++ }
}
''',
    "AndroidUtil.kt": '''package android.util
object Base64 {
  const val DEFAULT = 0
  fun decode(value: String, flags: Int) = java.util.Base64.getDecoder().decode(value)
}
object Log { fun e(tag: String, message: String) = 0; fun getStackTraceString(error: Throwable) = error.toString() }
''',
    "PgpResults.kt": '''package org.openintents.openpgp
class OpenPgpError(val message: String): android.os.Parcelable
class OpenPgpMetadata: android.os.Parcelable { override fun toString() = "filename=example" }
class OpenPgpSignatureResult(val result: Int): android.os.Parcelable
''',
    "GpgApi.kt": '''package org.ddosolitary.okcagent.gpg
import android.content.Context
import android.content.Intent
import java.io.InputStream
import java.io.OutputStream
class GpgApi(val context: Context, val callback: GpgApi.(Boolean) -> Unit): java.io.Closeable {
  companion object {
    val calls = mutableListOf<Intent>()
    var result: (Intent, InputStream?, OutputStream?) -> Intent? = { _, _, _ -> Intent().putExtra("result_code", 1) }
  }
  fun connect() { callback(true) }
  fun executeApi(intent: Intent, input: InputStream?, output: OutputStream?): Intent? {
    calls.add(intent)
    return result(intent, input, output)
  }
  override fun close() {}
}
''',
    "SshApi.kt": '''package org.ddosolitary.okcagent.ssh
import android.content.Context
import android.content.Intent
class SshApi(val context: Context, val callback: SshApi.(Boolean) -> Unit): java.io.Closeable {
  var connected = false
  fun connect() { kotlin.concurrent.thread { Thread.sleep(100); connected = true; callback(true) } }
  fun executeApi(intent: Intent): Intent { check(connected); return Intent().putExtra("result_code", 1).putExtra("ssh_public_key", "ssh-ed25519 TEST") }
  override fun close() {}
}
''',
    "SshError.kt": '''package org.openintents.ssh.authentication
class SshAuthenticationApiError(val message: String): android.os.Parcelable
''',
    "SshRequest.kt": '''package org.openintents.ssh.authentication.request
class SshPublicKeyRequest(val key: String) { fun toIntent() = android.content.Intent() }
''',
    "SshResponse.kt": '''package org.openintents.ssh.authentication.response
class SshPublicKeyResponse(val intent: android.content.Intent) { val sshPublicKey = intent.extras["ssh_public_key"] as String }
''',
    "GpgServiceTest.kt": '''import android.content.Intent
import org.ddosolitary.okcagent.AgentService
import org.ddosolitary.okcagent.gpg.GpgApi
import org.ddosolitary.okcagent.gpg.GpgAgentService
import org.openintents.openpgp.*
import org.openintents.openpgp.util.OpenPgpApi.*
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

fun gpgServiceTests() {
  var activeService: AgentService? = null
  var activePort = 0
  fun invoke(vararg args: String): Pair<Int, ByteArray> {
    ServerSocket(0).use { server ->
      server.soTimeout = 3000
      val service = GpgAgentService()
      activeService = service
      activePort = server.localPort
      val request = Intent().apply {
        action = AgentService.ACTION_RUN_AGENT
        putExtra(AgentService.EXTRA_PROXY_PORT, server.localPort)
        putExtra(GpgAgentService.EXTRA_GPG_ARGS, args.map { Base64.getEncoder().encodeToString(it.toByteArray()) }.toTypedArray())
      }
      service.onStartCommand(request, 0, 1)
      val control = server.accept()
      val controlInput = DataInputStream(control.getInputStream())
      check(controlInput.readUnsignedByte() == 0)
      val status = java.util.concurrent.CompletableFuture<Int>()
      val reader = thread {
        try {
          while (true) { val size = controlInput.readUnsignedShort(); if (size == 0) break; controlInput.readFully(ByteArray(size)) }
          status.complete(controlInput.readUnsignedByte())
        } catch (error: Throwable) { status.completeExceptionally(error) }
      }
      val data = java.io.ByteArrayOutputStream()
      server.soTimeout = 100
      while (!status.isDone) {
        try {
          server.accept().use { socket ->
            val input = DataInputStream(socket.getInputStream())
            val type = input.readUnsignedByte()
            input.readUTF()
            if (type == 1) {
              DataOutputStream(socket.getOutputStream()).apply { writeShort(3); write(byteArrayOf(0, -1, 1)); writeShort(0) }
            } else {
              check(type == 2)
              while (true) {
                val size = input.readUnsignedShort()
                if (size == 0) break
                val bytes = ByteArray(size); input.readFully(bytes); data.write(bytes)
              }
            }
          }
        } catch (_: SocketTimeoutException) { }
      }
      val result = status.get(3, TimeUnit.SECONDS)
      control.close(); reader.join(2000)
      return Pair(result, data.toByteArray())
    }
  }
  GpgApi.calls.clear()
  GpgApi.result = { request, _, output ->
    check(request.action == ACTION_GET_KEY)
    check(request.extras[EXTRA_REQUEST_ASCII_ARMOR] == false)
    check(request.getLongExtra(EXTRA_KEY_ID, 0) == 1L)
    output!!.write(byteArrayOf(0, -1, 1)); Intent().putExtra("result_code", 1)
  }
  val exported = invoke("--export", "0000000000000001")
  check(exported.first == 0 && exported.second.contentEquals(byteArrayOf(0, -1, 1)))
  for (args in listOf(listOf("--list-secret-keys"), listOf("--export-secret-keys"), listOf("--gen-key"), listOf("--sign"))) {
    GpgApi.calls.clear()
    check(invoke(*args.toTypedArray()).first == 1)
    check(GpgApi.calls.isEmpty()) { "Unsupported command reached provider" }
  }
  GpgApi.result = { _, _, output ->
    output!!.write("discard partial output".toByteArray())
    Intent().putExtra("result_code", 0).putExtra(RESULT_ERROR, OpenPgpError("Export failed"))
  }
  val failedExport = invoke("--export", "0000000000000001")
  check(failedExport.first == 1 && failedExport.second.isEmpty())
  var attempt = 0
  GpgApi.result = { request, _, output ->
    attempt++
    if (attempt == 1) {
      output!!.write("discard unauthorized output".toByteArray())
      activeService!!.onStartCommand(Intent().apply {
        action = "org.ddosolitary.okcagent.action.RESULT_CALLBACK"
        putExtra(AgentService.EXTRA_PROXY_PORT, activePort)
        putExtra("result", request)
      }, 0, 1)
      Intent().putExtra("result_code", 2).putExtra("intent", android.app.PendingIntent())
    } else {
      output!!.write("authorized output".toByteArray())
      Intent().putExtra("result_code", 1)
    }
  }
  val retried = invoke("--export", "0000000000000001")
  check(retried.first == 0 && String(retried.second) == "authorized output" && attempt == 2)
  GpgApi.result = { request, _, _ ->
    check(request.action == ACTION_BACKUP)
    check(request.extras[EXTRA_BACKUP_SECRET] == false)
    Intent().putExtra("result_code", 1)
  }
  check(invoke("--backup", "0000000000000001").first == 0)
  GpgApi.result = { request, _, _ ->
    check(request.action == ACTION_GET_KEY_IDS)
    val selectors = request.extras[EXTRA_USER_IDS] as Array<*>
    check(selectors.size == 1 && selectors[0] == "missing@example.com")
    Intent().putExtra("result_code", 0).putExtra(RESULT_ERROR, OpenPgpError("Selector rejected"))
  }
  check(invoke("--detach-sign", "--local-user", "missing@example.com").first == 1)
  GpgApi.result = { request, _, _ ->
    when (request.action) {
      ACTION_GET_KEY_IDS -> Intent().putExtra("result_code", 1).putExtra(RESULT_KEY_IDS, longArrayOf(7L))
      ACTION_DETACHED_SIGN -> {
        check(request.getLongExtra(EXTRA_SIGN_KEY_ID, 0L) == 7L)
        Intent().putExtra("result_code", 1).putExtra(RESULT_DETACHED_SIGNATURE, byteArrayOf(9))
      }
      else -> throw IllegalStateException("unexpected action ${request.action}")
    }
  }
  check(invoke("--detach-sign", "--local-user", "user@example.com").first == 0)
  GpgApi.result = { request, input, output ->
    if (request.action == ACTION_GET_KEY_IDS) {
      Intent().putExtra("result_code", 1).putExtra(RESULT_KEY_IDS, longArrayOf(2L))
    } else {
      check(request.action == ACTION_ENCRYPT)
      check(request.getLongArrayExtra(EXTRA_KEY_IDS)!!.contentEquals(longArrayOf(1L, 2L)))
      check(input!!.readBytes().contentEquals(byteArrayOf(0, -1, 1)))
      input.close()
      output!!.write("encrypted".toByteArray())
      Intent().putExtra("result_code", 1)
    }
  }
  check(invoke("--encrypt", "-r", "0000000000000001", "--encrypt-to", "user@example.com").first == 0)
  GpgApi.result = { request, input, output ->
    check(request.action == ACTION_DECRYPT_METADATA)
    check(output == null)
    input!!.close()
    Intent().putExtra("result_code", 1).putExtra(RESULT_METADATA, OpenPgpMetadata())
  }
  val metadata = invoke("--decrypt", "--metadata")
  check(metadata.first == 0 && String(metadata.second) == "filename=example\\n")
  val ssh = invoke("--export-ssh-key", "0000000000000001")
  check(ssh.first == 0 && String(ssh.second) == "ssh-ed25519 TEST\\n")
}
''',
}
