package world.yemyat.slicepilot.cloud

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.video.VideoCanvas

/**
 * Cloud camera preview: the CC2 streams its camera through Agora RTC (verified from the
 * Elegoo Matrix app: same Agora project as RTM, appId `d035320941e3...`, rtc credentials
 * from `/device/list/agora-token`, and an RTM `app_rtc_connect` handshake carrying
 * `client_rtc_uid` so the printer starts publishing video).
 */
class CloudRtcCamera(private val context: Context) {

    private var engine: RtcEngine? = null

    /** @return true when the engine started and joined the channel. */
    fun start(
        appId: String,
        token: String,
        channel: String,
        uid: Int,
        surface: SurfaceView,
        onRemoteJoined: (Int) -> Unit = {},
    ): Boolean = try {
        stop()
        val e = RtcEngine.create(context, appId, object : IRtcEngineEventHandler() {
            override fun onUserJoined(remoteUid: Int, elapsed: Int) {
                Log.i(TAG, "remote video user joined: $remoteUid")
                try {
                    engine?.setupRemoteVideo(VideoCanvas(surface, VideoCanvas.RENDER_MODE_HIDDEN, remoteUid))
                } catch (ex: Exception) {
                    Log.w(TAG, "setupRemoteVideo failed", ex)
                }
                onRemoteJoined(remoteUid)
            }

            override fun onUserOffline(remoteUid: Int, reason: Int) {
                Log.i(TAG, "remote video user offline: $remoteUid reason=$reason")
            }

            override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
                Log.i(TAG, "joined channel $channel as uid=$uid")
            }

            override fun onConnectionStateChanged(state: Int, reason: Int) {
                Log.i(TAG, "connection state=$state reason=$reason")
            }

            override fun onTokenPrivilegeWillExpire(token: String?) {
                Log.i(TAG, "rtc token privileges expiring")
            }
        })
        engine = e
        e.enableVideo()
        e.disableAudio() // Matrix agora_utils: setAudioProfile + disableAudio
        val roleRc = e.setClientRole(CLIENT_ROLE_BROADCASTER)
        val rc = e.joinChannel(token, channel, null, uid)
        Log.i(TAG, "joinChannel($channel, uid=$uid) -> $rc (role rc=$roleRc)")
        rc == 0
    } catch (ex: Exception) {
        Log.e(TAG, "rtc start failed", ex)
        stop()
        false
    }

    fun stop() {
        try { engine?.leaveChannel() } catch (_: Exception) {}
        try { RtcEngine.destroy() } catch (_: Exception) {}
        engine = null
    }

    private companion object {
        const val TAG = "CloudRtc"
        // Matrix agora_utils: clientRoleBroadcaster (Agora Constants.CLIENT_ROLE_BROADCASTER).
        const val CLIENT_ROLE_BROADCASTER = 1
    }
}
