package com.example.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewVMTest {

    @Test
    fun softReset_resetsToFavorites() {
        val vm = ViewVM()
        vm.setView(View.AllApps)
        
        vm.softReset()
        
        assertEquals(View.Favorites, vm.view.value)
    }

    @Test
    fun back_fromAllApps_goesToFavorites_andReturnsTrue() {
        val vm = ViewVM()
        vm.setView(View.AllApps)
        
        val handled = vm.back()
        
        assertTrue(handled)
        assertEquals(View.Favorites, vm.view.value)
    }

    @Test
    fun back_fromFavorites_returnsFalse() {
        val vm = ViewVM()
        vm.setView(View.Favorites)
        
        val handled = vm.back()
        
        assertFalse(handled)
        assertEquals(View.Favorites, vm.view.value)
    }
    
    @Test
    fun back_closesMenu_beforeView() {
        val vm = ViewVM()
        vm.setView(View.AllApps)
        vm.setMenu(MenuState.Sheet(Any()))
        
        val handled = vm.back()
        
        assertTrue(handled)
        assertEquals(MenuState.None, vm.menu.value)
        assertEquals(View.AllApps, vm.view.value) // Should stay in AllApps, just close menu
        
        // Second back should go to favorites
        val handled2 = vm.back()
        assertTrue(handled2)
        assertEquals(View.Favorites, vm.view.value)
    }
}
