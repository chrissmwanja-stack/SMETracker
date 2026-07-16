package com.example.smetracker.utils

import com.google.firebase.firestore.FirebaseFirestore

/**
 * Generates String IDs for local entities that are also destined for Firestore.
 *
 * Firestore auto-IDs are generated entirely client-side (a random 20-char ID) —
 * calling `.document()` with no path does NOT touch the network. This lets us
 * mint an ID the moment a record is created locally (offline-safe) and use that
 * same ID later when the row is pushed to Firestore, so a row never needs to
 * change its primary key after creation.
 *
 * Using a single throwaway collection reference ("_id_gen") is fine — the
 * collection is never actually written to; we only use its ID generator.
 */
object IdGenerator {
    private val idGenCollection by lazy {
        FirebaseFirestore.getInstance().collection("_id_gen")
    }

    fun newId(): String = idGenCollection.document().id
}