package com.example.smetracker.data.remote.auth

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.UUID

class BusinessRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    /**
     * First-time setup: the currently-signed-in phone number becomes the
     * owner of a brand new business. Writes businesses/{id},
     * businesses/{id}/members/{phone}, and phoneIndex/{phone} atomically.
     */
    suspend fun createBusinessWithOwner(
        ownerPhoneE164: String,
        ownerName: String,
        businessName: String
    ): Result<String> {
        return try {
            val businessId = UUID.randomUUID().toString()
            val businessRef = firestore.collection("businesses").document(businessId)
            val memberRef = businessRef.collection("members").document(ownerPhoneE164)
            val phoneIndexRef = firestore.collection("phoneIndex").document(ownerPhoneE164)

            firestore.runTransaction { txn ->
                // Guard: don't let a phone that's already indexed create a second business.
                val existing = txn.get(phoneIndexRef)
                if (existing.exists()) {
                    throw IllegalStateException("This phone number is already registered to a business.")
                }

                txn.set(businessRef, mapOf(
                    "name" to businessName,
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "ownerPhone" to ownerPhoneE164
                ))

                txn.set(memberRef, mapOf(
                    "role" to "owner",
                    "name" to ownerName,
                    "addedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                ))

                txn.set(phoneIndexRef, mapOf(
                    "businessId" to businessId,
                    "role" to "owner"
                ))
            }.await()

            Result.success(businessId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Owner-only action: provisions a worker's account ahead of time. The
     * worker does nothing to "accept" — they just log in with OTP on their
     * own phone afterward and phoneIndex routes them straight in.
     */
    suspend fun addWorker(
        businessId: String,
        workerPhoneE164: String,
        workerName: String
    ): Result<Unit> {
        return try {
            val memberRef = firestore.collection("businesses").document(businessId)
                .collection("members").document(workerPhoneE164)
            val phoneIndexRef = firestore.collection("phoneIndex").document(workerPhoneE164)

            firestore.runTransaction { txn ->
                val existing = txn.get(phoneIndexRef)
                if (existing.exists()) {
                    throw IllegalStateException("This phone number is already registered to a business.")
                }

                txn.set(memberRef, mapOf(
                    "role" to "worker",
                    "name" to workerName,
                    "addedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                ))

                txn.set(phoneIndexRef, mapOf(
                    "businessId" to businessId,
                    "role" to "worker"
                ))
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** For the "add worker" screen's member list, and future role-gated UI. */
    suspend fun getMembers(businessId: String): Result<List<Pair<String, Map<String, Any>>>> {
        return try {
            val snapshot = firestore.collection("businesses").document(businessId)
                .collection("members")
                .get()
                .await()
            Result.success(snapshot.documents.map { it.id to (it.data ?: emptyMap()) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}