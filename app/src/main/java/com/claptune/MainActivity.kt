package com.claptune

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.claptune.ui.AppTheme
import com.claptune.ui.AppViewModel
import com.claptune.ui.MainScreen
import com.claptune.ui.Screen
import com.claptune.ui.SearchScreen
import com.claptune.ui.SetupState

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var refreshTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            AppTheme {
                @Suppress("UNUSED_EXPRESSION") refreshTick // re-read permissions after returning from system screens
                val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshTick++ }
                val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshTick++ }

                when (vm.screen) {
                    Screen.MAIN -> MainScreen(
                        vm = vm,
                        setup = SetupState(
                            mic = granted(Manifest.permission.RECORD_AUDIO),
                            notifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
                            batteryOk = ignoringBatteryOptimizations(),
                        ),
                        onGrantMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onGrantNotifications = {
                            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onBattery = ::requestIgnoreBattery,
                    )
                    Screen.SEARCH -> SearchScreen(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            vm.handleShared(intent.getStringExtra(Intent.EXTRA_TEXT))
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun ignoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
