package com.carequeue.plus.data.model

data class User(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = ROLE_CUSTOMER,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_CUSTOMER = "customer"
        const val ROLE_ADMIN = "admin"
    }

    val isAdmin: Boolean get() = role == ROLE_ADMIN
}
