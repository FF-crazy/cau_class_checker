package com.ffcrazy.cauclasschecker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ffcrazy.cauclasschecker.AccountUiState
import com.ffcrazy.cauclasschecker.AccountViewModel
import com.ffcrazy.cauclasschecker.ui.theme.Border
import com.ffcrazy.cauclasschecker.ui.theme.ErrorRed
import com.ffcrazy.cauclasschecker.ui.theme.Green
import com.ffcrazy.cauclasschecker.ui.theme.GreenDeep
import com.ffcrazy.cauclasschecker.ui.theme.Ink
import com.ffcrazy.cauclasschecker.ui.theme.Muted
import com.ffcrazy.cauclasschecker.web.StoredAccount
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 左滑露出的操作区总宽度（每个按钮 74dp，两个）。 */
private val ACTIONS_WIDTH = 148.dp
private val ACTION_BUTTON_WIDTH = 74.dp

@Composable
fun AccountScreen(state: AccountUiState, vm: AccountViewModel, modifier: Modifier = Modifier) {
    if (state.onLoginScreen) {
        LoginFormScreen(state = state, vm = vm, modifier = modifier)
    } else {
        AccountListScreen(state = state, vm = vm, modifier = modifier)
    }
}

// ------------------------------------------------------------------ 一级：账号清单

@Composable
private fun AccountListScreen(state: AccountUiState, vm: AccountViewModel, modifier: Modifier = Modifier) {
    // 保证同一时刻只展开一行
    var revealedUser by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize()) {
        AppHeader("账号管理")

        Box(Modifier.fillMaxSize()) {
            if (state.accounts.isEmpty()) {
                EmptyHint()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.accounts, key = { it.username }) { account ->
                        SwipeRevealRow(
                            revealed = revealedUser == account.username,
                            onRevealedChange = { open ->
                                revealedUser = if (open) account.username else null
                            },
                            actionsWidth = ACTIONS_WIDTH,
                            actions = {
                                SwipeAction("验活", GreenDeep) {
                                    revealedUser = null
                                    vm.verify(account.username)
                                }
                                SwipeAction("删除", MaterialTheme.colorScheme.error) {
                                    revealedUser = null
                                    pendingDelete = account.username
                                }
                            },
                        ) {
                            AccountCard(
                                account = account,
                                onClick = {
                                    // 展开状态下点卡片先收起，避免误触
                                    if (revealedUser == account.username) {
                                        revealedUser = null
                                    } else {
                                        vm.openLogin(account.username)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // 圆形加号，浮在右下角
            FloatingActionButton(
                onClick = { vm.openLogin() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
                shape = CircleShape,
                containerColor = Green,
                contentColor = Color.White,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加账号")
            }
        }
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = { Text("删除账号", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink) },
            text = {
                Text(
                    if (name == state.activeUser) {
                        "删除 $name？它当前正在登录，删除后会一并退出登录。"
                    } else {
                        "从清单里移除 $name？账号本身不受影响，随时可以重新登录。"
                    },
                    fontSize = 14.sp,
                    color = Ink,
                    lineHeight = 21.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeAccount(name)
                    pendingDelete = null
                }) {
                    Text("删除", color = ErrorRed, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = Muted)
                }
            },
        )
    }
}

@Composable
private fun RowScope.SwipeAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxHeight()
            .width(ACTION_BUTTON_WIDTH)
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyHint() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有登录过账号", fontSize = 15.sp, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "点右下角的 + 用统一身份认证登录。",
            fontSize = 13.sp,
            color = Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AccountCard(account: StoredAccount, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Border, RoundedCornerShape(12.dp)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                account.username,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "上次登录 ${formatTime(account.loginAt)}",
                fontSize = 12.sp,
                color = Muted,
            )
        }
    }
}

// ------------------------------------------------------------------ 二级：登录表单

@Composable
private fun LoginFormScreen(state: AccountUiState, vm: AccountViewModel, modifier: Modifier = Modifier) {
    // 从清单点进来时预填用户名，只需要输密码
    var username by rememberSaveable(state.prefillUsername) { mutableStateOf(state.prefillUsername) }
    var password by rememberSaveable { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.closeLogin() }) {
                Text("← 返回", fontSize = 15.sp, color = GreenDeep, fontWeight = FontWeight.Medium)
            }
        }
        HorizontalDivider(color = Border, thickness = 1.dp)

        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Text("统一身份认证", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "登录后签到请求会带上你的身份。密码不会被保存。",
                fontSize = 13.sp,
                color = Muted,
                lineHeight = 20.sp,
            )

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("学号 / 工号") },
                singleLine = true,
                enabled = !state.busy,
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("密码") },
                singleLine = true,
                enabled = !state.busy,
                shape = RoundedCornerShape(10.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { vm.login(username, password) },
                enabled = !state.busy && username.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Green,
                    contentColor = Color.White,
                    disabledContainerColor = Green.copy(alpha = 0.4f),
                ),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("正在登录…", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("登录", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                "密码只用于本次登录，不会保存在手机上。登录凭证由学校服务器决定有效期，过期后需要重新登录。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 19.sp,
            )
        }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatTime(epochMillis: Long): String =
    runCatching {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
    }.getOrDefault("—")
