package com.aus.notelikeus.ui.theme

/**
 * Shared chrome alphas so borders, washes, and dividers feel consistent
 * across drawer, top bar, filters, cards, and settings.
 */
object Chrome {
    const val Hairline = 0.28f
    const val Divider = 0.4f
    const val ChipBorder = 0.35f
    const val SelectedBorder = 0.45f
    const val SelectedWash = 0.1f
    const val SoftWash = 0.08f
    const val PressWash = 0.05f
    const val CardHairline = 0.18f

    /**
     * Content of a control that cannot be used.
     *
     * Below the 4.5:1 body-text minimum on purpose -- WCAG exempts disabled controls, and the
     * whole job of this tier is to read as unavailable at a glance. Matches what the filter chips
     * already used before it had a name.
     */
    const val Disabled = 0.55f
}
