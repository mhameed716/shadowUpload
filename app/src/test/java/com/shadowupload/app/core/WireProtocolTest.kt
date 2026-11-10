package com.shadowupload.app.core

import com.shadowupload.app.core.net.HandshakeToken
import com.shadowupload.app.core.net.ProgressAggregator
import com.shadowupload.app.core.net.WireProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات وحدة للبروتوكول: ترميز/فك ترميز الرؤوس، تنقية الأسماء،
 * تجميع التقدم، ورموز المصافحة. تعمل على JVM بلا جهاز.
 */
class WireProtocolTest {

    @Test
    fun `ترميز وفك ترميز رأس الملف يعيدان نفس القيم`() {
        val header = WireProtocol.FileHeader(
            name = "صورة_العائلة.jpg",
            size = 4_194_304L,
            mimeType = "image/jpeg",
            checksumSha256 = "abc123",
            relativePath = "DCIM/2024",
            partIndex = 2,
            partCount = 5,
            offset = 1_048_576L,
            partLength = 1_048_576L
        )
        val decoded = WireProtocol.decodeFileHeader(WireProtocol.encodeFileHeader(header))
        assertEquals(header.name, decoded.name)
        assertEquals(header.size, decoded.size)
        assertEquals(header.mimeType, decoded.mimeType)
        assertEquals(header.checksumSha256, decoded.checksumSha256)
        assertEquals(header.relativePath, decoded.relativePath)
        assertEquals(header.partIndex, decoded.partIndex)
        assertEquals(header.partCount, decoded.partCount)
        assertEquals(header.offset, decoded.offset)
        assertEquals(header.partLength, decoded.partLength)
    }

    @Test
    fun `رأس بلا بيانات اختيارية يُفك بنجاح`() {
        val header = WireProtocol.FileHeader("a.txt", 10L, null, null, null)
        val decoded = WireProtocol.decodeFileHeader(WireProtocol.encodeFileHeader(header))
        assertEquals("a.txt", decoded.name)
        assertEquals(null, decoded.mimeType)
        assertEquals(1, decoded.partCount)
    }

    @Test
    fun `تنقية اسم الملف تمنع تجاوز المسار`() {
        // الشرطة المائلة تُستبدل قبل التحقق، فلا يمكن الهروب من مجلد الهدف
        assertFalse(WireProtocol.sanitizeFileName("../../etc/passwd").contains("/"))
        assertFalse(WireProtocol.sanitizeFileName("a/b\\c").contains("/"))
        assertFalse(WireProtocol.sanitizeFileName("a/b\\c").contains("\\"))
        // الأسماء الفارغة تُستبدل باسم آمن غير فارغ
        assertTrue(WireProtocol.sanitizeFileName("   ").isNotBlank())
        assertTrue(WireProtocol.sanitizeFileName("").isNotBlank())
    }

    @Test
    fun `رابط QR يُبنى ويُحلل بشكل صحيح`() {
        val uri = HandshakeToken.buildUri("192.168.1.7", 47821, "123456")
        val token = HandshakeToken.parseUri(uri)
        assertNotNull(token)
        assertEquals("192.168.1.7", token!!.host)
        assertEquals(47821, token.port)
        assertEquals("123456", token.code)
        assertFalse(token.isExpired)
    }

    @Test
    fun `رابط غير صالح يعيد null`() {
        assertEquals(null, HandshakeToken.parseUri("https://example.com"))
        assertEquals(null, HandshakeToken.parseUri("shadowupload://join"))
        assertEquals(null, HandshakeToken.parseUri("shadowupload://join?h=1.2.3.4"))
    }

    @Test
    fun `مقارنة الرموز ثابتة الزمن وصحيحة`() {
        assertTrue(HandshakeToken.constantTimeEquals("123456", "123456"))
        assertFalse(HandshakeToken.constantTimeEquals("123456", "123457"))
        assertFalse(HandshakeToken.constantTimeEquals("123456", "12345"))
    }

    @Test
    fun `مُجمِّع التقدم يجمع المسارات ويحسب النسبة`() {
        val aggregator = ProgressAggregator(totalBytes = 1000L, partCount = 2)
        aggregator.add(300L, 0)
        aggregator.add(200L, 1)
        assertEquals(500L, aggregator.transferred())
        assertEquals(300L, aggregator.partTransferred(0))
        assertEquals(200L, aggregator.partTransferred(1))
        assertEquals(0.5, aggregator.fraction(), 0.0001)
    }

    @Test
    fun `مُجمِّع التقدم لا يتجاوز 100 بالمئة`() {
        val aggregator = ProgressAggregator(totalBytes = 100L)
        aggregator.add(250L)
        assertEquals(1.0, aggregator.fraction(), 0.0001)
    }
}
