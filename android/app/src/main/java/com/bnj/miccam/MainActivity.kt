package com.bnj.miccam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusBadge: TextView
    private lateinit var statusDetails: TextView
    private lateinit var cameraSpinner: Spinner
    private lateinit var btnTorch: Button
    private lateinit var dimOverlay: FrameLayout
    private var isTorchOn = false
    private var isDimmed = false
    private var cameraIds: List<String> = emptyList()
    private var pendingAction: String? = null
    private var cameraCaptureRequested = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CameraStreamService.ACTION_STATUS_UPDATE) {
                val status = intent.getStringExtra(CameraStreamService.EXTRA_STATUS) ?: return
                val details = intent.getStringExtra(CameraStreamService.EXTRA_DETAILS) ?: ""
                val frames = intent.getLongExtra(CameraStreamService.EXTRA_FRAMES_SENT, 0L)
                val fps = intent.getIntExtra(CameraStreamService.EXTRA_FPS, 30)
                val bitrate = intent.getIntExtra(CameraStreamService.EXTRA_BITRATE, 2000)
                isTorchOn = intent.getBooleanExtra(CameraStreamService.EXTRA_TORCH_ON, false)
                updateUiStatus(status, details, frames, fps, bitrate)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(15, 23, 42)) // Slate 900
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        // Header branding
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.icon)
            contentDescription = "BNJ WebCam"
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply {
                marginEnd = dp(12)
            }
        })
        brand.addView(TextView(this).apply {
            text = "BNJ WebCam"
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        })
        content.addView(brand)

        content.addView(TextView(this).apply {
            text = "Cámara web USB profesional de baja latencia."
            textSize = 14f
            setTextColor(Color.rgb(148, 163, 184)) // Slate 400
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        })

        // Status Card
        val statusCard = createCardLayout()
        statusBadge = TextView(this).apply {
            text = "🔴 Cámara Detenida"
            textSize = 16f
            setTextColor(Color.rgb(248, 113, 113))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        statusCard.addView(statusBadge)

        statusDetails = TextView(this).apply {
            text = "Conecta el cable USB al PC y presiona Iniciar Cámara."
            textSize = 13f
            setTextColor(Color.rgb(203, 213, 225))
            gravity = Gravity.CENTER
        }
        statusCard.addView(statusDetails)
        content.addView(statusCard)

        // Camera Control Card
        val controlCard = createCardLayout()
        controlCard.addView(TextView(this).apply {
            text = "Seleccionar Cámara:"
            textSize = 13f
            setTextColor(Color.rgb(148, 163, 184))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })

        cameraSpinner = Spinner(this).apply {
            setBackgroundColor(Color.rgb(30, 41, 59))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        controlCard.addView(cameraSpinner)
        loadCameras()

        // Main Actions (Start / Stop)
        val mainActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(6))
        }
        val btnStart = createStyledButton("▶ Iniciar Cámara", Color.rgb(37, 99, 235)) {
            checkAndRequestPermissions(ACTION_START_CAMERA)
        }
        val btnStop = createStyledButton("⏹ Detener Cámara", Color.rgb(220, 38, 38)) {
            cameraCaptureRequested = false
            sendServiceAction(CameraStreamService.ACTION_STOP_CAMERA)
            updateUiStatus(CameraStreamService.STATUS_STOPPED, "Cámara detenida por el usuario", 0L)
        }
        mainActionRow.addView(btnStart)
        mainActionRow.addView(btnStop)
        controlCard.addView(mainActionRow)

        // Secondary Actions (Torch & Screen Saver Dimmer)
        val secondaryActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        btnTorch = createStyledButton("🔦 Linterna", Color.rgb(51, 65, 85)) {
            sendServiceAction(CameraStreamService.ACTION_TOGGLE_TORCH)
        }
        val btnDim = createStyledButton("🌙 Modo Ahorro", Color.rgb(51, 65, 85)) {
            toggleScreenDim(true)
        }
        secondaryActionRow.addView(btnTorch)
        secondaryActionRow.addView(btnDim)
        controlCard.addView(secondaryActionRow)

        content.addView(controlCard)

        // Social Links Card
        val linksRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        linksRow.addView(createLinkButton("YouTube", R.drawable.yt, "https://www.youtube.com/@BenjaminDuarteIUTUB"))
        linksRow.addView(createLinkButton("Web Oficial", R.drawable.web, "https://benjaminduarte.lat/"))
        linksRow.addView(createLinkButton("WhatsApp", R.drawable.wsp, "https://whatsapp.com/channel/0029VbDqWzFCHDycOjbgfu41"))
        content.addView(linksRow)

        scrollView.addView(content)
        root.addView(scrollView)

        // Screen Dimmer Overlay (Ahorro de energía y disipación de calor)
        dimOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            val hint = TextView(this@MainActivity).apply {
                text = "Modo Ahorro Activo (Cámara transmitiendo)\nToca dos veces para volver"
                textSize = 14f
                setTextColor(Color.DKGRAY)
                gravity = Gravity.CENTER
            }
            addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
            setOnClickListener {
                toggleScreenDim(false)
            }
        }
        root.addView(dimOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(CameraStreamService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
    }

    private fun toggleScreenDim(dim: Boolean) {
        isDimmed = dim
        dimOverlay.visibility = if (dim) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness = if (dim) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun updateUiStatus(status: String, details: String, frames: Long, fps: Int = 30, bitrate: Int = 2000) {
        when (status) {
            CameraStreamService.STATUS_STREAMING -> {
                statusBadge.text = "🟢 Transmitiendo a PC"
                statusBadge.setTextColor(Color.rgb(74, 222, 128))
                statusDetails.text = "720p @ $fps FPS · ${bitrate} kbps · Cuadros: $frames\n$details"
            }
            CameraStreamService.STATUS_WAITING_PC -> {
                statusBadge.text = "🟡 Esperando PC..."
                statusBadge.setTextColor(Color.rgb(251, 191, 36))
                statusDetails.text = "Cámara lista. Conecta el USB y pulsa 'Conectar teléfono' en PC.\n$details"
            }
            else -> {
                statusBadge.text = "🔴 Cámara Detenida"
                statusBadge.setTextColor(Color.rgb(248, 113, 113))
                statusDetails.text = details.ifEmpty { "Presiona 'Iniciar Cámara' para comenzar." }
            }
        }
        btnTorch.text = if (isTorchOn) "🔦 Linterna [ON]" else "🔦 Linterna"
    }

    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val bg = GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 59)) // Slate 800
                cornerRadius = dp(14).toFloat()
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(14))
            }
        }
    }

    private fun createStyledButton(label: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(10).toFloat()
            }
            background = bg
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(46),
                1f
            ).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        }
    }

    private fun createLinkButton(label: String, iconRes: Int, url: String): Button {
        val icon: Drawable = resources.getDrawable(iconRes, theme).apply {
            val size = dp(20)
            setBounds(0, 0, size, size)
        }
        return Button(this).apply {
            text = label
            textSize = 10f
            setTextColor(Color.rgb(203, 213, 225))
            val bg = GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 59))
                setStroke(dp(1), Color.rgb(71, 85, 105))
                cornerRadius = dp(8).toFloat()
            }
            background = bg
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = dp(6)
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(38)
            ).apply { setMargins(dp(3), 0, dp(3), 0) }
        }
    }

    private fun loadCameras() {
        val manager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        cameraIds = manager.cameraIdList.toList()
        val labels = cameraIds.map { id ->
            val facing = manager.getCameraCharacteristics(id).get(
                android.hardware.camera2.CameraCharacteristics.LENS_FACING
            )
            val description = when (facing) {
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "Frontal"
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "Trasera"
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL -> "Externa"
                else -> "Cámara"
            }
            "$description (ID $id)"
        }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        cameraSpinner.adapter = adapter
        val savedId = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(KEY_CAMERA_ID, null)
        val savedIndex = cameraIds.indexOf(savedId)
        if (savedIndex >= 0) cameraSpinner.setSelection(savedIndex)
        cameraSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(KEY_CAMERA_ID, cameraIds.getOrNull(position))
                    .apply()
                if (cameraCaptureRequested) startCapture(ACTION_START_CAMERA)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun checkAndRequestPermissions(action: String) {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startCapture(action)
        } else {
            pendingAction = action
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST)
        }
    }

    private fun startCapture(action: String) {
        if (action == ACTION_START_CAMERA) cameraCaptureRequested = true
        val intent = Intent(this, CameraStreamService::class.java).apply {
            this.action = action
            if (action == ACTION_START_CAMERA) {
                val selectedId = cameraIds.getOrNull(cameraSpinner.selectedItemPosition)
                putExtra(CameraStreamService.EXTRA_CAMERA_ID, selectedId)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUiStatus(CameraStreamService.STATUS_WAITING_PC, "Servicio iniciado.", 0L)
    }

    private fun sendServiceAction(action: String) {
        startService(Intent(this, CameraStreamService::class.java).setAction(action))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST) return
        val action = pendingAction
        pendingAction = null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (action != null) startCapture(action)
        } else {
            statusBadge.text = "🔴 Permiso Requerido"
            statusDetails.text = "Se requiere el permiso de cámara para transmitir."
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PERMISSIONS_REQUEST = 1001
        private const val PREFERENCES = "bnj_webcam_settings"
        private const val KEY_CAMERA_ID = "camera_id"
        private const val ACTION_START_CAMERA = "com.bnj.miccam.START_CAMERA"
    }
}
