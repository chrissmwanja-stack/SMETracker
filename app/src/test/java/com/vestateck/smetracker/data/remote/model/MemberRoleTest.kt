package com.vestateck.smetracker.data.remote.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemberRoleTest {

    @Test
    fun `fromString parses lowercase owner`() {
        assertEquals(MemberRole.OWNER, MemberRole.fromString("owner"))
    }

    @Test
    fun `fromString parses uppercase OWNER`() {
        assertEquals(MemberRole.OWNER, MemberRole.fromString("OWNER"))
    }

    @Test
    fun `fromString parses mixed case Worker`() {
        assertEquals(MemberRole.WORKER, MemberRole.fromString("Worker"))
    }

    @Test
    fun `fromString returns null for unrecognized value`() {
        assertNull(MemberRole.fromString("admin"))
    }

    @Test
    fun `fromString returns null for blank string`() {
        assertNull(MemberRole.fromString(""))
    }

    @Test
    fun `enum name matches the exact uppercase string firestore rules expect`() {
        // firestore.rules does an exact string match against 'OWNER'/'WORKER'
        // (see BusinessRepository's comments on ROLE_OWNER/ROLE_WORKER) — if
        // this enum's constant names ever changed, every write in
        // BusinessRepository would silently start failing security rules.
        assertEquals("OWNER", MemberRole.OWNER.name)
        assertEquals("WORKER", MemberRole.WORKER.name)
    }
}