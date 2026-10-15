package com.squatalarm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.squatalarm.app.ui.PreconfigScreen
import com.squatalarm.app.ui.HomeScreen
import com.squatalarm.app.ui.PermissionsScreen
import com.squatalarm.app.ui.SquatAlarmTheme
import com.squatalarm.app.ui.TestScreen

class MainActivity : ComponentActivity() {

    /** Se incrementa en cada onResume para releer permisos/preferencias al volver de Ajustes. */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // íconos de la barra de estado legibles en tema claro (Android 15 dibuja bajo las barras)
        setContent {
            SquatAlarmTheme {
                AppRoot(resumeTick = resumeTick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

enum class Screen { HOME, CALIBRATE, TEST, PERMISSIONS }

@Composable
fun AppRoot(resumeTick: Int) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    when (screen) {
        Screen.HOME -> HomeScreen(
            resumeTick = resumeTick,
            onCalibrate = { screen = Screen.CALIBRATE },
            onTest = { screen = Screen.TEST },
            onPermissions = { screen = Screen.PERMISSIONS },
        )
        Screen.CALIBRATE -> PreconfigScreen(onDone = { screen = Screen.HOME })
        Screen.TEST -> TestScreen(onDone = { screen = Screen.HOME })
        Screen.PERMISSIONS -> PermissionsScreen(resumeTick = resumeTick, onDone = { screen = Screen.HOME })
    }
}
