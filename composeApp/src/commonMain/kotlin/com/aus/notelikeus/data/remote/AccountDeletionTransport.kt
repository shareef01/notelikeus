package com.aus.notelikeus.data.remote

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
) : AccountDeletionTransport {
    private val url = "${workerBaseUrl.trimEnd('/')}/v1/account"

    override suspend fun deleteAccount() {
        val token = accessTokenProvider.accessToken()
            ?: throw SupabaseTransportException("account", 401, "missing access token")
        retryOnAttachmentRateLimit { send(url, token) }
    }
}

/** A build with no attachments Worker has nothing that holds the key to delete an auth user. */
object UnavailableAccountDeletionTransport : AccountDeletionTransport {
    override suspend fun deleteAccount() {
        error("Account deletion is not available in this build.")
    }
}
