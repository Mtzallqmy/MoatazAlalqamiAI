package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.Attachment
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalEncodingApi::class)
class FileAnalyzerTest {

    @Test
    fun `extractText decodes plain text attachments`() {
        val text = "Hello, world!"
        val att = Attachment(Base64.encode(text.encodeToByteArray()), "text/plain", "hello.txt")
        assertEquals(text, extractText(att))
    }

    @Test
    fun `extractText decodes JSON attachments`() {
        val json = """{"key": 1}"""
        val att = Attachment(Base64.encode(json.encodeToByteArray()), "application/json", "data.json")
        assertEquals(json, extractText(att))
    }

    @Test
    fun `extractText decodes by extension without a mime type`() {
        val csv = "a,b,c\n1,2,3"
        val att = Attachment(Base64.encode(csv.encodeToByteArray()), "", "report.csv")
        assertEquals(csv, extractText(att))
    }

    @Test
    fun `extractText returns null for binary formats`() {
        val pdf = Attachment(Base64.encode(byteArrayOf(0x25, 0x50, 0x44, 0x46)), "application/pdf", "doc.pdf")
        assertNull(extractText(pdf))
        val xlsx = Attachment(Base64.encode(byteArrayOf(0x50, 0x4B)), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "data.xlsx")
        assertNull(extractText(xlsx))
    }

    @Test
    fun `attachmentSummary reports name, size and type`() {
        val payload = "hello".encodeToByteArray()
        val att = Attachment(Base64.encode(payload), "application/pdf", "report.pdf")
        val summary = attachmentSummary(att)
        assertContains(summary, "Attached file: report.pdf")
        assertContains(summary, "5 B")
        assertContains(summary, "application/pdf")
    }

    @Test
    fun `attachmentSummary falls back for missing file name`() {
        val att = Attachment(Base64.encode("x".encodeToByteArray()), "image/png", null)
        val summary = attachmentSummary(att)
        assertContains(summary, "Attached file: attachment")
        assertContains(summary, "1 B")
    }

    @Test
    fun `formatBytes renders human readable sizes`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1500 * 1024))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `file classification covers documents and archives`() {
        assertEquals(com.inspiredandroid.kai.data.FileCategory.DOCUMENT, com.inspiredandroid.kai.data.classifyFile("application/pdf", "x.pdf"))
        assertEquals(com.inspiredandroid.kai.data.FileCategory.DOCUMENT, com.inspiredandroid.kai.data.classifyFile("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "x.docx"))
        assertEquals(com.inspiredandroid.kai.data.FileCategory.DOCUMENT, com.inspiredandroid.kai.data.classifyFile(null, "x.pptx"))
        assertEquals(com.inspiredandroid.kai.data.FileCategory.ARCHIVE, com.inspiredandroid.kai.data.classifyFile("application/zip", "x.zip"))
        assertEquals(com.inspiredandroid.kai.data.FileCategory.ARCHIVE, com.inspiredandroid.kai.data.classifyFile(null, "x.tar.gz"))
        assertNotNull(com.inspiredandroid.kai.data.supportedFileExtensions.find { it == "xlsx" })
        assertNotNull(com.inspiredandroid.kai.data.supportedFileExtensions.find { it == "zip" })
    }
}
