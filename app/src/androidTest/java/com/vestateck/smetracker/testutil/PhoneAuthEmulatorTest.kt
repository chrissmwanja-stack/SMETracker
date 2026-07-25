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
 * locally running Firebase Auth emulator.
 *
 * Requires `firebase emulators:start --only auth` to already be running before
 * this test executes.
 *
 * This must run on an Android emulator/AVD, not a physical device, because
 * 10.0.2.2 maps from the Android emulator to the host machine.
 */
@RunWith(AndroidJUnit4::class)
class PhoneAuthEmulatorTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    @Test
    fun signInWithPhoneNumber_completesAgainstEmulator() = runTest {
        val phoneNumber = "+256700123456"

        val scenario = ActivityScenario.launch(EmulatorHostActivity::class.java)

        try {
            val activity = captureActivity(scenario)

            val user = emulatorRule.signInWithPhoneNumber(phoneNumber, activity)

            assertEquals(phoneNumber, user.phoneNumber)
            assertFalse("expected a non-blank uid", user.uid.isBlank())
        } finally {
            scenario.close()
        }
    }

    /**
     * verifyPhoneNumber() needs a live Activity to attach to.
     *
     * ActivityScenario.onActivity{} runs on the main thread, so we hop back
     * to the test thread through a latch before returning the reference.
     */
    private fun captureActivity(
        scenario: ActivityScenario<EmulatorHostActivity>
    ): EmulatorHostActivity {
        val latch = CountDownLatch(1)
        var activityRef: EmulatorHostActivity? = null

        scenario.onActivity { activity ->
            activityRef = activity
            latch.countDown()
        }

        check(latch.await(5, TimeUnit.SECONDS)) {
            "EmulatorHostActivity failed to launch in time"
        }

        return activityRef ?: error("EmulatorHostActivity reference was never captured")
    }
}