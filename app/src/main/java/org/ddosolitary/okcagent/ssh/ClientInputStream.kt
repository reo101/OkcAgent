package org.ddosolitary.okcagent.ssh

import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// Keep reading client EOF while the request waits for provider authorization.
// A bounded pipe preserves SSH messages without consuming them in a monitor.
class ClientInputStream(private val socket: Socket) : InputStream() {
	private val input = PipedInputStream(65536)
	private val output = PipedOutputStream(input)
	private val active = AtomicBoolean(true)
	private val requestThread = Thread.currentThread()
	private val reader = thread(isDaemon = true, name = "ssh-client-input") {
		try { socket.getInputStream().copyTo(output) } catch (_: Exception) {
		} finally {
			try { output.close() } catch (_: Exception) { }
			if (active.get()) requestThread.interrupt()
		}
	}
	override fun read() = input.read()
	override fun read(b: ByteArray, off: Int, len: Int) = input.read(b, off, len)
	override fun close() {
		active.set(false)
		try { socket.shutdownInput() } catch (_: Exception) { }
		input.close()
		if (!Thread.currentThread().isInterrupted) reader.join(1000)
	}
}
