package com.vestateck.smetracker.testutil

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared setup for *SyncEmulatorTest classes (SaleSync, InventorySync,
 * ExpenseSync, etc.): sign a phone number in against the Auth emulator and
 * seed a business + phoneIndex entries the same way BusinessRepository
 * does in production, so tests exercise the real firestore.rules instead
 * of writing around them.
 *
 * Deliberately NOT a single atomic write, for the same reason
 * BusinessRepository.createBusinessWithOwner isn't one: the phoneIndex
 * create rule's Case 1 needs businesses/{id} to already exist by the time
 * it evaluates, so business must be written first and awaited before
 * phoneIndex.
 */
object EmulatorBusinessSeeder {

    /** Runs [signInWithPhoneNumber] against an EmulatorHostActivity, handling the activity plumbing. */
    suspend fun signIn(rule: FirebaseEmulatorRule, phoneNumber: String): FirebaseUser {
        val scenario = ActivityScenario.launch(EmulatorHostActivity::class.java)
        return try {
            rule.signInWithPhoneNumber(phoneNumber, captureActivity(scenario))
        } finally {
            scenario.close()
        }
    }

    /**
     * Must be called while signed in AS [ownerPhone] — businesses' create
     * rule requires request.resource.data.ownerPhone == myPhone().
     *
     * Returns the new businessId.
     */
    suspend fun seedBusinessWithOwner(
        firestore: FirebaseFirestore,
        ownerPhone: String
    ): String {
        val businessId = UUID.randomUUID().toString()

        firestore.collection("businesses").document(businessId)
            .set(
                mapOf(
                    "name" to "Test Business",
                    "createdAt" to System.currentTimeMillis(),
                    "ownerPhone" to ownerPhone
                )
            ).await()

        firestore.collection("phoneIndex").document(ownerPhone)
            .set(mapOf("businessId" to businessId, "role" to "OWNER"))
            .await()

        return businessId
    }

    /**
     * Must be called while still signed in AS THE OWNER of [businessId] —
     * phoneIndex's Case 2 create rule requires isOwnerOf(businessId) for
     * a WORKER entry. Mirrors BusinessRepository.addWorker, minus the
     * members subcollection write (not needed by any sync test).
     */
    suspend fun seedWorker(
        firestore: FirebaseFirestore,
        businessId: String,
        workerPhone: String
    ) {
        firestore.collection("phoneIndex").document(workerPhone)
            .set(mapOf("businessId" to businessId, "role" to "WORKER"))
            .await()
    }

    private fun captureActivity(scenario: ActivityScenario<EmulatorHostActivity>): Activity {
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