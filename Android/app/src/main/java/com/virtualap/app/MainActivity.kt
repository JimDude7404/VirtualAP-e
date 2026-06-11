package com.virtualap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.virtualap.app.ui.navigation.Screens
import com.virtualap.app.ui.screen.MainScreen
import com.virtualap.app.ui.screen.RootCheckScreen
import com.virtualap.app.ui.screen.SetupScreen
import com.virtualap.app.ui.theme.VirtualAPTheme
import com.virtualap.app.ui.viewmodel.AppViewModel
import com.virtualap.app.ui.viewmodel.InstallStatus
import com.virtualap.app.util.RootStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VirtualAPTheme {
                val appVm: AppViewModel = viewModel()
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = Screens.ROOT_CHECK) {
                    composable(Screens.ROOT_CHECK) {
                        RootCheckScreen(
                            rootStatus = appVm.rootStatus,
                            onCheckRoot = { appVm.checkRoot() },
                            onNavigateNext = {
                                if (appVm.installStatus == InstallStatus.Installed)
                                    navController.navigate(Screens.MAIN) {
                                        popUpTo(Screens.ROOT_CHECK) { inclusive = true }
                                    }
                                else
                                    navController.navigate(Screens.SETUP) {
                                        popUpTo(Screens.ROOT_CHECK) { inclusive = true }
                                    }
                            }
                        )
                    }
                    composable(Screens.SETUP) {
                        SetupScreen(
                            onInstalled = {
                                appVm.markInstalled()
                                navController.navigate(Screens.MAIN) {
                                    popUpTo(Screens.SETUP) { inclusive = true }
                                }
                            }
                        )
                    }
                    composable(Screens.MAIN) {
                        MainScreen()
                    }
                }

                // Auto-navigate once initial checks complete
                LaunchedEffect(appVm.rootStatus, appVm.installStatus) {
                    val current = navController.currentDestination?.route
                    when {
                        appVm.rootStatus == RootStatus.Granted && appVm.installStatus == InstallStatus.Installed
                            && current != Screens.MAIN ->
                            navController.navigate(Screens.MAIN) { popUpTo(0) }
                        appVm.rootStatus == RootStatus.Granted && appVm.installStatus == InstallStatus.NotInstalled
                            && current == Screens.ROOT_CHECK ->
                            navController.navigate(Screens.SETUP) {
                                popUpTo(Screens.ROOT_CHECK) { inclusive = true }
                            }
                    }
                }
            }
        }
    }
}
