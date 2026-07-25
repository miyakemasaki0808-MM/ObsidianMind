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
        assertEquals(0f, motion.haloAlpha, 0f)
        assertEquals(0f, motion.titleAlpha, 0f)
    }

    @Test
    fun `halo appears before the obsidian body`() {
        val motion = vigilithOpeningMotion(0.12f)

        assertTrue(motion.haloAlpha > 0f)
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
    fun `halo expands while the body appears`() {
        val early = vigilithOpeningMotion(0.10f)
        val settled = vigilithOpeningMotion(0.38f)

        assertTrue(early.haloScale < settled.haloScale)
        assertEquals(1f, settled.haloScale, 0.0001f)
    }

    @Test
    fun `body remains settled during the hold interval`() {
        val middle = vigilithOpeningMotion(0.55f)
        val late = vigilithOpeningMotion(0.75f)

        assertEquals(1f, middle.bodyAlpha, 0f)
        assertEquals(1f, late.bodyAlpha, 0f)
        assertEquals(1f, middle.bodyScale, 0f)
        assertEquals(0f, middle.bodyLiftFraction, 0f)
    }

    @Test
    fun `body halo and title fade during exit`() {
        val before = vigilithOpeningMotion(0.78f)
        val during = vigilithOpeningMotion(0.87f)

        assertTrue(during.bodyAlpha < before.bodyAlpha)
        assertTrue(during.haloAlpha < before.haloAlpha)
        assertTrue(during.titleAlpha < before.titleAlpha)
    }

    @Test
    fun `brand and navy background are gone at the end`() {
        val motion = vigilithOpeningMotion(1f)

        assertEquals(0f, motion.backdropAlpha, 0f)
        assertEquals(0f, motion.bodyAlpha, 0f)
        assertEquals(0f, motion.haloAlpha, 0f)
        assertEquals(0f, motion.titleAlpha, 0f)
    }

    @Test
    fun `timeline is clamped outside its range`() {
        assertEquals(vigilithOpeningMotion(0f), vigilithOpeningMotion(-1f))
        assertEquals(vigilithOpeningMotion(1f), vigilithOpeningMotion(2f))
    }
}
