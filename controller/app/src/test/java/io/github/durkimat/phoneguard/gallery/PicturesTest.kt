package io.github.durkimat.phoneguard.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class PicturesTest {
    @Test fun whiteIsLightAndBlackIsDark() { assertEquals(255, Pictures.luma(0xFFFFFFFF.toInt())); assertEquals(0, Pictures.luma(0xFF000000.toInt())) }

    @Test fun theSeeThroughPartOfAStickerIsLightNotBlack() { assertEquals(255, Pictures.luma(0)); assertEquals(127, Pictures.luma(0x80000000.toInt())) }
}
