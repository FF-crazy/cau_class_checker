package com.ffcrazy.cauclasschecker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.ffcrazy.cauclasschecker.domain.Sign
import com.ffcrazy.cauclasschecker.scan.ScanScreen
import com.ffcrazy.cauclasschecker.ui.CheckInScreen
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
                // 只有一个二级页面，用不着引 navigation 库
                var scanning by rememberSaveable { mutableStateOf(false) }

                if (scanning) {
                    BackHandler { scanning = false }
                    ScanScreen(
                        onDecoded = { text ->
                            scanning = false
                            val session = Sign.parseSignUrl(text)
                            if (session == null) {
                                vm.showError(CheckInViewModel.MSG_BAD_QR + text)
                            } else {
                                vm.startSession(session, "识别成功：扫码")
                            }
                        },
                        onClose = { scanning = false },
                    )
                } else {
                    CheckInScreen(
                        vm = vm,
                        onOpenScanner = { scanning = true },
                    )
                }
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
