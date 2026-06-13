package com.app.colorwallar

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class WallColorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var activeColor: Int = Color.parseColor("#5B9BD5")
    var opacity: Int = 140
    var isActive: Boolean = true

    // Bitmap donde dibujamos la máscara del FloodFill
    private var maskBitmap: Bitmap? = null
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Paint para manchas manuales (toque simple sin FloodFill)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Umbral de similitud de color (0-255), más alto = más área rellena
    var colorTolerance: Int = 40

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Crear bitmap transparente del tamaño de la vista
        maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        maskBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    /**
     * Ejecuta FloodFill en el bitmap dado, en el punto (x, y) de la vista.
     * @param cameraBitmap Frame capturado de la cámara (puede ser diferente resolución)
     * @param touchX Coordenada X del toque en la vista
     * @param touchY Coordenada Y del toque en la vista
     */
    fun floodFillFromCamera(cameraBitmap: Bitmap, touchX: Float, touchY: Float) {
        val viewW = width
        val viewH = height

        // Escalar el bitmap de la cámara al tamaño de la vista
        val scaledBitmap = Bitmap.createScaledBitmap(cameraBitmap, viewW, viewH, true)

        // Coordenadas del toque en el bitmap escalado
        val pixelX = touchX.toInt().coerceIn(0, viewW - 1)
        val pixelY = touchY.toInt().coerceIn(0, viewH - 1)

        // Color semilla (el color de la pared donde tocó)
        val seedColor = scaledBitmap.getPixel(pixelX, pixelY)

        // Crear nueva máscara
        val newMask = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)

        // Ejecutar FloodFill en background thread
        Thread {
            floodFill(scaledBitmap, newMask, pixelX, pixelY, seedColor)
            scaledBitmap.recycle()

            // Actualizar UI en main thread
            post {
                maskBitmap?.recycle()
                maskBitmap = newMask
                invalidate()
            }
        }.start()
    }

    /**
     * Algoritmo FloodFill iterativo con cola (evita StackOverflow).
     * Rellena en `mask` todos los píxeles de `source` similares al `seedColor`.
     */
    private fun floodFill(
        source: Bitmap,
        mask: Bitmap,
        startX: Int,
        startY: Int,
        seedColor: Int
    ) {
        val w = source.width
        val h = source.height

        // Color de overlay con opacidad
        val fillColor = Color.argb(
            opacity,
            Color.red(activeColor),
            Color.green(activeColor),
            Color.blue(activeColor)
        )

        // Leer todos los píxeles de una vez (más eficiente)
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val maskPixels = IntArray(w * h) { Color.TRANSPARENT }

        // Visited array
        val visited = BooleanArray(w * h)

        // Cola para BFS
        val queue = ArrayDeque<Int>()
        val startIndex = startY * w + startX
        queue.add(startIndex)
        visited[startIndex] = true

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val cx = idx % w
            val cy = idx / w

            // Si el color es similar al seed, pintar
            if (isSimilarColor(pixels[idx], seedColor, colorTolerance)) {
                maskPixels[idx] = fillColor

                // Agregar vecinos (4-direccional)
                if (cx > 0) {
                    val left = idx - 1
                    if (!visited[left]) { visited[left] = true; queue.add(left) }
                }
                if (cx < w - 1) {
                    val right = idx + 1
                    if (!visited[right]) { visited[right] = true; queue.add(right) }
                }
                if (cy > 0) {
                    val up = idx - w
                    if (!visited[up]) { visited[up] = true; queue.add(up) }
                }
                if (cy < h - 1) {
                    val down = idx + w
                    if (!visited[down]) { visited[down] = true; queue.add(down) }
                }
            }
        }

        mask.setPixels(maskPixels, 0, w, 0, 0, w, h)
    }

    /**
     * Compara dos colores en espacio RGB con tolerancia.
     */
    private fun isSimilarColor(c1: Int, c2: Int, tolerance: Int): Boolean {
        val dr = Math.abs(Color.red(c1) - Color.red(c2))
        val dg = Math.abs(Color.green(c1) - Color.green(c2))
        val db = Math.abs(Color.blue(c1) - Color.blue(c2))
        return dr <= tolerance && dg <= tolerance && db <= tolerance
    }

    fun clearAll() {
        maskBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    fun updateOpacity(newAlpha: Int) {
        opacity = newAlpha
        invalidate()
    }
}