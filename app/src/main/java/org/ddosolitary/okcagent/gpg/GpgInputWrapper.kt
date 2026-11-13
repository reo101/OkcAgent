package org.ddosolitary.okcagent.gpg

import org.ddosolitary.okcagent.AgentService
import org.ddosolitary.okcagent.readExact
import org.ddosolitary.okcagent.writeString
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Socket

class GpgInputWrapper(private val port: Int, private val path: String,
	private val stat: AgentService.StreamStatus?) : InputStream() {
	private var socket: Socket? = null
	private var stream: InputStream? = null
	private var buffer = ByteArray(0)
	private var bufferPosition = 0
	private var eof = false
	private var failure: EOFException? = null
	private var temporary: File? = null
	private var replay: RandomAccessFile? = null

	override fun read(): Int {
		val byte = ByteArray(1)
		return if (read(byte) == -1) -1 else byte[0].toInt() and 0xff
	}

	override fun read(b: ByteArray, off: Int, len: Int): Int {
		require(off >= 0 && len >= 0 && off <= b.size - len)
		if (len == 0) return 0
		failure?.let { throw it }
		if (eof) return -1
		if (stream == null) {
			socket = Socket("127.0.0.1", port)
			socket!!.getOutputStream().let { it.write(1); writeString(it, path) }
			stream = socket!!.getInputStream()
		}
		try {
			if (bufferPosition == buffer.size) {
				val header = readExact(stream!!, 2) ?: throw EOFException("Missing input frame")
				val size = (header[0].toInt() and 0xff) * 256 + (header[1].toInt() and 0xff)
				if (size == 0) { eof = true; return -1 }
				buffer = readExact(stream!!, size) ?: throw EOFException("Truncated input frame")
				bufferPosition = 0
			}
			val count = minOf(len, buffer.size - bufferPosition)
			buffer.copyInto(b, off, bufferPosition, bufferPosition + count)
			bufferPosition += count
			return count
		} catch (error: EOFException) {
			failure = error
			stat?.exception = error
			throw error
		}
	}

	fun getAutoReopenStream(): InputStream {
		check(replay == null) { "Input replay stream already exists" }
		temporary = File.createTempFile("gpg-input-", null)
		val cache = RandomAccessFile(temporary!!, "rw")
		replay = cache
		return object : InputStream() {
			private var position = 0L
			override fun read(): Int {
				val byte = ByteArray(1)
				return if (read(byte) == -1) -1 else byte[0].toInt() and 0xff
			}
			@Synchronized override fun read(b: ByteArray, off: Int, len: Int): Int {
				require(off >= 0 && len >= 0 && off <= b.size - len)
				if (len == 0) return 0
				cache.seek(position)
				val count = if (position < cache.length()) {
					cache.read(b, off, minOf(len.toLong(), cache.length() - position).toInt())
				} else {
					this@GpgInputWrapper.read(b, off, len).also {
						if (it != -1) cache.write(b, off, it)
					}
				}
				if (count != -1) position += count
				return count
			}
			@Synchronized override fun close() { position = 0 }
		}
	}

	override fun close() {
		try { socket?.close() } finally {
			try { replay?.close() } finally { temporary?.delete() }
		}
	}
}
