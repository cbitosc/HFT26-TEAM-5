package app.getknit.knit.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.getknit.knit.badge.BadgeAskState
import app.getknit.knit.badge.BadgeJournal
import app.getknit.knit.badge.HeldBadge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * The holder's side of the supporter badge (docs/SUPPORTER_BADGE.md §5, ADR 2026-10.r99s) over the settings DataStore,
 * beside the customer keys it is earned with. `badge_show` is a choice and rides a backup, as do `badge_sealed_version`
 * and `badge_published` (the version a badge-only change was published under, and what it published); `badge_held…` (the held
 * badges and the ask state) is this phone's and never does ([SettingsKeys.TRANSIENT_PREFIXES]), so a restored phone
 * shows nothing until it asks again with a fresh `r`. A value that fails to parse reads as absent.
 */
class BadgeStore(
    private val dataStore: DataStore<Preferences>,
) : BadgeJournal {
    override val show: Flow<Boolean> = dataStore.data.map { it[SHOW] ?: false }.distinctUntilChanged()

    override val held: Flow<List<HeldBadge>> =
        dataStore.data.map { prefs -> prefs[HELD]?.let(::decodeHeld).orEmpty() }.distinctUntilChanged()

    override val askState: Flow<BadgeAskState> =
        dataStore.data.map { prefs -> prefs[STATE]?.let(::decodeState) ?: BadgeAskState() }.distinctUntilChanged()

    override suspend fun setShow(on: Boolean) {
        dataStore.edit { it[SHOW] = on }
    }

    override suspend fun putHeld(
        badges: List<HeldBadge>,
        state: BadgeAskState,
    ) {
        dataStore.edit {
            if (badges.isEmpty()) it.remove(HELD) else it[HELD] = encodeHeld(badges)
            it[STATE] = encodeState(state)
        }
    }

    override suspend fun putAskState(state: BadgeAskState) {
        dataStore.edit { it[STATE] = encodeState(state) }
    }

    override val sealedVersion: Flow<Long> = dataStore.data.map { it[SEALED_VERSION] ?: 0L }.distinctUntilChanged()

    override val publishedFingerprint: Flow<String?> = dataStore.data.map { it[PUBLISHED] }.distinctUntilChanged()

    override suspend fun putSealed(
        version: Long,
        fingerprint: String,
    ) {
        dataStore.edit {
            it[SEALED_VERSION] = version
            it[PUBLISHED] = fingerprint
        }
    }

    /** Names nothing it holds. */
    override fun toString(): String = "BadgeStore"

    @Serializable
    private class StoredBadge(
        val w: String,
        val r: String,
    )

    @Serializable
    private class StoredState(
        val nb: Long = 0,
        val f: Int = 0,
    )

    companion object {
        val SHOW = booleanPreferencesKey(SettingsKeys.BADGE_SHOW)
        val HELD = stringPreferencesKey(SettingsKeys.BADGE_HELD)
        val STATE = stringPreferencesKey(SettingsKeys.BADGE_HELD_STATE)
        val SEALED_VERSION = longPreferencesKey(SettingsKeys.BADGE_SEALED_VERSION)
        val PUBLISHED = stringPreferencesKey(SettingsKeys.BADGE_PUBLISHED)

        private val json = Json { ignoreUnknownKeys = true }
        private val b64 = Base64.getEncoder()
        private val b64d = Base64.getDecoder()

        private fun encodeHeld(badges: List<HeldBadge>): String =
            json.encodeToString(badges.map { StoredBadge(b64.encodeToString(it.wrapper), b64.encodeToString(it.r)) })

        private fun decodeHeld(text: String): List<HeldBadge>? =
            runCatching {
                json.decodeFromString<List<StoredBadge>>(text).map { HeldBadge(b64d.decode(it.w), b64d.decode(it.r)) }
            }.getOrNull()

        private fun encodeState(state: BadgeAskState): String = json.encodeToString(StoredState(state.notBefore, state.failures))

        private fun decodeState(text: String): BadgeAskState? =
            runCatching { json.decodeFromString<StoredState>(text) }.getOrNull()?.let { BadgeAskState(it.nb, it.f) }
    }
}
