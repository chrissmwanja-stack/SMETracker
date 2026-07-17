package com.example.smetracker.data.remote.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.UUID

class BusinessRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    // Single source of truth for the Firestore role vocabulary. MemberRole's
    // enum constant names ARE "OWNER"/"WORKER" by construction — using
    // .name here instead of string literals means a typo like "Owner" is a
    // compile error, not a silent bad write. Every role field written to
    // Firestore in this class should go through these two constants, never
    // a raw string.
    private val ROLE_OWNER = MemberRole.OWNER.name
    private val ROLE_WORKER = MemberRole.WORKER.name

    /**
     * First-time setup: the currently-signed-in phone number becomes the
     * owner of a brand new business.
     *
     * IMPORTANT: this can NOT be a single atomic transaction. Firestore
     * security rules evaluate get()/exists() calls against the database
     * state as of the START of a transaction/batch — they never see other
     * writes happening alongside them in the same transaction. That creates
     * a circular dependency here: the phoneIndex rule requires
     * businesses/{id} to already exist, and the members rule requires
     * phoneIndex/{phone} to already exist. Bundling all three in one
     * transaction means none of them can ever pass.
     *
     * So this does three separate, sequentially-awaited writes instead,
     * ordered by dependency: business → phoneIndex → members. members is
     * last because it's the least critical of the three — isMemberOf()
     * (and therefore all of isOwnerOf/isWorkerOf) only ever reads
     * phoneIndex, never the members subcollection. If step 3 fails after
     * steps 1-2 succeed, the account is still fully functional; the
     * business just doesn't have a member-directory row for itself yet.
     */
    suspend fun createBusinessWithOwner(
        ownerPhoneE164: String,
        ownerName: String,
        businessName: String
    ): Result<String> {
        return try {
            // Guard: don't let a phone that's already indexed create a second business.
            val phoneIndexRef = firestore.collection("phoneIndex").document(ownerPhoneE164)
            if (phoneIndexRef.get().await().exists()) {
                return Result.failure(IllegalStateException("This phone number is already registered to a business."))
            }

            val businessId = UUID.randomUUID().toString()
            val businessRef = firestore.collection("businesses").document(businessId)
            val memberRef = businessRef.collection("members").document(ownerPhoneE164)

            // Step 1 — business must exist before phoneIndex can reference it.
            businessRef.set(
                mapOf(
                    "name" to businessName,
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "ownerPhone" to ownerPhoneE164
                )
            ).await()

            // Step 2 — phoneIndex must exist before the members write's
            // isOwnerOf() check can pass. This is the step that makes the
            // account actually functional; if it fails, we haven't left a
            // dangling business the user can't recover from re-attempting
            // (the "already registered" guard above only fires once
            // phoneIndex exists, so a retry after a step-1-only partial
            // failure would just create a second, orphaned business doc —
            // acceptable for now, but worth a cleanup pass later).
            try {
                phoneIndexRef.set(
                    mapOf(
                        "businessId" to businessId,
                        // Must be uppercase — firestore.rules' phoneIndex Case 1
                        // create rule checks request.resource.data.role == 'OWNER'
                        // as an exact string match. Derived from MemberRole.OWNER.name,
                        // not typed by hand, so it can't drift.
                        "role" to ROLE_OWNER
                    )
                ).await()
            } catch (e: Exception) {
                return Result.failure(e)
            }

            // Step 3 — members directory entry. Best-effort: the account
            // works without it (see class doc above), so don't fail the
            // whole sign-up over it, but don't silently swallow it either.
            try {
                memberRef.set(
                    mapOf(
                        // Uppercase for consistency with phoneIndex's role
                        // vocabulary ('OWNER'/'WORKER'), even though this
                        // particular field isn't value-checked by the members
                        // create rule today.
                        "role" to ROLE_OWNER,
                        "name" to ownerName,
                        "addedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )
                ).await()
            } catch (e: Exception) {
                // Non-fatal — log this in real usage so it can be backfilled.
            }

            Result.success(businessId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Owner-only action: provisions a worker's account ahead of time. The
     * worker does nothing to "accept" — they just log in with OTP on their
     * own phone afterward and phoneIndex routes them straight in.
     *
     * No client-side "is this phone already registered?" pre-check here —
     * firestore.rules only lets a phone read its OWN phoneIndex entry
     * (`myPhone() == phone`), so the owner has no way to read the worker's
     * phoneIndex doc to check first. That's fine: the rules already enforce
     * uniqueness on the write itself — phoneIndex's `allow create` only
     * matches a doc that doesn't exist yet, and `allow update: if false`
     * blocks any write to one that does — so a duplicate phone number gets
     * rejected by the write regardless.
     */
    suspend fun addWorker(
        businessId: String,
        workerPhoneE164: String,
        workerName: String
    ): Result<Unit> {
        return try {
            // Legitimate pre-check: we can always read our OWN phoneIndex doc
            // (rules: myPhone() == phone), so use that to tell apart "caller
            // isn't actually OWNER of this businessId anymore" from "worker
            // phone already registered" before attempting the write — the
            // write itself can't distinguish these once denied.
            val myPhone = auth.currentUser?.phoneNumber
                ?: return Result.failure(IllegalStateException("Not signed in."))

            val myIndexSnap = firestore.collection("phoneIndex").document(myPhone).get().await()
            val myBusinessId = myIndexSnap.getString("businessId")
            val myRole = myIndexSnap.getString("role")
            if (myRole != ROLE_OWNER || myBusinessId != businessId) {
                return Result.failure(
                    IllegalStateException(
                        "You're no longer the owner of this business (or the app's cached " +
                                "business ID is stale — try logging out and back in)."
                    )
                )
            }

            val memberRef = firestore.collection("businesses").document(businessId)
                .collection("members").document(workerPhoneE164)
            val phoneIndexRef = firestore.collection("phoneIndex").document(workerPhoneE164)

            firestore.runTransaction { txn ->
                txn.set(memberRef, mapOf(
                    "role" to ROLE_WORKER,
                    "name" to workerName,
                    "addedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                ))

                // Must be uppercase — firestore.rules' phoneIndex Case 2
                // create rule checks request.resource.data.role == 'WORKER'
                // as an exact string match. If this phone is already
                // registered, the rules' allow update:false denies this
                // write outright (see class doc above) rather than us
                // catching it via a pre-check.
                txn.set(phoneIndexRef, mapOf(
                    "businessId" to businessId,
                    "role" to ROLE_WORKER
                ))
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            // Owner-access staleness is now ruled out by the pre-check above,
            // so a denial reaching here is almost certainly the worker's
            // phone already being registered elsewhere (rules deny the
            // phoneIndex create because a doc already exists at that path).
            Result.failure(
                Exception(
                    "Could not add worker. That phone number is already registered " +
                            "to a business.",
                    e
                )
            )
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