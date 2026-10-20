package com.hmx.pdf.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveLocationManagerTest {

    @Test
    fun newExportsUseTheHMXPDFFolderBelowDownload() {
        assertEquals("HMXPDF", SaveLocationManager.DEFAULT_EXPORT_FOLDER)
        assertEquals(
            "Download/HMXPDF",
            SaveLocationManager.defaultDownloadRelativePath("Download")
        )
        assertEquals("Download/HMXPDF", SaveLocationManager.DEFAULT_DOWNLOAD_LABEL)
    }

    @Test
    fun aCustomSaveDestinationKeepsItsOwnLabel() {
        assertEquals(
            "content://com.example/tree/MyDocs",
            SaveLocationManager.displaySavePath("content://com.example/tree/MyDocs")
        )
    }

    @Test
    fun clearingTheCustomDestinationFallsBackToDownloadHMXPDF() {
        assertEquals(
            "Download/HMXPDF",
            SaveLocationManager.displaySavePath(null)
        )
    }
}
