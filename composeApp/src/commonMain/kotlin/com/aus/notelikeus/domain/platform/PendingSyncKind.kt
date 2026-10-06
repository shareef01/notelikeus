package com.aus.notelikeus.domain.platform

/**
 * The three delayed cloud writes a queued command can ask for.
 *
 * A domain type because [SyncCoordinator] is a domain contract and both of its families take one:
 * the queue itself (`data.sync`) names the kind it is storing, and the *callers* have to say which
 * semantic they mean. It lives here rather than beside the queue so the domain does not have to
 * reach into infrastructure for a value it names in its own API.
 */
enum class PendingSyncKind { UPLOAD, DELETE, RESTORE }
