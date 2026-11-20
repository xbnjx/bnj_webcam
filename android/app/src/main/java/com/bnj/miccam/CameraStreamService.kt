package com.bnj.miccam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.sqrt

class CameraStreamService : Service() {
    private data class StreamPacket(val type: Int, val payload: ByteArray)

    @Volatile private var running = false
    @Volatile private var cameraActive = false
    @Volatile private var clientConnected = false
    @Volatile private var isTorchOn = false
    @Volatile private var targetFps = DEFAULT_TARGET_FPS
    @Volatile private var targetBitrateKbps = DEFAULT_TARGET_BITRATE_KBPS
    @Volatile private var currentExposureComp = 0
    @Volatile private var jpegQuality = INITIAL_JPEG_QUALITY

    private val output = AtomicReference<DataOutputStream?>(null)
    private val activeSocket = AtomicReference<Socket?>(null)
    private val outgoingLock = Object()
    private val outgoingPackets = ArrayDeque<StreamPacket>()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequest: CaptureRequest.Builder? = null
    private var networkThread: Thread? = null
    private var selectedCameraId: String? = null
    private var qualityAdjustmentFrames = 0
    private var lastVideoFrameAtNs = 0L
    private var totalFramesSent = 0L
    private var lastBroadcastStatus = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CAMERA -> {
                stopCamera()
                updateForegroundState()
                broadcastStatus(STATUS_STOPPED, "Cámara detenida")
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_TORCH -> {
                toggleTorch()
                return START_NOT_STICKY
            }
            ACTION_START_CAMERA -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "Falta el permiso de cámara")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val requestedCameraId = intent.getStringExtra(EXTRA_CAMERA_ID)
                val cameraChanged = cameraActive &&
                    requestedCameraId != null && requestedCameraId != selectedCameraId
                selectedCameraId = requestedCameraId
                cameraActive = true
                if (cameraChanged) {
                    cameraHandler?.post {
                        releaseCamera()
                        if (running && cameraActive) startCamera()
                    }
                }
            }
            else -> return START_NOT_STICKY
        }

        running = true
        updateForegroundState()
        if (networkThread == null || networkThread?.isAlive != true) {
            networkThread = thread(name = "miccam-network") { connectToClient() }
        }
        if (intent.action == ACTION_START_CAMERA &&
            (cameraDevice == null && cameraThread == null)
        ) {
            startCamera()
        }
        return START_NOT_STICKY
    }

    private fun broadcastStatus(status: String, details: String = "", force: Boolean = false) {
        if (!force && status == lastBroadcastStatus && status != STATUS_STREAMING) {
            return
        }
        lastBroadcastStatus = status
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_DETAILS, details)
            putExtra(EXTRA_FRAMES_SENT, totalFramesSent)
            putExtra(EXTRA_TORCH_ON, isTorchOn)
            putExtra(EXTRA_FPS, targetFps)
            putExtra(EXTRA_BITRATE, targetBitrateKbps)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun updateForegroundState() {
        if (!cameraActive) {
            running = false
            stopSelf()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "Android no permitió iniciar el servicio de captura", error)
            stopSelf()
        }
    }

    private fun startCamera() {
        if (cameraDevice != null || cameraThread != null) return
        cameraThread = HandlerThread("miccam-camera").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = manager.cameraIdList
            val cameraId = selectedCameraId?.takeIf { it in ids }
                ?: ids.firstOrNull { id ->
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
                }
                ?: ids.firstOrNull()
                ?: throw IOException("No se encontró una cámara disponible")
            selectedCameraId = cameraId
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IOException("La cámara no informa formatos de captura")
            val sizes = map.getOutputSizes(ImageFormat.JPEG)
                ?: throw IOException("La cámara no admite captura JPEG")
            val size = chooseMaximumSize(sizes, map, targetFps)
            Log.i(
                TAG,
                "Usando cámara $cameraId a ${size.width}x${size.height}, máximo 720p y $targetFps FPS"
            )
            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val now = SystemClock.elapsedRealtimeNanos()
                        val minInterval = 1_000_000_000L / targetFps
                        if (now - lastVideoFrameAtNs < minInterval) {
                            return@setOnImageAvailableListener
                        }
                        lastVideoFrameAtNs = now
                        val buffer = image.planes[0].buffer
                        val jpeg = ByteArray(buffer.remaining())
                        buffer.get(jpeg)
                        adaptJpegQuality(jpeg.size)
                        enqueuePacket(TYPE_VIDEO, jpeg)
                        totalFramesSent++
                    } catch (error: Exception) {
                        Log.e(TAG, "No se pudo enviar el fotograma de cámara", error)
                    } finally {
                        image.close()
                    }
                }, cameraHandler)
            }
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!running || !cameraActive) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createCaptureSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    Log.w(TAG, "La cámara se desconectó")
                    broadcastStatus(STATUS_STOPPED, "Cámara desconectada", force = true)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "Error de Camera2: $error")
                    stopCamera()
                    updateForegroundState()
                    broadcastStatus(STATUS_STOPPED, "Error de hardware de cámara", force = true)
                }
            }, cameraHandler)
        } catch (error: Exception) {
            Log.e(TAG, "No se pudo iniciar la cámara", error)
            stopCamera()
        }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        val reader = imageReader ?: return
        try {
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running || !cameraActive) {
                            session.close()
                            return
                        }
                        captureSession = session
                        try {
                            captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(reader.surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            }
                            applyCameraSettings()
                            if (clientConnected) {
                                broadcastStatus(STATUS_STREAMING, "Cámara transmitiendo a 720p @ $targetFps FPS", force = true)
                            } else {
                                broadcastStatus(STATUS_WAITING_PC, "Cámara activa. Esperando PC...", force = true)
                            }
                        } catch (error: Exception) {
                            Log.e(TAG, "No se pudo iniciar la captura de fotogramas", error)
                            stopCamera()
                            updateForegroundState()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "No se pudo configurar la sesión de cámara")
                        stopCamera()
                        updateForegroundState()
                    }
                },
                cameraHandler
            )
        } catch (error: Exception) {
            Log.e(TAG, "No se pudo crear la sesión de cámara", error)
            stopCamera()
        }
    }

    private fun applyCameraSettings() {
        val session = captureSession ?: return
        val request = captureRequest ?: return
        val id = selectedCameraId ?: return
        try {
            request.set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(id)

            // FPS Range
            val supportedRanges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )
            val selectedRange = supportedRanges?.minWithOrNull(
                compareBy<Range<Int>> {
                    when {
                        targetFps in it -> 0
                        targetFps < it.lower -> it.lower - targetFps
                        else -> targetFps - it.upper
                    }
                }.thenBy { kotlin.math.abs(it.upper - targetFps) }
                    .thenBy { it.upper - it.lower }
            )
            selectedRange?.let {
                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            }

            // Exposición
            val expRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            if (expRange != null && expRange.lower <= expRange.upper) {
                val clampedExp = currentExposureComp.coerceIn(expRange.lower, expRange.upper)
                request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedExp)
            }

            // Linterna
            if (isTorchOn) {
                request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            session.setRepeatingRequest(request.build(), null, cameraHandler)
        } catch (error: Exception) {
            Log.e(TAG, "No se pudieron aplicar configuraciones de cámara", error)
        }
    }

    private fun toggleTorch() {
        isTorchOn = !isTorchOn
        applyCameraSettings()
        if (clientConnected) {
            broadcastStatus(STATUS_STREAMING, if (isTorchOn) "Linterna encendida" else "Linterna apagada", force = true)
        }
    }

    private fun chooseMaximumSize(
        sizes: Array<Size>,
        map: android.hardware.camera2.params.StreamConfigurationMap,
        fps: Int
    ): Size {
        val frameDurationLimitNs = 1_000_000_000L / fps
        val sizesWithin720p = sizes.filter { size ->
            maxOf(size.width, size.height) <= MAX_VIDEO_DIMENSION &&
                minOf(size.width, size.height) <= MAX_VIDEO_SHORT_DIMENSION
        }
        if (sizesWithin720p.isEmpty()) {
            throw IOException("La cámara no ofrece una resolución de 720p o menor")
        }
        val sizesAtRequestedFps = sizesWithin720p.filter { size ->
            val minimumDurationNs = map.getOutputMinFrameDuration(ImageFormat.JPEG, size)
            minimumDurationNs > 0L && minimumDurationNs <= frameDurationLimitNs
        }
        if (sizesAtRequestedFps.isNotEmpty()) {
            return sizesAtRequestedFps.maxBy { it.width.toLong() * it.height }
        }
        val sizesWithKnownDuration = sizesWithin720p.mapNotNull { size ->
            map.getOutputMinFrameDuration(ImageFormat.JPEG, size)
                .takeIf { it > 0L }
                ?.let { size to it }
        }
        if (sizesWithKnownDuration.isNotEmpty()) {
            val fastestDuration = sizesWithKnownDuration.minOf { it.second }
            return sizesWithKnownDuration
                .filter { it.second == fastestDuration }
                .maxBy { it.first.width.toLong() * it.first.height }
                .first
        }
        return sizesWithin720p.maxBy { it.width.toLong() * it.height }
    }

    private fun adaptJpegQuality(frameBytes: Int) {
        qualityAdjustmentFrames++
        if (qualityAdjustmentFrames < QUALITY_ADJUSTMENT_INTERVAL) return
        qualityAdjustmentFrames = 0
        val desiredBytes = targetBitrateKbps * 1000.0 / 8.0 / targetFps
        if (frameBytes > 0) {
            val adjusted = jpegQuality * sqrt(desiredBytes / frameBytes)
            val nextQuality = adjusted.toInt().coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY)
            if (kotlin.math.abs(nextQuality - jpegQuality) >= JPEG_QUALITY_STEP) {
                jpegQuality = nextQuality
                val request = captureRequest
                val session = captureSession
                if (request != null && session != null) {
                    try {
                        request.set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                        session.setRepeatingRequest(request.build(), null, cameraHandler)
                    } catch (error: Exception) {
                        Log.w(TAG, "No se pudo ajustar la calidad JPEG", error)
                    }
                }
            }
        }
    }

    private fun applySettingsPayload(input: DataInputStream, size: Int) {
        try {
            val fps = input.readInt()
            val bitrate = input.readInt()
            targetFps = fps.coerceIn(15, 60)
            targetBitrateKbps = bitrate.coerceIn(500, 10000)

            if (size >= 12) {
                val exposure = input.readInt()
                currentExposureComp = exposure
            }

            // Descartar bytes adicionales si el cliente envió un formato extendido
            val remaining = size - (if (size >= 12) 12 else 8)
            if (remaining > 0) {
                val skip = ByteArray(remaining)
                input.readFully(skip)
            }

            Log.i(TAG, "Ajustes recibidos desde PC: $targetFps FPS, $targetBitrateKbps kbps, Exp $currentExposureComp")
            cameraHandler?.post { applyCameraSettings() }
        } catch (e: Exception) {
            Log.w(TAG, "Error al procesar ajustes del cliente: ${e.message}")
        }
    }

    private fun connectToClient() {
        while (running) {
            try {
                if (!clientConnected) {
                    broadcastStatus(STATUS_WAITING_PC, "Esperando cliente PC en puerto $PORT...")
                }
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress("127.0.0.1", PORT), 2500)
                activeSocket.set(socket)
                socket.sendBufferSize = SOCKET_SEND_BUFFER_SIZE

                socket.use {
                    val stream = DataOutputStream(it.getOutputStream())
                    val inputStream = DataInputStream(it.getInputStream())
                    output.set(stream)
                    synchronized(outgoingLock) {
                        outgoingPackets.clear()
                    }

                    // IMPORTANTE: Para evitar que ADB loopback genere parpadeos de conexión falsa,
                    // esperamos recibir el paquete de confirmación/ajustes del cliente antes de
                    // marcar el estado como conectado y transmitiendo.
                    val handshakeType = inputStream.readUnsignedByte()
                    val handshakeSize = inputStream.readInt()
                    if (handshakeType == TYPE_SETTINGS) {
                        applySettingsPayload(inputStream, handshakeSize)
                    } else {
                        val buf = ByteArray(handshakeSize)
                        inputStream.readFully(buf)
                    }

                    // Ahora sí está confirmado el cliente PC
                    clientConnected = true
                    updateForegroundNotification()
                    broadcastStatus(STATUS_STREAMING, "Conectado al cliente PC por USB", force = true)

                    val writer = thread(name = "miccam-sender") {
                        writeQueuedPackets(socket, stream)
                    }

                    Log.i(TAG, "Cliente Windows conectado y verificado")
                    try {
                        // Bucle de lectura de comandos adicionales en vivo
                        while (running && clientConnected && activeSocket.get() === socket) {
                            val type = inputStream.readUnsignedByte()
                            val size = inputStream.readInt()
                            if (type == TYPE_SETTINGS) {
                                applySettingsPayload(inputStream, size)
                            } else {
                                val buf = ByteArray(size)
                                inputStream.readFully(buf)
                            }
                        }
                    } finally {
                        writer.interrupt()
                        synchronized(outgoingLock) {
                            outgoingPackets.clear()
                            outgoingLock.notifyAll()
                        }
                    }
                }
            } catch (error: Exception) {
                val wasConnected = clientConnected
                clientConnected = false
                if (running) {
                    if (wasConnected) {
                        broadcastStatus(STATUS_WAITING_PC, "Conexión perdida. Esperando PC...", force = true)
                    }
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } finally {
                clientConnected = false
                output.set(null)
                activeSocket.set(null)
                updateForegroundNotification()
            }
        }
    }

    private fun updateForegroundNotification() {
        if (!running || !cameraActive) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun stopCamera() {
        cameraActive = false
        isTorchOn = false
        val handler = cameraHandler
        if (handler == null) {
            releaseCamera()
        } else {
            handler.post { releaseCamera() }
        }
    }

    private fun releaseCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        captureRequest = null
        imageReader?.close()
        imageReader = null
        cameraHandler = null
        cameraThread?.quitSafely()
        cameraThread = null
    }

    private fun enqueuePacket(type: Int, payload: ByteArray) {
        if (output.get() == null) return
        synchronized(outgoingLock) {
            if (type == TYPE_VIDEO) {
                val iterator = outgoingPackets.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().type == TYPE_VIDEO) iterator.remove()
                }
            }
            if (outgoingPackets.size >= MAX_QUEUED_PACKETS) {
                val iterator = outgoingPackets.iterator()
                var removedVideo = false
                while (iterator.hasNext()) {
                    if (iterator.next().type == TYPE_VIDEO) {
                        iterator.remove()
                        removedVideo = true
                        break
                    }
                }
                if (!removedVideo) outgoingPackets.pollFirst()
            }
            outgoingPackets.addLast(StreamPacket(type, payload))
            outgoingLock.notifyAll()
        }
    }

    private fun writeQueuedPackets(socket: Socket, stream: DataOutputStream) {
        try {
            while (running && activeSocket.get() === socket) {
                val packet = synchronized(outgoingLock) {
                    while (outgoingPackets.isEmpty() && running && activeSocket.get() === socket) {
                        outgoingLock.wait()
                    }
                    outgoingPackets.pollFirst()
                } ?: continue
                stream.writeByte(packet.type)
                stream.writeInt(packet.payload.size)
                stream.write(packet.payload)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: IOException) {
            if (running && activeSocket.get() === socket) {
                Log.w(TAG, "Se perdió la conexión con el cliente", error)
                try {
                    socket.close()
                } catch (closeError: IOException) {
                    Log.w(TAG, "No se pudo cerrar el socket interrumpido", closeError)
                }
            }
        }
    }

    override fun onDestroy() {
        running = false
        cameraActive = false
        isTorchOn = false
        clientConnected = false
        output.set(null)
        synchronized(outgoingLock) {
            outgoingPackets.clear()
            outgoingLock.notifyAll()
        }
        try {
            activeSocket.getAndSet(null)?.close()
        } catch (error: IOException) {
            Log.w(TAG, "No se pudo cerrar el socket de streaming", error)
        }
        releaseCamera()
        networkThread?.interrupt()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        broadcastStatus(STATUS_STOPPED, "Servicio finalizado", force = true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Captura BNJ WebCam",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val content = if (clientConnected) {
            "Transmitiendo video a PC (USB)"
        } else {
            "Cámara activa (esperando conexión con PC)"
        }

        val stopIntent = Intent(this, CameraStreamService::class.java).apply {
            action = ACTION_STOP_CAMERA
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingStop = PendingIntent.getService(this, 100, stopIntent, flags)

        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingOpen = if (openIntent != null) {
            PendingIntent.getActivity(this, 101, openIntent, flags)
        } else null

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle("BNJ WebCam")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)

        if (pendingOpen != null) {
            builder.setContentIntent(pendingOpen)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            val stopAction = Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Detener",
                pendingStop
            ).build()
            builder.addAction(stopAction)
        }

        return builder.build()
    }

    companion object {
        const val ACTION_STOP_CAMERA = "com.bnj.miccam.STOP_CAMERA"
        const val ACTION_START_CAMERA = "com.bnj.miccam.START_CAMERA"
        const val ACTION_TOGGLE_TORCH = "com.bnj.miccam.TOGGLE_TORCH"
        const val ACTION_STATUS_UPDATE = "com.bnj.miccam.STATUS_UPDATE"

        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DETAILS = "details"
        const val EXTRA_FRAMES_SENT = "frames_sent"
        const val EXTRA_TORCH_ON = "torch_on"
        const val EXTRA_FPS = "target_fps"
        const val EXTRA_BITRATE = "target_bitrate"

        const val STATUS_STOPPED = "STOPPED"
        const val STATUS_WAITING_PC = "WAITING_PC"
        const val STATUS_STREAMING = "STREAMING"

        private const val TAG = "CameraStreamService"
        private const val CHANNEL_ID = "bnj_miccam_capture"
        private const val NOTIFICATION_ID = 1
        private const val PORT = 12345
        private const val RECONNECT_DELAY_MS = 1000L
        private const val TYPE_VIDEO = 1
        private const val TYPE_SETTINGS = 3
        private const val MAX_QUEUED_PACKETS = 8
        private const val SOCKET_SEND_BUFFER_SIZE = 256 * 1024
        private const val MAX_VIDEO_DIMENSION = 1280
        private const val MAX_VIDEO_SHORT_DIMENSION = 720
        private const val DEFAULT_TARGET_FPS = 30
        private const val DEFAULT_TARGET_BITRATE_KBPS = 2000
        private const val INITIAL_JPEG_QUALITY = 80
        private const val MIN_JPEG_QUALITY = 20
        private const val MAX_JPEG_QUALITY = 95
        private const val QUALITY_ADJUSTMENT_INTERVAL = 8
        private const val JPEG_QUALITY_STEP = 2
    }
}
