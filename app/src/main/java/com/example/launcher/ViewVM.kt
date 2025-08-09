package com.example.launcher

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ViewVM: ViewModel() {

    // Backing mutable state
    private val _view = MutableStateFlow<View>(View.Favorites)
    val view: StateFlow<View> = _view

    fun setView(view: View) {
        _view.value = view
    }
}