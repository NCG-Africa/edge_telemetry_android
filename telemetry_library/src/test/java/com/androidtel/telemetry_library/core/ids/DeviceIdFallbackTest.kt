package com.androidtel.telemetry_library.core.ids

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** R9 (#135): device_id is never missing — UUIDv4 fallback, persisted once, else in-memory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DeviceIdFallbackTest {

    private fun assertUuidV4(id: String) = assertEquals(4, UUID.fromString(id).version())

    private fun generatorWith(prefs: SharedPreferences): IdGenerator {
        val ctx = mockk<Context> {
            every { applicationContext } returns this
            every { getSharedPreferences(any(), any()) } returns prefs
        }
        return IdGenerator().apply { initialize(ctx) }
    }

    @Test
    fun `not initialized falls back to an in-memory uuid stable across calls`() {
        val gen = IdGenerator()
        val id = gen.getOrGenerateDeviceId()
        assertUuidV4(id)
        assertEquals(id, gen.getOrGenerateDeviceId())
    }

    @Test
    fun `primary read failure persists a fallback uuid under its own key`() {
        val real = RuntimeEnvironment.getApplication()
            .getSharedPreferences("fallback_test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        // The primary device_id slot is unreadable (e.g. wrong type); the fallback slot works.
        val broken = mockk<SharedPreferences> {
            every { getString("device_id", any()) } throws ClassCastException("not a String")
            every { getString("device_id_fallback", any()) } answers { real.getString("device_id_fallback", null) }
            every { edit() } answers { real.edit() }
        }

        val id = generatorWith(broken).getOrGenerateDeviceId()

        assertUuidV4(id)
        assertEquals(id, real.getString("device_id_fallback", null))
        assertEquals("a new process reads the same id", id, generatorWith(broken).getOrGenerateDeviceId())
    }

    @Test
    fun `once a fallback is persisted it wins even when the primary slot reads again`() {
        val real = RuntimeEnvironment.getApplication()
            .getSharedPreferences("fallback_flip_test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val broken = mockk<SharedPreferences> {
            every { getString("device_id", any()) } throws ClassCastException("transient")
            every { getString("device_id_fallback", any()) } answers { real.getString("device_id_fallback", null) }
            every { edit() } answers { real.edit() }
        }
        val fallback = generatorWith(broken).getOrGenerateDeviceId()

        // Next process: the primary slot is readable (and populated) again.
        real.edit().putString("device_id", "device_primary_android").commit()

        assertEquals("device_id must not flip between processes", fallback, generatorWith(real).getOrGenerateDeviceId())
    }

    @Test
    fun `read and write both failing falls back to an in-memory uuid stable across calls`() {
        val broken = mockk<SharedPreferences> {
            every { getString(any(), any()) } throws SecurityException("prefs unavailable")
            every { edit() } throws SecurityException("prefs unavailable")
        }
        val gen = generatorWith(broken)

        val id = gen.getOrGenerateDeviceId()

        assertUuidV4(id)
        assertEquals(id, gen.getOrGenerateDeviceId())
        assertNotEquals("per process, not shared", id, generatorWith(broken).getOrGenerateDeviceId())
    }
}
