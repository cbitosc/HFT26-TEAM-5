package app.getknit.knit.ui.emergency

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.emergency.EmergencySeverity
import app.getknit.knit.emergency.EmergencyValidation
import app.getknit.knit.ui.chat.rememberLocationGate
import org.koin.androidx.compose.koinViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyScreen(onBack: () -> Unit) {
    val viewModel: EmergencyViewModel = koinViewModel()
    val options by viewModel.selectableConversations.collectAsStateWithLifecycle()
    val conversationsLoading by viewModel.conversationsLoading.collectAsStateWithLifecycle()
    val message by viewModel.messageText.collectAsStateWithLifecycle()
    val severity by viewModel.severity.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedConversationId.collectAsStateWithLifecycle()
    val secureReady by viewModel.isSecureFormReady.collectAsStateWithLifecycle()
    val demoReady by viewModel.isDemoFormReady.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val location by viewModel.stagedLocation.collectAsStateWithLifecycle()
    val locationStatus by viewModel.locationStatus.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(EmergencyMode.Secure) }
    var recipientMenuOpen by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val locationGate = rememberLocationGate(viewModel::locationPermissionDenied)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emergency report") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .displayCutoutPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            EmergencyHeader(onCall112 = { dial112(context) })
            ModeSelector(selected = mode, onSelected = {
                mode = it
                showErrors = false
            })

            if (mode == EmergencyMode.Secure) {
                Section(
                    title = "Secure recipient",
                    description = "Choose an existing private chat. Nearby and public rooms are never used.",
                ) {
                    RecipientPicker(
                        options = options,
                        selectedId = selectedId,
                        loading = conversationsLoading,
                        expanded = recipientMenuOpen,
                        onExpandedChange = { recipientMenuOpen = it },
                        onSelected = viewModel::updateSelectedConversation,
                        onRefresh = viewModel::refreshSecureContacts,
                        showSelectionError = showErrors && selectedId == null,
                    )
                }
            } else {
                DemoWarning()
            }

            Section(title = "Emergency severity", description = "Choose the level that best matches the help you need.") {
                SeverityPicker(selected = severity, onSelected = viewModel::updateSeverity)
                if (showErrors && severity == null) {
                    FieldError("Select a severity level.")
                }
            }

            Section(title = "What is happening?", description = "Give the recipient clear, practical information.") {
                val messageError = messageError(message)
                OutlinedTextField(
                    value = message,
                    onValueChange = viewModel::updateMessage,
                    label = { Text("Emergency description") },
                    placeholder = { Text("For example: Injured person near the north gate") },
                    minLines = 4,
                    maxLines = 7,
                    isError = (showErrors || message.isNotEmpty()) && messageError != null,
                    supportingText = {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                when {
                                    showErrors && message.isBlank() -> "Describe the emergency."
                                    messageError == EmergencyFieldError.MessageTooLong -> "Shorten the description to send it safely."
                                    else -> "Include who needs help and where you are."
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Text("${message.length}/${EmergencyValidation.MAX_MESSAGE_CHARS}")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Section(title = "Location", description = "Optional. Your location is read only after you tap attach.") {
                LocationAttachment(
                    status = locationStatus,
                    accuracyMeters = location?.accuracyMeters,
                    onAttach = { locationGate.runOrRequest(viewModel::requestLocation) },
                    onRemove = viewModel::clearLocation,
                    onOpenLocationSettings = {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                    onOpenAppSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
            }

            Section(
                title = if (mode == EmergencyMode.Secure) "Transmission status" else "Demo status",
                description =
                    if (mode ==
                        EmergencyMode.Secure
                    ) {
                        "Queued is not the same as delivered or acknowledged."
                    } else {
                        "Demo reports remain only for this app session."
                    },
            ) {
                ResultCard(result = result)
                if (mode == EmergencyMode.Demo) {
                    DemoReports(reports)
                }
            }

            Button(
                onClick = {
                    showErrors = true
                    if (mode == EmergencyMode.Secure) showConfirm = true else viewModel.submitLocalDemo()
                },
                enabled = !isSending && if (mode == EmergencyMode.Secure) secureReady else demoReady,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Encrypting and queuing…")
                } else {
                    Icon(
                        if (mode == EmergencyMode.Secure) Icons.AutoMirrored.Filled.Send else Icons.Filled.ReportProblem,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (mode == EmergencyMode.Secure) "Review secure SOS" else "Save demo report")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showConfirm) {
        val recipient = options.firstOrNull { it.conversationId == selectedId }?.displayName ?: "selected secure contact"
        AlertDialog(
            onDismissRequest = { if (!isSending) showConfirm = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Send emergency SOS?") },
            text = {
                Text(
                    "This report will be encrypted for $recipient and queued on the offline mesh. " +
                        "Delivery is not guaranteed until a receipt arrives.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        viewModel.sendSos()
                    },
                    enabled = !isSending,
                ) { Text("Send secure SOS") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }, enabled = !isSending) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmergencyHeader(onCall112: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ReportProblem, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Request help", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Send an offline SOS to a secure contact, or rehearse the flow without transmitting.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            OutlinedButton(onClick = onCall112, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Call, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Call 112")
            }
        }
    }
}

@Composable
private fun ModeSelector(
    selected: EmergencyMode,
    onSelected: (EmergencyMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Report type", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = selected == EmergencyMode.Secure,
                onClick = { onSelected(EmergencyMode.Secure) },
                label = { Text("Secure SOS") },
                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
            )
            FilterChip(
                selected = selected == EmergencyMode.Demo,
                onClick = { onSelected(EmergencyMode.Demo) },
                label = { Text("Local demo") },
                leadingIcon = { Icon(Icons.Filled.ReportProblem, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Section(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipientPicker(
    options: List<EmergencyConversationOption>,
    selectedId: String?,
    loading: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    showSelectionError: Boolean,
) {
    if (loading) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text("Loading secure contacts…")
        }
    } else if (options.isEmpty()) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No secure contacts available", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Start or accept a private DM or group conversation first. SOS sending is disabled and will not fall back to Nearby.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Check again")
                }
            }
        }
    } else {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
            OutlinedTextField(
                value = options.firstOrNull { it.conversationId == selectedId }?.displayName.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Secure contact") },
                placeholder = { Text("Choose a contact or group") },
                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                isError = showSelectionError,
                supportingText = if (showSelectionError) ({ Text("Choose who should receive this SOS.") }) else null,
                modifier =
                    Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                        .fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (option.type == ConversationType.GROUP) "Encrypted group" else "Encrypted direct message",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(if (option.type == ConversationType.GROUP) Icons.Filled.Group else Icons.Filled.Person, null)
                        },
                        onClick = {
                            onSelected(option.conversationId)
                            onExpandedChange(false)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SeverityPicker(
    selected: EmergencySeverity?,
    onSelected: (EmergencySeverity) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SeverityOption(
            EmergencySeverity.ASSISTANCE,
            "Assistance",
            "Help is needed, but there is no immediate danger.",
            selected,
            onSelected,
        )
        SeverityOption(
            EmergencySeverity.URGENT,
            "Urgent",
            "Prompt help is needed to prevent the situation worsening.",
            selected,
            onSelected,
        )
        SeverityOption(EmergencySeverity.CRITICAL, "Critical", "There is immediate danger to life or safety.", selected, onSelected)
    }
}

@Composable
private fun SeverityOption(
    value: EmergencySeverity,
    title: String,
    description: String,
    selected: EmergencySeverity?,
    onSelected: (EmergencySeverity) -> Unit,
) {
    val isSelected = value == selected
    val selectedColor =
        when (value) {
            EmergencySeverity.ASSISTANCE -> MaterialTheme.colorScheme.secondaryContainer
            EmergencySeverity.URGENT -> MaterialTheme.colorScheme.tertiaryContainer
            EmergencySeverity.CRITICAL -> MaterialTheme.colorScheme.errorContainer
        }
    Surface(
        color = if (isSelected) selectedColor else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().selectable(isSelected, onClick = { onSelected(value) }, role = Role.RadioButton),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isSelected, onClick = null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LocationAttachment(
    status: LocationStatus,
    accuracyMeters: Float?,
    onAttach: () -> Unit,
    onRemove: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (status) {
                        LocationStatus.Idle -> "No location attached"
                        LocationStatus.Acquiring -> "Finding your location…"
                        LocationStatus.Attached -> "Location attached"
                        LocationStatus.PermissionDenied -> "Location permission was denied"
                        LocationStatus.PermissionDeniedPermanently -> "Location permission is blocked"
                        LocationStatus.ServicesOff -> "Device location is turned off"
                        LocationStatus.Unavailable -> "A location could not be obtained"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when (status) {
                        LocationStatus.Idle -> {
                            "Nothing will be attached unless you choose to add it."
                        }

                        LocationStatus.Acquiring -> {
                            "Checking for a recent device location."
                        }

                        LocationStatus.Attached -> {
                            accuracyMeters?.let { "Accuracy: approximately ±${it.toInt()} m" }
                                ?: "Accuracy unavailable"
                        }

                        LocationStatus.PermissionDenied -> {
                            "Allow approximate or precise location to attach a real reading."
                        }

                        LocationStatus.PermissionDeniedPermanently -> {
                            "Enable location permission in this app's system settings."
                        }

                        LocationStatus.ServicesOff -> {
                            "Turn on device location, then try again."
                        }

                        LocationStatus.Unavailable -> {
                            "No recent location is available. Open Maps or another location app, then retry."
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (status) {
                LocationStatus.Attached -> IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove attached location") }
                LocationStatus.Acquiring -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                else -> Unit
            }
        }
        HorizontalDivider()
        when (status) {
            LocationStatus.Idle -> {
                TextButton(onClick = onAttach, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Attach location") }
            }

            LocationStatus.PermissionDenied, LocationStatus.Unavailable -> {
                TextButton(onClick = onAttach, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Retry") }
            }

            LocationStatus.PermissionDeniedPermanently -> {
                TextButton(onClick = onOpenAppSettings, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Open app settings") }
            }

            LocationStatus.ServicesOff -> {
                TextButton(
                    onClick = onOpenLocationSettings,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("Open location settings") }
            }

            LocationStatus.Acquiring, LocationStatus.Attached -> {
                // The progress indicator and remove icon above are the actions for these states.
            }
        }
    }
}

@Composable
private fun DemoWarning() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Demo mode — saved on this device only. Not transmitted.", fontWeight = FontWeight.Bold)
                Text("Use this mode to demonstrate the form. It creates no mesh message or delivery status.")
            }
        }
    }
}

@Composable
private fun ResultCard(result: EmergencyResult?) {
    if (result == null) {
        Text("No report has been submitted yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val presentation = resultPresentation(result)
    Surface(color = presentation.container, contentColor = presentation.content, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(presentation.icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(presentation.title, fontWeight = FontWeight.SemiBold)
                Text(presentation.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun resultPresentation(result: EmergencyResult): ResultPresentation =
    when (result) {
        is EmergencyResult.SecureQueued -> {
            val body =
                when {
                    result.waitingForRecipientKey -> {
                        "Saved securely. It is waiting for the recipient's encryption key " +
                            "before it can leave this phone."
                    }

                    !result.indexed -> {
                        "Encrypted and queued for the mesh. Local emergency indexing could not be completed."
                    }

                    else -> {
                        "Encrypted, saved, and queued for mesh delivery. This does not mean delivered or acknowledged."
                    }
                }
            ResultPresentation(
                "SOS queued",
                body,
                Icons.Filled.CheckCircle,
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        is EmergencyResult.DemoSaved -> {
            ResultPresentation(
                "Demo report saved",
                "Stored in memory on this device only. Nothing was transmitted.",
                Icons.Filled.Warning,
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }

        is EmergencyResult.ValidationFailed -> {
            ResultPresentation(
                "Check the report",
                validationMessage(result.field),
                Icons.Filled.Warning,
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        EmergencyResult.SecureContactUnavailable -> {
            ResultPresentation(
                "Secure contact unavailable",
                "The selected conversation is no longer available. Choose another secure contact.",
                Icons.Filled.Warning,
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        EmergencyResult.SendFailed -> {
            ResultPresentation(
                "SOS was not queued",
                "The mesh could not accept this report. Your description remains available so you can retry.",
                Icons.Filled.Warning,
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        EmergencyResult.PersistenceFailed -> {
            ResultPresentation(
                "SOS status is uncertain",
                "The send returned without a local message record, so the app cannot claim it was queued.",
                Icons.Filled.Warning,
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

private fun validationMessage(error: EmergencyFieldError): String =
    when (error) {
        EmergencyFieldError.SecureContactMissing -> "Choose an existing secure contact or group."
        EmergencyFieldError.SeverityMissing -> "Select a severity level."
        EmergencyFieldError.MessageMissing -> "Describe the emergency."
        EmergencyFieldError.MessageTooLong -> "Shorten the emergency description."
        EmergencyFieldError.PayloadInvalid -> "The report could not be safely encoded. Review its details and retry."
    }

@Composable
private fun DemoReports(reports: List<LocalEmergencyReport>) {
    if (reports.isEmpty()) {
        Text("No demo reports saved in this session.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        reports.forEach { report ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(report.severity.readableName(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(report.message, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Saved ${DateFormat.getTimeInstance(
                            DateFormat.SHORT,
                        ).format(Date(report.timestamp))}${if (report.location != null) " · Location attached" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldError(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

private fun EmergencySeverity.readableName(): String =
    when (this) {
        EmergencySeverity.ASSISTANCE -> "Assistance"
        EmergencySeverity.URGENT -> "Urgent"
        EmergencySeverity.CRITICAL -> "Critical"
    }

private fun dial112(context: android.content.Context) {
    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
}

private enum class EmergencyMode { Secure, Demo }

private data class ResultPresentation(
    val title: String,
    val body: String,
    val icon: ImageVector,
    val container: Color,
    val content: Color,
)
