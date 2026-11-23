package com.metrolist.core.api.backup

import com.metrolist.shared.backup.BackupValidationException
import com.metrolist.shared.backup.BackupValidationFailure
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupErrorCodeTest {
    @Test
    fun archiveProblemsAreBadRequest() {
        assertEquals("bad_request", BackupApi.errorCodeFor(BackupValidationException(BackupValidationFailure.UNSUPPORTED_FORMAT)))
        assertEquals("bad_request", BackupApi.errorCodeFor(IllegalStateException("Backup does not contain Metrolist data")))
        assertEquals("bad_request", BackupApi.errorCodeFor(IllegalArgumentException("x")))
        assertEquals("bad_request", BackupApi.errorCodeFor(ZipException("zip END header not found")))
        assertEquals("bad_request", BackupApi.errorCodeFor(FileNotFoundException("x")))
    }

    @Test
    fun otherFailuresAreInternal() {
        assertEquals("internal", BackupApi.errorCodeFor(IOException("disk full")))
        assertEquals("internal", BackupApi.errorCodeFor(RuntimeException("boom")))
    }
}
