package com.xmo.mapvideoplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val shell = ShellRunner()
    private val controller by lazy { WindowController(applicationContext, shell) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LauncherScreen(
                        appPackageName = packageName,
                        checkSystemState = ::checkSystemState,
                        onStartDualView = { useWaze, onStatus ->
                            launchDualView(useWaze = useWaze, onStatus = onStatus)
                        },
                    )
                }
            }
        }
    }

    private suspend fun checkSystemState(): SystemState = withContext(Dispatchers.IO) {
        SystemState(
            writeSecureSettingsGranted = controller.isWriteSecureSettingsGranted(),
            rootAvailable = controller.isRootAvailable(),
        )
    }

    private fun launchDualView(
        useWaze: Boolean,
        onStatus: (String) -> Unit,
    ) {
        val leftApp = if (useWaze) AppConfig.WAZE else AppConfig.GOOGLE_MAPS
        val rightApp = AppConfig.YOUTUBE
        onStatus("Launching ${leftApp.displayName} + ${rightApp.displayName}…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                controller.launchSideBySide(
                    leftApp = leftApp,
                    rightApp = rightApp,
                    leftRatio = AppConfig.DEFAULT_LEFT_RATIO,
                )
            }
            when (result) {
                is WindowController.Result.Success -> {
                    val base = "Launched ${leftApp.displayName} + ${rightApp.displayName}."
                    val message = if (result.warnings.isEmpty()) {
                        base
                    } else {
                        base + "\n\nWarnings:\n" + result.warnings.joinToString("\n") { "• $it" }
                    }
                    onStatus(message)
                    // Step out of the way so our launcher activity isn't covering the freeform windows.
                    moveTaskToBack(true)
                }
                is WindowController.Result.Failure -> {
                    onStatus("FAILED: ${result.message}")
                }
            }
        }
    }
}

data class SystemState(
    val writeSecureSettingsGranted: Boolean,
    val rootAvailable: Boolean,
)

@Composable
private fun LauncherScreen(
    appPackageName: String,
    checkSystemState: suspend () -> SystemState,
    onStartDualView: (useWaze: Boolean, onStatus: (String) -> Unit) -> Unit,
) {
    var systemState by remember { mutableStateOf<SystemState?>(null) }
    var status by remember { mutableStateOf("Checking system state…") }

    LaunchedEffect(Unit) {
        val state = checkSystemState()
        systemState = state
        status = readinessMessage(state, appPackageName)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Xmo Launcher",
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Maps 40% · YouTube 60%",
                color = Color(0xFFAAAAAA),
                fontSize = 22.sp,
            )
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            BigButton(
                label = "Start (Maps + YouTube)",
                color = Color(0xFF7E57C2),
            ) {
                onStartDualView(false) { status = it }
            }
            BigButton(
                label = "Start (Waze + YouTube)",
                color = Color(0xFF1E88E5),
            ) {
                onStartDualView(true) { status = it }
            }
        }

        StatusPanel(
            modifier = Modifier.align(Alignment.BottomCenter),
            status = status,
            systemState = systemState,
        )
    }
}

private fun readinessMessage(state: SystemState, appPackageName: String): String {
    return if (!state.writeSecureSettingsGranted) {
        "SETUP REQUIRED — grant WRITE_SECURE_SETTINGS once via:\n\n" +
            "    adb shell pm grant $appPackageName android.permission.WRITE_SECURE_SETTINGS\n\n" +
            "Then relaunch this app."
    } else if (!state.rootAvailable) {
        "Ready. (su unavailable — Waze workaround will be skipped with a warning.)"
    } else {
        "Ready."
    }
}

@Composable
private fun BigButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .width(420.dp)
            .height(120.dp),
    ) {
        Text(text = label, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusPanel(
    modifier: Modifier = Modifier,
    status: String,
    systemState: SystemState?,
) {
    Box(
        modifier = modifier
            .width(820.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF222226))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IndicatorDot(value = systemState?.writeSecureSettingsGranted, criticalIfFalse = true)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "WRITE_SECURE_SETTINGS",
                    color = Color(0xFFE0E0E0),
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IndicatorDot(value = systemState?.rootAvailable, criticalIfFalse = false)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "su  (advisory — only needed for Waze workaround)",
                    color = Color(0xFFE0E0E0),
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF3A3A40)),
            )
            Spacer(Modifier.height(10.dp))
            val scrollState = rememberScrollState()
            Text(
                text = status,
                color = Color(0xFFE0E0E0),
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
            )
        }
    }
}

@Composable
private fun IndicatorDot(value: Boolean?, criticalIfFalse: Boolean) {
    val color = when (value) {
        true -> Color(0xFF4CAF50)
        false -> if (criticalIfFalse) Color(0xFFE53935) else Color(0xFFFFB300)
        null -> Color(0xFF9E9E9E)
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}
