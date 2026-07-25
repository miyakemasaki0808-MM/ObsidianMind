package com.example.newproject.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VigilithOpeningMotionTest {

    @Test
    fun `opening starts in darkness with every brand element hidden`() {
        val motion = vigilithOpeningMotion(0f)

        assertEquals(1f, motion.backdropAlpha, 0f)
        assertEquals(0f, motion.bodyAlpha, 0f)
        assertEquals(0f, motion.eyeAlpha, 0f)
        assertEquals(0f, motion.haloAlpha, 0f)
        assertEquals(0f, motion.titleAlpha, 0f)
    }

    @Test
    fun `reading lenses light before the obsidian body`() {
        val motion = vigilithOpeningMotion(0.12f)

        assertTrue(motion.eyeAlpha > 0f)
        assertEquals(0f, motion.bodyAlpha, 0f)
        assertEquals(0f, motion.titleAlpha, 0f)
    }

    @Test
    fun `body settles before the title completes`() {
        val motion = vigilithOpeningMotion(0.50f)

        assertEquals(1f, motion.bodyAlpha, 0f)
        assertEquals(1f, motion.bodyScale, 0f)
        assertEquals(0f, motion.bodyLiftFraction, 0f)
        assertEquals(1f, motion.haloAlpha, 0f)
        assertTrue(motion.titleAlpha > 0f)
        assertTrue(motion.titleAlpha < 1f)
    }

    @Test
    fun `lens focus contracts while it wakes`() {
        val early = vigilithOpeningMotion(0.08f)
        val focused = vigilithOpeningMotion(0.30f)

        assertTrue(early.eyeFocusScale > focused.eyeFocusScale)
        assertEquals(1f, focused.eyeFocusScale, 0.0001f)
    }

    @Test
    fun `lens pulse happens once around focus lock`() {
        assertEquals(0f, vigilithOpeningMotion(0.20f).eyePulse, 0f)
        assertEquals(1f, vigilithOpeningMotion(0.38f).eyePulse, 0.0001f)
        assertEquals(0f, vigilithOpeningMotion(0.60f).eyePulse, 0f)
    }

    @Test
    fun `brand and navy background are gone at the end`() {
        val motion = vigilithOpeningMotion(1f)

        assertEquals(0f, motion.backdropAlpha, 0f)
        assertEquals(0f, motion.bodyAlpha, 0f)
        assertEquals(0f, motion.eyeAlpha, 0f)
        assertEquals(0f, motion.haloAlpha, 0f)
        assertEquals(0f, motion.titleAlpha, 0f)
    }

    @Test
    fun `timeline is clamped outside its range`() {
        assertEquals(vigilithOpeningMotion(0f), vigilithOpeningMotion(-1f))
        assertEquals(vigilithOpeningMotion(1f), vigilithOpeningMotion(2f))
    }
}
