package com.ekoehler.expressivecutout.core

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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

    /** Kept so [releaseDeafen] can run from [OnCallBus], which has no context of its own. */
    private var appContext: Context? = null

    /** Re-reads the platform's microphone-mute and speakerphone state into the flows. */
    fun refresh(context: Context) {
        val audio = context.getSystemService<AudioManager>() ?: return
        _muted.value = audio.isMicrophoneMute
        _speakerOn.value = audio.speakerOn
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
        val silence = !_deafened.value
        _deafened.value = silence
        scope.launch {
            synchronized(deafenLock) {
                _deafened.value = if (silence) silenceCall() else restoreCall()
            }
        }
    }

    /** Called when the call ends: the silence outlives the call, so it must be lifted here. */
    fun releaseDeafen() {
        if (!_deafened.value) return
        _deafened.value = false
        scope.launch { synchronized(deafenLock) { restoreCall() } }
    }

    /** Silences the call and returns whether it is now silent. */
    private fun silenceCall(): Boolean {
        val callApp = OnCallBus.state.value?.packageName
        val voiceApp = (callApp ?: DEFAULT_VOICE_APP).takeIf { it == DEFAULT_VOICE_APP }
        if (voiceApp != null && ShizukuState.status.value == ShizukuStatus.READY &&
            ShizukuState.runAsShell("appops set $voiceApp PLAY_AUDIO ignore")
        ) {
            silencedApp = voiceApp
            return true
        }
        val audio = appContext?.getSystemService<AudioManager>() ?: return false
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0) }
        streamMutedByUs = audio.isStreamMute(AudioManager.STREAM_VOICE_CALL)
        return streamMutedByUs
    }

    /** Undoes whatever [silenceCall] did, and returns false: the call is audible again. */
    private fun restoreCall(): Boolean {
        silencedApp?.let { app ->
            ShizukuState.runAsShell("appops set $app PLAY_AUDIO default")
            silencedApp = null
        }
        if (streamMutedByUs) {
            val audio = appContext?.getSystemService<AudioManager>()
            runCatching { audio?.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0) }
            streamMutedByUs = false
        }
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
