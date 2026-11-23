package com.metrolist.core.api.dc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DcPairingRulesTest {
    private val link = "link:abc123abc123"
    private val view = PairingView(pairing = mapOf("r1" to "123456", "quiet" to ""), linkId = link)

    @Test
    fun acceptBeforeConfirmIsSasUnconfirmed() {
        val step = DcPairingRules.accept(emptySet(), view, "r1")
        assertEquals("bad_request", step.errorCode)
        assertTrue(step.errorMessage!!.startsWith("sas_unconfirmed"))
        assertNull(step.effect)
    }

    @Test
    fun confirmThenAcceptApproves() {
        val confirm = DcPairingRules.confirmSas(emptySet(), view, "r1", match = true)
        assertNull(confirm.errorCode)
        assertNull(confirm.effect, "confirming does not approve yet")
        assertEquals(setOf("r1"), confirm.confirmed)

        val accept = DcPairingRules.accept(confirm.confirmed, view, "r1")
        assertNull(accept.errorCode)
        assertEquals(PairingEffect.Approve("r1"), accept.effect)
        assertTrue(accept.confirmed.isEmpty())
    }

    @Test
    fun matchFalseRejectsAndForgetsTheConfirmation() {
        val step = DcPairingRules.confirmSas(setOf("r1"), view, "r1", match = false)
        assertEquals(PairingEffect.Reject("r1"), step.effect)
        assertTrue(step.confirmed.isEmpty())
    }

    @Test
    fun rejectTwiceIsAlwaysOk() {
        val first = DcPairingRules.reject(setOf("r1"), view, "r1")
        assertNull(first.errorCode)
        assertEquals(PairingEffect.Reject("r1"), first.effect)
        assertTrue(first.confirmed.isEmpty())
        val second = DcPairingRules.reject(first.confirmed, PairingView(emptyMap(), null), "r1")
        assertNull(second.errorCode)
    }

    @Test
    fun unknownIdsAreNotFoundExceptForReject() {
        assertEquals("not_found", DcPairingRules.accept(emptySet(), view, "nope").errorCode)
        assertEquals("not_found", DcPairingRules.confirmSas(emptySet(), view, "nope", true).errorCode)
        assertEquals("not_found", DcPairingRules.accept(emptySet(), view, "link:ffffffffffff").errorCode)
        assertNull(DcPairingRules.reject(emptySet(), view, "nope").errorCode)
    }

    @Test
    fun requestWithoutSasAcceptsDirectly() {
        val step = DcPairingRules.accept(emptySet(), view, "quiet")
        assertNull(step.errorCode)
        assertEquals(PairingEffect.Approve("quiet"), step.effect)
    }

    @Test
    fun linkOffersAcceptWithoutSasAndRejectDismisses() {
        assertEquals(PairingEffect.AcceptLink, DcPairingRules.accept(emptySet(), view, link).effect)
        assertEquals(PairingEffect.DismissLink, DcPairingRules.reject(emptySet(), view, link).effect)
        assertEquals(PairingEffect.DismissLink, DcPairingRules.confirmSas(emptySet(), view, link, match = false).effect)
        val ok = DcPairingRules.confirmSas(emptySet(), view, link, match = true)
        assertNull(ok.errorCode)
        assertNull(ok.effect)
        // Rejecting a stale link id does nothing.
        assertNull(DcPairingRules.reject(emptySet(), PairingView(emptyMap(), null), link).effect)
    }

    @Test
    fun staleConfirmationsArePruned() {
        val step = DcPairingRules.confirmSas(setOf("gone"), view, "r1", match = true)
        assertEquals(setOf("r1"), step.confirmed)
    }
}
