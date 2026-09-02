package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.remote.BackendConfig
import com.aus.notelikeus.data.remote.RemoteBackend

fun isR2AttachmentsEnabled(): Boolean =
    BackendConfig.remoteBackend == RemoteBackend.SUPABASE &&
        BackendConfig.attachmentsWorkerUrl.isNotBlank()
