package com.aus.notelikeus.util

import android.content.Intent
import com.aus.notelikeus.ui.navigation.InternalNavigationToken

/** Opaque token extra — do not treat a public boolean as proof of same-app origin. */
const val EXTRA_INTERNAL_NAV_TOKEN = "com.aus.notelikeus.INTERNAL_NAV_TOKEN"

/** @deprecated Prefer [EXTRA_INTERNAL_NAV_TOKEN]; kept only so old code paths compile. */
@Deprecated("Use EXTRA_INTERNAL_NAV_TOKEN")
const val EXTRA_INTERNAL_NAV = "com.aus.notelikeus.INTERNAL_NAV"

fun Intent.markInternalNavigation(): Intent =
    putExtra(EXTRA_INTERNAL_NAV_TOKEN, InternalNavigationToken.current())
