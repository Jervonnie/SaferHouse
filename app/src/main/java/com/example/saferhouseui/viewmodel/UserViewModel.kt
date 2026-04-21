package com.example.saferhouseui.viewmodel

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.saferhouseui.ElderlyMember
import com.example.saferhouseui.UserProfile

class UserViewModel : ViewModel() {
    // In-memory list of users
    val users = mutableStateListOf<UserProfile>(
        UserProfile(
            "caretaker@demo.com",
            "password123",
            "helper",
            "Juan Dela Cruz",
            "Brgy. New Era, Quezon City",
            "09123456789",
            mutableListOf(ElderlyMember("1", "Lolo Mao", "0912-345-6789", 82, "Safe", "Just now"))
        )
    )

    // Current session state
    var currentUserEmail by mutableStateOf("")
        private set

    val currentUser: UserProfile?
        get() = users.find { it.email == currentUserEmail }

    fun login(email: String, password: String): Boolean {
        val user = users.find { it.email == email && it.password == password }
        return if (user != null) {
            currentUserEmail = email
            true
        } else {
            false
        }
    }

    fun register(email: String, password: String) {
        users.add(UserProfile(email, password))
        currentUserEmail = email
    }

    fun updateRole(role: String) {
        currentUser?.role = role
    }

    fun updateProfile(name: String, address: String, contact: String) {
        currentUser?.let {
            it.name = name
            it.address = address
            it.contact = contact
            
            // For demo purposes, if they just set up as a caretaker, give them a default elder
            if (it.role == "helper" && it.managedElders.isEmpty()) {
                it.managedElders.add(ElderlyMember("default", "Lolo Mao", "0987-654-3210", 82, "Safe", "Just now"))
            }
        }
    }

    fun addElderlyMember(name: String) {
        currentUser?.managedElders?.add(
            ElderlyMember(
                id = java.util.UUID.randomUUID().toString(),
                name = name
            )
        )
    }

    fun logout() {
        currentUserEmail = ""
    }
}
