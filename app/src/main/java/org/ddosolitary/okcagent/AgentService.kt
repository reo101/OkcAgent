package org.ddosolitary.okcagent

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.Closeable
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

abstract class AgentService : Service() {
	companion object {
		const val ACTION_RUN_AGENT = "org.ddosolitary.okcagent.action.RUN_AGENT"
		const val EXTRA_PROXY_PORT = "org.ddosolitary.okcagent.extra.PROXY_PORT"
		private const val RESULT_CODE_ERROR = 0
		private const val RESULT_CODE_SUCCESS = 1
		private const val RESULT_CODE_USER_INTERACTION_REQUIRED = 2
		private const val ACTION_RESULT_CALLBACK = "org.ddosolitary.okcagent.action.RESULT_CALLBACK"
		private const val ACTION_TERMINATE_SERVICE = "org.ddosolitary.okcagent.action.TERMINATE_SERVICE"
		private const val EXTRA_RESULT_CODE = "result_code"
		private const val EXTRA_PENDING_INTENT = "intent"

		val lockObj = ReentrantLock()
		private val authorizationLock = ReentrantLock()
	}

	private class NullableIntentHolder(val intent: Intent?)
	private class ThreadContext(val thread: Thread, val queue: ArrayBlockingQueue<NullableIntentHolder>)
	class StreamStatus(@Volatile var exception: Exception?)

	private val threadMap = ConcurrentHashMap<Int, ThreadContext>()
	@Volatile private var exited = false

	private fun checkServiceExit() {
		if (threadMap.isEmpty()) stopSelf()
	}

	protected fun checkThreadExit(port: Int) {
		Handler(mainLooper).post {
			threadMap.remove(port)
			(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(port)
			if (!exited) checkServiceExit()
		}
	}

	// GPG's control connection is output-only. Its input EOF is cancellation,
	// not a protocol message. Do not use this monitor on the SSH data socket.
	protected fun watchClientDisconnect(socket: Socket): Closeable {
		val requestThread = Thread.currentThread()
		val active = AtomicBoolean(true)
		socket.soTimeout = 500
		val monitor = thread(isDaemon = true, name = "gpg-client-disconnect") {
			try {
				while (active.get()) {
					try {
						if (socket.getInputStream().read() == -1) break
					} catch (_: SocketTimeoutException) { }
				}
			} catch (_: Exception) {
			} finally {
				if (active.get()) requestThread.interrupt()
			}
		}
		return Closeable {
			active.set(false)
			if (!Thread.currentThread().isInterrupted) monitor.join(1000)
		}
	}

	protected abstract fun getErrorMessage(intent: Intent): String?

	protected abstract fun runAgent(port: Int, intent: Intent)

	protected fun callApi(executeApi: (Intent) -> Intent?, req: Intent, port: Int, stat: StreamStatus?): Intent? {
		var reqIntent = req
		while (true) {
			lockObj.lockInterruptibly()
			val resIntent = try {
				executeApi(reqIntent) ?: throw IllegalStateException("Provider is not connected")
			} finally {
				lockObj.unlock()
			}
			stat?.exception?.let { throw it }
			when (resIntent.getIntExtra(EXTRA_RESULT_CODE, RESULT_CODE_ERROR)) {
					RESULT_CODE_SUCCESS -> return resIntent
					RESULT_CODE_USER_INTERACTION_REQUIRED -> {
						authorizationLock.lockInterruptibly()
						try {
						val runnerIntent = Intent(this, IntentRunnerActivity::class.java).apply {
							action = IntentRunnerActivity.ACTION_RUN_PENDING_INTENT
							flags = Intent.FLAG_ACTIVITY_NEW_TASK
							putExtra(
								IntentRunnerActivity.EXTRA_API_INTENT,
								resIntent.getParcelableExtra<PendingIntent>(EXTRA_PENDING_INTENT)
							)
							putExtra(
								IntentRunnerActivity.EXTRA_CALLBACK_INTENT,
								Intent(this@AgentService, this@AgentService.javaClass).apply {
									action = ACTION_RESULT_CALLBACK
									putExtra(EXTRA_PROXY_PORT, port)
								}
							)
						}
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
							val pi = PendingIntent.getActivity(
								this,
								port,
								runnerIntent,
								PendingIntent.FLAG_UPDATE_CURRENT
							)
							val notification = NotificationCompat.Builder(this, getString(R.string.channel_id_auth))
								.setPriority(NotificationCompat.PRIORITY_HIGH)
								.setSmallIcon(R.drawable.ic_key)
								.setContentTitle(getString(R.string.notification_auth_title))
								.setContentText(getString(R.string.notification_auth_content))
								.setStyle(
									NotificationCompat.BigTextStyle().bigText(
										getString(R.string.notification_auth_content)
									)
								)
								.setAutoCancel(true)
								.setOngoing(true)
								.setContentIntent(pi)
								.build()
							(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(
								port,
								notification
							)
						} else {
							startActivity(runnerIntent)
						}
						try {
							val response = threadMap[port]?.queue?.poll(120, TimeUnit.SECONDS)
								?: throw IllegalStateException("Authorization timed out")
							reqIntent = response.intent ?: return null
						} finally {
							(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(port)
						}
						} finally { authorizationLock.unlock() }
					}
					RESULT_CODE_ERROR -> {
						throw IllegalStateException(getErrorMessage(resIntent) ?: getString(R.string.error_api))
					}
					else -> throw IllegalStateException("Provider returned an invalid result code")
				}
		}
	}

	protected fun startForeground(title: Int, text: Int, id: Int) {
		val intent = Intent(this, this.javaClass).apply { action = ACTION_TERMINATE_SERVICE }
		val pi = PendingIntent.getService(this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT)
		val notification = NotificationCompat.Builder(this, getString(R.string.channel_id_service))
			.setPriority(NotificationCompat.PRIORITY_MIN)
			.setSmallIcon(R.drawable.ic_key)
			.setContentTitle(getString(title))
			.setContentText(getString(text))
			.addAction(R.drawable.ic_stop, getString(R.string.text_terminate), pi)
			.build()
		startForeground(id, notification)
	}

	override fun onBind(intent: Intent): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val serviceChannel = NotificationChannel(
				getString(R.string.channel_id_service),
				getString(R.string.channel_service),
				NotificationManager.IMPORTANCE_MIN
			)
			val authChannel = NotificationChannel(
				getString(R.string.channel_id_auth),
				getString(R.string.channel_auth),
				NotificationManager.IMPORTANCE_HIGH
			)
			val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			mgr.createNotificationChannel(serviceChannel)
			mgr.createNotificationChannel(authChannel)
		}
	}

	override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
		val port = intent.getIntExtra(EXTRA_PROXY_PORT, -1)
		when (intent.action) {
			ACTION_RUN_AGENT -> {
				if (port !in 1..65535) { checkServiceExit(); return START_NOT_STICKY }
				if (!threadMap.containsKey(port)) {
					val worker = thread(start = false) { runAgent(port, intent) }
					threadMap[port] = ThreadContext(worker, ArrayBlockingQueue(1))
					worker.start()
				} else checkServiceExit()
			}
			ACTION_RESULT_CALLBACK -> threadMap[port]?.queue?.offer(
				NullableIntentHolder(intent.getParcelableExtra(IntentRunnerActivity.EXTRA_RESULT_INTENT))
			) ?: checkServiceExit()
			ACTION_TERMINATE_SERVICE -> stopSelf()
		}
		return START_NOT_STICKY
	}

	override fun onDestroy() {
		exited = true
		for ((port, ctx) in threadMap) {
			(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(port)
			ctx.thread.interrupt()
		}
		for ((_, ctx) in threadMap) {
			ctx.thread.join(3000)
		}
		super.onDestroy()
	}
}
