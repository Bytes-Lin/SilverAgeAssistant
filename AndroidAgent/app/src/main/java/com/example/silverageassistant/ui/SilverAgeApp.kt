package com.example.silverageassistant.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Sos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.silverageassistant.ui.conversation.ConversationRoute
import com.example.silverageassistant.ui.family.FamilyContactsScreen
import com.example.silverageassistant.ui.family.FamilyHomeScreen
import com.example.silverageassistant.ui.home.ElderHomeScreen
import com.example.silverageassistant.ui.navigation.AppDestination
import com.example.silverageassistant.ui.onboarding.ElderSetupRoute
import com.example.silverageassistant.ui.onboarding.FamilySetupRoute
import com.example.silverageassistant.ui.onboarding.OnboardingViewModel
import com.example.silverageassistant.ui.placeholder.FeaturePlaceholderScreen
import com.example.silverageassistant.ui.reminders.ReminderRoute
import com.example.silverageassistant.ui.role.RoleSelectionScreen

@Composable
fun SilverAgeApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    onboardingViewModel: OnboardingViewModel = viewModel(),
) {
    val onboardingState by onboardingViewModel.uiState.collectAsState()
    NavHost(
        navController = navController,
        startDestination = AppDestination.RoleSelection.route,
        modifier = modifier,
    ) {
        composable(AppDestination.RoleSelection.route) {
            RoleSelectionScreen(
                isLoading = onboardingState.isRestoringProfiles,
                onElderSelected = {
                    navController.navigateOnce(AppDestination.ElderSetup)
                },
                onFamilySelected = { navController.navigateOnce(AppDestination.FamilySetup) },
            )
        }
        composable(AppDestination.ElderSetup.route) {
            ElderSetupRoute(
                onBack = { navController.popBackStack() },
                onCompleted = {
                    navController.navigate(AppDestination.ElderHome.route) {
                        popUpTo(AppDestination.RoleSelection.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                viewModel = onboardingViewModel,
            )
        }
        composable(AppDestination.FamilySetup.route) {
            val isEditing = navController.previousBackStackEntry?.destination?.route ==
                AppDestination.FamilyHome.route
            FamilySetupRoute(
                onBack = { navController.popBackStack() },
                onCompleted = {
                    if (isEditing) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(AppDestination.FamilyHome.route) {
                            popUpTo(AppDestination.RoleSelection.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
                viewModel = onboardingViewModel,
            )
        }
        composable(AppDestination.ElderHome.route) {
            ElderHomeScreen(
                elderName = onboardingState.elderDraft.displayName.trim(),
                onConversation = { navController.navigateOnce(AppDestination.Conversation) },
                onReminders = { navController.navigateOnce(AppDestination.Reminders) },
                onLifeAssistant = { navController.navigateOnce(AppDestination.LifeAssistant) },
                onFamilyContacts = { navController.navigateOnce(AppDestination.FamilyContacts) },
                onMusic = { navController.navigateOnce(AppDestination.Music) },
                onSos = { navController.navigateOnce(AppDestination.Sos) },
                onSettings = { navController.navigateOnce(AppDestination.Settings) },
            )
        }
        composable(AppDestination.FamilyHome.route) {
            FamilyHomeScreen(
                profile = onboardingState.familyDraft,
                bindingStatus = onboardingState.familyBindingStatus,
                onEditProfile = { navController.navigateOnce(AppDestination.FamilySetup) },
            )
        }
        composable(AppDestination.Conversation.route) {
            ConversationRoute(onBack = { navController.popBackStack() })
        }
        composable(AppDestination.Reminders.route) {
            ReminderRoute(
                onBack = { navController.popBackStack() },
                onContactFamily = { navController.navigateOnce(AppDestination.FamilyContacts) },
            )
        }
        composable(AppDestination.FamilyContacts.route) {
            FamilyContactsScreen(onBack = { navController.popBackStack() })
        }
        composable(AppDestination.LifeAssistant.route) {
            FeaturePlaceholderScreen(
                title = "生活助手",
                message = "以后可以在这里用语音查询生活信息，并在确认后协助完成操作。",
                icon = Icons.Rounded.ShoppingBag,
                onBack = { navController.popBackStack() },
            )
        }
        composable(AppDestination.Music.route) {
            FeaturePlaceholderScreen(
                title = "听音乐",
                message = "以后可以在这里播放手机中已授权的本地音乐，断网时也能使用。",
                icon = Icons.Rounded.MusicNote,
                onBack = { navController.popBackStack() },
            )
        }
        composable(AppDestination.Sos.route) {
            FeaturePlaceholderScreen(
                title = "紧急求助",
                message = "紧急求助的本地拨号、短信和取消倒计时将在安全里程碑中接入。",
                icon = Icons.Rounded.Sos,
                importantMessage = "如现在需要急救，请直接拨打 120。",
                onBack = { navController.popBackStack() },
            )
        }
        composable(AppDestination.Settings.route) {
            FeaturePlaceholderScreen(
                title = "设置",
                message = "以后可以在这里调整角色、语音和模型服务。模型密钥只会加密保存在老人设备中。",
                icon = Icons.Rounded.Settings,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private fun NavHostController.navigateOnce(destination: AppDestination) {
    navigate(destination.route) { launchSingleTop = true }
}
