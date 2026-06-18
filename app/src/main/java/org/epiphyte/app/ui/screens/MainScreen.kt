package org.epiphyte.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.epiphyte.app.controller.AppController
import org.epiphyte.app.controller.AppStatus
import org.epiphyte.app.storage.Contact
import org.epiphyte.app.storage.StoredMessage
import org.epiphyte.app.ui.theme.*

@Composable
fun MainScreen(controller: AppController) {
    var currentPeer by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsDialog(controller = controller, onDismiss = { showSettings = false })
    }

    if (currentPeer != null) {
        ChatView(
            controller = controller,
            peerOnion = currentPeer!!,
            onBack = { currentPeer = null }
        )
    } else {
        ContactListView(
            controller = controller,
            onSelectPeer = { currentPeer = it },
            onSettings = { showSettings = true }
        )
    }
}

@Composable
fun ContactListView(
    controller: AppController,
    onSelectPeer: (String) -> Unit,
    onSettings: () -> Unit
) {
    val contacts by controller.contacts.collectAsState()
    val status by controller.status.collectAsState()
    var addAddress by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().background(DarkBackground)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(DarkSurface)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\uD83C\uDF3F Epiphyte",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            StatusIndicator(status)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "\u2699",
                fontSize = 20.sp,
                color = TextSecondary,
                modifier = Modifier.clickable { onSettings() }
            )
        }

        // Add contact bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = addAddress,
                onValueChange = { addAddress = it },
                placeholder = { Text("Add .onion address...", color = TextMuted, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(48.dp),
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
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (addAddress.isNotBlank()) {
                        controller.addContact(addAddress)
                        addAddress = ""
                    }
                })
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (addAddress.isNotBlank()) {
                        controller.addContact(addAddress)
                        addAddress = ""
                    }
                },
                modifier = Modifier.size(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Contact list
        val visibleContacts = contacts.filter { !it.blocked }
        if (visibleContacts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No contacts yet", color = TextMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(visibleContacts, key = { it.onionAddress }) { contact ->
                    ContactRow(contact = contact, onClick = { onSelectPeer(contact.onionAddress) })
                }
            }
        }
    }
}

@Composable
fun ContactRow(contact: Contact, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (contact.status == "connected") AccentGreen else TextMuted)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = contact.status,
                fontSize = 11.sp,
                color = TextMuted
            )
        }
    }
}

@Composable
fun ChatView(
    controller: AppController,
    peerOnion: String,
    onBack: () -> Unit
) {
    val contacts by controller.contacts.collectAsState()
    val allMessages by controller.messages.collectAsState()
    val contact = contacts.find { it.onionAddress == peerOnion }
    val peerName = contact?.displayName ?: "${peerOnion.take(12)}..."
    val peerConnected = controller.isPeerConnected(peerOnion)
    var msgInput by remember { mutableStateOf("") }
    val messages = remember(allMessages[peerOnion]) {
        allMessages[peerOnion] ?: controller.getMessages(peerOnion)
    }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        // Chat header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(DarkSurface)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\u2190",
                fontSize = 22.sp,
                color = TextPrimary,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp)
            )
            Text(
                text = peerName,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (peerConnected) {
                Text(text = "\uD83D\uDD12", fontSize = 16.sp)
            }
        }

        // Messages
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages, key = { it.msgId }) { msg ->
                MessageBubble(msg)
            }
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\uD83D\uDCCE",
                fontSize = 20.sp,
                color = TextSecondary,
                modifier = Modifier.clickable { /* file picker */ }.padding(end = 8.dp)
            )
            OutlinedTextField(
                value = msgInput,
                onValueChange = { msgInput = it },
                placeholder = { Text("Message...", color = TextMuted, fontSize = 14.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant,
                    cursorColor = AccentBlue,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(20.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (msgInput.isNotBlank()) {
                        controller.sendMessage(peerOnion, msgInput.trim())
                        msgInput = ""
                    }
                })
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (msgInput.isNotBlank()) {
                        controller.sendMessage(peerOnion, msgInput.trim())
                        msgInput = ""
                    }
                },
                modifier = Modifier.height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Text("Send", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun MessageBubble(msg: StoredMessage) {
    if (msg.msgType == "system") {
        Text(
            text = "\u2500\u2500\u2500 ${msg.text} \u2500\u2500\u2500",
            fontSize = 11.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (msg.isOurs) Arrangement.End else Arrangement.Start
        ) {
            if (msg.isOurs) Spacer(modifier = Modifier.weight(0.2f))
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .background(
                        if (msg.isOurs) AccentBlue.copy(alpha = 0.85f) else DarkSurfaceVariant,
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (msg.isOurs) 16.dp else 4.dp,
                            bottomEnd = if (msg.isOurs) 4.dp else 16.dp
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(
                    text = msg.text,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    lineHeight = 20.sp
                )
            }
            if (!msg.isOurs) Spacer(modifier = Modifier.weight(0.2f))
        }
    }
}

@Composable
fun StatusIndicator(status: AppStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (status.connected) AccentGreen else if (status.text == "Failed") ErrorRed else Color(0xFFE6B31A))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = status.text,
            fontSize = 11.sp,
            color = if (status.connected) AccentGreen else TextSecondary
        )
    }
}

@Composable
fun SettingsDialog(controller: AppController, onDismiss: () -> Unit) {
    val address = if (controller.torManager.onionAddress.isNotEmpty())
        "${controller.torManager.onionAddress}.onion"
    else "Loading..."

    val fingerprint = controller.identity?.getFingerprint() ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = { Text("Settings", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your Address:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(address, color = TextPrimary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Fingerprint:", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(fingerprint, color = TextPrimary, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AccentBlue)
            }
        }
    )
}
