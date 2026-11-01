package dev.admcp.mcp

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HostToolsTest {
    @Test
    fun parsesJsonAfterToolBannerLines() {
        val parsed = parseJsonLoose("Build log: C:/tmp/build.log\nResolved project\n{\"success\":true,\"previews\":[]}\n")
        assertNotNull(parsed)
        assertTrue(parsed.toString().contains("\"success\":true"))
    }

    @Test
    fun summarizesApkLikeZipContents() {
        val zip = Files.createTempFile("admcp-host-tools-", ".apk")
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            listOf("AndroidManifest.xml", "resources.arsc", "classes.dex", "lib/arm64-v8a/libfixture.so").forEach { name ->
                output.putNextEntry(ZipEntry(name))
                output.write(byteArrayOf(1))
                output.closeEntry()
            }
        }
        val summary = summarizeZip(zip)
        assertNotNull(summary)
        assertEquals(1, summary.dexFiles)
        assertEquals(1, summary.nativeLibraries.size)
        assertTrue(summary.hasManifest)
        assertTrue(summary.hasResources)
    }
}
