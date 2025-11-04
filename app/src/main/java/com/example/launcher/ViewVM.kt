package com.example.launcher

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface View {
    data object Favorites : View
    data class AllApps(val index: Int) : View
}

sealed interface MenuState {
    data object None : MenuState
    data class Sheet(val item: Any) : MenuState
    data class Popup(val item: Any, val yPos: Float) : MenuState
}

class ViewVM: ViewModel() {
    private val _leaveTimeStamp = MutableStateFlow(0L)
    val leaveTime: StateFlow<Long> = _leaveTimeStamp
    private val _view = MutableStateFlow<View>(View.Favorites)
    private var _menu =  MutableStateFlow<MenuState>(MenuState.None)
    val view: StateFlow<View> = _view
    val menu: StateFlow<MenuState> = _menu

    fun setView(view: View) {
        _view.value = view
    }

    fun setMenu(menuState: MenuState) {
        _menu.value = menuState
    }
    fun setLeaveTime(timeStamp: Long) {
        _leaveTimeStamp.value = timeStamp
    }
}