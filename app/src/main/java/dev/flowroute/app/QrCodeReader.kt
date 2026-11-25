package dev.flowroute.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

internal enum class QrScanTarget { CONFIG, SUBSCRIPTION, QWDDT }

internal object QrCodeReader {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    fun normalizePayload(text: String, target: QrScanTarget = QrScanTarget.CONFIG): String? {
        val value = text.trim()
        return when (target) {
            QrScanTarget.CONFIG -> value.takeIf { it.startsWith("vless://", ignoreCase = true) && it.length > 8 }
            QrScanTarget.QWDDT -> value.takeIf {
                it.startsWith("qwdtt://", ignoreCase = true) || it.startsWith("qwdtt:config", ignoreCase = true) ||
                    it.startsWith("wdtt://", ignoreCase = true)
            }
            QrScanTarget.SUBSCRIPTION -> value.takeIf {
                (it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)) &&
                    runCatching { !java.net.URI(it).host.isNullOrBlank() }.getOrDefault(false)
            }
        }
    }

    fun decodePixels(pixels: IntArray, width: Int, height: Int): String? = runCatching {
        MultiFormatReader().apply { setHints(hints) }.decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        ).text
    }.getOrNull()

    fun decodeLuminance(source: ByteArray, width: Int, height: Int, rotation: Int): String? {
        val rotated = when ((rotation % 360 + 360) % 360) {
            90 -> rotate90(source, width, height)
            180 -> Rotated(source.reversedArray(), width, height)
            270 -> rotate90(rotate90(rotate90(source, width, height)))
            else -> Rotated(source, width, height)
        }
        val reader = MultiFormatReader().apply { setHints(hints) }
        return try {
            val luminance = PlanarYUVLuminanceSource(
                rotated.data, rotated.width, rotated.height,
                0, 0, rotated.width, rotated.height, false,
            )
            reader.decode(BinaryBitmap(HybridBinarizer(luminance))).text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun rotate90(source: ByteArray, width: Int, height: Int) = rotate90(
        Rotated(source, width, height)
    )

    private fun rotate90(input: Rotated): Rotated {
        val output = ByteArray(input.data.size)
        val outputWidth = input.height
        for (y in 0 until input.height) {
            for (x in 0 until input.width) {
                val outputX = input.height - 1 - y
                val outputY = x
                output[outputY * outputWidth + outputX] = input.data[y * input.width + x]
            }
        }
        return Rotated(output, outputWidth, input.width)
    }

    private data class Rotated(val data: ByteArray, val width: Int, val height: Int)
}
