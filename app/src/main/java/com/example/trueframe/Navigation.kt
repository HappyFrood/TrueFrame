package com.example.trueframe

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.example.trueframe.ui.main.MainScreen
import com.example.trueframe.ui.editor.EditorScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryDecorators = listOf(
      rememberSaveableStateHolderNavEntryDecorator(),
      rememberViewModelStoreNavEntryDecorator(),
    ),
    transitionSpec = {
      slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(300)
      ) + fadeIn(animationSpec = tween(300)) togetherWith
        slideOutHorizontally(
          targetOffsetX = { -it },
          animationSpec = tween(300)
        ) + fadeOut(animationSpec = tween(300))
    },
    popTransitionSpec = {
      slideInHorizontally(
        initialOffsetX = { -it },
        animationSpec = tween(300)
      ) + fadeIn(animationSpec = tween(300)) togetherWith
        slideOutHorizontally(
          targetOffsetX = { it },
          animationSpec = tween(300)
        ) + fadeOut(animationSpec = tween(300))
    },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(onItemClick = { navKey -> backStack.add(navKey) })
        }
        entry<Editor> { editor ->
          EditorScreen(projectId = editor.projectId, onBack = { backStack.removeLastOrNull() })
        }
      },
  )
}
