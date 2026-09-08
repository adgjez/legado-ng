package io.legado.app.ui.association

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileAssociationViewModelTest {

    @Test
    fun recognizesJavaScriptByFileNameOrMimeType() {
        assertTrue(isJavaScriptFileAssociation("source.js", "text/plain"))
        assertTrue(isJavaScriptFileAssociation("SOURCE.JS", null))
        assertTrue(isJavaScriptFileAssociation("shared-file", "application/javascript"))
        assertTrue(isJavaScriptFileAssociation("shared-file", "text/x-javascript; charset=utf-8"))
    }

    @Test
    fun doesNotTreatOrdinaryTextOrJsonAsJavaScript() {
        assertFalse(isJavaScriptFileAssociation("novel.txt", "text/plain"))
        assertFalse(isJavaScriptFileAssociation("source.json", "application/json"))
    }
}
