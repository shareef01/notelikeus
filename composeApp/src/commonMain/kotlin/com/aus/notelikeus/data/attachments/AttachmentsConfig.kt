package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.data.remote.BackendConfig

fun isR2AttachmentsEnabled(): Boolean = BackendConfig.attachmentsWorkerUrl.isNotBlank()
