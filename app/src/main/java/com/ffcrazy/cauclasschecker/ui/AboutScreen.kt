package com.ffcrazy.cauclasschecker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ffcrazy.cauclasschecker.ui.theme.Border
import com.ffcrazy.cauclasschecker.ui.theme.GreenDeep
import com.ffcrazy.cauclasschecker.ui.theme.Ink
import com.ffcrazy.cauclasschecker.ui.theme.Muted

private const val REPO_URL = "https://github.com/FF-crazy/cau_class_checker"

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text("我爱易签到", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(4.dp))
        Text("版本 $version", fontSize = 13.sp, color = Muted)

        Spacer(Modifier.height(24.dp))

        Text(
            "把教室大屏上的易签到二维码，用当前时间重新签发一遍。",
            fontSize = 14.sp,
            color = Ink,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "只保留 ip 和 ipt，t 与 tt 一律按当前时间重算，每 0.5 秒刷新一次，因此旧码也能复活。",
            fontSize = 13.sp,
            color = Muted,
            lineHeight = 21.sp,
        )

        Spacer(Modifier.height(28.dp))

        SectionTitle("开源仓库")
        Text(
            REPO_URL.removePrefix("https://"),
            fontSize = 14.sp,
            color = GreenDeep,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { openUrl(context, REPO_URL) }
                .padding(vertical = 6.dp),
        )

        Spacer(Modifier.height(20.dp))

        SectionTitle("开源组件")
        InfoRow("Jetpack Compose", "界面")
        InfoRow("CameraX", "相机预览与逐帧分析")
        InfoRow("ZXing", "二维码编码与解码")

        Spacer(Modifier.height(20.dp))

        SectionTitle("许可证")
        Text("GPL-3.0", fontSize = 14.sp, color = Ink, modifier = Modifier.padding(vertical = 6.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = Border, thickness = 1.dp)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun InfoRow(name: String, purpose: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontSize = 14.sp, color = Ink)
        Text(purpose, fontSize = 12.sp, color = Muted)
    }
}

@Suppress("DEPRECATION")
private fun appVersion(context: Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "—"

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
