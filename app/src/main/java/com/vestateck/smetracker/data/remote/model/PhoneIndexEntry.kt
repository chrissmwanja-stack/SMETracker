package com.example.smetracker.data.remote.model

// Firestore path: phoneIndex/{phoneNumberE164}  (top-level collection, not nested under a business)
//
// Purpose: after a phone number completes Firebase Phone Auth, the app needs to know
// which business it belongs to and what role it has — without a server-side function.
// A security rule allows a verified phone number to read ONLY its own entry here
// (request.auth.token.phone_number == resource.id), which is what makes this safe
// without a Cloud Function minting custom claims.
data class PhoneIndexEntry(
    val businessId: String = "",
    val role: MemberRole = MemberRole.WORKER
)