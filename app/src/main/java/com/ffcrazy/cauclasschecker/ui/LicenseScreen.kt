package com.ffcrazy.cauclasschecker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ffcrazy.cauclasschecker.ui.theme.Border
import com.ffcrazy.cauclasschecker.ui.theme.GreenDeep
import com.ffcrazy.cauclasschecker.ui.theme.Ink
import com.ffcrazy.cauclasschecker.ui.theme.Muted

/**
 * 开源协议原文。
 *
 * 正文直接从 `assets/gpl-3.0.txt` 读 —— 就是仓库根目录那份 LICENSE 的副本，
 * 不硬编码进代码，换许可证时只要替换资源文件。
 *
 * 用 LazyColumn 而不是一个超长 Text：GPL-3.0 有六百多行，
 * 单节点渲染整段文本会卡在布局阶段，按行懒加载则只渲染可见部分。
 */
@Composable
fun LicenseScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lines = remember {
        runCatching {
            context.assets.open(LICENSE_ASSET).bufferedReader().use { it.readLines() }
        }.getOrDefault(emptyList())
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", fontSize = 15.sp, color = GreenDeep, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "GNU GPL v3",
                fontSize = 13.sp,
                color = Muted,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
        HorizontalDivider(color = Border, thickness = 1.dp)

        if (lines.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("协议原文缺失", fontSize = 14.sp, color = Muted)
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                item { Spacer(Modifier.height(14.dp)) }
                items(lines) { line ->
                    Text(
                        text = line.ifEmpty { " " },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        color = Ink,
                    )
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

private const val LICENSE_ASSET = "gpl-3.0.txt"
