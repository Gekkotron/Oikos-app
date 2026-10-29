package com.gekkotron.oikos.presence

import android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
import android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
import android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED
import com.gekkotron.oikos.data.PresenceConfig
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundDataWarningTest {
    private val on = PresenceConfig(enabled = true, alias = "julien", homeLat = 1.0, homeLng = 2.0, radiusM = 83)

    @Test
    fun `presence on with background data blocked warns`() {
        assertTrue(shouldWarnBackgroundData(on, RESTRICT_BACKGROUND_STATUS_ENABLED))
    }

    @Test
    fun `presence on with background data allowed stays quiet`() {
        assertFalse(shouldWarnBackgroundData(on, RESTRICT_BACKGROUND_STATUS_DISABLED))
        assertFalse(shouldWarnBackgroundData(on, RESTRICT_BACKGROUND_STATUS_WHITELISTED))
    }

    @Test
    fun `presence off or not loaded never warns`() {
        assertFalse(shouldWarnBackgroundData(on.copy(enabled = false), RESTRICT_BACKGROUND_STATUS_ENABLED))
        assertFalse(shouldWarnBackgroundData(null, RESTRICT_BACKGROUND_STATUS_ENABLED))
    }
}
