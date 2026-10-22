package com.hmx.pdf.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagesToPdfRotationTest {

    @Test fun clockwiseRotationIsOneQuarterTurn() {
        assertEquals(1, rotateQuarterTurns(0, 1))
        assertEquals(2, rotateQuarterTurns(1, 1))
        assertEquals(3, rotateQuarterTurns(2, 1))
        assertEquals(0, rotateQuarterTurns(3, 1))
    }

    @Test fun counterClockwiseRotationIsOneQuarterTurnBack() {
        assertEquals(3, rotateQuarterTurns(0, -1))
        assertEquals(0, rotateQuarterTurns(1, -1))
        assertEquals(1, rotateQuarterTurns(2, -1))
        assertEquals(2, rotateQuarterTurns(3, -1))
    }

    @Test fun repeatedRotationsStayNormalized() {
        assertEquals(0, normalizeQuarterTurns(0))
        assertEquals(1, normalizeQuarterTurns(1))
        assertEquals(0, normalizeQuarterTurns(4))
        assertEquals(3, normalizeQuarterTurns(-1))
        assertEquals(2, normalizeQuarterTurns(-6))
        assertEquals(3, normalizeQuarterTurns(Int.MAX_VALUE))
    }

    @Test fun rotationOnlyChangesTheRequestedEntry() {
        val rotations = listOf(0, 0, 0)
        assertEquals(listOf(1, 0, 0), rotateQuarterTurnsAt(rotations, 0, 1))
        assertEquals(listOf(0, 3, 0), rotateQuarterTurnsAt(rotations, 1, -1))
        assertEquals(listOf(0, 0, 2), rotateQuarterTurnsAt(rotations, 2, 2))
    }

    @Test fun duplicateEntriesRemainIndependentBecauseRotationIsIndexed() {
        val duplicateRotations = listOf(0, 0)
        val firstRotated = rotateQuarterTurnsAt(duplicateRotations, 0, 1)
        val secondRotated = rotateQuarterTurnsAt(firstRotated, 1, 3)
        assertEquals(listOf(1, 3), secondRotated)
        assertEquals(listOf(0, 0), duplicateRotations)
    }

    @Test fun orderIsPreservedWhenRotating() {
        val rotations = listOf(0, 0, 0, 0)
        val updated = rotateQuarterTurnsAt(rotations, 2, 1)
        assertEquals(4, updated.size)
        assertEquals(listOf(0, 0, 1, 0), updated)
    }

    @Test fun invalidRotationIndexIsANoOp() {
        val rotations = listOf(1, 2)
        assertEquals(rotations, rotateQuarterTurnsAt(rotations, -1, 1))
        assertEquals(rotations, rotateQuarterTurnsAt(rotations, 2, 1))
    }

    @Test fun viewerIndexTracksRemoval() {
        assertEquals(-1, viewerIndexAfterRemoval(-1, 0, 3))
        assertEquals(-1, viewerIndexAfterRemoval(0, 0, 0))
        assertEquals(-1, viewerIndexAfterRemoval(1, 1, 2))
        assertEquals(0, viewerIndexAfterRemoval(0, 1, 2))
        assertEquals(1, viewerIndexAfterRemoval(2, 1, 2))
        assertEquals(1, viewerIndexAfterRemoval(2, 0, 2))
    }

    @Test fun emptySelectionHasNoViewerIndex() {
        assertEquals(-1, viewerIndexAfterRemoval(0, 0, 0))
        assertEquals(-1, viewerIndexAfterRemoval(2, 0, 0))
    }
}
