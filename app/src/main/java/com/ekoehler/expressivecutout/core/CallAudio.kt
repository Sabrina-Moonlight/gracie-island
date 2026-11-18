package com.ekoehler.expressivecutout.core

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.getSystemService
import com.ekoehler.expressivecutout.system.ShizukuState
import com.ekoehler.expressivecutout.system.ShizukuStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The voice-chat app whose audio the deafen button silences when the call app is not known. */
private const val DEFAULT_VOICE_APP = "com.discord"

private const val TAG = "CallAudio"

/** How many times lifting the silence is tried before giving up, each one checked afterwards. */
private const val LIFT_ATTEMPTS = 3

/** Pause before the system is asked whether the silence is really gone, so it has applied it. */
private const val LIFT_SETTLE_MS = 250L

/** Taps closer together than this count as one, so a bounced touch can't undo its own toggle. */
private const val TOGGLE_DEBOUNCE_MS = 600L

/**
 * The in-call audio route behind the expanded phone tile's Mute and Speaker buttons. Dialers expose
 * neither as a notification action, so the two toggles drive [AudioManager] directly and this bus
 * holds what the platform last reported so the buttons can light up while they are on. Deliberately
 * re-read from the platform on every call rather than cached: the dialer's own in-call screen
 * changes both behind our back. Sits beside [OnCallBus], which holds who the call is with.
 */
object CallAudioBus {

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _speakerOn = MutableStateFlow(false)
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    private val _deafened = MutableStateFlow(false)
    val deafened: StateFlow<Boolean> = _deafened.asStateFlow()

    /**
     * Shizuku work blocks until the shell command exits, so it runs here, off the main thread. The
     * object lives as long as the process and its scope holds nothing to release, so it is never
     * cancelled.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Serialises deafen work, so two quick taps can never apply their changes out of order. */
    private val deafenLock = Any()

    /** The app whose audio [toggleDeafen] silenced through Shizuku, or null when nothing is held. */
    private var silencedApp: String? = null

    /** Whether the voice-call stream was muted by [toggleDeafen] as the fallback, without Shizuku. */
    private var streamMutedByUs = false

    /** The voice-call volume before the fallback muted it, put back when the call is audible again. */
    private var savedCallVolume: Int? = null

    /** Whether the microphone was muted by [toggleDeafen] (it was open before), so only then is it opened again. */
    private var micMutedByUs = false

    /** When the last accepted tap happened, on the monotonic clock. */
    private var lastToggleMs = 0L

    /** Kept so [releaseDeafen] can run from [OnCallBus], which has no context of its own. */
    private var appContext: Context? = null

    /** Re-reads the platform's microphone-mute and speakerphone state into the flows. */
    fun refresh(context: Context) {
        appContext = context.applicationContext
        val audio = context.getSystemService<AudioManager>() ?: return
        _muted.value = audio.isMicrophoneMute
        _speakerOn.value = audio.speakerOn
        healStaleSilence()
    }

    /** Whether the check for a silence left behind by an earlier run has already been done. */
    private var healChecked = false

    /**
     * A silence set by an earlier run (or version) of the app outlives it and nothing remembers it,
     * which would leave the call app mute for good. Once per run, when a call starts, ask the system
     * what it really holds for the call app and lift it if it is still silenced.
     */
    private fun healStaleSilence() {
        if (healChecked || ShizukuState.status.value != ShizukuStatus.READY) return
        scope.launch { synchronized(deafenLock) { healLocked() } }
    }

    /** The body of [healStaleSilence]; runs under [deafenLock], so it never races a tap. */
    private fun healLocked() {
        if (healChecked || silencedApp != null || streamMutedByUs) return
        val state = appOpState(DEFAULT_VOICE_APP) ?: return
        healChecked = true
        // A live sound can stay silenced even with the permission already back on allow.
        val stuck = ShizukuState.shellOutput("dumpsys audio | grep -i opPlayAudio")
            ?.isNotBlank() == true
        if (state.isSilenced() || stuck) liftAppOp(DEFAULT_VOICE_APP)
    }

    /** What `appops` reports for [app]'s audio, or null when Shizuku could not be asked. */
    private fun appOpState(app: String): String? =
        ShizukuState.shellOutput("appops get $app PLAY_AUDIO 2>&1")

    private fun String.isSilenced() = contains("ignore", ignoreCase = true) ||
        contains("deny", ignoreCase = true)

    /** The numeric UID of [app], which `appops --uid` needs: it does not take a package name. */
    private fun uidOf(app: String): Int? = runCatching {
        appContext?.packageManager?.getApplicationInfo(app, 0)?.uid
    }.getOrNull()

    /**
     * Lets [app] play again. Setting the permission to allow is not enough: a sound that was already
     * silenced keeps its silence while the permission is merely "allow" (the system only re-checks it
     * on a real change). So the numeric UID is taken through ignore and then allow, which makes the
     * system re-check the live call, and the package is left on allow. What the system reports
     * afterwards is checked and the whole thing retried. Returns whether the app is audible again.
     */
    private fun liftAppOp(app: String): Boolean {
        val uid = uidOf(app)
        val script = buildString {
            if (uid != null) {
                append("appops set --uid $uid PLAY_AUDIO ignore; ")
                append("appops set --uid $uid PLAY_AUDIO allow; ")
            }
            append("appops set $app PLAY_AUDIO allow")
        }
        repeat(LIFT_ATTEMPTS) {
            ShizukuState.runAsShell(script)
            Thread.sleep(LIFT_SETTLE_MS)
            val state = appOpState(app)
            Log.i(TAG, "PLAY_AUDIO of $app after lifting: $state")
            if (state != null && !state.isSilenced()) return true
        }
        return false
    }

    /**
     * Silences (or restores) everything the other side says. A voice chat app like Discord plays
     * the call on a stream the platform volume cannot reliably mute, so with Shizuku ready the app's
     * own audio is switched off through its `PLAY_AUDIO` app op, which the system applies to the
     * sound already playing. Without Shizuku, or for a call app that is not a voice chat, it falls
     * back to muting the voice-call stream. The flow flips at once and is corrected if that failed.
     */
    fun toggleDeafen(context: Context) {
        appContext = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (now - lastToggleMs < TOGGLE_DEBOUNCE_MS) return
        lastToggleMs = now
        scope.launch {
            synchronized(deafenLock) {
                // Decided from what was really applied, never from the flow the button reads.
                val silence = silencedApp == null && !streamMutedByUs
                _deafened.value = if (silence) silenceCall() else restoreCall()
            }
        }
    }

    /** Called when the call ends: the silence outlives the call, so it must be lifted here. */
    fun releaseDeafen() {
        if (silencedApp == null && !streamMutedByUs) return
        scope.launch { synchronized(deafenLock) { _deafened.value = restoreCall() } }
    }

    /** Silences the call and the microphone, and returns whether the call is now silent. */
    private fun silenceCall(): Boolean {
        val silent = silenceAudio()
        if (silent) muteMic()
        return silent
    }

    /** Deafening also mutes the microphone, unless the user had already muted it themselves. */
    private fun muteMic() {
        val audio = appContext?.getSystemService<AudioManager>() ?: return
        if (!audio.isMicrophoneMute) {
            runCatching { audio.isMicrophoneMute = true }
            micMutedByUs = audio.isMicrophoneMute
        }
        _muted.value = audio.isMicrophoneMute
    }

    /** Opens the microphone again, but only if [muteMic] is what closed it. */
    private fun unmuteMic() {
        if (!micMutedByUs) return
        val audio = appContext?.getSystemService<AudioManager>() ?: return
        runCatching { audio.isMicrophoneMute = false }
        micMutedByUs = false
        _muted.value = audio.isMicrophoneMute
    }

    /** The part of [silenceCall] that silences what the other side says. */
    private fun silenceAudio(): Boolean {
        val callApp = OnCallBus.state.value?.packageName
        val voiceApp = (callApp ?: DEFAULT_VOICE_APP).takeIf { it == DEFAULT_VOICE_APP }
        if (voiceApp != null && ShizukuState.status.value == ShizukuStatus.READY &&
            ShizukuState.runAsShell("appops set $voiceApp PLAY_AUDIO ignore")
        ) {
            silencedApp = voiceApp
            return true
        }
        val audio = appContext?.getSystemService<AudioManager>() ?: return false
        savedCallVolume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0) }
        streamMutedByUs = audio.isStreamMute(AudioManager.STREAM_VOICE_CALL)
        return streamMutedByUs
    }

    /**
     * Undoes whatever [silenceCall] did, and returns whether the call is still silent. The voice app
     * is always let through, even when nothing is remembered as silenced. If the system still holds
     * the silence after every try, it stays remembered and this returns true, so the button tells the
     * truth and the next tap tries again.
     */
    private fun restoreCall(): Boolean {
        val stillSilent = restoreAudio()
        if (!stillSilent) unmuteMic()
        return stillSilent
    }

    /** The part of [restoreCall] that lets the other side be heard again. */
    private fun restoreAudio(): Boolean {
        val app = silencedApp ?: DEFAULT_VOICE_APP
        if (ShizukuState.status.value == ShizukuStatus.READY) {
            if (!liftAppOp(app)) {
                silencedApp = app
                return true
            }
        } else if (silencedApp != null) {
            // Shizuku is out of reach, so the silence cannot be lifted yet; tapping again retries.
            return true
        }
        silencedApp = null
        // Always checked, not only after our own fallback: a mute left on the stream by an earlier
        // try would otherwise keep the call silent however the app op is set.
        val audio = appContext?.getSystemService<AudioManager>()
        if (audio != null && (streamMutedByUs || audio.isStreamMute(AudioManager.STREAM_VOICE_CALL))) {
            runCatching { audio.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0) }
            if (audio.isStreamMute(AudioManager.STREAM_VOICE_CALL)) {
                @Suppress("DEPRECATION")
                runCatching { audio.setStreamMute(AudioManager.STREAM_VOICE_CALL, false) }
            }
            savedCallVolume?.let { volume ->
                runCatching { audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, volume, 0) }
            }
        }
        streamMutedByUs = false
        savedCallVolume = null
        return false
    }

    /** Flips the microphone mute, then re-reads it so the button shows what actually took effect. */
    fun toggleMute(context: Context) {
        val audio = context.getSystemService<AudioManager>() ?: return
        runCatching { audio.isMicrophoneMute = !audio.isMicrophoneMute }
        _muted.value = audio.isMicrophoneMute
    }

    /** Routes the call to the built-in speaker, or back to the default device, then re-reads it. */
    fun toggleSpeaker(context: Context) {
        val audio = context.getSystemService<AudioManager>() ?: return
        runCatching { audio.speakerOn = !audio.speakerOn }
        _speakerOn.value = audio.speakerOn
    }

    /**
     * Speakerphone through whichever API the running platform supports: the communication-device
     * routing added in Android 12, and the long-deprecated flag on everything below it.
     */
    private var AudioManager.speakerOn: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            isSpeakerphoneOn
        }
        set(value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (value) {
                    availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { setCommunicationDevice(it) }
                } else {
                    clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                isSpeakerphoneOn = value
            }
        }
}
