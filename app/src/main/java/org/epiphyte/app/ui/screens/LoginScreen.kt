package org.epiphyte.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.epiphyte.app.controller.AppController
import org.epiphyte.app.ui.theme.*

@Composable
fun LoginScreen(
    controller: AppController,
    onLoginSuccess: () -> Unit
) {
    var isCreateMode by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var useBridges by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.15f))

        Text(
            text = "\uD83C\uDF3F Epiphyte",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "End-to-end encrypted messenger",
            fontSize = 13.sp,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Login / Create toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = { isCreateMode = false },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!isCreateMode) AccentBlue else DarkSurfaceVariant
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Login", fontSize = 14.sp)
            }
            Button(
                onClick = { isCreateMode = true },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCreateMode) AccentBlue else DarkSurfaceVariant
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Create", fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Passphrase input
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it; errorText = "" },
            placeholder = { Text("Enter passphrase...", color = TextMuted) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = DarkSurfaceVariant,
                focusedContainerColor = DarkSurface,
                unfocusedContainerColor = DarkSurface,
                cursorColor = AccentBlue,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(imeAction = if (isCreateMode) ImeAction.Next else ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (!isCreateMode && passphrase.isNotBlank()) {
                    scope.launch { doUnlock(controller, passphrase, "", false, useBridges, { errorText = it }, { isLoading = it }, onLoginSuccess) }
                }
            })
        )

        // Confirm passphrase (create mode)
        if (isCreateMode) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = confirmPassphrase,
                onValueChange = { confirmPassphrase = it; errorText = "" },
                placeholder = { Text("Confirm passphrase...", color = TextMuted) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = DarkSurfaceVariant,
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface,
                    cursorColor = AccentBlue,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    scope.launch { doUnlock(controller, passphrase, confirmPassphrase, true, useBridges, { errorText = it }, { isLoading = it }, onLoginSuccess) }
                })
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bridge toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = useBridges,
                onCheckedChange = { useBridges = it },
                colors = CheckboxDefaults.colors(
                    checkedColor = AccentBlue,
                    uncheckedColor = TextSecondary
                )
            )
            Text(
                text = "Use bridges (China, Iran, etc.)",
                fontSize = 13.sp,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Unlock button
        Button(
            onClick = {
                scope.launch {
                    doUnlock(controller, passphrase, confirmPassphrase, isCreateMode, useBridges, { errorText = it }, { isLoading = it }, onLoginSuccess)
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            shape = RoundedCornerShape(8.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = if (isCreateMode) "Create & Enter" else "Unlock",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        // Error
        if (errorText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = errorText, color = ErrorRed, fontSize = 12.sp, textAlign = TextAlign.Center)
        }

        Spacer(modifier = Modifier.weight(0.2f))

        Text(text = "v2.0.0", fontSize = 10.sp, color = TextMuted)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private suspend fun doUnlock(
    controller: AppController,
    passphrase: String,
    confirm: String,
    isCreate: Boolean,
    useBridges: Boolean,
    setError: (String) -> Unit,
    setLoading: (Boolean) -> Unit,
    onSuccess: () -> Unit
) {
    if (passphrase.isBlank()) return

    if (isCreate) {
        if (passphrase.length < 4) {
            setError("Passphrase must be at least 4 characters.")
            return
        }
        if (passphrase != confirm) {
            setError("Passphrases don't match!")
            return
        }
    }

    setLoading(true)
    try {
        val success = withContext(Dispatchers.IO) {
            controller.unlock(passphrase, isCreate, useBridges)
        }
        if (success) {
            controller.startTor()
            onSuccess()
        } else {
            setError("Wrong passphrase.")
        }
    } catch (e: Exception) {
        setError("Error: ${e.message?.take(50) ?: "Unknown"}")
    } finally {
        setLoading(false)
    }
}
