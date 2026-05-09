package com.example.saferhouseui.viewmodel

import android.app.Application
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.example.saferhouseui.data.model.ActivityLog
import kotlinx.coroutines.launch
import androidx.core.net.toUri

import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings

class ElderlyViewModel(
    application: Application,
    private val authViewModel: AuthViewModel
) : AndroidViewModel(application) {
    
    // The final emergency state (Alert sent)
    var isEmergencyActive by mutableStateOf(false)
        private set

    // The state for the 10-second confirmation dialog
    var isConfirmationDialogOpen by mutableStateOf(false)
        private set

    var countdownValue by mutableIntStateOf(10)
        private set

    // Indicates if the audio distress feature is running in background
    var isAudioDetectionEnabled by mutableStateOf(true)
        private set

    // Daily Check-In state
    var isCheckInPending by mutableStateOf(false)
        private set
    
    var checkInTimeoutJob: Job? = null
    
    // Local Alarm state
    var isLocalAlarmActive by mutableStateOf(false)
        private set
    
    private var mediaPlayer: MediaPlayer? = null

    private var countdownJob: Job? = null
    private var audioDetectionJob: Job? = null

    init {
        // startAudioDistressDetection() // DISABLED: Prevent automated SMS/Calls during dev
    }

    /**
     * Triggers a daily check-in prompt for the elder.
     */
    fun triggerDailyCheckIn() {
        isCheckInPending = true
        updateStatus("Check-In Pending")
        checkInTimeoutJob?.cancel()
        checkInTimeoutJob = viewModelScope.launch {
            delay(300000) // 5 minutes timeout for demo, should be longer in real app
            if (isCheckInPending) {
                // Timeout reached, notify caregiver without triggering full emergency
                notifyCheckInMissed()
            }
        }
    }

    fun respondToCheckIn() {
        isCheckInPending = false
        checkInTimeoutJob?.cancel()
        updateStatus("Safe")
        addLog("DAILY_CHECK", "Elder responded to daily check-in and is safe.")
        Log.d("ElderlyViewModel", "User responded to check-in.")
    }

    private fun notifyCheckInMissed() {
        isCheckInPending = false
        updateStatus("Missed Check-In")
        addLog("DAILY_CHECK_MISSED", "Elder missed the scheduled daily check-in.")
        Log.d("ElderlyViewModel", "Daily check-in missed. Caregiver notified.")
    }

    /**
     * Simulates the constant background listening for distress keywords.
     * Both manual button and this detection will lead to the same confirmation process.
     */
    fun startAudioDistressDetection() {
        isAudioDetectionEnabled = true
        audioDetectionJob?.cancel()
        audioDetectionJob = viewModelScope.launch {
            Log.d("ElderlyViewModel", "Audio Distress Detection started. Listening for keywords...")
            // Simulated: If a keyword is detected (e.g., after some time), it triggers the same confirmation dialog
            // We don't trigger it immediately to avoid "already ON" state on dashboard entry
            delay(60000) // Simulate detection after 1 minute for demo purposes
            Log.d("ElderlyViewModel", "Keyword Detected via Audio!")
            triggerEmergency()
        }
    }

    /**
     * Entry point for both Manual SOS and Audio Detection.
     * Triggers the 10-second confirmation dialog.
     */
    fun triggerEmergency() {
        if (!isEmergencyActive && !isConfirmationDialogOpen) {
            isConfirmationDialogOpen = true
            startCountdown()
        }
    }

    private fun startCountdown() {
        countdownValue = 10
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (countdownValue > 0) {
                delay(1000)
                countdownValue--
            }
            confirmEmergency()
        }
    }

    fun cancelEmergency() {
        isConfirmationDialogOpen = false
        countdownJob?.cancel()
        Log.d("ElderlyViewModel", "Emergency cancelled by user.")
    }

    fun confirmEmergency() {
        isConfirmationDialogOpen = false
        countdownJob?.cancel()
        isEmergencyActive = true
        updateStatus("Emergency")
        addLog("SOS", "Emergency alert manually triggered by elder.")
        sendEmergencyEscalation()
    }

    private fun sendEmergencyEscalation() {
        // Multi-Contact Emergency Alert
        val currentUser = authViewModel.users.find { it.email == authViewModel.currentUserEmail }
        val currentElder = currentUser?.managedElders?.find { it.phoneNumber == currentUser.contact } 
            ?: authViewModel.users.flatMap { it.managedElders }.find { it.phoneNumber == authViewModel.currentUser?.contact }

        val contacts = mutableListOf<String>()
        
        // Add specific emergency contacts if available
        currentElder?.emergencyContacts?.let { contacts.addAll(it) }
        
        // Always include the primary caregiver
        val caregiver = authViewModel.users.find { it.role == "caregiver" }
        caregiver?.contact?.let { if (!contacts.contains(it)) contacts.add(it) }
        
        // Add a mock Barangay contact if list is empty
        if (contacts.isEmpty()) {
            contacts.add("09123456789") // Mock Barangay
        }

        val elderName = authViewModel.currentUser?.name ?: "User"
        val elderContact = authViewModel.currentUser?.contact ?: "Unknown"
        val location = authViewModel.currentUser?.address ?: "Unknown Location"

        Log.d("ElderlyViewModel", "Escalation Response: Sending Automated SMS to ${contacts.size} contacts")

        val smsManager = getApplication<Application>().getSystemService(SmsManager::class.java)
        val message = "EMERGENCY ALERT: $elderName (Reg. No: $elderContact) needs help!\nLocation: $location\nView on Maps: https://www.google.com/maps/search/?api=1&query=${location.replace(" ", "+")}"

        var smsFailed = false
        contacts.forEach { contact ->
            try {
                smsManager.sendTextMessage(contact, null, message, null, null)
            } catch (e: Exception) {
                Log.e("ElderlyViewModel", "Failed to send SMS to $contact: ${e.message}")
                smsFailed = true
            }
        }

        // If SMS failed or as a secondary measure, trigger local alarm
        if (smsFailed || contacts.isEmpty()) {
            startLocalAlarm()
        }

        // Redirect to Dialer for the first contact
        if (contacts.isNotEmpty()) {
            try {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = "tel:${contacts[0]}".toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                getApplication<Application>().startActivity(intent)
            } catch (e: Exception) {
                Log.e("ElderlyViewModel", "Failed to open Dialer: ${e.message}")
            }
        }
    }

    fun startLocalAlarm() {
        if (isLocalAlarmActive) return
        isLocalAlarmActive = true
        try {
            val alert: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer = MediaPlayer.create(getApplication(), alert)
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e("ElderlyViewModel", "Error playing local alarm: ${e.message}")
        }
    }

    fun stopLocalAlarm() {
        isLocalAlarmActive = false
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun toggleEmergency() {
        if (isEmergencyActive) {
            // Option to reset state if already active
            isEmergencyActive = false
            updateStatus("Safe")
        } else {
            // Manual SOS button - zero trust, immediate escalation
            confirmEmergency()
        }
    }

    private fun updateStatus(status: String) {
        val currentElderUser = authViewModel.currentUser
        if (currentElderUser != null) {
            authViewModel.users.forEach { user ->
                val elderIndex = user.managedElders.indexOfFirst { it.phoneNumber == currentElderUser.contact }
                if (elderIndex != -1) {
                    val updatedElder = user.managedElders[elderIndex].copy(
                        status = status,
                        lastSeen = when (status) {
                            "Safe" -> "Just now"
                            "Check-In Pending" -> "Checking in..."
                            "Missed Check-In" -> "No response"
                            "Emergency" -> "Alert Triggered"
                            else -> user.managedElders[elderIndex].lastSeen
                        }
                    )
                    val newList = user.managedElders.toMutableList()
                    newList[elderIndex] = updatedElder
                    authViewModel.updateUser(user.copy(managedElders = newList))
                }
            }
        }
    }

    private fun addLog(type: String, description: String) {
        val currentElderUser = authViewModel.currentUser ?: return
        val log = ActivityLog(
            userId = currentElderUser.email,
            type = type,
            description = description
        )

        // Add to caregiver's logs (mocking shared storage)
        authViewModel.users.forEach { user ->
            if (user.role == "caregiver") {
                val updatedLogs = user.activityLogs.toMutableList()
                updatedLogs.add(0, log) // Add to top
                authViewModel.updateUser(user.copy(activityLogs = updatedLogs))
            }
        }
    }

    fun updateProfile(name: String, address: String, contact: String) {
        authViewModel.currentUser?.let { user ->
            authViewModel.updateUser(
                user.copy(
                    name = name,
                    address = address,
                    contact = contact
                )
            )
        }
    }
}
