package org.deer.borrowai.data.service.recording

import kotlinx.coroutines.CompletableDeferred
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted

/** Requests microphone access through [AVAudioSession]. Only prompts when permission is undetermined. */
class IosMicrophonePermission : MicrophonePermission {
    override suspend fun request(): Boolean {
        val session = AVAudioSession.sharedInstance()
        if (session.recordPermission == AVAudioSessionRecordPermissionGranted) return true
        val deferred = CompletableDeferred<Boolean>()
        session.requestRecordPermission { granted -> deferred.complete(granted) }
        return deferred.await()
    }
}
