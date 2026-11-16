package dev.raggedlib.calibre

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LibraryWriteCheckTest {
    @TempDir
    lateinit var root: Path

    private fun library(userVersion: Int) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${root.resolve("metadata.db")}").use { c ->
            c.createStatement().use { s ->
                s.execute("create table books (id integer primary key)")
                s.execute("pragma user_version = $userVersion")
            }
        }
    }

    @Test
    fun `a writable library with a schema the installed Calibre knows can be written`() {
        library(28)
        assertNull(LibraryWriteCheck.readOnlyReason(root, supportedSchema = 28))
        assertNull(LibraryWriteCheck.readOnlyReason(root, supportedSchema = 29))
        assertEquals(listOf("metadata.db"), Files.list(root).use { s -> s.map { it.fileName.toString() }.toList() }) // probe removed
    }

    @Test
    fun `a schema newer than the installed Calibre knows keeps it read-only`() {
        library(29)
        val reason = assertNotNull(LibraryWriteCheck.readOnlyReason(root, supportedSchema = 28))
        assertContains(reason, "schema version 29")
    }

    @Test
    fun `a directory raggedlib can't create files in keeps it read-only`() {
        assumeFalse(System.getProperty("user.name") == "root", "root ignores directory permissions")
        library(28)
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assertContains(assertNotNull(LibraryWriteCheck.readOnlyReason(root, supportedSchema = 28)), "can't create files")
        } finally {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `a read-only metadata db keeps it read-only`() {
        assumeFalse(System.getProperty("user.name") == "root", "root ignores file permissions")
        library(28)
        Files.setPosixFilePermissions(root.resolve("metadata.db"), PosixFilePermissions.fromString("r--r--r--"))
        assertContains(assertNotNull(LibraryWriteCheck.readOnlyReason(root, supportedSchema = 28)), "metadata.db isn't writable")
    }

    @Test
    fun `no metadata db, a missing directory, or an unknown Calibre keep it read-only`() {
        assertContains(assertNotNull(LibraryWriteCheck.readOnlyReason(root, supportedSchema = 28)), "no metadata.db")
        assertContains(assertNotNull(LibraryWriteCheck.readOnlyReason(root.resolve("gone"), supportedSchema = 28)), "not found")
        library(28)
        assertContains(assertNotNull(LibraryWriteCheck.readOnlyReason(root, supportedSchema = null)), "calibre-debug")
    }
}
