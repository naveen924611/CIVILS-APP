package com.naveen.civilscompanion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.naveen.civilscompanion.theme.Cc
import com.naveen.civilscompanion.theme.CivilsTheme
import com.naveen.civilscompanion.ui.alerts.AlertsScreen
import com.naveen.civilscompanion.ui.ask.askRoutes
import com.naveen.civilscompanion.ui.briefs.BriefsScreen
import com.naveen.civilscompanion.ui.library.libraryRoutes
import com.naveen.civilscompanion.ui.login.LoginScreen
import com.naveen.civilscompanion.ui.nav.Destination
import com.naveen.civilscompanion.ui.nav.NavRail
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.notes.notesRoutes
import com.naveen.civilscompanion.ui.settings.SettingsScreen
import com.naveen.civilscompanion.ui.setup.SetupScreen
import com.naveen.civilscompanion.ui.sheets.sheetsRoutes
import com.naveen.civilscompanion.ui.today.todayRoutes
import com.naveen.civilscompanion.ui.voice.FloatingMic
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var navEvents: NavEvents
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) navEvents.post(AppLinks.fromIntent(intent))
        setContent {
            val ui by vm.ui.collectAsStateWithLifecycle()
            val dark = when (ui.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            CivilsTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, ui.textScale)) {
                    AppRoot(vm)
                }
            }
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
private fun AppRoot(vm: MainViewModel) {
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
    val selected = Routes.railFor(entry?.destination?.route)

    // A notification button was tapped: go to the right screen.
    val pending by vm.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val p = pending
        when (p?.action) {
            AppLinks.ACTION_OPEN_ALERTS -> {
                nav.goTo(Destination.Alerts)
                vm.consumePending()
            }
            AppLinks.ACTION_OPEN_ROUTE -> {
                p?.route?.let { route -> runCatching { nav.navigate(route) } }
                vm.consumePending()
            }
            AppLinks.ACTION_PLAY_BRIEF, AppLinks.ACTION_OPEN_BRIEF -> nav.goTo(Destination.Briefs)
        }
    }

    Row(Modifier.fillMaxSize().background(Cc.colors.background)) {
        NavRail(selected = selected, onSelect = { nav.goTo(it) })
        Box(Modifier.weight(1f).fillMaxSize()) {
            NavHost(nav, startDestination = Routes.TODAY, modifier = Modifier.fillMaxSize()) {
                composable(Routes.BRIEFS) { BriefsScreen() }
                composable(Routes.ALERTS) { AlertsScreen(onOpenBriefs = { nav.goTo(Destination.Briefs) }) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(onLogout = vm::logout, onRunSetup = vm::reopenSetup, nav = nav)
                }
                libraryRoutes(nav)
                notesRoutes(nav)
                todayRoutes(nav)
                askRoutes(nav)
                sheetsRoutes(nav)
            }
            FloatingMic(nav, Modifier.align(Alignment.BottomEnd).padding(20.dp))
        }
    }
}
