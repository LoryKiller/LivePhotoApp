package com.livephoto.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import com.bumptech.glide.Glide
import com.livephoto.app.databinding.ActivityLivePhotoViewerBinding
import java.io.File

class LivePhotoViewerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LiveViewer"
        const val EXTRA_PHOTO_URI = "extra_photo_uri"
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        
        private const val FADE_DURATION = 550L
        private const val HOLD_DELAY = 100L
    }

    private lateinit var binding: ActivityLivePhotoViewerBinding
    private var exoPlayer: ExoPlayer? = null
    
    private var isHeld = false
    private var isVideoActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        binding = ActivityLivePhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE 
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN 
                    or View.SYSTEM_UI_FLAG_FULLSCREEN 
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
        
        supportActionBar?.hide()

        val photoUriStr = intent.getStringExtra(EXTRA_PHOTO_URI)
        val videoUriStr = intent.getStringExtra(EXTRA_VIDEO_URI)

        if (photoUriStr == null || videoUriStr == null) {
            finish()
            return
        }

        // 1. Image
        Glide.with(this).load(Uri.parse(photoUriStr)).into(binding.imagePhoto)

        // 2. Player
        initExo(Uri.parse(videoUriStr))

        // 3. Interaction
        setupTouch()
    }

    @OptIn(UnstableApi::class)
    private fun initExo(uri: Uri) {
        // Direct file integrity check
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (!f.exists() || f.length() < 1000) {
                Log.e(TAG, "Video file error: ${f.absolutePath} (Size: ${f.length()})")
                Toast.makeText(this, "Video file corrupted or missing", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Configure extractors. 
        // We only use FLAG_READ_MOTION_PHOTO_METADATA if we are playing the original image.
        // If it's our extracted .mp4, we should NOT use it as it can cause "Source error" 
        // if the extractor tries to find metadata that isn't there.
        val isMp4 = uri.path?.endsWith(".mp4") == true
        val extractorsFactory = DefaultExtractorsFactory()
        if (!isMp4) {
            extractorsFactory.setMp4ExtractorFlags(Mp4Extractor.FLAG_READ_MOTION_PHOTO_METADATA)
        }

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractorsFactory))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build().apply {
            
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                // Removing explicit MIME type to allow ExoPlayer to sniff the format,
                // which is more robust for extracted streams that might have issues.
                .build()
            
            setMediaItem(mediaItem)
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
            volume = 0f
            prepare()
            
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    Log.d(TAG, "Player State: $state")
                    if (state == Player.STATE_READY && isHeld && !isVideoActive) {
                        startPlayback()
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Critical Player Error: ${error.message}", error)
                    val msg = when(error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "Source error: format not recognized"
                        PlaybackException.ERROR_CODE_DECODING_FAILED -> "Decoding error"
                        else -> "Playback error: ${error.errorCodeName}"
                    }
                    Toast.makeText(this@LivePhotoViewerActivity, msg, Toast.LENGTH_LONG).show()
                }
            })
        }
        binding.videoView.player = exoPlayer
        binding.videoView.alpha = 0f
    }

    private fun setupTouch() {
        binding.root.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isHeld = true
                    binding.root.postDelayed({
                        if (isHeld) startPlayback()
                    }, HOLD_DELAY)
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isHeld = false
                    stopPlayback()
                    true
                }
                else -> false
            }
        }
    }

    private fun startPlayback() {
        val p = exoPlayer ?: return
        if (isVideoActive || !isHeld) return
        
        if (p.playbackState != Player.STATE_READY) {
            p.prepare()
            return
        }

        isVideoActive = true
        p.seekTo(0)
        p.volume = 1f
        
        // Wait for the first frame to be rendered before fading in.
        // This prevents "flicker" and buffer sync issues on TextureView.
        p.play()

        binding.videoView.animate()
            .alpha(1f)
            .setDuration(FADE_DURATION)
            .setListener(null)
            .start()
        
        binding.imagePhoto.animate()
            .alpha(0.7f)
            .setDuration(FADE_DURATION)
            .start()
    }

    private fun stopPlayback() {
        if (!isVideoActive) return
        isVideoActive = false
        
        // iOS style: Smooth fade out back to the static image
        binding.videoView.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isVideoActive) {
                        exoPlayer?.pause()
                        exoPlayer?.volume = 0f
                        exoPlayer?.seekTo(0)
                    }
                }
            })
            .start()

        binding.imagePhoto.animate()
            .alpha(1f)
            .setDuration(FADE_DURATION)
            .start()
    }

    override fun onResume() {
        super.onResume()
        if (exoPlayer == null) {
            val videoUriStr = intent.getStringExtra(EXTRA_VIDEO_URI)
            if (videoUriStr != null) {
                initExo(Uri.parse(videoUriStr))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // If we want to be very safe with memory, we can pause playback here
        // but releasePlayer in onStop is usually enough for modern devices.
        if (isVideoActive) {
            stopPlayback()
        }
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }
}
