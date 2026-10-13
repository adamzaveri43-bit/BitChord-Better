package com.music.bitchord

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.QueueShuffle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A new queue plays in order unless it was started from a Shuffle action —
 * the fix for albums coming out in a random order because a Shuffle pressed
 * earlier had left shuffle on for everything after.
 */
class QueueShuffleNewQueueTest {

    @Before
    fun stubSettings() {
        // AppSettings persists the shuffle state; give it preferences that
        // accept writes and keep nothing.
        lateinit var editor: android.content.SharedPreferences.Editor
        editor = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences.Editor::class.java.classLoader,
            arrayOf(android.content.SharedPreferences.Editor::class.java),
        ) { _, method, _ ->
            if (method.returnType == android.content.SharedPreferences.Editor::class.java) editor else null
        } as android.content.SharedPreferences.Editor
        val prefs = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java),
        ) { _, method, _ ->
            if (method.name == "edit") editor else null
        } as android.content.SharedPreferences
        val field = AppSettings::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        field.set(AppSettings, prefs)
        // Clear anything a previous test left armed.
        QueueShuffle.takeForNewQueue()
        QueueShuffle.setEnabled(false)
    }

    @Test
    fun songPickedWhileShuffleIsLeftOnPlaysInOrderAndTurnsItOff() {
        QueueShuffle.setEnabled(true)

        assertFalse(QueueShuffle.takeForNewQueue())
        assertFalse(QueueShuffle.enabled.value)
    }

    @Test
    fun shuffleActionShufflesThatQueueOnly() {
        QueueShuffle.enableForNextQueue()

        assertTrue(QueueShuffle.takeForNewQueue())
        assertTrue(QueueShuffle.enabled.value)

        // The next song picked from a page plays in order again.
        assertFalse(QueueShuffle.takeForNewQueue())
        assertFalse(QueueShuffle.enabled.value)
    }

    @Test
    fun shuffleOffStaysOff() {
        assertFalse(QueueShuffle.takeForNewQueue())
        assertFalse(QueueShuffle.enabled.value)
    }
}
