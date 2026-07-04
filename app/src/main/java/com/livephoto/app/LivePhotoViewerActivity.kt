package com.livephoto.app

import android.animation.ObjectAnimator
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
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.livephoto.app.databinding.ActivityLivePhotoViewerBinding
import java.io.File

class LivePhotoViewerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LiveViewer"
        const val EXTRA_PHOTO_URI = "extra_photo_uri"
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        
        private const val FADE_DURATION = 600L
        private const val FADE_START_DELAY = 100L
        private const val HOLD_DELAY = 100L
        private const val ZOOM_SCALE = 1.08f
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
        Glide.with(this)
            .load(Uri.parse(photoUriStr))
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean = false
                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    updateViewportRatio(resource.intrinsicWidth, resource.intrinsicHeight)
                    return false
                }
            })
            .into(binding.imagePhoto)

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

    private fun updateViewportRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        
        binding.root.post {
            val containerWidth = binding.root.width
            val containerHeight = binding.root.height
            if (containerWidth <= 0 || containerHeight <= 0) return@post

            val containerRatio = containerWidth.toFloat() / containerHeight
            val imageRatio = width.toFloat() / height
            
            val finalWidth: Int
            val finalHeight: Int
            
            if (imageRatio > containerRatio) {
                // Image is wider than screen: match width, calculate height
                finalWidth = containerWidth
                finalHeight = (containerWidth / imageRatio).toInt()
            } else {
                // Image is taller than screen: match height, calculate width
                finalHeight = containerHeight
                finalWidth = (containerHeight * imageRatio).toInt()
            }
            
            val params = binding.viewport.layoutParams
            params.width = finalWidth
            params.height = finalHeight
            binding.viewport.layoutParams = params
        }
    }

    private fun setupTouch() {
        binding.root.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isHeld = true
                    binding.root.postDelayed({
                        if (isHeld) {
                            // Instant feedback: start zooming the photo immediately
                            val totalDuration = FADE_DURATION + FADE_START_DELAY
                            ObjectAnimator.ofFloat(binding.imagePhoto, View.SCALE_X, ZOOM_SCALE).setDuration(totalDuration).start()
                            ObjectAnimator.ofFloat(binding.imagePhoto, View.SCALE_Y, ZOOM_SCALE).setDuration(totalDuration).start()
                            startPlayback()
                        }
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
        p.play()

        val totalDuration = FADE_DURATION + FADE_START_DELAY

        // 1. Zooms start immediately
        ObjectAnimator.ofFloat(binding.videoView, View.SCALE_X, ZOOM_SCALE).setDuration(totalDuration).start()
        ObjectAnimator.ofFloat(binding.videoView, View.SCALE_Y, ZOOM_SCALE).setDuration(totalDuration).start()
        ObjectAnimator.ofFloat(binding.imagePhoto, View.SCALE_X, ZOOM_SCALE).setDuration(totalDuration).start()
        ObjectAnimator.ofFloat(binding.imagePhoto, View.SCALE_Y, ZOOM_SCALE).setDuration(totalDuration).start()

        // 2. Fade video in after delay
        // We keep the imagePhoto at Alpha 1.0 during this transition to prevent a brightness dip.
        // As the video on top becomes opaque, it naturally hides the photo.
        ObjectAnimator.ofFloat(binding.videoView, View.ALPHA, 1f).apply {
            duration = FADE_DURATION
            startDelay = FADE_START_DELAY
            start()
        }
    }

    private fun stopPlayback() {
        // Always reset image state
        val duration = FADE_DURATION
        ObjectAnimator.ofFloat(binding.imagePhoto, View.ALPHA, 1f).setDuration(duration).start()
        ObjectAnimator.ofFloat(binding.imagePhoto, View.SCALE_X, 1f).setDuration(duration).start()
        ObjectAnimator.ofFloat(binding.imagePhoto, View.SCALE_Y, 1f).setDuration(duration).start()

        if (!isVideoActive) return
        isVideoActive = false
        
        // iOS style: Smooth fade out back to the static image
        ObjectAnimator.ofFloat(binding.videoView, View.ALPHA, 0f).setDuration(duration).start()
        ObjectAnimator.ofFloat(binding.videoView, View.SCALE_X, 1f).setDuration(duration).start()
        ObjectAnimator.ofFloat(binding.videoView, View.SCALE_Y, 1f).setDuration(duration).start()
        
        // Pause player after fade
        binding.root.postDelayed({
            if (!isVideoActive) {
                exoPlayer?.pause()
                exoPlayer?.volume = 0f
                exoPlayer?.seekTo(0)
            }
        }, duration)
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
