package io.github.durkimat.phoneguard

import io.github.durkimat.phoneguard.core.AppCmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StoreAppTest {
    private fun cmd(kind: String = "install", pkg: String = StoreApp.PKG, url: String = StoreApp.URL, sha: String = StoreApp.SHA256) = AppCmd("j1", kind, pkg, "Aurora Store", url, sha, StoreApp.SIZE)

    @Test fun thePinnedAuroraInstallIsAccepted() { assertNull(StoreApp.refusal(cmd())); assertNull(StoreApp.refusal(cmd(sha = StoreApp.SHA256.uppercase()))) }

    @Test fun anyOtherPackageIsRefused() { assertEquals("this phone only installs its app store", StoreApp.refusal(cmd(pkg = "com.example.evil"))) }

    @Test fun anyOtherAddressIsRefused() {
        assertNotNull(StoreApp.refusal(cmd(url = "https://example.com/com.aurora.store_76.apk")))
        assertNotNull(StoreApp.refusal(cmd(url = "https://f-droid.org/repo/com.aurora.store_77.apk")))
        assertNotNull(StoreApp.refusal(cmd(url = "http://f-droid.org/repo/com.aurora.store_76.apk")))
    }

    @Test fun anyOtherChecksumIsRefused() { assertNotNull(StoreApp.refusal(cmd(sha = "0".repeat(64)))); assertNotNull(StoreApp.refusal(cmd(sha = ""))) }

    @Test fun onlyTheInstallKindIsThisPath() { assertNotNull(StoreApp.refusal(cmd(kind = "update"))); assertNotNull(StoreApp.refusal(cmd(kind = "remove"))) }

    @Test fun theRunCommandUsesOnlyThePinnedValues() {
        val p = StoreApp.pinned(cmd().copy(size = 1))
        assertEquals(StoreApp.URL, p.url); assertEquals(StoreApp.SHA256, p.sha256); assertEquals(StoreApp.PKG, p.pkg); assertEquals(StoreApp.SIZE, p.size)
    }

    @Test fun theChecksumIsASha256() { assertEquals(64, StoreApp.SHA256.length); assertEquals(true, StoreApp.SHA256.all { it in '0'..'9' || it in 'a'..'f' }) }
}
