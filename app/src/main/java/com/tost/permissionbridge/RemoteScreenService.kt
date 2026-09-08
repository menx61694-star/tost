package com.tost.permissionbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

class RemoteScreenService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var pending: ((ByteArray?) -> Unit)? = null
    @Volatile private var latestFrame: ByteArray? = null
    @Volatile private var latestFrameAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopScreenService()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) {
            stopScreenService()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Screen sharing is active"), type)
        } catch (_: Exception) {
            stopScreenService()
            return START_NOT_STICKY
        }
        startProjection(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        if (projection != null) return
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, data)
            val metrics = resources.displayMetrics
            val sourceWidth = metrics.widthPixels.coerceAtLeast(1)
            val sourceHeight = metrics.heightPixels.coerceAtLeast(1)
            val scale = minOf(1f, 854f / sourceWidth.toFloat())
            val width = ((sourceWidth * scale).toInt() and -2).coerceAtLeast(320)
            val height = ((sourceHeight * scale).toInt() and -2).coerceAtLeast(320)

            thread = HandlerThread("tost-screen").also { it.start() }
            handler = Handler(thread!!.looper)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            reader!!.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bytes = image.use { convertToJpeg(it, width, height) } ?: return@setOnImageAvailableListener
                latestFrame = bytes
                latestFrameAt = System.currentTimeMillis()
                val callback = pending
                pending = null
                callback?.invoke(bytes)
            }, handler)

            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopScreenService()
                }
            }, handler)

            display = projection!!.createVirtualDisplay(
                "TostRemoteScreen",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                null,
                handler
            )
        } catch (_: Exception) {
            stopScreenService()
        }
    }

    private fun convertToJpeg(image: Image, width: Int, height: Int): ByteArray? {
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val rowStride = plane.rowStride.coerceAtLeast(pixelStride * width)
            val rowPadding = (rowStride - pixelStride * width) / pixelStride
            val bitmapWidth = width + rowPadding
            val buffer = plane.buffer
            val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = if (bitmapWidth == width) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
            ByteArrayOutputStream().use { output ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 62, output)
                if (cropped !== bitmap) cropped.recycle()
                bitmap.recycle()
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun capture(callback: (ByteArray?) -> Unit) {
        if (projection == null || reader == null) {
            callback(null)
            return
        }
        val frame = latestFrame
        if (frame != null && System.currentTimeMillis() - latestFrameAt < 2_000L) {
            callback(frame)
            return
        }
        pending?.invoke(null)
        pending = callback
    }

    private fun stopScreenService() {
        pending?.invoke(null)
        pending = null
        latestFrame = null
        latestFrameAt = 0L
        display?.release(); display = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        reader?.close(); reader = null
        thread?.quitSafely(); thread = null; handler = null
        if (instance === this) instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        pending?.invoke(null)
        pending = null
        latestFrame = null
        latestFrameAt = 0L
        display?.release(); display = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        reader?.close(); reader = null
        thread?.quitSafely(); thread = null; handler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tost screen sharing", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("Tost screen sharing")
        .setContentText(text)
        .setOngoing(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "tost_screen_share"
        private const val NOTIFICATION_ID = 1003
        const val ACTION_STOP = "com.tost.permissionbridge.REMOTE_SCREEN_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        @Volatile private var instance: RemoteScreenService? = null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, RemoteScreenService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RemoteScreenService::class.java).setAction(ACTION_STOP))
        }

        fun isActive() = instance != null

        fun requestSnapshot(callback: (ByteArray?) -> Unit) {
            val service = instance
            if (service == null) callback(null)
            else service.handler?.post { service.capture(callback) } ?: callback(null)
        }
    }
}
