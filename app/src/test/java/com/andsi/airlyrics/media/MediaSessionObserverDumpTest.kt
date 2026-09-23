package com.andsi.airlyrics.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.media.dump.MediaSessionDump
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaController
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaSessionObserverDumpTest {
    private lateinit var context: Context
    private lateinit var mainHandler: Handler
    private var observer: MediaSessionObserver? = null
    private val dumps = mutableListOf<MediaSessionDump>()
    private val mediaUpdates = mutableListOf<CurrentMediaInfo>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mainHandler = Handler(Looper.getMainLooper())
    }

    @After
    fun tearDown() {
        observer?.stop()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun queueCallbackAndSessionEpoch_areCapturedInDump() {
        val controller = createController(
            metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Song")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, "Album Artist")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 180_000L)
                .build(),
            playbackState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 1_000L, 1f)
                .build()
        )
        val controllerShadow = Shadow.extract<ShadowMediaController>(controller)
        observer = MediaSessionObserver(
            context = context,
            handler = mainHandler,
            listener = object : MediaSessionObserver.Listener {
                override fun onCurrentMediaChanged(media: CurrentMediaInfo) {
                    mediaUpdates += media
                }

                override fun onMediaSourceLost(packageName: String) = Unit

                override fun onObservationError(message: String, error: Throwable) {
                    throw AssertionError(message, error)
                }
            },
            dumpSink = { dumps += it }
        )

        observer!!.refresh(listOf(controller))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1L, mediaUpdates.single().sessionEpoch)
        assertEquals("Album Artist", mediaUpdates.single().albumArtist)
        assertEquals(1L, dumps.single().sessionEpoch)
        assertTrue(dumps.single().metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION))

        val registeredCallback = controllerShadow.callbacks.single()
        registeredCallback.onQueueChanged(emptyList())
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dumps.size >= 2)
        assertEquals(1L, dumps.last().sessionEpoch)
    }

    private fun createController(
        metadata: MediaMetadata,
        playbackState: PlaybackState
    ): MediaController {
        @Suppress("UNCHECKED_CAST")
        val sessionControllerClass =
            Class.forName("android.media.session.ISessionController") as Class<Any>
        val sessionController = ReflectionHelpers.createDeepProxy(sessionControllerClass)
        val token = ReflectionHelpers.callConstructor(
            MediaSession.Token::class.java,
            ReflectionHelpers.ClassParameter.from(
                Int::class.javaPrimitiveType!!,
                nextTokenId.incrementAndGet()
            ),
            ReflectionHelpers.ClassParameter.from(
                sessionControllerClass,
                sessionController
            )
        )
        val controller = MediaController(context, token)
        Shadow.extract<ShadowMediaController>(controller).apply {
            setPackageName("app.symfonium")
            setMetadata(metadata)
            setPlaybackState(playbackState)
        }
        return controller
    }

    companion object {
        private val nextTokenId = AtomicInteger(20_000)
    }
}
