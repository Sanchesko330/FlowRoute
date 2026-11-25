package dev.flowroute.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeReaderTest {
    private val link = "vless://00000000-0000-4000-8000-000000000001@example.com:443?encryption=none&security=tls&type=tcp#Server"

    @Test
    fun decodesVlessQrInAllCameraRotations() {
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 320, 320)
        val pixels = IntArray(matrix.width * matrix.height)
        val luminance = ByteArray(pixels.size)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                val index = y * matrix.width + x
                pixels[index] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                luminance[index] = if (matrix[x, y]) 0 else 255.toByte()
            }
        }
        assertEquals(link, QrCodeReader.decodePixels(pixels, matrix.width, matrix.height))
        listOf(0, 90, 180, 270).forEach { rotation ->
            assertEquals("rotation=$rotation", link, QrCodeReader.decodeLuminance(luminance, matrix.width, matrix.height, rotation))
        }
    }

    @Test
    fun acceptsOnlyVlessPayloads() {
        assertEquals(link, QrCodeReader.normalizePayload("  $link\n"))
        assertEquals(link.replace("vless", "VLESS"), QrCodeReader.normalizePayload(link.replace("vless", "VLESS")))
        assertNull(QrCodeReader.normalizePayload("https://example.com/subscription"))
        assertNull(QrCodeReader.normalizePayload("vless://"))
    }

    @Test
    fun acceptsQwdttPayloadsOnlyInQwdttMode() {
        val quick = "qwdtt://config?peer=203.0.113.10&hashes=hash&pass=password"
        val legacy = "wdtt://203.0.113.10:56000:56001:9000:password:hash"
        assertEquals(quick, QrCodeReader.normalizePayload(quick, QrScanTarget.QWDDT))
        assertEquals(legacy, QrCodeReader.normalizePayload(legacy, QrScanTarget.QWDDT))
        assertNull(QrCodeReader.normalizePayload(quick))
        assertNull(QrCodeReader.normalizePayload(link, QrScanTarget.QWDDT))
    }

    @Test
    fun decodesAndValidatesSubscriptionQr() {
        val subscription = "https://example.com/subscription?token=abc123"
        val matrix = QRCodeWriter().encode(subscription, BarcodeFormat.QR_CODE, 320, 320)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val decoded = QrCodeReader.decodePixels(pixels, matrix.width, matrix.height)
        assertEquals(subscription, QrCodeReader.normalizePayload(decoded.orEmpty(), QrScanTarget.SUBSCRIPTION))
        assertEquals(subscription, QrCodeReader.normalizePayload("  $subscription\n", QrScanTarget.SUBSCRIPTION))
        assertNull(QrCodeReader.normalizePayload(link, QrScanTarget.SUBSCRIPTION))
        assertNull(QrCodeReader.normalizePayload("https:///missing-host", QrScanTarget.SUBSCRIPTION))
    }
}
