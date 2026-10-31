package app.getknit.knit.ui.emergency

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.emergency.EmergencyRepository
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.emergency.EmergencyCodec
import app.getknit.knit.emergency.EmergencyIds
import app.getknit.knit.emergency.EmergencyKind
import app.getknit.knit.emergency.EmergencyLocation
import app.getknit.knit.emergency.EmergencyPayload
import app.getknit.knit.emergency.EmergencySeverity
import app.getknit.knit.emergency.EmergencyValidation
import app.getknit.knit.identity.Identity
import app.getknit.knit.location.LocationFix
import app.getknit.knit.location.LocationFixPolicy
import app.getknit.knit.location.LocationPrecision
import app.getknit.knit.location.LocationSource
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.ui.OfferedConversations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.ceil

internal class EmergencyViewModel(
    private val groups: GroupRepository,
    private val messages: MessageRepository,
    private val mesh: MeshController,
    private val emergencyRepository: EmergencyRepository,
    private val identity: Identity,
    private val locationSource: LocationSource,
    private val offeredConversations: OfferedConversations,
) : ViewModel() {
    private val _result = MutableStateFlow<EmergencyResult?>(null)
    val result: StateFlow<EmergencyResult?> = _result

    private val _reports = MutableStateFlow<List<LocalEmergencyReport>>(emptyList())
    val reports: StateFlow<List<LocalEmergencyReport>> = _reports

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private val _stagedLocation = MutableStateFlow<EmergencyLocation?>(null)
    val stagedLocation: StateFlow<EmergencyLocation?> = _stagedLocation

    private val _locationStatus = MutableStateFlow(LocationStatus.Idle)
    val locationStatus: StateFlow<LocationStatus> = _locationStatus

    private val _selectedConversationId = MutableStateFlow<String?>(null)
    val selectedConversationId: StateFlow<String?> = _selectedConversationId

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText

    private val _severity = MutableStateFlow<EmergencySeverity?>(null)
    val severity: StateFlow<EmergencySeverity?> = _severity

    private val _selectableConversations = MutableStateFlow<List<EmergencyConversationOption>>(emptyList())
    val selectableConversations: StateFlow<List<EmergencyConversationOption>> = _selectableConversations

    private val _conversationsLoading = MutableStateFlow(true)
    val conversationsLoading: StateFlow<Boolean> = _conversationsLoading

    private var locationJob: Job? = null

    val isSecureFormReady: StateFlow<Boolean> =
        combine(_selectedConversationId, _messageText, _severity, _selectableConversations) { selected, message, severity, options ->
            selected != null &&
                options.any { it.conversationId == selected } &&
                messageError(message) == null &&
                severity != null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isDemoFormReady: StateFlow<Boolean> =
        combine(_messageText, _severity) { message, severity -> messageError(message) == null && severity != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        refreshSecureContacts()
    }

    fun refreshSecureContacts() {
        viewModelScope.launch {
            _conversationsLoading.value = true
            val snapshot = offeredConversations.snapshot()
            val activeGroups = groups.active().associateBy { it.groupId }
            _selectableConversations.value =
                snapshot.ids
                    .filter(::isSecureEmergencyDestination)
                    .mapNotNull { id ->
                        when (Conversations.kindFor(id)) {
                            ConversationKind.DM -> {
                                EmergencyConversationOption(id, snapshot.labels.labelFor(id).text, ConversationType.DM)
                            }

                            ConversationKind.GROUP -> {
                                val group = activeGroups[id] ?: return@mapNotNull null
                                val fallback =
                                    GroupMembersStore
                                        .decode(group.members)
                                        .asSequence()
                                        .filter { it != snapshot.me }
                                        .map { snapshot.labels.labelFor(it).name }
                                        .take(3)
                                        .joinToString()
                                        .ifBlank { "Secure group" }
                                EmergencyConversationOption(id, group.name.ifBlank { fallback }, ConversationType.GROUP)
                            }

                            else -> {
                                null
                            }
                        }
                    }.sortedWith(compareBy(EmergencyConversationOption::type, EmergencyConversationOption::displayName))
            if (_selectedConversationId.value !in _selectableConversations.value.map { it.conversationId }) {
                _selectedConversationId.value = null
            }
            _conversationsLoading.value = false
        }
    }

    fun updateSelectedConversation(id: String?) {
        _selectedConversationId.value =
            id?.takeIf { candidate ->
                _selectableConversations.value.any { it.conversationId == candidate }
            }
        _result.value = null
    }

    fun updateMessage(text: String) {
        _messageText.value = text
        _result.value = null
    }

    fun updateSeverity(severity: EmergencySeverity) {
        _severity.value = severity
        _result.value = null
    }

    fun submitLocalDemo() {
        val message = _messageText.value.trim()
        val selectedSeverity = _severity.value
        val error = messageError(message)
        if (error != null || selectedSeverity == null) {
            _result.value = EmergencyResult.ValidationFailed(error ?: EmergencyFieldError.SeverityMissing)
            return
        }
        val report =
            LocalEmergencyReport(
                emergencyId = EmergencyIds.new(),
                severity = selectedSeverity,
                message = message,
                timestamp = System.currentTimeMillis(),
                location = _stagedLocation.value,
            )
        _reports.value = listOf(report) + _reports.value
        _result.value = EmergencyResult.DemoSaved(report.emergencyId)
        clearDraft()
    }

    fun sendSos() {
        if (_isSending.value) return
        val conversationId = _selectedConversationId.value
        val message = _messageText.value.trim()
        val selectedSeverity = _severity.value
        val options = _selectableConversations.value
        val fieldError = messageError(message)
        if (conversationId == null || options.none { it.conversationId == conversationId }) {
            _result.value = EmergencyResult.ValidationFailed(EmergencyFieldError.SecureContactMissing)
            return
        }
        if (fieldError != null || selectedSeverity == null) {
            _result.value = EmergencyResult.ValidationFailed(fieldError ?: EmergencyFieldError.SeverityMissing)
            return
        }

        _isSending.value = true
        _result.value = null
        viewModelScope.launch {
            try {
                sendValidatedSos(conversationId, message, selectedSeverity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _result.value = EmergencyResult.SendFailed
            } finally {
                _isSending.value = false
            }
        }
    }

    private suspend fun sendValidatedSos(
        conversationId: String,
        message: String,
        severity: EmergencySeverity,
    ) {
        val now = System.currentTimeMillis()
        val payload =
            EmergencyPayload(
                kind = EmergencyKind.SOS,
                emergencyId = EmergencyIds.new(),
                severity = severity,
                createdAt = now,
                expiresAt = now + EmergencyValidation.MAX_LIFETIME_MS,
                message = message,
                location = _stagedLocation.value,
            )
        val body =
            runCatching { EmergencyCodec.encode(payload, now) }.getOrElse {
                _result.value = EmergencyResult.ValidationFailed(EmergencyFieldError.PayloadInvalid)
                return
            }
        val kind = Conversations.kindFor(conversationId)
        val group = if (kind == ConversationKind.GROUP) groups.find(conversationId)?.takeIf { !it.left }?.toGroupInfo() else null
        val recipientId = conversationId.takeIf { kind == ConversationKind.DM }
        if (recipientId == null && group == null) {
            _result.value = EmergencyResult.SecureContactUnavailable
            return
        }

        val messageId = FrameId.new()
        if (!mesh.sendChat(body, null, emptyList(), recipientId, group, null, messageId)) {
            _result.value = EmergencyResult.SendFailed
            return
        }
        val stored = messages.find(messageId)
        if (stored == null) {
            _result.value = EmergencyResult.PersistenceFailed
            return
        }
        val indexed =
            runCatching {
                emergencyRepository.indexAuthenticatedMessage(messageId, identity.nodeId())
                true
            }.getOrDefault(false)
        _result.value = EmergencyResult.SecureQueued(messageId, stored.pendingKey, indexed)
        clearDraft()
    }

    fun requestLocation() {
        locationJob?.cancel()
        locationJob = null
        val precision = locationSource.precision()
        if (precision == LocationPrecision.None) {
            _locationStatus.value = LocationStatus.PermissionDenied
            return
        }
        if (!locationSource.isEnabled()) {
            _locationStatus.value = LocationStatus.ServicesOff
            return
        }
        _locationStatus.value = LocationStatus.Acquiring
        locationJob =
            viewModelScope.launch {
                try {
                    val recent =
                        LocationFixPolicy.usableLastKnown(
                            locationSource.lastKnown(),
                            SystemClock.elapsedRealtime(),
                        )
                    _stagedLocation.value = recent?.toEmergencyLocationOrNull()
                    _locationStatus.value =
                        if (_stagedLocation.value == null) LocationStatus.Unavailable else LocationStatus.Attached
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _locationStatus.value = LocationStatus.Unavailable
                }
            }
    }

    fun locationPermissionDenied(permanently: Boolean) {
        _locationStatus.value =
            if (permanently) LocationStatus.PermissionDeniedPermanently else LocationStatus.PermissionDenied
    }

    fun clearLocation() {
        locationJob?.cancel()
        locationJob = null
        _stagedLocation.value = null
        _locationStatus.value = LocationStatus.Idle
    }

    private fun clearDraft() {
        _messageText.value = ""
        _severity.value = null
        clearLocation()
    }

    override fun onCleared() {
        locationJob?.cancel()
    }

    private fun LocationFix.toEmergencyLocationOrNull(): EmergencyLocation? {
        val accuracy = accuracyM?.takeIf { it.isFinite() && it > 0f } ?: return null
        return EmergencyLocation(
            latitude = lat,
            longitude = lon,
            accuracyMeters = ceil(accuracy.toDouble()).toInt().coerceAtLeast(1).toFloat(),
            capturedAt = timeMs,
        )
    }
}

internal fun messageError(message: String): EmergencyFieldError? =
    when {
        message.isBlank() -> EmergencyFieldError.MessageMissing
        message.length > EmergencyValidation.MAX_MESSAGE_CHARS -> EmergencyFieldError.MessageTooLong
        message.encodeToByteArray().size > EmergencyValidation.MAX_MESSAGE_BYTES -> EmergencyFieldError.MessageTooLong
        else -> null
    }

enum class EmergencyFieldError {
    SecureContactMissing,
    SeverityMissing,
    MessageMissing,
    MessageTooLong,
    PayloadInvalid,
}

sealed interface EmergencyResult {
    data class SecureQueued(
        val messageId: String,
        val waitingForRecipientKey: Boolean,
        val indexed: Boolean,
    ) : EmergencyResult

    data class DemoSaved(
        val emergencyId: String,
    ) : EmergencyResult

    data class ValidationFailed(
        val field: EmergencyFieldError,
    ) : EmergencyResult

    data object SecureContactUnavailable : EmergencyResult

    data object SendFailed : EmergencyResult

    data object PersistenceFailed : EmergencyResult
}

enum class LocationStatus {
    Idle,
    Acquiring,
    Attached,
    PermissionDenied,
    PermissionDeniedPermanently,
    ServicesOff,
    Unavailable,
}

data class LocalEmergencyReport(
    val emergencyId: String,
    val severity: EmergencySeverity,
    val message: String,
    val timestamp: Long,
    val location: EmergencyLocation?,
)

enum class ConversationType { DM, GROUP }

data class EmergencyConversationOption(
    val conversationId: String,
    val displayName: String,
    val type: ConversationType,
)

internal fun isSecureEmergencyDestination(conversationId: String): Boolean =
    when (Conversations.kindFor(conversationId)) {
        ConversationKind.DM, ConversationKind.GROUP -> true
        ConversationKind.NEARBY, ConversationKind.MESHTASTIC, ConversationKind.COMMONS -> false
    }
