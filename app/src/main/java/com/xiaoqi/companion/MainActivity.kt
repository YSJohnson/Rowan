package com.xiaoqi.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import com.xiaoqi.companion.feature.chat.RowanHomeScreen
import com.xiaoqi.companion.feature.chat.RowanMotion
import com.xiaoqi.companion.feature.chat.ChatScreen
import com.xiaoqi.companion.feature.chat.ChatViewModel
import com.xiaoqi.companion.feature.chat.MemoryRoomScreen
import com.xiaoqi.companion.feature.chat.SettingsScreen
import com.xiaoqi.companion.ui.theme.CompanionTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CompanionTheme {
                RowanAppNavHost(viewModel = chatViewModel)
            }
        }
    }
}

private object RowanRoutes {
    const val Home = "home"
    const val Chat = "chat"
    const val Settings = "settings"
    const val MemoryRoom = "memory-room"
}

@Composable
private fun RowanAppNavHost(
    viewModel: ChatViewModel,
    navController: NavHostController = rememberNavController(),
) {
    val slideIn = slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = tween(RowanMotion.MediumMs),
    ) + fadeIn(animationSpec = tween(RowanMotion.ShortMs))
    val slideOut = slideOutHorizontally(
        targetOffsetX = { -it / 3 },
        animationSpec = tween(RowanMotion.MediumMs),
    ) + fadeOut(animationSpec = tween(RowanMotion.ShortMs))
    val popSlideIn = slideInHorizontally(
        initialOffsetX = { -it },
        animationSpec = tween(RowanMotion.MediumMs),
    ) + fadeIn(animationSpec = tween(RowanMotion.ShortMs))
    val popSlideOut = slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = tween(RowanMotion.MediumMs),
    ) + fadeOut(animationSpec = tween(RowanMotion.ShortMs))

    NavHost(
        navController = navController,
        startDestination = RowanRoutes.Home,
        enterTransition = { slideIn },
        exitTransition = { slideOut },
        popEnterTransition = { popSlideIn },
        popExitTransition = { popSlideOut },
    ) {
        composable(
            route = RowanRoutes.Home,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            RowanHomeScreen(
                viewModel = viewModel,
                onOpenChat = { navController.navigate(RowanRoutes.Chat) },
                onOpenSettings = {
                    viewModel.prepareSettings()
                    navController.navigate(RowanRoutes.Settings)
                },
                onOpenMemoryRoom = { navController.navigate(RowanRoutes.MemoryRoom) },
            )
        }
        composable(RowanRoutes.Chat) {
            ChatScreen(
                viewModel = viewModel,
                onOpenMemoryRoom = { navController.navigate(RowanRoutes.MemoryRoom) },
                onOpenSettings = {
                    viewModel.prepareSettings()
                    navController.navigate(RowanRoutes.Settings)
                },
            )
        }
        composable(RowanRoutes.Settings) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(RowanRoutes.MemoryRoom) {
            MemoryRoomScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
