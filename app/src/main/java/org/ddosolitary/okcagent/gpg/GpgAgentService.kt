package org.ddosolitary.okcagent.gpg

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import org.ddosolitary.okcagent.AgentService
import org.ddosolitary.okcagent.R
import org.ddosolitary.okcagent.writeString
import org.ddosolitary.okcagent.ssh.SshApi
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpMetadata
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi.*
import org.openintents.ssh.authentication.SshAuthenticationApiError
import org.openintents.ssh.authentication.request.SshPublicKeyRequest
import org.openintents.ssh.authentication.response.SshPublicKeyResponse
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class GpgAgentService : AgentService() {
	companion object {
		const val EXTRA_GPG_ARGS = "org.ddosolitary.okcagent.extra.GPG_ARGS"
		private const val LOG_TAG = "GpgAgentService"
	}

	override fun getErrorMessage(intent: Intent): String? =
		when (val error = intent.getParcelableExtra<android.os.Parcelable>(RESULT_ERROR)) {
			is OpenPgpError -> error.message
			is SshAuthenticationApiError -> error.message
			else -> null
		}

	private fun keyId(value: String): Long? {
		val match = Regex("(?:0[xX])?([0-9a-fA-F]{16})").matchEntire(value) ?: return null
		return match.groupValues[1].toULong(16).toLong()
	}

	private fun execute(api: GpgApi, request: Intent, port: Int, stat: StreamStatus,
		input: InputStream? = null, output: OutputStream? = null): Intent {
		// A provider can produce partial output before asking for authorization.
		// Commit only the successful attempt, never failed or duplicate output.
		val temporary = if (output != null) File.createTempFile("gpg-output-", null, cacheDir) else null
		try {
			val result = callApi({ intent ->
				if (temporary == null) api.executeApi(intent, input, null)
				else temporary.outputStream().use { api.executeApi(intent, input, it) }
			}, request, port, stat) ?: throw IllegalStateException("Authorization was canceled")
			if (temporary != null) temporary.inputStream().use { it.copyTo(output!!) }
			return result
		} finally { temporary?.delete() }
	}

	private fun signingKey(api: GpgApi, args: GpgArguments, configured: Long, port: Int, stat: StreamStatus): Long {
		val selector = args.options["local-user"] ?: args.options["default-user"] ?: args.options["default-key"]
		val selected = if (selector == null) configured else keyId(selector) ?: run {
			// The provider's sign-key dialog dereferences a null email when the
			// selector is a bare address. Resolve through the key-id query that
			// recipient selection already uses.
			val result = execute(api, Intent().apply {
				action = ACTION_GET_KEY_IDS
				putExtra(EXTRA_USER_IDS, arrayOf(selector))
			}, port, stat)
			val resolved = result.getLongArrayExtra(RESULT_KEY_IDS)
			require(resolved != null && resolved.size == 1) {
				"Expected exactly one signing key for $selector, found ${resolved?.size ?: 0}"
			}
			resolved[0]
		}
		require(selected != 0L && selected != -1L) { "No signing key selected" }
		return selected
	}

	private fun selectedKeys(api: GpgApi, selectors: List<String>, configured: Long, port: Int, stat: StreamStatus): LongArray {
		if (selectors.isEmpty()) {
			require(configured != 0L && configured != -1L) { "Select a key or configure the GPG key in OkcAgent" }
			return longArrayOf(configured)
		}
		val ids = mutableListOf<Long>()
		for (selector in selectors) {
			keyId(selector)?.let { ids.add(it) } ?: run {
				require(selector.isNotBlank() && selector.contains('@')) { "Use a full 16-digit key ID or an email address: $selector" }
				val result = execute(api, Intent().apply {
					action = ACTION_GET_KEY_IDS
					putExtra(EXTRA_USER_IDS, arrayOf(selector))
				}, port, stat)
				val resolved = result.getLongArrayExtra(RESULT_KEY_IDS)
				require(resolved != null && resolved.isNotEmpty()) { "No key found for $selector" }
				ids.addAll(resolved.toList())
			}
		}
		return ids.distinct().toLongArray()
	}

	private fun exportSshKey(args: GpgArguments, configured: Long, port: Int, output: OutputStream) {
		val selected = args.arguments.firstOrNull()?.let {
			keyId(it) ?: throw IllegalArgumentException("SSH export requires a full 16-digit authentication subkey ID")
		} ?: configured
		require(selected != 0L && selected != -1L) { "No SSH key selected" }
		val bound = Semaphore(0)
		var connected = false
		SshApi(this) { connected = it; bound.release() }.use { api ->
			api.connect()
			check(bound.tryAcquire(15, TimeUnit.SECONDS) && connected) { "Cannot connect to the SSH provider" }
			val result = callApi({ api.executeApi(it) }, SshPublicKeyRequest(selected.toString()).toIntent(), port, null)
				?: throw IllegalStateException("SSH export was canceled")
			output.write((SshPublicKeyResponse(result).sshPublicKey.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
		}
	}

	override fun runAgent(port: Int, intent: Intent) {
		var socket: Socket? = null
		var monitor: Closeable? = null
		var control: OutputStream? = null
		var success = false
		try {
			socket = Socket("127.0.0.1", port)
			control = socket.getOutputStream().also { it.write(0) }
			monitor = watchClientDisconnect(socket)
			val args = GpgArguments.parse(this, intent.getStringArrayExtra(EXTRA_GPG_ARGS)?.map {
				Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8)
			})
			args.validate()
			val configured = getSharedPreferences(getString(R.string.pref_main), Context.MODE_PRIVATE)
				.getLong(getString(R.string.key_gpg_key), -1)
			GpgOutputWrapper(port, args.options["output"] ?: "-").use { output ->
				if (args.options.containsKey("export-ssh-key")) {
					exportSshKey(args, configured, port, output)
				} else if (args.options.containsKey("list-config")) {
					output.write("cfg:version:0.3.0\ncfg:provider:${getString(R.string.provider_package_id)}\n".toByteArray())
				} else {
					val bound = Semaphore(0)
					var connected = false
					GpgApi(this) { connected = it; bound.release() }.use { api ->
						api.connect()
						check(bound.tryAcquire(15, TimeUnit.SECONDS) && connected) { "Cannot connect to the OpenPGP provider" }
						val stat = StreamStatus(null)
						val request = Intent().apply {
							putExtra(EXTRA_REQUEST_ASCII_ARMOR, args.options.containsKey("armor") || args.options.containsKey("clear-sign"))
							args.options["compress-level"]?.let { putExtra(EXTRA_ENABLE_COMPRESSION, it.toInt() > 0) }
							args.options["set-filename"]?.let { putExtra(EXTRA_ORIGINAL_FILENAME, it) }
						}
						when {
							args.options.containsKey("check-permission") -> {
								request.action = ACTION_CHECK_PERMISSION
								execute(api, request, port, stat)
							}
							args.options.containsKey("export") -> {
								for (id in selectedKeys(api, args.arguments, configured, port, stat)) {
									request.action = ACTION_GET_KEY
									request.putExtra(EXTRA_KEY_ID, id)
									execute(api, request, port, stat, output = output)
								}
							}
							args.options.containsKey("backup") -> {
								request.action = ACTION_BACKUP
								request.putExtra(EXTRA_KEY_IDS, selectedKeys(api, args.arguments, configured, port, stat))
								request.putExtra(EXTRA_BACKUP_SECRET, args.options.containsKey("backup-secret"))
								execute(api, request, port, stat, output = output)
							}
							else -> {
								val signing = args.options.containsKey("sign") || args.options.containsKey("clear-sign") || args.options.containsKey("detach-sign")
								if (signing) request.putExtra(EXTRA_SIGN_KEY_ID, signingKey(api, args, configured, port, stat))
								if (args.options.containsKey("encrypt")) {
									val recipients = listOf("recipient", "encrypt-to").flatMap { args.options[it]?.split('\u0000') ?: emptyList() }
									require(recipients.isNotEmpty()) { "Encryption requires a recipient" }
									request.putExtra(EXTRA_KEY_IDS, selectedKeys(api, recipients, configured, port, stat))
								}
								request.action = when {
									args.options.containsKey("clear-sign") -> ACTION_CLEARTEXT_SIGN
									args.options.containsKey("detach-sign") -> ACTION_DETACHED_SIGN
									args.options.containsKey("encrypt") -> if (signing) ACTION_SIGN_AND_ENCRYPT else ACTION_ENCRYPT
									args.options.containsKey("metadata") -> ACTION_DECRYPT_METADATA
									else -> ACTION_DECRYPT_VERIFY
								}
								if (args.options.containsKey("verify") && args.arguments.size == 2) {
									request.putExtra(EXTRA_DETACHED_SIGNATURE, GpgInputWrapper(port, args.arguments[0], null).use { it.readBytes() })
								}
								GpgInputWrapper(port, args.arguments.lastOrNull() ?: "-", stat).use { input ->
									val destination = if (args.options.containsKey("metadata") || args.options.containsKey("verify") && !args.options.containsKey("decrypt") || args.options.containsKey("detach-sign")) null else output
									val result = execute(api, request, port, stat, input.getAutoReopenStream(), destination)
									when {
										args.options.containsKey("detach-sign") -> output.write(result.getByteArrayExtra(RESULT_DETACHED_SIGNATURE)
											?: throw IllegalStateException("Provider returned no detached signature"))
										args.options.containsKey("metadata") -> {
											val metadata = result.getParcelableExtra<OpenPgpMetadata>(RESULT_METADATA)
												?: throw IllegalStateException("Provider returned no metadata")
											output.write((metadata.toString() + "\n").toByteArray())
										}
										request.action == ACTION_DECRYPT_VERIFY -> {
											val signature = result.getParcelableExtra<OpenPgpSignatureResult>(RESULT_SIGNATURE)
												?: throw IllegalStateException("Provider returned no signature result")
											writeString(control, "Signature result: ${signature.result}")
											require(signature.result == -1 && !args.options.containsKey("verify") || signature.result == 1 || signature.result == 3) { "Signature verification failed" }
										}
									}
									if (signing && args.options["status-fd"] == "2") {
										// OpenPgpApi does not return GnuPG's algorithm/fingerprint fields.
										writeString(control, "[GNUPG:] SIG_CREATED")
									}
								}
							}
						}
					}
				}
			}
			success = true
		} catch (error: Exception) {
			Log.e(LOG_TAG, Log.getStackTraceString(error))
			try { control?.let { writeString(it, "[E] $error") } } catch (_: Exception) { }
		} finally {
			try { monitor?.close() } catch (_: Exception) { }
			try { control?.write(byteArrayOf(0, 0, if (success) 0 else 1)) } catch (_: Exception) { }
			try { socket?.close() } catch (_: Exception) { }
			checkThreadExit(port)
		}
	}

	override fun onCreate() {
		super.onCreate()
		startForeground(R.string.notification_gpg_title, R.string.notification_gpg_content, R.integer.notification_id_gpg)
	}
}
