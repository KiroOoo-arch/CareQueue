package com.carequeue.plus.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.carequeue.plus.ui.auth.LoginScreen
import com.carequeue.plus.ui.auth.RegisterScreen
import com.carequeue.plus.ui.auth.SplashScreen
import com.carequeue.plus.ui.customer.CustomerHomeScreen
import com.carequeue.plus.ui.customer.MyQueueScreen
import com.carequeue.plus.ui.customer.QueueHistoryScreen
import com.carequeue.plus.ui.customer.ServiceDetailsScreen
import com.carequeue.plus.ui.admin.AdminDashboardScreen
import com.carequeue.plus.ui.admin.AnalyticsScreen
import com.carequeue.plus.ui.admin.QueueManagementScreen
import com.carequeue.plus.ui.customer.SettingsScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Splash.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Auth screens
        composable(Screen.Splash.route) {
            SplashScreen(
                onNavigateToLogin = { navController.navigate(Screen.Login.route) },
                onNavigateToCustomerHome = { userId ->
                    navController.navigate(Screen.CustomerHome.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                },
                onNavigateToAdminDashboard = { userId ->
                    navController.navigate(Screen.AdminDashboard.createRoute(userId)) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Login.route) {
            LoginScreen(
                onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                onLoginSuccess = { user ->
                    if (user.isAdmin) {
                        navController.navigate(Screen.AdminDashboard.createRoute(user.uid)) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Screen.CustomerHome.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Screen.Register.route) {
            RegisterScreen(
                onNavigateBack = { navController.popBackStack() },
                onRegisterSuccess = { user ->
                    if (user.isAdmin) {
                        navController.navigate(Screen.AdminDashboard.createRoute(user.uid)) {
                            popUpTo(Screen.Register.route) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Screen.CustomerHome.route) {
                            popUpTo(Screen.Register.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        // Customer screens
        composable(Screen.CustomerHome.route) {
            CustomerHomeScreen(
                onNavigateToServiceDetails = { businessId, queueId ->
                    navController.navigate(Screen.ServiceDetails.createRoute(businessId, queueId))
                },
                onNavigateToHistory = { userId ->
                    navController.navigate(Screen.QueueHistory.createRoute(userId))
                },
                onNavigateToSettings = { userId ->
                    navController.navigate(Screen.Settings.createRoute(userId))
                },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.ServiceDetails.route,
            arguments = listOf(
                navArgument("businessId") { type = NavType.StringType },
                navArgument("queueId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val businessId = backStackEntry.arguments?.getString("businessId") ?: ""
            val queueId = backStackEntry.arguments?.getString("queueId") ?: ""
            ServiceDetailsScreen(
                businessId = businessId,
                queueId = queueId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToMyQueue = { qId, userId ->
                    navController.navigate(Screen.MyQueue.createRoute(qId, userId))
                }
            )
        }

        composable(
            route = Screen.MyQueue.route,
            arguments = listOf(
                navArgument("queueId") { type = NavType.StringType },
                navArgument("userId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val queueId = backStackEntry.arguments?.getString("queueId") ?: ""
            val userId = backStackEntry.arguments?.getString("userId") ?: ""
            MyQueueScreen(
                queueId = queueId,
                userId = userId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.QueueHistory.route,
            arguments = listOf(
                navArgument("userId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId") ?: ""
            QueueHistoryScreen(
                userId = userId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Admin screens
        composable(
            route = Screen.AdminDashboard.route,
            arguments = listOf(
                navArgument("userId") { type = NavType.StringType }
            )
        ) {
            AdminDashboardScreen(
                onNavigateToQueueManagement = { queueId ->
                    navController.navigate(Screen.QueueManagement.createRoute(queueId))
                },
                onNavigateToAnalytics = { userId ->
                    navController.navigate(Screen.Analytics.createRoute(userId))
                },
                onNavigateToSettings = { userId ->
                    navController.navigate(Screen.Settings.createRoute(userId))
                },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.QueueManagement.route,
            arguments = listOf(
                navArgument("queueId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val queueId = backStackEntry.arguments?.getString("queueId") ?: ""
            QueueManagementScreen(
                queueId = queueId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Analytics.route,
            arguments = listOf(
                navArgument("userId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val adminId = backStackEntry.arguments?.getString("userId") ?: ""
            AnalyticsScreen(
                adminId = adminId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Common screens
        composable(
            route = Screen.Settings.route,
            arguments = listOf(
                navArgument("userId") { type = NavType.StringType }
            )
        ) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
