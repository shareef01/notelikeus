package com.aus.notelikeus.data.remote



import com.aus.notelikeus.domain.model.ChecklistItem

import com.aus.notelikeus.domain.model.Label

import com.aus.notelikeus.domain.model.Note



internal fun Note.toCloudMap(): Map<String, Any?> = buildMap {

    put("localId", id)

    put("title", title)

    put("content", content)

    put("timestamp", timestamp)

    put("color", color)

    put("isPinned", isPinned)

    put("isArchived", isArchived)

    put("isTrashed", isTrashed)

    put("position", position)

    put("isLocked", isLocked)

    put("reminderTimestamp", reminderTimestamp)

    put(

        "labels",

        labels.map { label ->

            mapOf("name" to label.name)

        }

    )

    put(

        "checklist",

        checklist.map { item -> item.toCloudMap() }

    )

}



@Suppress("UNCHECKED_CAST")

internal suspend fun Map<String, Any?>.toCloudNote(

    noteId: Long,

    resolveLabel: suspend (String) -> Label

): Note {

    val rawLabels = (this["labels"] as? List<Map<String, Any?>>).orEmpty()

    val labels = mutableListOf<Label>()

    for (entry in rawLabels) {

        val name = (entry["name"] as? String)?.trim()

        if (!name.isNullOrEmpty()) {

            labels.add(resolveLabel(name))

        }

    }



    val checklist = (this["checklist"] as? List<Map<String, Any?>>)

        ?.mapIndexed { index, item ->

            ChecklistItem(

                text = item["text"] as? String ?: "",

                isChecked = item["isChecked"] as? Boolean ?: false,

                position = (item["position"] as? Number)?.toInt() ?: index

            )

        }

        .orEmpty()



    return Note(

        id = noteId,

        title = this["title"] as? String ?: "",

        content = this["content"] as? String ?: "",

        timestamp = (this["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),

        color = (this["color"] as? Number)?.toInt() ?: 0,

        isPinned = this["isPinned"] as? Boolean ?: false,

        isArchived = this["isArchived"] as? Boolean ?: false,

        isTrashed = this["isTrashed"] as? Boolean ?: false,

        position = (this["position"] as? Number)?.toInt() ?: 0,

        isLocked = this["isLocked"] as? Boolean ?: false,

        reminderTimestamp = (this["reminderTimestamp"] as? Number)?.toLong(),

        labels = labels,

        attachments = emptyList(),

        checklist = checklist

    )

}



private fun ChecklistItem.toCloudMap(): Map<String, Any> = mapOf(

    "text" to text,

    "isChecked" to isChecked,

    "position" to position

)



internal fun Note.isCloudSyncEligible(): Boolean = !isLocked

internal fun syncMetaMap(noteCount: Int): Map<String, Any> = mapOf(

    "lastSyncAt" to System.currentTimeMillis(),

    "noteCount" to noteCount,

    "platform" to "android"

)

