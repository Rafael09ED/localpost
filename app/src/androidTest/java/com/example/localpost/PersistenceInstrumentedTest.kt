package com.example.localpost

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PersistenceInstrumentedTest {
    @Test fun keyAndNameSurvivePreferenceReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "identity-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val first = Identity.load(preferences)
            assertEquals("", first.displayName)
            assertTrue(preferences.edit().putString("displayName", "Alice").commit())
            val reloaded = Identity.load(context.getSharedPreferences(name, Context.MODE_PRIVATE))
            assertEquals("Alice", reloaded.displayName)
            assertEquals(first.authorId, reloaded.authorId)
            assertArrayEquals(first.keys.public.encoded, reloaded.keys.public.encoded)
            assertArrayEquals(first.keys.private.encoded, reloaded.keys.private.encoded)
            assertTrue(Post.create(reloaded, "Still the same key").verify())
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun debugLogRetainsLastFiftyEvents() {
        val log = DebugLog()
        for (index in 0 until 60) log.add("event $index")
        assertEquals(50, log.events.value.size)
        assertTrue(log.events.value.first().endsWith("event 10"))
        assertTrue(log.events.value.last().endsWith("event 59"))
    }
}