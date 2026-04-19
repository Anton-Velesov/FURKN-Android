package com.furkn.vpn.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.furkn.vpn.R

private val AppBg = Color(0xFF000000)
private val CardBg = Color(0xFF111111)
private val SoftText = Color(0xFFE7E7E7)
private val LineColor = Color(0xFF3A3A3A)

@Composable
fun AppScreen(
    viewModel: AppViewModel = viewModel(),
    onConnectRequest: () -> Unit,
    onDisconnectRequest: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    MaterialTheme {
        when (state.currentScreen) {
            "login" -> LoginScreen(
                phone = state.phone,
                isLoading = state.isLoading,
                message = state.message,
                onPhoneChange = viewModel::updatePhone,
                onSendSmsClick = viewModel::sendCode
            )

            "code" -> CodeScreen(
                phone = state.phone,
                code = state.code,
                isLoading = state.isLoading,
                message = state.message,
                onCodeChange = viewModel::updateCode,
                onBackClick = viewModel::backToLogin,
                onVerifyClick = viewModel::verifyCode
            )

            "main" -> MainScreen(
                loginToken = state.loginToken,
                isConnected = state.isConnected,
                onConnectClick = {
                    onConnectRequest()
                    viewModel.markConnected()
                },
                onDisconnectClick = {
                    onDisconnectRequest()
                    viewModel.disconnect()
                },
                onTerpilyClick = {
                    viewModel.openTerpilyScreen(context)
                }
            )

            "terpily" -> TerpilyScreen(
                installedApps = state.installedApps,
                excludedPackages = state.excludedPackages,
                onAddClick = { packageName ->
                    viewModel.addExcludedPackage(context, packageName)
                },
                onDeleteClick = { packageName ->
                    viewModel.removeExcludedPackage(context, packageName)
                },
                onBackClick = viewModel::backToMain
            )
        }
    }
}
@Composable
fun LoginScreen(
    phone: String,
    isLoading: Boolean,
    message: String?,
    onPhoneChange: (String) -> Unit,
    onSendSmsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("FURKN VPN", color = SoftText)

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            label = { Text("Твои цифры", color = SoftText) },
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF151515), RoundedCornerShape(12.dp)),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SoftText),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = SoftText,
                unfocusedTextColor = SoftText,
                focusedContainerColor = Color(0xFF151515),
                unfocusedContainerColor = Color(0xFF151515),
                focusedBorderColor = Color.White,
                unfocusedBorderColor = LineColor,
                focusedLabelColor = SoftText,
                unfocusedLabelColor = SoftText,
                cursorColor = Color.White
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSendSmsClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = CardBg,
                contentColor = SoftText
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Чекни")
        }

        if (isLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
        }

        if (message != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, color = SoftText)
        }
    }
}

@Composable
fun CodeScreen(
    phone: String,
    code: String,
    isLoading: Boolean,
    message: String?,
    onCodeChange: (String) -> Unit,
    onBackClick: () -> Unit,
    onVerifyClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Чекаем", color = SoftText)

        Spacer(modifier = Modifier.height(8.dp))

        Text("Цифры: $phone", color = SoftText)

        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("Чекаем", color = SoftText) },
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF151515), RoundedCornerShape(12.dp)),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SoftText),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = SoftText,
                unfocusedTextColor = SoftText,
                focusedContainerColor = Color(0xFF151515),
                unfocusedContainerColor = Color(0xFF151515),
                focusedBorderColor = Color.White,
                unfocusedBorderColor = LineColor,
                focusedLabelColor = SoftText,
                unfocusedLabelColor = SoftText,
                cursorColor = Color.White
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onVerifyClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = CardBg,
                contentColor = SoftText
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Угадал?")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onBackClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = CardBg,
                contentColor = SoftText
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Ну нах")
        }

        if (isLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
        }

        if (message != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, color = SoftText)
        }
    }
}

@Composable
fun MainScreen(
    loginToken: String?,
    isConnected: Boolean,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onTerpilyClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DarkMenuButton(
                text = "Терпилы",
                onClick = onTerpilyClick
            )

            Spacer(modifier = Modifier.height(12.dp))

            Image(
                painter = painterResource(
                    id = if (isConnected) R.drawable.shackles else R.drawable.rkn_ban
                ),
                contentDescription = if (isConnected) "Disconnect VPN" else "Connect VPN",
                modifier = Modifier
                    .size(if (isConnected) 250.dp else 180.dp)
                    .offset(y = if (isConnected) 0.dp else 60.dp)
                    .clickable(
                        enabled = !loginToken.isNullOrBlank() || isConnected
                    ) {
                        if (isConnected) {
                            onDisconnectClick()
                        } else {
                            onConnectClick()
                        }
                    },
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(if (isConnected) 18.dp else 90.dp))

            Image(
                painter = painterResource(id = R.drawable.faces_photo),
                contentDescription = "Faces photo",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp)),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun TerpilyScreen(
    installedApps: List<InstalledAppItem>,
    excludedPackages: List<String>,
    onAddClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onBackClick: () -> Unit
) {
    val suggestedApps = installedApps.filter { it.isSuggested }
    val otherApps = installedApps.filter { !it.isSuggested }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBg)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DarkMenuButton(
            text = "Ну нах",
            onClick = onBackClick
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Чёрный список терпил",
            color = SoftText,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (suggestedApps.isNotEmpty()) {
                item {
                    Text(
                        text = "Частые терпилы",
                        color = SoftText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(suggestedApps) { app ->
                    AppRow(
                        label = app.label,
                        packageName = app.packageName,
                        isSelected = excludedPackages.contains(app.packageName),
                        onAddClick = onAddClick,
                        onDeleteClick = onDeleteClick
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Все приложения",
                        color = SoftText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            items(otherApps) { app ->
                AppRow(
                    label = app.label,
                    packageName = app.packageName,
                    isSelected = excludedPackages.contains(app.packageName),
                    onAddClick = onAddClick,
                    onDeleteClick = onDeleteClick
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        DarkMenuButton(
            text = "Применить",
            onClick = onBackClick
        )
    }
}

@Composable
private fun AppRow(
    label: String,
    packageName: String,
    isSelected: Boolean,
    onAddClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(CardBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = label,
                color = SoftText,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = packageName,
                color = Color(0xFF8E8E8E),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Button(
            onClick = {
                if (isSelected) {
                    onDeleteClick(packageName)
                } else {
                    onAddClick(packageName)
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSelected) Color(0xFF7A1F1F) else Color(0xFF1F4D2B),
                contentColor = SoftText
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(if (isSelected) "Убрать" else "В список")
        }
    }
}
@Composable
private fun DarkMenuButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CardBg,
            contentColor = SoftText
        )
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge
        )
    }
}