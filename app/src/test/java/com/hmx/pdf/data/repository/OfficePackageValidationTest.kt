package com.hmx.pdf.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficePackageValidationTest {

    @Test
    fun `docx with content types and document part is valid`() {
        val file = packageFile("docx", "word/document.xml")
        assertTrue(SaveRecovery.validateOfficePackage(file, "docx"))
    }

    @Test
    fun `xlsx with content types and workbook part is valid`() {
        val file = packageFile("xlsx", "xl/workbook.xml")
        assertTrue(SaveRecovery.validateOfficePackage(file, "xlsx"))
    }

    @Test
    fun `package without its main document part is rejected`() {
        val file = packageFile("docx", "word/styles.xml")
        assertFalse(SaveRecovery.validateOfficePackage(file, "docx"))
    }

    @Test
    fun `empty package is rejected`() {
        val file = File(tempDir("clearpdf-office-validation"), "empty.docx").apply { writeText("") }
        assertFalse(SaveRecovery.validateOfficePackage(file, "docx"))
    }

    private fun packageFile(extension: String, mainEntry: String): File {
        val file = File(tempDir("clearpdf-office-package"), "document.$extension")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(mainEntry))
            zip.write("<document/>".toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }
}
