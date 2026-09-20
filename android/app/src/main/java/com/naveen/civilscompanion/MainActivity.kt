package com.naveen.civilscompanion

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.theme.CivilsTheme
import com.naveen.civilscompanion.ui.login.LoginScreen
import com.naveen.civilscompanion.ui.nav.Destination
import com.naveen.civilscompanion.ui.nav.NavRail
import com.naveen.civilscompanion.ui.placeholder.PlaceholderScreen
import com.naveen.civilscompanion.ui.placeholder.SettingsPlaceholder
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CivilsTheme { AppRoot() }
        }
    }
}

@Composable
private fun AppRoot(vm: MainViewModel = hiltViewModel()) {
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    if (!loggedIn) {
        LoginScreen()
        return
    }

    // Android 13+ asks before showing notifications (the Tab M10 on Android 9/10 does not).
    if (Build.VERSION.SDK_INT >= 33) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val selected = Destination.entries.firstOrNull { it.route == entry?.destination?.route } ?: Destination.Today

    Row(Modifier.fillMaxSize().background(Cc.colors.background)) {
        NavRail(selected = selected, onSelect = { dest ->
            nav.navigate(dest.route) {
                popUpTo(Destination.Today.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        })
        NavHost(nav, startDestination = Destination.Today.route, modifier = Modifier.fillMaxSize()) {
            Destination.entries.forEach { dest ->
                composable(dest.route) {
                    if (dest == Destination.Settings) SettingsPlaceholder(onLogout = vm::logout)
                    else PlaceholderScreen(dest)
                }
            }
        }
    }
}
