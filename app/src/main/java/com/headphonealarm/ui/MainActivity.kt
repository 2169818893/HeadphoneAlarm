package com.headphonealarm.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.headphonealarm.alarmApp
import com.headphonealarm.ui.edit.AlarmEditScreen
import com.headphonealarm.ui.list.AlarmListScreen
import com.headphonealarm.ui.theme.AppBackground
import com.headphonealarm.ui.theme.AppTheme
import com.headphonealarm.ui.theme.HeadphoneAlarmTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings = remember { alarmApp.settingsRepository }
            val themeKey by settings.themeKey.collectAsStateWithLifecycle(AppTheme.DEFAULT.key)
            HeadphoneAlarmTheme(theme = AppTheme.fromKey(themeKey)) {
                AppBackground {
                    AlarmNavHost()
                }
            }
        }
    }
}

private object Routes {
    const val LIST = "list"
    const val EDIT = "edit"
    const val ARG_ID = "alarmId"
    const val NEW_ID = -1L
    fun edit(id: Long) = "$EDIT?$ARG_ID=$id"
}

@Composable
private fun AlarmNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            AlarmListScreen(
                // launchSingleTop 避免连点造成多个编辑页堆叠
                onAddAlarm = {
                    navController.navigate(Routes.edit(Routes.NEW_ID)) { launchSingleTop = true }
                },
                onEditAlarm = { id ->
                    navController.navigate(Routes.edit(id)) { launchSingleTop = true }
                }
            )
        }
        composable(
            route = "${Routes.EDIT}?${Routes.ARG_ID}={${Routes.ARG_ID}}",
            arguments = listOf(
                navArgument(Routes.ARG_ID) {
                    type = NavType.LongType
                    defaultValue = Routes.NEW_ID
                }
            )
        ) { entry ->
            val alarmId = entry.arguments?.getLong(Routes.ARG_ID) ?: Routes.NEW_ID
            AlarmEditScreen(
                alarmId = alarmId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
