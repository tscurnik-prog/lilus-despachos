package com.lilus.despachos

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La pantalla no se apaga mientras se escanea
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!vm.back()) finish()
            }
        })

        setContent {
            LilusTheme {
                val ctx = LocalContext.current
                val msg = vm.message
                LaunchedEffect(msg) {
                    if (msg != null) {
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        vm.clearMessage()
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    when (val s = vm.screen) {
                        Screen.Home -> HomeScreen(vm)
                        Screen.Scan -> ScanScreen(vm)
                        is Screen.Done -> DetailScreen(vm, s.uuid, justClosed = true)
                        Screen.History -> HistoryScreen(vm)
                        is Screen.Detail -> DetailScreen(vm, s.uuid, justClosed = false)
                        Screen.Config -> ConfigScreen(vm)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.loadHome()
    }
}
