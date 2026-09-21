package com.carequeue.plus.navigation

sealed class Screen(val route: String) {
    // Auth screens
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Register : Screen("register")

    // Customer screens
    object CustomerHome : Screen("customer_home")
    object ServiceDetails : Screen("service_details/{businessId}/{queueId}") {
        fun createRoute(businessId: String, queueId: String) = "service_details/$businessId/$queueId"
    }
    object MyQueue : Screen("my_queue/{queueId}/{userId}") {
        fun createRoute(queueId: String, userId: String) = "my_queue/$queueId/$userId"
    }
    object QueueHistory : Screen("queue_history/{userId}") {
        fun createRoute(userId: String) = "queue_history/$userId"
    }

    // Admin screens
    object AdminDashboard : Screen("admin_dashboard/{userId}") {
        fun createRoute(userId: String) = "admin_dashboard/$userId"
    }
    object QueueManagement : Screen("queue_management/{queueId}") {
        fun createRoute(queueId: String) = "queue_management/$queueId"
    }
    object Analytics : Screen("analytics/{userId}") {
        fun createRoute(userId: String) = "analytics/$userId"
    }

    // Common
    object Settings : Screen("settings/{userId}") {
        fun createRoute(userId: String) = "settings/$userId"
    }
}
