package io.github.durkimat.phoneguard.gallery

import io.github.durkimat.phoneguard.launcher.Key
import org.junit.Assert.*
import org.junit.Test

class GalleryModelTest {
    private fun item(n: Int, bucket: String = "Camera", video: Boolean = false, time: Long = n.toLong()) = GItem("content://m/$n", video, "IMG_$n", time, bucket, if (video) 12 else 0)
    private fun model(items: List<GItem>) = GalleryModel().also { it.setItems(items) }

    @Test fun theGridOpensOnThePhotosFolderWhenThereIsOne() {
        val m = model(listOf(item(1, "Screenshots"), item(2, "Camera"), item(3, "WhatsApp Images")))
        assertEquals("Camera", m.album.name); assertEquals(listOf("IMG_2"), m.items.map { it.name })
    }

    @Test fun withNoPhotosFolderItOpensOnEverything() {
        val m = model(listOf(item(1, "Screenshots"), item(2, "Download")))
        assertEquals(GalleryModel.ALL, m.album.name); assertEquals(2, m.items.size)
    }

    @Test fun albumsAreAllFirstThenFoldersNewestFirst() {
        val a = GalleryModel.albums(listOf(item(1, "Old", time = 10), item(2, "New", time = 99), item(3, "Camera", time = 50)))
        assertEquals(listOf(GalleryModel.ALL, "New", "Camera", "Old"), a.map { it.name })
        assertEquals(listOf("IMG_2", "IMG_3", "IMG_1"), a[0].items.map { it.name }) // newest first
    }

    @Test fun anEmptyPhoneIsSafe() {
        val m = model(emptyList())
        assertEquals(1, m.albums.size); assertTrue(m.items.isEmpty()); assertTrue(m.visible().isEmpty())
        assertEquals(GAction.None, m.onKey(Key.OK, -1)); assertEquals(GView.GRID, m.view)
        m.onKey(Key.DOWN, -1); m.onKey(Key.STAR, -1) // no crash
    }

    @Test fun theD_padAndDigitsMoveTheHighlightAndTheViewFollows() {
        val m = model((1..30).map { item(it) })
        m.onKey(Key.RIGHT, -1); assertEquals(1, m.sel)
        m.onKey(Key.DIGIT, 8); assertEquals(4, m.sel)
        m.onKey(Key.DIGIT, 4); assertEquals(3, m.sel)
        repeat(3) { m.onKey(Key.DOWN, -1) }
        assertEquals(12, m.sel); assertEquals(2, m.top) // row 4 is below rows 0..2: the view moved down two rows
        m.onKey(Key.UP, -1); assertEquals(9, m.sel); assertEquals(2, m.top) // still in view
    }

    @Test fun poundAndStarPageThroughTheGrid() {
        val m = model((1..40).map { item(it) })
        m.onKey(Key.POUND, -1); assertEquals(9, m.sel); m.onKey(Key.STAR, -1); assertEquals(0, m.sel)
    }

    @Test fun visibleTilesAreTheRowsOnScreen() {
        val m = model((1..40).map { item(it) })
        assertEquals(0..8, m.visible()); assertEquals(0..11, m.visible(margin = 1))
        repeat(5) { m.onKey(Key.DOWN, -1) }
        assertEquals(9..17, m.visible()); assertEquals(6..20, m.visible(margin = 1)) // sel 15 is row 5: the view's top is row 3
    }

    @Test fun okOpensTheViewerAndBackReturnsToTheSameTile() {
        val m = model((1..10).map { item(it) })
        repeat(4) { m.onKey(Key.RIGHT, -1) }
        assertEquals(GAction.ShowItem, m.onKey(Key.OK, -1)); assertEquals(GView.VIEWER, m.view)
        m.onKey(Key.RIGHT, -1); assertEquals(5, m.sel)
        assertEquals(GAction.Redraw, m.onKey(Key.SOFT_RIGHT, -1)); assertEquals(GView.GRID, m.view); assertEquals(5, m.sel)
    }

    @Test fun theViewerStopsAtTheEnds() {
        val m = model((1..3).map { item(it) }); m.onKey(Key.OK, -1)
        m.onKey(Key.LEFT, -1); assertEquals(0, m.sel)
        repeat(5) { m.onKey(Key.RIGHT, -1) }; assertEquals(2, m.sel)
    }

    @Test fun okOnAPhotoTogglesColourAndOnAVideoPlaysIt() {
        val m = model(listOf(item(1, time = 9), item(2, video = true, time = 1))) // newest first: the photo, then the video
        m.onKey(Key.OK, -1); assertFalse(m.colour); assertEquals("Colour", m.leftLabel)
        m.onKey(Key.OK, -1); assertTrue(m.colour); assertEquals("Dots", m.leftLabel)
        m.onKey(Key.RIGHT, -1); assertEquals("Play", m.leftLabel)
        val a = m.onKey(Key.OK, -1); assertTrue(a is GAction.Play && a.item.video)
        assertEquals(GAction.Play(m.current!!), m.onKey(Key.SOFT_LEFT, -1))
    }

    @Test fun foldersListChoosesAFolderAndGridStartsAtTheTop() {
        val m = model(listOf(item(1, "Camera"), item(2, "Screenshots"), item(3, "Screenshots")))
        m.onKey(Key.RIGHT, -1) // (one tile only in Camera: stays 0)
        assertEquals(GAction.Redraw, m.onKey(Key.SOFT_LEFT, -1)); assertEquals(GView.FOLDERS, m.view)
        assertEquals(m.albumIndex, m.folderSel) // opens on the folder in use
        m.onKey(Key.DOWN, -1); m.onKey(Key.DOWN, -1) // beyond Camera
        m.onKey(Key.OK, -1)
        assertEquals(GView.GRID, m.view); assertEquals(0, m.sel)
        assertTrue(m.album.name == "Screenshots" || m.album.name == "Camera")
    }

    @Test fun backLeavesTheGalleryFromTheGridAndStepsBackElsewhere() {
        val m = model(listOf(item(1)))
        m.onKey(Key.SOFT_LEFT, -1); assertEquals(GAction.Redraw, m.onKey(Key.SOFT_RIGHT, -1)); assertEquals(GView.GRID, m.view)
        assertEquals(GAction.Exit, m.onKey(Key.SOFT_RIGHT, -1)); assertEquals(GAction.Home, m.onKey(Key.END, -1))
    }

    @Test fun digitOneSwitchesBetweenDotsAndColourInTheGrid() {
        val m = model(listOf(item(1))); m.onKey(Key.DIGIT, 1); assertTrue(m.colour); m.onKey(Key.DIGIT, 1); assertFalse(m.colour)
    }
}
