package com.deskbuddy
import org.junit.Assert.*
import org.junit.Test
class GazeGeometryTest {
    @Test fun distanceWorksAtDifferentResolutions() {
        assertTrue(GazeGeometry.isLooking(70f,480f,0f,15f))
        assertTrue(GazeGeometry.isLooking(140f,960f,0f,15f))
        assertFalse(GazeGeometry.isLooking(15f,480f,0f,0f))
    }
    @Test fun turningAwayDoesNotCountAndInvalidInputIsSafe() {
        assertFalse(GazeGeometry.isLooking(100f,480f,50f,0f))
        assertFalse(GazeGeometry.isLooking(100f,480f,0f,50f))
        assertFalse(GazeGeometry.isLooking(100f,0f,0f,0f))
    }
}
