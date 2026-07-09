package com.aus.notelikeus.data.backup



import android.content.Context

import com.aus.notelikeus.BuildConfig

import com.aus.notelikeus.R

import com.aus.notelikeus.domain.model.ChecklistItem

import com.aus.notelikeus.domain.model.Label

import com.aus.notelikeus.domain.model.Note

import com.aus.notelikeus.domain.repository.NoteRepository

import dagger.hilt.android.qualifiers.ApplicationContext

import org.json.JSONArray

import org.json.JSONObject

import javax.inject.Inject

import javax.inject.Singleton



@Singleton

class NoteBackupExporter @Inject constructor(

    private val repository: NoteRepository,

    @ApplicationContext private val context: Context

) {

    suspend fun createJson(): String {

        val notes = repository.getAllNotesForBackup()

        val labels = repository.getAllLabelsSnapshot()



        val root = JSONObject()

        root.put("version", BACKUP_VERSION)

        root.put("exportedAt", System.currentTimeMillis())

        root.put("app", context.getString(R.string.app_name))

        root.put("appVersion", BuildConfig.VERSION_NAME)



        root.put("labels", JSONArray().apply {

            labels.forEach { put(it.toJson()) }

        })



        root.put("notes", JSONArray().apply {

            notes.forEach { put(it.toJson()) }

        })



        return root.toString(2)

    }



    private fun Label.toJson(): JSONObject = JSONObject().apply {

        put("id", id)

        put("name", name)

    }



    private fun Note.toJson(): JSONObject = JSONObject().apply {

        put("id", id)

        put("title", title)

        put("content", content)

        put("timestamp", timestamp)

        put("color", color)

        put("isPinned", isPinned)

        put("isArchived", isArchived)

        put("isTrashed", isTrashed)

        put("position", position)

        put("isLocked", isLocked)

        reminderTimestamp?.let { put("reminderTimestamp", it) }

        put("labels", JSONArray().apply { labels.forEach { put(it.name) } })

        put("checklist", JSONArray().apply { checklist.forEach { put(it.toJson()) } })

    }



    private fun ChecklistItem.toJson(): JSONObject = JSONObject().apply {

        put("text", text)

        put("isChecked", isChecked)

        put("position", position)

    }



    companion object {

        const val BACKUP_VERSION = 3

        const val BACKUP_MIME_TYPE = "application/json"

        const val BACKUP_FILE_PREFIX = "notelikeus_backup"

    }

}

