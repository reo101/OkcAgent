package org.ddosolitary.okcagent.gpg

import org.ddosolitary.okcagent.writeString
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GpgOutputWrapper(private val port: Int, private val path: String) : OutputStream() {
	private var stream: OutputStream? = null
	private var socket: Socket? = null

	override fun write(b: Int) {
		write(byteArrayOf(b.toByte()))
	}

	override fun write(b: ByteArray) {
		write(b, 0, b.size)
	}

	override fun write(b: ByteArray, off: Int, len: Int) {
		require(off >= 0 && len >= 0 && off <= b.size - len)
		if (len == 0) return
		if (stream == null) {
			socket = Socket("127.0.0.1", port)
			stream = socket!!.getOutputStream().also {
				it.write(2)
				writeString(it, path)
			}
		}

		var cur = off
		while (cur < off + len) {
			val frameLen = minOf(UShort.MAX_VALUE.toInt(), off + len - cur)
			val lenBuf = ByteBuffer.allocate(Short.SIZE_BYTES).apply {
				order(ByteOrder.BIG_ENDIAN)
				putShort(frameLen.toUShort().toShort())
			}.array()
			stream!!.write(lenBuf)
			stream!!.write(b, cur, frameLen)
			cur += frameLen
		}
	}

	override fun flush() {
		stream?.flush()
	}

	override fun close() {
		try { stream?.write(byteArrayOf(0, 0)) } finally {
			socket?.close()
			socket = null
			stream = null
		}
	}
}
