package com.metrolist.core.api.dc

import com.metrolist.shared.deviceconnect.DeviceConnectManager
import java.security.MessageDigest

/** `dc.setPort` range: 1024..65535 and an integer. The manager also allows 0 (system-chosen), the API does not expose it. */
object DcPortRange {
    fun parse(value: Double?): Int? {
        if (value == null || value.isNaN() || value.isInfinite() || value != Math.floor(value)) return null
        val port = value.toLong()
        return if (port in 1024L..65535L) port.toInt() else null
    }
}

/** Id of the request raised by a pairing-link offer: `link:` + the first 12 hex chars of sha1(address). */
fun linkRequestId(address: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(address.toByteArray(Charsets.UTF_8))
    return "link:" + digest.joinToString("") { "%02x".format(it) }.take(12)
}

const val LINK_REQUEST_PREFIX = "link:"

/** What the manager currently asks the user about: pairing requests (id to sas) and at most one link offer. */
data class PairingView(
    val pairing: Map<String, String>,
    val linkId: String?,
) {
    companion object {
        fun of(state: DeviceConnectManager.State): PairingView =
            PairingView(
                pairing = state.pairingRequests.associate { it.requestId to it.sas },
                linkId = state.linkOffer?.let { linkRequestId(it.address) },
            )
    }
}

/** Side effect a rule asks the caller to perform on the manager. */
sealed interface PairingEffect {
    data class Approve(
        val requestId: String,
    ) : PairingEffect

    data class Reject(
        val requestId: String,
    ) : PairingEffect

    data object AcceptLink : PairingEffect

    data object DismissLink : PairingEffect
}

/** Result of one rule: the new confirmed set, then either an error (api code and text) or an optional effect. */
data class PairingStep(
    val confirmed: Set<String>,
    val effect: PairingEffect? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

/**
 * The M3-4b contract is "accept is valid only after `confirmSas {match:true}`", but the manager has no separate SAS step
 * (`approvePairing` already means "the codes match"). So the core keeps a `confirmed` set; these pure rules decide what
 * each call does and what the set becomes. Stale ids (no longer requested) are pruned on every call.
 */
object DcPairingRules {
    private fun known(
        view: PairingView,
        id: String,
    ) = id in view.pairing || id == view.linkId

    private fun prune(
        confirmed: Set<String>,
        view: PairingView,
    ): Set<String> = confirmed.filterTo(HashSet()) { known(view, it) }

    private fun notFound(confirmed: Set<String>) = PairingStep(confirmed, errorCode = "not_found", errorMessage = "request not found")

    fun confirmSas(
        confirmed: Set<String>,
        view: PairingView,
        requestId: String,
        match: Boolean,
    ): PairingStep {
        val live = prune(confirmed, view)
        if (!known(view, requestId)) return notFound(live)
        val isLink = requestId == view.linkId && requestId !in view.pairing
        return if (match) {
            // Confirming does not approve: the user still has to accept. A link offer has no code to compare.
            PairingStep(if (isLink) live else live + requestId)
        } else {
            PairingStep(live - requestId, if (isLink) PairingEffect.DismissLink else PairingEffect.Reject(requestId))
        }
    }

    fun accept(
        confirmed: Set<String>,
        view: PairingView,
        requestId: String,
    ): PairingStep {
        val live = prune(confirmed, view)
        if (requestId == view.linkId && requestId !in view.pairing) return PairingStep(live, PairingEffect.AcceptLink)
        if (requestId.startsWith(LINK_REQUEST_PREFIX) && requestId !in view.pairing) return notFound(live)
        val sas = view.pairing[requestId] ?: return notFound(live)
        if (sas.isNotEmpty() && requestId !in live) {
            return PairingStep(live, errorCode = "bad_request", errorMessage = "sas_unconfirmed: confirm the code first")
        }
        return PairingStep(live - requestId, PairingEffect.Approve(requestId))
    }

    /** Idempotent: never an error, an unknown id is a no-op. */
    fun reject(
        confirmed: Set<String>,
        view: PairingView,
        requestId: String,
    ): PairingStep {
        val live = prune(confirmed, view) - requestId
        val effect =
            when {
                requestId.startsWith(LINK_REQUEST_PREFIX) -> if (requestId == view.linkId) PairingEffect.DismissLink else null
                else -> PairingEffect.Reject(requestId)
            }
        return PairingStep(live, effect)
    }
}

/** A request the UI is asked about (`dc.request` event). */
data class DcRequestEvent(
    val requestId: String,
    val peer: String,
    val sas: String,
)

/** Emits each pairing request and link offer once (per subscription), also those already pending at subscribe time. */
class DcRequestDiff {
    private val seen = HashSet<String>()

    fun next(state: DeviceConnectManager.State): List<DcRequestEvent> {
        val out = ArrayList<DcRequestEvent>()
        val live = HashSet<String>()
        for (request in state.pairingRequests) {
            if (!request.requiresApproval) continue
            live += request.requestId
            if (seen.add(request.requestId)) out += DcRequestEvent(request.requestId, request.deviceName, request.sas)
        }
        state.linkOffer?.let { offer ->
            val id = linkRequestId(offer.address)
            live += id
            if (seen.add(id)) out += DcRequestEvent(id, offer.deviceName, "")
        }
        seen.retainAll(live)
        return out
    }
}
