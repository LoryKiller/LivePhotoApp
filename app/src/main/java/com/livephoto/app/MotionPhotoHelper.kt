package com.livephoto.app

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Robust Motion Photo Extractor.
 * Version 2.4: Optimized for speed and absolute file accessibility.
 */
object MotionPhotoHelper {
    private const val TAG = "MotionPhotoHelper"

    fun extractVideo(context: Context, imageUri: Uri): Uri? {
        val resolver = context.contentResolver
        var inputStream: InputStream? = null
        try {
            inputStream = resolver.openInputStream(imageUri) ?: return null
            val bytes = inputStream.readBytes()
            val totalSize = bytes.size
            
            if (totalSize < 4096) return null // Too small to be a motion photo

            var videoOffset = -1

            // 1. GContainer Search (Semantic="MotionPhoto")
            // Search for "Item:Semantic=\"MotionPhoto\"" using byte array for memory efficiency
            val semanticMarker = "Item:Semantic=\"MotionPhoto\"".toByteArray(Charsets.ISO_8859_1)
            val semanticPos = findLastPattern(bytes, semanticMarker)
            if (semanticPos != -1) {
                // Search backwards from the semantic marker for the "Item:Length=\"" attribute
                val lengthPattern = "Item:Length=\"".toByteArray(Charsets.ISO_8859_1)
                val lengthIdx = findLastPattern(bytes, lengthPattern, semanticPos)
                if (lengthIdx != -1) {
                    val start = lengthIdx + lengthPattern.size
                    // Find the closing quote within a reasonable range (32 bytes)
                    var end = -1
                    for (i in start until (start + 32).coerceAtMost(totalSize)) {
                        if (bytes[i] == '\"'.code.toByte()) {
                            end = i
                            break
                        }
                    }
                    if (end != -1) {
                        val lengthValueStr = String(bytes, start, end - start, Charsets.ISO_8859_1)
                        val lengthValue = lengthValueStr.toIntOrNull()
                        if (lengthValue != null && lengthValue > 0) {
                            videoOffset = totalSize - lengthValue
                            Log.d(TAG, "GContainer match! Length: $lengthValue, Offset: $videoOffset")
                        }
                    }
                }
            }

            // 2. MicroVideoOffset Search (Legacy)
            if (videoOffset == -1) {
                val microPattern = "MicroVideoOffset=\"".toByteArray(Charsets.ISO_8859_1)
                val microIdx = findLastPattern(bytes, microPattern)
                if (microIdx != -1) {
                    val start = microIdx + microPattern.size
                    var end = -1
                    for (i in start until (start + 32).coerceAtMost(totalSize)) {
                        if (bytes[i] == '\"'.code.toByte()) {
                            end = i
                            break
                        }
                    }
                    if (end != -1) {
                        val offsetFromEndStr = String(bytes, start, end - start, Charsets.ISO_8859_1)
                        val offsetFromEnd = offsetFromEndStr.toIntOrNull()
                        if (offsetFromEnd != null && offsetFromEnd > 0) {
                            videoOffset = totalSize - offsetFromEnd
                            Log.d(TAG, "MicroVideo match! Offset from end: $offsetFromEnd, Absolute: $videoOffset")
                        }
                    }
                }
            }

            // 3. Samsung Search (MotionPhotoVideo)
            if (videoOffset == -1) {
                val samsungMarker = "MotionPhotoVideo".toByteArray(Charsets.ISO_8859_1)
                val samsungPos = findLastPattern(bytes, samsungMarker)
                if (samsungPos != -1) {
                    videoOffset = samsungPos + samsungMarker.size
                    Log.d(TAG, "Samsung match! Offset: $videoOffset")
                }
            }

            // 4. Bruteforce 'ftyp' search (Last resort or verification)
            val ftyp = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
            
            // If we found an offset, verify it looks like a valid MP4 start
            if (videoOffset != -1) {
                // Check if 'ftyp' is near the offset (usually at offset + 4)
                val checkRange = 32
                var foundFtypNear = false
                for (i in videoOffset until (videoOffset + checkRange).coerceAtMost(totalSize - 4)) {
                    if (bytes[i] == ftyp[0] && bytes[i+1] == ftyp[1] && bytes[i+2] == ftyp[2] && bytes[i+3] == ftyp[3]) {
                        foundFtypNear = true
                        // Adjust offset to the start of the ftyp box (4 bytes before 'ftyp')
                        if (i - 4 >= 0) {
                            videoOffset = i - 4
                        }
                        break
                    }
                }
                if (!foundFtypNear) {
                    Log.w(TAG, "Marker-based offset $videoOffset seems invalid (no ftyp). Falling back to brute force.")
                    videoOffset = -1
                }
            }

            if (videoOffset == -1) {
                var lastFtyp = findLastPattern(bytes, ftyp)
                while (lastFtyp > 1024) {
                    val b1 = bytes[lastFtyp - 4].toInt() and 0xFF
                    val b2 = bytes[lastFtyp - 3].toInt() and 0xFF
                    val b3 = bytes[lastFtyp - 2].toInt() and 0xFF
                    val b4 = bytes[lastFtyp - 1].toInt() and 0xFF
                    val size = (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
                    
                    if (size in 8..100000000) { // Plausible ftyp box size (up to 100MB)
                        videoOffset = lastFtyp - 4
                        Log.d(TAG, "Bruteforce match! Valid 'ftyp' found at $videoOffset (Size: $size)")
                        break
                    }
                    lastFtyp = findLastPattern(bytes, ftyp, lastFtyp - 1)
                }
            }

            if (videoOffset in 1 until totalSize - 128) {
                // Save to cache directory so it's cleared by OS if needed
                val cacheDir = File(context.cacheDir, "motion_videos")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                
                // Use a unique name or overwrite the same one if you only view one at a time
                val videoFile = File(cacheDir, "current_motion_video.mp4")
                if (videoFile.exists()) videoFile.delete()

                FileOutputStream(videoFile).use { fos ->
                    fos.write(bytes, videoOffset, totalSize - videoOffset)
                    fos.flush()
                    // sync() is good for integrity but can be slow; 
                    // since we read it immediately, flush() is usually enough.
                    fos.fd.sync()
                }

                if (videoFile.exists() && videoFile.length() > 1024) {
                    Log.d(TAG, "Extraction success: ${videoFile.length()} bytes saved to cache.")
                    return Uri.fromFile(videoFile)
                }
            } else {
                Log.w(TAG, "No valid motion video offset detected.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical extraction error", e)
        } finally {
            inputStream?.close()
        }
        return null
    }

    private fun findLastPattern(data: ByteArray, pattern: ByteArray, startIndex: Int = data.size - pattern.size): Int {
        if (data.size < pattern.size || startIndex < 0) return -1
        val start = if (startIndex > data.size - pattern.size) data.size - pattern.size else startIndex
        for (i in start downTo 0) {
            var found = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }
}
