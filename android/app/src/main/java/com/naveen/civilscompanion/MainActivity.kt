package com.naveen.civilscompanion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.theme.CivilsTheme
import com.naveen.civilscompanion.ui.alerts.AlertsScreen
import com.naveen.civilscompanion.ui.briefs.BriefsScreen
import com.naveen.civilscompanion.ui.login.LoginScreen
import com.naveen.civilscompanion.ui.nav.Destination
import com.naveen.civilscompanion.ui.nav.NavRail
import com.naveen.civilscompanion.ui.placeholder.PlaceholderScreen
import com.naveen.civilscompanion.ui.settings.SettingsScreen
import com.naveen.civilscompanion.ui.setup.SetupScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var navEvents: NavEvents

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) navEvents.post(AppLinks.fromIntent(intent))
        setContent {
            CivilsTheme { AppRoot() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navEvents.post(AppLinks.fromIntent(intent))
    }
}

private fun NavHostController.goTo(dest: Destination) {
    navigate(dest.route) {
        popUpTo(Destination.Today.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppRoot(vm: MainViewModel = hiltViewModel()) {
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    if (!loggedIn) {
        LoginScreen()
        return
    }

    val setupDone by vm.setupDone.collectAsStateWithLifecycle()
    if (!setupDone) {
        SetupScreen(onFinished = vm::finishSetup)
        return
    }

    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val selected = Destination.entries.firstOrNull { it.route == entry?.destination?.route } ?: Destination.Today

    // A notification button was tapped: go to the right screen.
    val pending by vm.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        when (pending?.action) {
            AppLinks.ACTION_OPEN_ALERTS -> {
                nav.goTo(Destination.Alerts)
                vm.consumePending()
            }
            AppLinks.ACTION_PLAY_BRIEF, AppLinks.ACTION_OPEN_BRIEF -> nav.goTo(Destination.Briefs)
        }
    }

    Row(Modifier.fillMaxSize().background(Cc.colors.background)) {
        NavRail(selected = selected, onSelect = { nav.goTo(it) })
        NavHost(nav, startDestination = Destination.Today.route, modifier = Modifier.fillMaxSize()) {
            Destination.entries.forEach { dest ->
                composable(dest.route) {
                    when (dest) {
                        Destination.Briefs -> BriefsScreen()
                        Destination.Alerts -> AlertsScreen(onOpenBriefs = { nav.goTo(Destination.Briefs) })
                        Destination.Settings -> SettingsScreen(onLogout = vm::logout, onRunSetup = vm::reopenSetup)
                        else -> PlaceholderScreen(dest)
                    }
                }
            }
        }
    }
}
