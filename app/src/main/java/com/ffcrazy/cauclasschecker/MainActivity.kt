package com.ffcrazy.cauclasschecker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ffcrazy.cauclasschecker.domain.Sign
import com.ffcrazy.cauclasschecker.scan.ScanScreen
import com.ffcrazy.cauclasschecker.ui.HomeHeader
import com.ffcrazy.cauclasschecker.ui.HomeScreen
import com.ffcrazy.cauclasschecker.ui.SessionScreen
import com.ffcrazy.cauclasschecker.ui.theme.CauCheckInTheme

class MainActivity : ComponentActivity() {

    // 用 Activity 的 viewModels() 而不是 Compose 的 viewModel()，
    // 这样不必再引 lifecycle-viewmodel-compose，少一个版本要操心。
    private val vm: CheckInViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CauCheckInTheme {
                AppRoot(vm)
            }
        }
    }

    /**
     * 「只在前台刷新」由这里保证：切后台就停掉计时器，回来立刻续上。
     * 状态全在 ViewModel 里，所以旋转屏幕、切前后台都不会丢会话。
     */
    override fun onResume() {
        super.onResume()
        vm.setForeground(true)
    }

    override fun onPause() {
        vm.setForeground(false)
        super.onPause()
    }
}

@Composable
private fun AppRoot(vm: CheckInViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var scanning by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // 一次性提示。ViewModel 侧用的是 Channel，所以转屏不会重放同一条。
    LaunchedEffect(vm) {
        vm.messages.collect { msg ->
            snackbarHostState.showSnackbar(
                message = msg.text,
                duration = if (msg.long) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // 页面由「有没有会话」决定：拿到会话就自动进二级页面。
            // 扫码 / 相册 / 粘贴三条路都汇入 startSession，所以这里不用分别处理。
            when {
                scanning -> {
                    BackHandler { scanning = false }
                    ScanScreen(
                        onDecoded = { text ->
                            scanning = false
                            val session = Sign.parseSignUrl(text)
                            if (session == null) {
                                vm.showError(CheckInViewModel.MSG_BAD_QR + text)
                            } else {
                                vm.startSession(session)
                                vm.showMessage(CheckInViewModel.MSG_OK)
                            }
                        },
                        onClose = { scanning = false },
                    )
                }

                state.hasSession -> {
                    BackHandler { vm.clearSession() }
                    SessionScreen(vm = vm, onBack = { vm.clearSession() })
                }

                else -> Column(Modifier.fillMaxSize()) {
                    HomeHeader()
                    HomeScreen(
                        state = state,
                        vm = vm,
                        onOpenScanner = { scanning = true },
                    )
                }
            }
        }

        // 浮在所有页面之上：相机页也能弹提示
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}
