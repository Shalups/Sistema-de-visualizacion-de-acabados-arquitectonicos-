package com.app.colorwallar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.app.colorwallar.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val CAMERA_PERMISSION_CODE = 101

    // Último frame capturado de la cámara
    private var latestBitmap: Bitmap? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    data class WallColor(val hex: String, val name: String)
    private val colors = listOf(
        WallColor("#5B9BD5", "Azul Cielo"),
        WallColor("#7FB77E", "Verde Salvia"),
        WallColor("#C8704A", "Terracota"),
        WallColor("#F7C948", "Amarillo"),
        WallColor("#E87DA3", "Rosa"),
        WallColor("#A26DC2", "Morado"),
        WallColor("#4EBFB0", "Turquesa"),
        WallColor("#FFFFFF", "Blanco"),
        WallColor("#555555", "Gris")
    )
    private var selectedColorIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }

        setupColorButtons()
        setupOpacitySlider()
        setupToleranceSlider()
        setupTouchDetection()
    }

    private fun setupTouchDetection() {
        // Al tocar el overlay, ejecutar FloodFill con el último frame
        binding.colorOverlay.setOnTouchListener { _, event ->
            if (!binding.colorOverlay.isActive) return@setOnTouchListener false

            val bitmap = latestBitmap ?: run {
                Toast.makeText(this, "Cámara no lista, intenta de nuevo", Toast.LENGTH_SHORT).show()
                return@setOnTouchListener false
            }

            binding.tvInstruction.text = "Analizando pared..."
            binding.colorOverlay.floodFillFromCamera(bitmap, event.x, event.y)

            // Restaurar texto después
            binding.colorOverlay.postDelayed({
                binding.tvInstruction.text = "Toca la pared para pintar"
            }, 1500)

            true
        }
    }

    private fun setupColorButtons() {
        val colorBtns = listOf(
            binding.colorBtn1, binding.colorBtn2, binding.colorBtn3,
            binding.colorBtn4, binding.colorBtn5, binding.colorBtn6,
            binding.colorBtn7, binding.colorBtn8, binding.colorBtn9
        )

        colorBtns.forEachIndexed { index, btn ->
            btn.setOnClickListener {
                val wc = colors[index]
                binding.colorOverlay.activeColor = Color.parseColor(wc.hex)
                binding.colorOverlay.isActive = true
                binding.tvActiveColor.text = "● ${wc.name}"
                updateButtonSelection(colorBtns, index)
            }
        }

        binding.colorBtnClear.setOnClickListener {
            binding.colorOverlay.clearAll()
            binding.colorOverlay.isActive = false
            binding.tvActiveColor.text = "Toca un color"
            updateButtonSelection(colorBtns, -1)
        }

        updateButtonSelection(colorBtns, 0)
        binding.colorOverlay.activeColor = Color.parseColor(colors[0].hex)
    }

    private fun updateButtonSelection(
        buttons: List<android.widget.FrameLayout>,
        selectedIndex: Int
    ) {
        buttons.forEachIndexed { i, btn ->
            btn.alpha = if (i == selectedIndex) 1.0f else 0.5f
            btn.scaleX = if (i == selectedIndex) 1.15f else 1.0f
            btn.scaleY = if (i == selectedIndex) 1.15f else 1.0f
        }
    }

    private fun setupOpacitySlider() {
        binding.seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = (50 + (progress / 100f) * 170).toInt()
                binding.colorOverlay.updateOpacity(alpha)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupToleranceSlider() {
        binding.seekTolerance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Tolerancia de 10 a 80
                binding.colorOverlay.colorTolerance = 10 + (progress * 0.7f).toInt()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.seekTolerance.progress = 43 // Default ~40 de tolerancia
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            // ImageAnalysis para capturar frames
            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            androidx.camera.core.resolutionselector.ResolutionStrategy(
                                android.util.Size(640, 480),
                                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                latestBitmap = imageProxyToBitmap(imageProxy)
                imageProxy.close()
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Error al iniciar cámara: ${e.message}", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val bitmap = Bitmap.createBitmap(
            imageProxy.width,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))

        // Rotar según la orientación del sensor
        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { bitmap.recycle() }
        } else bitmap
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Se necesita permiso de cámara", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        latestBitmap?.recycle()
    }
}