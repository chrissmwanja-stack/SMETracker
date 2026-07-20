package com.vestateck.smetracker.testutil

import android.app.Activity
import android.os.Bundle

/**
 * Exists only so PhoneAuthProvider.verifyPhoneNumber() has a live Activity
 * to attach to in instrumented tests. No UI, no logic — see
 * FirebaseEmulatorRule.signInWithPhoneNumber().
 *
 * Lives in androidTest, not main, so it never ships in a release build.
 */
class EmulatorHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}