package com.dafeiyu.mobile.data

/** Computes effective behavior without mutating any user-selected effects. */
object PowerSavingPolicy {
    data class Effective(
        val glassMaterial: Boolean,
        val pageTransitions: Boolean,
        val springButtons: Boolean,
        val reduceTransparency: Boolean,
        val reduceMotion: Boolean,
        val effectsLocked: Boolean,
        val persistentConnectionsAllowed: Boolean,
        val automaticDownloadsAllowed: Boolean,
        val pushNotificationsAllowed: Boolean = true,
    )

    fun resolve(
        powerSavingMode: Boolean,
        glassMaterial: Boolean,
        pageTransitions: Boolean,
        springButtons: Boolean,
        appInForeground: Boolean = true,
        reduceTransparency: Boolean = false,
        reduceMotion: Boolean = false,
    ): Effective {
        val effectiveGlass = glassMaterial && !powerSavingMode && !reduceTransparency
        return Effective(
            glassMaterial = effectiveGlass,
            pageTransitions = pageTransitions && !powerSavingMode && !reduceMotion,
            springButtons = springButtons && !powerSavingMode && !reduceMotion,
            reduceTransparency = reduceTransparency || !effectiveGlass,
            reduceMotion = reduceMotion || powerSavingMode,
            effectsLocked = powerSavingMode,
            persistentConnectionsAllowed = !powerSavingMode || appInForeground,
            automaticDownloadsAllowed = !powerSavingMode || appInForeground,
        )
    }
}
