package com.vestateck.smetracker.data.remote.sync.entities

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.firestore.FirebaseFirestoreException
import com.vestateck.smetracker.data.database.SMEDatabase
import com.vestateck.smetracker.data.entities.Customer
import com.vestateck.smetracker.data.entities.Debt
import com.vestateck.smetracker.data.entities.Task
import com.vestateck.smetracker.testutil.EmulatorBusinessSeeder
import com.vestateck.smetracker.testutil.FirebaseEmulatorRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for CustomerSync, DebtSync, and TaskSync — the three
 * entities with no owner/worker split (see each class's doc comment: "no
 * worker/owner split, same as Customer/Task"). Their only real rule
 * surface is isMemberOf(businessId), so these tests focus on that boundary
 * (a signed-in phone with no relationship to this business must be denied)
 * rather than repeating role-split coverage that doesn't apply here.
 *
 * Also documents one thing worth Chris's attention rather than silently
 * assuming it's fine: firestore.rules' `allow delete: if isOwnerOf(...)`
 * on these three collections does NOT restrict what the app actually does
 * on a "delete" - SMERepository.delete*() always performs a soft delete
 * (isDeleted = true via a normal write), which the rules see as an
 * `update`, not a `delete`. That means a WORKER can currently soft-delete
 * any customer/task/debt despite the owner-only delete rule reading like
 * it should stop them. workerCanSoftDeleteCustomer_becauseSoftDeleteIsAnUpdateNotADelete
 * below asserts today's actual behavior (not a failure) so it stays
 * visible and intentional rather than being rediscovered as a surprise.
 *
 * Requires `firebase emulators:start --only auth,firestore` running
 * locally, and must run on an Android emulator/AVD (10.0.2.2 host mapping).
 */
@RunWith(AndroidJUnit4::class)
class SharedEntitySyncEmulatorTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var db: SMEDatabase
    private val testScope = CoroutineScope(Dispatchers.IO)

    private val ownerPhone = "+256700500001"
    private val workerPhone = "+256700500002"
    private val strangerPhone = "+256700500099" // signed in, but never added to this business

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SMEDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    // -- Customer -----------------------------------------------------------

    @Test
    fun customerSync_memberPush_succeeds() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)

        val customer = Customer(name = "Jane Doe", phone = "+256700900001", pendingSync = true)
        db.smeDao().insertCustomer(customer)

        CustomerSync(db.smeDao(), emulatorRule.firestore, testScope).pushPending(businessId)

        assertEquals(false, db.smeDao().getPendingSyncCustomers().any { it.id == customer.id })
        val remote = emulatorRule.firestore
            .collection("businesses").document(businessId)
            .collection("customers").document(customer.id).get().await()
        assertTrue(remote.exists())
    }

    @Test
    fun customerSync_nonMemberDirectWrite_isDenied() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)

        EmulatorBusinessSeeder.signIn(emulatorRule, strangerPhone) // never seeded into phoneIndex for this business
        try {
            emulatorRule.firestore
                .collection("businesses").document(businessId)
                .collection("customers").document("intruder-doc")
                .set(mapOf("name" to "Intruder")).await()
            fail("expected a non-member phone to be denied writing customers")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }
    }

    @Test
    fun workerCanSoftDeleteCustomer_becauseSoftDeleteIsAnUpdateNotADelete() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)
        EmulatorBusinessSeeder.seedWorker(emulatorRule.firestore, businessId, workerPhone)

        val customer = Customer(name = "Soft Delete Me", pendingSync = true)
        db.smeDao().insertCustomer(customer)
        CustomerSync(db.smeDao(), emulatorRule.firestore, testScope).pushPending(businessId)

        // Worker soft-deletes it locally, then pushes - same code path as
        // any other edit (isDeleted is just another field on the doc).
        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)
        val dbWorker = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, SMEDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            dbWorker.smeDao().insertCustomer(customer.copy(isDeleted = true, pendingSync = true))
            CustomerSync(dbWorker.smeDao(), emulatorRule.firestore, testScope).pushPending(businessId)

            val remote = emulatorRule.firestore
                .collection("businesses").document(businessId)
                .collection("customers").document(customer.id).get().await()
            assertEquals(
                "documents current behavior: a worker CAN soft-delete despite the owner-only delete rule",
                true,
                remote.getBoolean("isDeleted")
            )
        } finally {
            dbWorker.close()
        }
    }

    // -- Debt -----------------------------------------------------------------

    @Test
    fun debtSync_memberPush_succeeds() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)
        EmulatorBusinessSeeder.seedWorker(emulatorRule.firestore, businessId, workerPhone)
        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)

        val debt = Debt(customerName = "Jane Doe", amount = 15000.0, pendingSync = true)
        db.smeDao().insertDebt(debt)

        DebtSync(db.smeDao(), emulatorRule.firestore, testScope).pushPending(businessId, workerPhone)

        assertEquals(false, db.smeDao().getPendingSyncDebts().any { it.id == debt.id })
        val remote = emulatorRule.firestore
            .collection("businesses").document(businessId)
            .collection("debts").document(debt.id).get().await()
        assertTrue(remote.exists())
        assertEquals(workerPhone, remote.getString("recordedBy"))
    }

    @Test
    fun debtSync_nonMemberDirectWrite_isDenied() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)

        EmulatorBusinessSeeder.signIn(emulatorRule, strangerPhone)
        try {
            emulatorRule.firestore
                .collection("businesses").document(businessId)
                .collection("debts").document("intruder-debt")
                .set(mapOf("customerName" to "Intruder", "amount" to 1.0)).await()
            fail("expected a non-member phone to be denied writing debts")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }
    }

    // -- Task -------------------------------------------------------------------

    @Test
    fun taskSync_memberPush_succeeds() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)

        val task = Task(title = "Restock sugar", pendingSync = true)
        db.smeDao().insertTask(task)

        TaskSync(db.smeDao(), emulatorRule.firestore, testScope).pushPending(businessId)

        assertEquals(false, db.smeDao().getPendingSyncTasks().any { it.id == task.id })
        val remote = emulatorRule.firestore
            .collection("businesses").document(businessId)
            .collection("tasks").document(task.id).get().await()
        assertTrue(remote.exists())
    }

    @Test
    fun taskSync_nonMemberDirectWrite_isDenied() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)

        EmulatorBusinessSeeder.signIn(emulatorRule, strangerPhone)
        try {
            emulatorRule.firestore
                .collection("businesses").document(businessId)
                .collection("tasks").document("intruder-task")
                .set(mapOf("title" to "Intruder task")).await()
            fail("expected a non-member phone to be denied writing tasks")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }
    }
}