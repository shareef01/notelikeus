package com.aus.notelikeus.ui.main.components

import org.jetbrains.compose.resources.StringResource
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode

fun viewModeLabelRes(mode: NoteViewMode): StringResource = when (mode) {
    NoteViewMode.GRID_2 -> Res.string.view_mode_grid_2
    NoteViewMode.GRID_3 -> Res.string.view_mode_grid_3
    NoteViewMode.GRID_4 -> Res.string.view_mode_grid_4
    NoteViewMode.GRID_5 -> Res.string.view_mode_grid_5
    NoteViewMode.LIST -> Res.string.view_mode_list
    NoteViewMode.COMPACT -> Res.string.view_mode_compact
}

fun sortOrderLabelRes(order: NoteSortOrder): StringResource = when (order) {
    NoteSortOrder.MANUAL -> Res.string.sort_manual
    NoteSortOrder.NEWEST -> Res.string.sort_newest
    NoteSortOrder.OLDEST -> Res.string.sort_oldest
}
