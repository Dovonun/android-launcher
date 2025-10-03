package com.example.launcher

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ViewVM: ViewModel() {

    // Backing mutable state
    private val _leaveTimeStamp = MutableStateFlow(0L)
    val leaveTimeStamp: StateFlow<Long> = _leaveTimeStamp
    private val _view = MutableStateFlow<View>(View.Favorites)
    val view: StateFlow<View> = _view

    fun setView(view: View) {
        _view.value = view
    }

    fun setLeveTimeStamp(timeStamp: Long) {
        _leaveTimeStamp.value = timeStamp
    }
}