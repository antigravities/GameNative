package com.winlator.xenvironment

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XEnvironmentTest {
    @Test
    fun stopEnvironmentComponents_continuesStoppingWhenAComponentThrows() {
        val stopOrder = mutableListOf<String>()
        val environment = XEnvironment(mockk<Context>(relaxed = true), mockk<ImageFs>(relaxed = true))
        environment.addComponent(
            RecordingComponent("first", stopOrder),
        )
        environment.addComponent(
            RecordingComponent("second", stopOrder, throwsOnStop = true),
        )
        environment.addComponent(
            RecordingComponent("third", stopOrder),
        )

        environment.stopEnvironmentComponents()

        assertEquals(listOf("third", "second", "first"), stopOrder)
    }

    @Test
    fun stopEnvironmentComponents_stopsInReverseRegistrationOrder() {
        val stopOrder = mutableListOf<String>()
        val environment = XEnvironment(mockk<Context>(relaxed = true), mockk<ImageFs>(relaxed = true))
        environment.addComponent(RecordingComponent("one", stopOrder))
        environment.addComponent(RecordingComponent("two", stopOrder))

        environment.stopEnvironmentComponents()

        assertEquals(listOf("two", "one"), stopOrder)
    }

    private class RecordingComponent(
        private val name: String,
        private val stopOrder: MutableList<String>,
        private val throwsOnStop: Boolean = false,
    ) : EnvironmentComponent() {
        override fun start() {
        }

        override fun stop() {
            stopOrder += name
            if (throwsOnStop) {
                throw IllegalStateException("boom")
            }
        }
    }
}
