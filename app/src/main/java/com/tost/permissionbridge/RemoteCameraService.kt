package com.tost.permissionbridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class RemoteCameraService : Service() {
    private lateinit var cameraManager: CameraManager
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var pending: ((ByteArray?) -> Unit)? = null
    private var latestFrame: ByteArray? = null
    private var repeating = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        cameraManager = getSystemService(CameraManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCameraService()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopCameraService()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Remote camera is active"), type)
        } catch (_: Exception) {
            stopCameraService()
            return START_NOT_STICKY
        }
        startCamera()
        return START_NOT_STICKY
    }

    private fun startCamera() {
        if (camera != null) return
        thread = HandlerThread("tost-camera").also { it.start() }
        handler = Handler(thread!!.looper)
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val c = cameraManager.getCameraCharacteristics(id)
                c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return
            reader = ImageReader.newInstance(960, 540, ImageFormat.JPEG, 3).also { imageReader ->
                imageReader.setOnImageAvailableListener({ source ->
                    val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val bytes = image.use { imageData ->
                        val buffer = imageData.planes[0].buffer
                        ByteArray(buffer.remaining()).also(buffer::get)
                    }
                    latestFrame = bytes
                    val callback = pending
                    pending = null
                    callback?.invoke(bytes)
                }, handler)
            }
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    val output = reader?.surface ?: return
                    device.createCaptureSession(listOf(output), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            startRepeating()
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { stopCameraService() }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); camera = null; stopCameraService() }
                override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; stopCameraService() }
            }, handler)
        } catch (_: SecurityException) {
            stopCameraService()
        } catch (_: Exception) {
            stopCameraService()
        }
    }

    private fun startRepeating() {
        val device = camera ?: return
        val captureSession = session ?: return
        val output = reader?.surface ?: return
        if (repeating) return
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(output)
                set(android.hardware.camera2.CaptureRequest.JPEG_QUALITY, 58.toByte())
            }.build()
            captureSession.setRepeatingRequest(request, null, handler)
            repeating = true
        } catch (_: Exception) {
            stopCameraService()
        }
    }

    private fun capture(callback: (ByteArray?) -> Unit) {
        if (camera == null || session == null || reader == null) {
            callback(null)
            return
        }
        latestFrame?.let {
            callback(it)
            return
        }
        pending?.invoke(null)
        pending = callback
    }

    private fun stopCameraService() {
        pending?.invoke(null)
        pending = null
        repeating = false
        latestFrame = null
        session?.stopRepeating()
        session?.close(); session = null
        camera?.close(); camera = null
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
        repeating = false
        latestFrame = null
        session?.close(); session = null
        camera?.close(); camera = null
        reader?.close(); reader = null
        thread?.quitSafely(); thread = null; handler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tost remote camera", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("Tost remote camera")
        .setContentText(text)
        .setOngoing(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "tost_remote_camera"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_START = "com.tost.permissionbridge.REMOTE_CAMERA_START"
        const val ACTION_STOP = "com.tost.permissionbridge.REMOTE_CAMERA_STOP"
        @Volatile private var instance: RemoteCameraService? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RemoteCameraService::class.java).setAction(ACTION_START))
        }
        fun stop(context: Context) {
            context.startService(Intent(context, RemoteCameraService::class.java).setAction(ACTION_STOP))
        }
        fun isActive() = instance != null
        fun requestSnapshot(callback: (ByteArray?) -> Unit) {
            val service = instance
            if (service == null) callback(null) else service.handler?.post { service.capture(callback) } ?: callback(null)
        }
    }
}
