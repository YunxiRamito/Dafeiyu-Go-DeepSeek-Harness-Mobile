@file:Suppress("UNUSED_PARAMETER")

package com.dafeiyu.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Compile-only screen/Sidebar boundaries owned by other agents. */
class SettingsNavigationState
class WorkspaceSidebarMorphState
val LocalSettingsNavigationState = staticCompositionLocalOf<SettingsNavigationState?> { null }
val LocalPageHeaderInset = staticCompositionLocalOf { 0.dp }

@Composable
fun rememberSettingsNavigationState() = remember { SettingsNavigationState() }

fun Modifier.workspaceSidebarMorphAnchor(state: WorkspaceSidebarMorphState): Modifier = this

@Composable
fun TitleMorphHeader(state: SettingsNavigationState, x: Float, y: Float, modifier: Modifier) = Unit
