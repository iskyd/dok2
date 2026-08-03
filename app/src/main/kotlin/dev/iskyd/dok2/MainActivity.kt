package dev.iskyd.dok2

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.iskyd.dok2.ui.LibraryScreen
import dev.iskyd.dok2.ui.LiveScreen
import dev.iskyd.dok2.ui.MapScreen
import dev.iskyd.dok2.ui.SettingsScreen
import dev.iskyd.dok2.ui.theme.Dok2Theme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Only ask for battery optimization once location — the hard requirement — is granted.
            if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                maybeRequestBatteryOptimization()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNeededPermissions()
        val app = application as Dok2Application
        setContent { Dok2Theme { MainScaffold(app) } }
    }

    private fun requestNeededPermissions() {
        val needed = buildList {
            if (
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            maybeRequestBatteryOptimization()
        }
    }

    /**
     * Asks the user to exempt dok2 from battery optimization (DOCUMENTATION.md "Known device
     * problems", mitigation 1). Shown once during onboarding, behind a rationale dialog, because a
     * bare settings intent confuses users.
     */
    private fun maybeRequestBatteryOptimization() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("Battery optimization")
            .setMessage(
                "To record GPS while the screen is off, allow dok2 to run " +
                    "without battery optimization."
            )
            .setPositiveButton("Allow") { _, _ ->
                val intent =
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    )
                try {
                    startActivity(intent)
                } catch (error: ActivityNotFoundException) {
                    Log.w(TAG, "device does not expose battery optimization settings", error)
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}

@Composable
private fun MainScaffold(app: Dok2Application) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.LIBRARY) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (item in AppScreen.entries) {
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = { ScreenDot(selected = screen == item) },
                        label = { Text(item.label) },
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            when (screen) {
                AppScreen.LIBRARY -> LibraryScreen(trackRepository = app.trackRepository)
                AppScreen.LIVE -> LiveScreen()
                AppScreen.MAP -> MapScreen(trackRepository = app.trackRepository)
                AppScreen.SETTINGS -> SettingsScreen(settingsRepository = app.settingsRepository)
            }
        }
    }
}

private enum class AppScreen(val label: String) {
    LIBRARY("Library"),
    LIVE("Live"),
    MAP("Map"),
    SETTINGS("Settings"),
}

/**
 * A dependency-free selection indicator for the bottom bar. The default material icons artifact is
 * not on the classpath, so the icon slot shows a dot that fills with the primary colour when the
 * tab is selected instead of pulling in a second icons dependency.
 */
@Composable
private fun ScreenDot(selected: Boolean) {
    Box(
        Modifier.size(8.dp)
            .clip(CircleShape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
            )
    )
}
