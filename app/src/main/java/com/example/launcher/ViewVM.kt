package com.example.launcher

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ViewVM: ViewModel() {

    // Backing mutable state
    private val _leaveTimeStamp = MutableStateFlow(0L)
    val leaveTime: StateFlow<Long> = _leaveTimeStamp
    private val _view = MutableStateFlow<View>(View.Favorites)
    val view: StateFlow<View> = _view

    fun setView(view: View) {
        _view.value = view
    }

    fun setLeaveTime(timeStamp: Long) {
        _leaveTimeStamp.value = timeStamp
    }
}