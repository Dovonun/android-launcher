package com.example.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface View {
    data object Favorites : View
    data object AllApps : View
}

sealed interface MenuState {
    data object None : MenuState
    data class Sheet(val item: Any) : MenuState
    data class Popup(val entries: List<UiRow>, val yPos: Float) : MenuState
}

class ViewVM : ViewModel() {
    private var leaveJob: Job? = null
    private val _view = MutableStateFlow<View>(View.Favorites)
    private var _menu = MutableStateFlow<MenuState>(MenuState.None)
    val view: StateFlow<View> = _view
    val menu: StateFlow<MenuState> = _menu

    fun leave() {
        setMenu(MenuState.None)
        leaveJob?.cancel()
        leaveJob = viewModelScope.launch {
            delay(5000)
            setView(View.Favorites)
        }
    }

    fun softReset() {
        leaveJob?.cancel()
        leaveJob = null
        setMenu(MenuState.None)
    }

    fun setView(view: View) {
        _view.value = view
    }

    fun setMenu(menuState: MenuState) {
        _menu.value = menuState
    }
}