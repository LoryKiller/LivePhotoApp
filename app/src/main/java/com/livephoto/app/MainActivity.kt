package com.livephoto.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.livephoto.app.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> handleSelectedUri(uri) }
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.values.all { it }) {
            Toast.makeText(this, "Permission granted. Select again.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Select Photo (Files app) - Grey Button (Bottom)
        binding.btnPickFiles.setOnClickListener {
            if (checkStoragePermission()) launchPicker(PickerMode.FILES_APP)
        }

        // Select File (Choose app) - White Button (Top)
        binding.btnPickChooser.setOnClickListener {
            if (checkStoragePermission()) launchPicker(PickerMode.APP_CHOOSER)
        }
    }

    private enum class PickerMode { FILES_APP, APP_CHOOSER }

    private fun checkStoragePermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            permissionLauncher.launch(arrayOf(perm))
            false
        }
    }

    private fun launchPicker(mode: PickerMode) {
        val intent = if (mode == PickerMode.FILES_APP) {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        }
        
        val finalIntent = if (mode == PickerMode.APP_CHOOSER) {
            Intent.createChooser(intent, "Select Photo")
        } else {
            intent
        }
        pickLauncher.launch(finalIntent)
    }

    private fun handleSelectedUri(uri: Uri) {
        // Show a simple toast or you could use a ProgressBar
        Toast.makeText(this, "Extracting...", Toast.LENGTH_SHORT).show()
        
        // Capture context weakly or ensure we don't leak it in the thread
        val appContext = applicationContext 
        
        thread(isDaemon = true) {
            val videoUri = MotionPhotoHelper.extractVideo(appContext, uri)
            runOnUiThread {
                if (!isFinishing) {
                    if (videoUri != null) {
                        val intent = Intent(this, LivePhotoViewerActivity::class.java).apply {
                            putExtra(LivePhotoViewerActivity.EXTRA_PHOTO_URI, uri.toString())
                            putExtra(LivePhotoViewerActivity.EXTRA_VIDEO_URI, videoUri.toString())
                        }
                        startActivity(intent)
                    } else {
                        Toast.makeText(this, "Not a Motion Photo.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
