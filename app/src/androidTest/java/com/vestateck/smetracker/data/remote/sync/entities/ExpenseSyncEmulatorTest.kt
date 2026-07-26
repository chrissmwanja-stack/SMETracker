package com.vestateck.smetracker.data.remote.sync.entities

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.firestore.FirebaseFirestoreException
import com.vestateck.smetracker.data.database.SMEDatabase
import com.vestateck.smetracker.data.entities.Expense
import com.vestateck.smetracker.data.remote.model.MemberRole
import com.vestateck.smetracker.testutil.EmulatorBusinessSeeder
import com.vestateck.smetracker.testutil.FirebaseEmulatorRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for ExpenseSync against a real Firestore emulator +
 * real firestore.rules. This is the entity with the most rule surface of
 * anything that isn't Sale/Inventory: role-scoped reads AND a create rule
 * that dictates the exact shape of the document, not just who can write it
 * (see RemoteExpense.kt's class doc and firestore.rules' expenses match
 * block).
 *
 * Requires `firebase emulators:start --only auth,firestore` running
 * locally, and must run on an Android emulator/AVD (10.0.2.2 host mapping).
 */
@RunWith(AndroidJUnit4::class)
class ExpenseSyncEmulatorTest {

    @get:Rule
    val emulatorRule = FirebaseEmulatorRule()

    private lateinit var db: SMEDatabase
    private val testScope = CoroutineScope(Dispatchers.IO)

    private val ownerPhone = "+256700300001"
    private val workerPhone = "+256700300002"

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

    private fun newExpenseSync() = ExpenseSync(db.smeDao(), emulatorRule.firestore, testScope)

    @Test
    fun pushPending_owner_autoApprovesWithApprovalFieldsSet() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)

        val expense = Expense(description = "Fuel", amount = 20000.0, pendingSync = true)
        db.smeDao().insertExpense(expense)

        newExpenseSync().pushPending(businessId, ownerPhone, MemberRole.OWNER)

        val remote = emulatorRule.firestore
            .collection("businesses").document(businessId)
            .collection("expenses").document(expense.id).get().await()
        assertEquals("APPROVED", remote.getString("status"))
        assertEquals(ownerPhone, remote.getString("approvedBy"))
        assertTrue("approvedAt should be set on an owner's own entry", remote.getLong("approvedAt") != null)
        assertEquals(false, db.smeDao().getExpenseById(expense.id)?.pendingSync)
    }

    @Test
    fun pushPending_worker_startsPendingWithNoApprovalFields() = runTest {
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)
        EmulatorBusinessSeeder.seedWorker(emulatorRule.firestore, businessId, workerPhone)
        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)

        val expense = Expense(description = "Boda fare", amount = 5000.0, pendingSync = true)
        db.smeDao().insertExpense(expense)

        newExpenseSync().pushPending(businessId, workerPhone, MemberRole.WORKER)

        val remote = emulatorRule.firestore
            .collection("businesses").document(businessId)
            .collection("expenses").document(expense.id).get().await()
        assertTrue("worker's own expense should still write", remote.exists())
        assertEquals("PENDING", remote.getString("status"))
        assertNull(remote.getString("approvedBy"))
        assertNull(remote.getLong("approvedAt"))
        assertEquals(false, db.smeDao().getExpenseById(expense.id)?.pendingSync)
    }

    @Test
    fun workerCannotCreateAPreApprovedExpense_rulesDenyIt() = runTest {
        // Belt-and-braces, same spirit as SaleSyncEmulatorTest's rules-level
        // check: if ExpenseSync's own role branch ever got dropped, this
        // makes sure firestore.rules would still stop a worker from
        // self-approving an expense outright.
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)
        EmulatorBusinessSeeder.seedWorker(emulatorRule.firestore, businessId, workerPhone)
        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)

        try {
            emulatorRule.firestore
                .collection("businesses").document(businessId)
                .collection("expenses").document("fake-expense")
                .set(
                    mapOf(
                        "description" to "Suspicious", "amount" to 1.0,
                        "recordedBy" to workerPhone, "status" to "APPROVED",
                        "approvedBy" to workerPhone, "approvedAt" to System.currentTimeMillis()
                    )
                ).await()
            fail("expected PERMISSION_DENIED for a worker self-approving an expense")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }
    }

    @Test
    fun workerScopedQuery_onlyReturnsOwnExpenses() = runTest {
        // Seed one expense recorded by the owner and one by the worker,
        // written directly as the owner (who can write anything) so this
        // test is purely about read scoping, not create-rule behavior.
        EmulatorBusinessSeeder.signIn(emulatorRule, ownerPhone)
        val businessId = EmulatorBusinessSeeder.seedBusinessWithOwner(emulatorRule.firestore, ownerPhone)
        EmulatorBusinessSeeder.seedWorker(emulatorRule.firestore, businessId, workerPhone)

        val expensesRef = emulatorRule.firestore.collection("businesses").document(businessId).collection("expenses")
        expensesRef.document("owner-exp").set(
            mapOf("description" to "Rent", "amount" to 1.0, "recordedBy" to ownerPhone, "status" to "APPROVED", "approvedBy" to ownerPhone, "approvedAt" to 1L)
        ).await()
        expensesRef.document("worker-exp").set(
            mapOf("description" to "Fuel", "amount" to 1.0, "recordedBy" to workerPhone, "status" to "PENDING")
        ).await()

        EmulatorBusinessSeeder.signIn(emulatorRule, workerPhone)

        // Same scoped query ExpenseSync.attachListener builds for a worker.
        val scoped = expensesRef.whereEqualTo("recordedBy", workerPhone).get().await()
        assertEquals(listOf("worker-exp"), scoped.documents.map { it.id })

        // And the unscoped query the rules are what actually reject, not
        // just something the app chooses not to run - confirms attachListener's
        // doc comment ("an unfiltered query from a worker is denied outright,
        // not silently filtered") is still true.
        try {
            expensesRef.get().await()
            fail("expected an unscoped expenses query as a worker to be denied")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }
    }
}