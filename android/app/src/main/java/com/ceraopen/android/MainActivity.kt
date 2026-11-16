// SPDX-License-Identifier: GPL-3.0-or-later
package com.ceraopen.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.ceraopen.android.ui.MainScreen
import com.ceraopen.android.ui.theme.CeraTheme

class MainActivity : ComponentActivity() {

    private val isDebuggable: Boolean
        get() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    private val viewModel: CeraViewModel by viewModels {
        // The application, not the activity: the view model outlives
        // configuration changes, and it only needs a context to start the
        // Bluetooth service.
        CeraViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // `--ez demo true` fills the dashboard with sample data so the layout
        // can be reviewed on an emulator; debug builds only. `--ez running
        // true` and `--ez locked true` show the other two dashboard states.
        if (intent.getBooleanExtra("demo", false) && isDebuggable) {
            viewModel.enableDemo(
                running = intent.getBooleanExtra("running", false),
                locked = intent.getBooleanExtra("locked", false),
            )
        }
        setContent {
            CeraTheme {
                val context = LocalContext.current
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { /* the next scan retries if anything was denied */ }

                LaunchedEffect(Unit) {
                    if (!hasBluetoothPermissions(context)) {
                        launcher.launch(androidRequirements().toTypedArray())
                    }
                }

                MainScreen(
                    viewModel = viewModel,
                    onRequestPermissions = {
                        launcher.launch(androidRequirements().toTypedArray())
                    },
                )
            }
        }
    }
}
