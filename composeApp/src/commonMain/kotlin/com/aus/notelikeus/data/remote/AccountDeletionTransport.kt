package com.aus.notelikeus.data.remote

import kotlinx.coroutines.CancellationException

/**
 * Asks the server to delete the signed-in account.
 *
 * Returns once the server has accepted the request, whether it has finished or left the rest to its
 * sweep (the account's notes are gone either way). Throws otherwise, and then nothing was deleted.
 * See `supabase/migrations/20261102010000_account_deletion.sql` for the protocol.
 */
interface AccountDeletionTransport {
    suspend fun deleteAccount()
}

class WorkerAccountDeletionTransport(
    workerBaseUrl: String,
    private val accessTokenProvider: SupabaseAccessTokenProvider,
    private val send: suspend (url: String, accessToken: String) -> Unit = ::attachmentWorkerDelete,
    /**
     * A read that raises the database's NL003 when the account is being deleted, and returns
     * normally while it is live (`get_sync_generation`). Used only to settle an unclear outcome.
     */
    private val checkAccountLive: (suspend () -> Unit)? = null,
) : AccountDeletionTransport {
    private val url = "${workerBaseUrl.trimEnd('/')}/v1/account"

    override suspend fun deleteAccount() {
        val token = accessTokenProvider.accessToken()
            ?: throw SupabaseTransportException("account", 401, "missing access token")
        try {
            retryOnAttachmentRateLimit { send(url, token) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (answered: SupabaseTransportException) {
            // A 4xx is the Worker refusing before anything was deleted. A 5xx may have come after the
            // database accepted the request.
            if (answered.statusCode in 400..499) throw answered
            settleUnclearOutcome(answered)
        } catch (failure: Exception) {
            // A lost connection or a timeout: the request may have got through.
            settleUnclearOutcome(failure)
        }
    }

    /**
     * AUD-010: returns when the account turns out to be deleted after all, rethrows [failure] when it
     * is still live (then nothing was deleted, as the message says), and throws
     * [AccountDeletionOutcomeUnknownException] when even that cannot be told.
     */
    private suspend fun settleUnclearOutcome(failure: Exception) {
        val check = checkAccountLive ?: throw AccountDeletionOutcomeUnknownException(failure)
        try {
            check()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (checkFailure: Exception) {
            if (checkFailure.isAccountDeletedRefusal()) return
            throw AccountDeletionOutcomeUnknownException(failure)
        }
        throw failure
    }
}

/**
 * Account deletion failed in a way that does not say whether the server accepted it, and asking the
 * server did not settle it either. The account may be deleted; if it was, this device learns so (NL003)
 * at its next sync and removes its copy then.
 */
class AccountDeletionOutcomeUnknownException(cause: Throwable) :
    Exception("Could not confirm whether the account was deleted", cause)

/** A build with no attachments Worker has nothing that holds the key to delete an auth user. */
object UnavailableAccountDeletionTransport : AccountDeletionTransport {
    override suspend fun deleteAccount() {
        error("Account deletion is not available in this build.")
    }
}
