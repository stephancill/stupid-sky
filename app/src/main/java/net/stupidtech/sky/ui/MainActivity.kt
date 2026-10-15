package net.stupidtech.sky.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import net.stupidtech.sky.alerts.AlertWorker
import net.stupidtech.sky.data.UserLocation

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            vm.onLocationPermission(result.values.any { it })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AlertWorker.ensureChannel(this)
        AlertWorker.schedule(this)
        val fromAlert = intent?.getStringExtra(EXTRA_HEX) != null
        handleIntent(intent)
        // Reopen where the user left the map; centre on them only on first launch (or when opened to show
        // a specific aircraft, the alert decides).
        if (savedInstanceState == null && !fromAlert && !hasSavedCamera(this)) {
            if (UserLocation.hasPermission(this)) {
                vm.locateUser()
            } else {
                locationPermission.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                StupidSkyScreen(
                    vm,
                    requestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    requestLocation = {
                        locationPermission.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.setActive(true)
    }

    override fun onStop() {
        vm.setActive(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_HEX)?.let { vm.focusHex(it) }
    }

    companion object {
        const val EXTRA_HEX = "hex"
    }
}
