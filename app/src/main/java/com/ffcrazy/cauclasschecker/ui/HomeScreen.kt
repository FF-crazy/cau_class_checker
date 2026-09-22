package com.ffcrazy.cauclasschecker.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ffcrazy.cauclasschecker.CheckInUiState
import com.ffcrazy.cauclasschecker.CheckInViewModel
import com.ffcrazy.cauclasschecker.domain.Sign
import com.ffcrazy.cauclasschecker.scan.QrImageDecoder
import com.ffcrazy.cauclasschecker.ui.theme.Border
import com.ffcrazy.cauclasschecker.ui.theme.ErrorRed
import com.ffcrazy.cauclasschecker.ui.theme.Green
import com.ffcrazy.cauclasschecker.ui.theme.GreenDeep
import com.ffcrazy.cauclasschecker.ui.theme.Ink
import com.ffcrazy.cauclasschecker.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主页：两个导入入口，外加一个粘贴链接的输入框。
 *
 * 粘贴这条路必须留着——没相机权限、或二维码太糊扫不出来时，它是唯一的退路。
 * 但它不抢戏，所以只占一行。
 */
@Composable
fun HomeScreen(
    state: CheckInUiState,
    vm: CheckInViewModel,
    onOpenScanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var linkText by rememberSaveable { mutableStateOf("") }

    // PickVisualMedia 不需要任何存储权限——系统相册自己负责授权
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // 解码大图可能要几百毫秒，别卡住主线程
            val text = withContext(Dispatchers.IO) { QrImageDecoder.decode(context, uri) }
            if (text == null) {
                vm.showError(CheckInViewModel.MSG_NO_QR)
            } else {
                val session = Sign.parseSignUrl(text)
                if (session == null) {
                    vm.showError(CheckInViewModel.MSG_BAD_QR + text)
                } else {
                    vm.startSession(session)
                    vm.showMessage(CheckInViewModel.MSG_OK)
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Button(
            onClick = onOpenScanner,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Green,
                contentColor = Color.White,
            ),
        ) {
            Text("从摄像头导入", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenDeep),
        ) {
            Text("从相册导入", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { vm.applyClipboard(readClipboard(context)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenDeep),
        ) {
            Text("从粘贴板导入", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(28.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = linkText,
                onValueChange = { linkText = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp),
                placeholder = {
                    Text("或粘贴签到链接", fontSize = 13.sp, color = Muted)
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
            )
            Button(
                onClick = { vm.applyLink(linkText) },
                enabled = linkText.isNotBlank(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GreenDeep,
                    contentColor = Color.White,
                ),
                modifier = Modifier.heightIn(min = 52.dp),
            ) {
                Text("生成", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

    }
}

/**
 * 读粘贴板首条内容。
 *
 * 只在用户**主动点击按钮**时读——Android 10 起后台读粘贴板会被系统拒绝，
 * 而点击时应用处于前台，是允许的。Android 12+ 还会顺手弹一个系统级的
 * 「已粘贴」提示，那个我们控制不了，属正常现象。
 *
 * 拿不到就返回 null，由 ViewModel 决定怎么提示。
 */
private fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return runCatching { clip.getItemAt(0).coerceToText(context)?.toString() }.getOrNull()
}

/** 通用顶栏：左对齐一个标题 + 细分隔线，三个 Tab 共用。 */
@Composable
fun AppHeader(title: String) {
    Column {
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        HorizontalDivider(color = Border, thickness = 1.dp)
    }
}
