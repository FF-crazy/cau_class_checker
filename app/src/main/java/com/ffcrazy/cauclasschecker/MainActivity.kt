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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ffcrazy.cauclasschecker.domain.Sign
import com.ffcrazy.cauclasschecker.scan.ScanScreen
import com.ffcrazy.cauclasschecker.ui.AboutScreen
import com.ffcrazy.cauclasschecker.ui.AccountScreen
import com.ffcrazy.cauclasschecker.ui.AppHeader
import com.ffcrazy.cauclasschecker.ui.HomeScreen
import com.ffcrazy.cauclasschecker.ui.SessionScreen
import com.ffcrazy.cauclasschecker.ui.theme.Border
import com.ffcrazy.cauclasschecker.ui.theme.CauCheckInTheme
import com.ffcrazy.cauclasschecker.ui.theme.GreenDeep

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

private enum class AppTab(val label: String, val icon: ImageVector) {
    CHECK_IN("签到", Icons.Filled.CheckCircle),
    ACCOUNT("账号管理", Icons.Filled.Person),
    ABOUT("关于", Icons.Filled.Info),
}

@Composable
private fun AppRoot(vm: CheckInViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 存下标而不是枚举本身 —— rememberSaveable 对枚举要额外写 Saver，下标省事
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val tab = AppTab.entries[tabIndex]

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
        if (scanning) {
            // 相机页是全屏模态，不要底部栏
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
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                        AppTab.entries.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = tabIndex == index,
                                onClick = { tabIndex = index },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label, fontSize = 12.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = GreenDeep,
                                    selectedTextColor = GreenDeep,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                    }
                },
            ) { inner ->
                Box(
                    Modifier
                        .padding(inner)
                        .fillMaxSize(),
                ) {
                    when (tab) {
                        AppTab.CHECK_IN -> Column(Modifier.fillMaxSize()) {
                            // 有会话就自动进二级页面；扫码/相册/粘贴三条路都汇入 startSession
                            if (state.hasSession) {
                                BackHandler { vm.clearSession() }
                                SessionScreen(vm = vm, onBack = { vm.clearSession() })
                            } else {
                                AppHeader("我爱易签到")
                                HomeScreen(
                                    state = state,
                                    vm = vm,
                                    onOpenScanner = { scanning = true },
                                )
                            }
                        }

                        AppTab.ACCOUNT -> Column(Modifier.fillMaxSize()) {
                            AppHeader("账号管理")
                            AccountScreen()
                        }

                        AppTab.ABOUT -> Column(Modifier.fillMaxSize()) {
                            AppHeader("关于")
                            AboutScreen()
                        }
                    }
                }
            }
        }

        // 浮在所有页面之上。有底部栏时抬高，别被挡住。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (scanning) 16.dp else 96.dp, start = 16.dp, end = 16.dp),
        )
    }
}
