package io.github.durkimat.media

import org.junit.Assert.*
import org.junit.Test

class GridNavTest {
    // 3 columns, 8 tiles:  0 1 2 / 3 4 5 / 6 7
    @Test fun leftAndRightStepInReadingOrderAndStopAtTheEnds() {
        assertEquals(1, GridNav.move(0, 8, 3, Dir.RIGHT)); assertEquals(3, GridNav.move(2, 8, 3, Dir.RIGHT)); assertEquals(2, GridNav.move(3, 8, 3, Dir.LEFT))
        assertEquals(0, GridNav.move(0, 8, 3, Dir.LEFT)); assertEquals(7, GridNav.move(7, 8, 3, Dir.RIGHT))
    }

    @Test fun upAndDownMoveARowAndStopAtTheEdges() {
        assertEquals(4, GridNav.move(1, 8, 3, Dir.DOWN)); assertEquals(1, GridNav.move(4, 8, 3, Dir.UP))
        assertEquals(1, GridNav.move(1, 8, 3, Dir.UP))
    }

    @Test fun downIntoAShortLastRowLandsOnTheLastTile() {
        assertEquals(7, GridNav.move(5, 8, 3, Dir.DOWN)); assertEquals(7, GridNav.move(4, 8, 3, Dir.DOWN))
        assertEquals(7, GridNav.move(7, 8, 3, Dir.DOWN)) // already on the last row
        assertEquals(6, GridNav.move(3, 8, 3, Dir.DOWN))
    }

    @Test fun emptyAndSingleGridsAreSafe() {
        assertEquals(0, GridNav.move(0, 0, 3, Dir.DOWN)); assertEquals(0, GridNav.move(5, 1, 3, Dir.DOWN)); assertEquals(0, GridNav.move(0, 1, 3, Dir.RIGHT))
    }

    @Test fun pagesMoveWholeRowsAndClamp() {
        assertEquals(9, GridNav.page(0, 30, 3, 3, true)); assertEquals(0, GridNav.page(9, 30, 3, 3, false)); assertEquals(27, GridNav.page(27, 30, 3, 3, true)); assertEquals(29, GridNav.page(20, 30, 3, 3, true))
        assertEquals(2, GridNav.page(2, 30, 3, 3, false)) // same column
    }

    @Test fun theViewMovesOnlyAsFarAsNeeded() {
        assertEquals(0, GridNav.firstRow(5, 30, 3, 3, 0)); assertEquals(1, GridNav.firstRow(9, 30, 3, 3, 0)) // row 3 is below rows 0..2
        assertEquals(1, GridNav.firstRow(4, 30, 3, 3, 1)); assertEquals(0, GridNav.firstRow(0, 30, 3, 3, 4))
        assertEquals(7, GridNav.firstRow(29, 30, 3, 3, 0)) // 10 rows, last 3 shown
        assertEquals(0, GridNav.firstRow(2, 2, 3, 3, 5)) // fewer rows than fit
    }
}
