package world.yemyat.slicepilot.cloud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.video.VideoCanvas

/**
 * Cloud camera preview: the CC2 streams its camera through Agora RTC (same Agora project
 * as RTM, rtc credentials from `/device/list/agora-token`, and an RTM method 1054
 * `{action:"start", client_rtc_uid}` handshake so the printer publishes video).
 *
 * Uses [TextureView] (not `SurfaceView`) so Jetpack Compose clipping, pinch-zoom
 * `graphicsLayer` transforms, and moving the view between the Printer card and
 * full-screen route never lose or hide the video behind a black background.
 */
class CloudRtcCamera(private val context: Context) {

    private var engine: RtcEngine? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var boundRemoteUid: Int? = null
    @Volatile private var statsLogged = 0

    /** @return true when the engine started and joined the channel. */
    fun start(
        appId: String,
        token: String,
        channel: String,
        uid: Int,
        view: TextureView,
        onRemoteJoined: (Int) -> Unit = {},
        onFirstFrame: () -> Unit = {},
    ): Boolean = try {
        stop()
        boundRemoteUid = null
        statsLogged = 0

        fun bindRemote(remoteUid: Int, source: String) {
            if (remoteUid == 0) return
            val alreadyBound = boundRemoteUid == remoteUid
            boundRemoteUid = remoteUid
            if (!alreadyBound) {
                mainHandler.post {
                    try {
                        val rc = engine?.setupRemoteVideo(VideoCanvas(view, VideoCanvas.RENDER_MODE_FIT, remoteUid))
                        Log.i(TAG, "setupRemoteVideo(uid=$remoteUid via $source) -> $rc")
                    } catch (ex: Exception) {
                        Log.w(TAG, "setupRemoteVideo failed", ex)
                    }
                }
                onRemoteJoined(remoteUid)
            }
        }

        val e = RtcEngine.create(context.applicationContext, appId, object : IRtcEngineEventHandler() {
            override fun onUserJoined(remoteUid: Int, elapsed: Int) {
                Log.i(TAG, "remote video user joined: $remoteUid (elapsed=${elapsed}ms)")
                bindRemote(remoteUid, "onUserJoined")
            }

            override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
                Log.i(TAG, "remote video state uid=$uid state=$state reason=$reason elapsed=${elapsed}ms")
                if (state == Constants.REMOTE_VIDEO_STATE_STARTING || state == Constants.REMOTE_VIDEO_STATE_DECODING) {
                    bindRemote(uid, "onRemoteVideoStateChanged")
                }
            }

            override fun onFirstRemoteVideoFrame(uid: Int, width: Int, height: Int, elapsed: Int) {
                Log.i(TAG, "first remote video frame uid=$uid ${width}x$height elapsed=${elapsed}ms")
                bindRemote(uid, "onFirstRemoteVideoFrame")
                onFirstFrame()
            }

            override fun onUserOffline(remoteUid: Int, reason: Int) {
                Log.i(TAG, "remote video user offline: $remoteUid reason=$reason")
                if (boundRemoteUid == remoteUid) boundRemoteUid = null
            }

            override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
                Log.i(TAG, "joined channel $channel as uid=$uid")
            }

            override fun onConnectionStateChanged(state: Int, reason: Int) {
                Log.i(TAG, "connection state=$state reason=$reason")
            }

            override fun onError(err: Int) {
                Log.w(TAG, "rtc error=$err")
            }

            override fun onRtcStats(stats: RtcStats?) {
                if (stats != null && statsLogged < 3) {
                    statsLogged++
                    Log.i(TAG, "rtc stats: users=${stats.users} rxKbps=${stats.rxKBitRate} rxVideoKbps=${stats.rxVideoKBitRate}")
                }
            }

            override fun onTokenPrivilegeWillExpire(token: String?) {
                Log.i(TAG, "rtc token privileges expiring")
            }
        })
        engine = e
        e.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
        e.enableVideo()
        e.disableAudio()
        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
            autoSubscribeVideo = true
            autoSubscribeAudio = false
            publishCameraTrack = false
            publishMicrophoneTrack = false
        }
        val rc = e.joinChannel(token, channel, uid, options)
        Log.i(TAG, "joinChannel($channel, uid=$uid, AUDIENCE) -> $rc")
        rc == 0
    } catch (ex: Exception) {
        Log.e(TAG, "rtc start failed", ex)
        stop()
        false
    }

    fun stop() {
        boundRemoteUid = null
        try { engine?.leaveChannel() } catch (_: Exception) {}
        try { RtcEngine.destroy() } catch (_: Exception) {}
        engine = null
    }

    private companion object {
        const val TAG = "CloudRtc"
    }
}
