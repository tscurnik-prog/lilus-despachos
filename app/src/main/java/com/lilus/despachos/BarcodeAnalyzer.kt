package com.lilus.despachos

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Evita lecturas repetidas cuando la cámara queda apuntando al mismo código:
 * el mismo texto no se vuelve a informar hasta que pasa [sameCodeMs] SIN verlo,
 * y entre dos lecturas cualesquiera hay una pausa mínima de [anyCodeMs].
 * Lógica pura (testeable).
 */
class ScanDebouncer(private val sameCodeMs: Long = 2500, private val anyCodeMs: Long = 700) {
    private var lastCode: String? = null
    private var lastSeenAt = 0L
    private var lastEmitAt = 0L

    @Synchronized
    fun accept(code: String, now: Long): Boolean {
        if (code == lastCode && now - lastSeenAt < sameCodeMs) {
            lastSeenAt = now // sigue frente a la cámara: se extiende la espera
            return false
        }
        if (now - lastEmitAt < anyCodeMs) return false
        lastCode = code
        lastSeenAt = now
        lastEmitAt = now
        return true
    }

    @Synchronized
    fun reset() {
        lastCode = null; lastSeenAt = 0L; lastEmitAt = 0L
    }
}

class BarcodeAnalyzer(
    private val debouncer: ScanDebouncer,
    private val onCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile var paused: Boolean = false

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_PDF417,
            )
            .build()
    )

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (paused || media == null) {
            image.close(); return
        }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                // Si hay varios códigos en cuadro, se toma el más grande (el más cercano / centrado)
                val best = barcodes
                    .filter { !it.rawValue.isNullOrEmpty() }
                    .maxByOrNull { b -> b.boundingBox?.let { it.width() * it.height() } ?: 0 }
                val raw = best?.rawValue
                val code = CodeRules.clean(raw)
                if (code != null && !paused && debouncer.accept(code, System.currentTimeMillis())) {
                    onCode(code)
                }
            }
            .addOnCompleteListener { image.close() }
    }

    fun close() {
        try { scanner.close() } catch (e: Exception) { }
    }
}
