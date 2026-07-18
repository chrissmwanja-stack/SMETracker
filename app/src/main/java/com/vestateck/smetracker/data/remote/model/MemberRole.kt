package com.vestateck.smetracker.data.remote.model

enum class MemberRole {
    OWNER,
    WORKER;

    companion object {
        fun fromString(value: String): MemberRole? = when (value.lowercase()) {
            "owner" -> OWNER
            "worker" -> WORKER
            else -> null
        }
    }
}
