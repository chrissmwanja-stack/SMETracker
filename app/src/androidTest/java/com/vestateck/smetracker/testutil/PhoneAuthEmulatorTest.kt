package com.vestateck.smetracker.testutil

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises FirebaseEmulatorRule.signInWithPhoneNumber() against a real,
 * locally running Firebase Auth emulator (see firebase.json).
 *
 * Requires `firebase emulators:start` to already be running before this
 * test executes, and must run on an Android *emulator* (AVD) — 10.0.2.2
 * only NATs to the host loopback from an AVD, not a physical device.
 */
@RunWith(AndroidJUnit4::class)
class PhoneAuthEmulatorTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    @Test
    fun signInWithPhoneNumber_completesAgainstEmulator() = runTest {
        val phoneNumber = "+15555550123"
        val activity = launchHostActivity()

        val user = emulatorRule.signInWithPhoneNumber(phoneNumber, activity)

        assertEquals(phoneNumber, user.phoneNumber)
        assertFalse("expected a non-blank uid", user.uid.isBlank())
    }

    /**
     * verifyPhoneNumber() needs a live Activity to attach to.
     * ActivityScenario.onActivity{} runs on the main thread, so we hop back
     * to the test thread via a latch before returning the reference.
     */
    private fun launchHostActivity(): EmulatorHostActivity {
        val latch = CountDownLatch(1)
        var activityRef: EmulatorHostActivity? = null

        ActivityScenario.launch(EmulatorHostActivity::class.java).onActivity { activity ->
            activityRef = activity
            latch.countDown()
        }

        check(latch.await(5, TimeUnit.SECONDS)) { "EmulatorHostActivity failed to launch in time" }
        return activityRef ?: error("EmulatorHostActivity reference was never captured")
    }
}