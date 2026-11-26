package io.github.durkimat.phoneguard.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryDataTest {
    @Test fun theDateTakenWinsOverTheDateAdded() { assertEquals(1_600_000_000_000L, GalleryData.time(1_600_000_000_000L, 1_700_000_000L)) }

    @Test fun withNoDateTakenTheDateAddedIsUsedInTheSameUnit() { assertEquals(1_700_000_000_000L, GalleryData.time(0, 1_700_000_000L)) }

    @Test fun aCopiedOldPhotoSortsByWhenItWasTaken() {
        val old = GItem("a", false, "old", GalleryData.time(1_500_000_000_000L, 1_700_000_100L), "Camera") // taken long ago, copied on just now
        val new = GItem("b", false, "new", GalleryData.time(0, 1_700_000_000L), "Camera")
        assertEquals(listOf("new", "old"), GalleryModel.albums(listOf(old, new))[0].items.map { it.name })
    }
}
