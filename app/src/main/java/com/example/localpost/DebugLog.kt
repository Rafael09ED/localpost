package com.example.localpost

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugLog {
    private val state = MutableStateFlow<List<String>>(emptyList())
    val events = state.asStateFlow()

    fun add(message: String) {
        Log.d("PublicGossip", message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        state.value = (state.value + "$time $message").takeLast(50)
    }
}