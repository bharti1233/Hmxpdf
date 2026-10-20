package com.hmx.pdf.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveLocationManagerTest {

    @Test
    fun `new exports use the HMXPDF folder below Download`() {
        assertEquals("HMXPDF", SaveLocationManager.DEFAULT_EXPORT_FOLDER)
        assertEquals(
            "Download/HMXPDF",
            SaveLocationManager.defaultDownloadRelativePath("Download")
        )
        assertEquals("Download/HMXPDF", SaveLocationManager.DEFAULT_DOWNLOAD_LABEL)
    }

    @Test
    fun `a custom save destination keeps its own label`() {
        assertEquals(
            "content://com.example/tree/MyDocs",
            SaveLocationManager.displaySavePath("content://com.example/tree/MyDocs")
        )
    }

    @Test
    fun `clearing the custom destination falls back to Download HMXPDF`() {
        assertEquals(
            "Download/HMXPDF",
            SaveLocationManager.displaySavePath(null)
        )
    }
}
