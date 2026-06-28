package com.example.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private var bitrate: Int,
    private val fps: Int,
    val mimeType: String = selectMimeType(),
    private val onEncodedFrame: (data: ByteArray, flags: Int, timestampUs: Long) -> Unit,
    private val onStatsUpdate: (fps: Int, bitrateKbps: Long) -> Unit
) {

    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private var encoderJob: Job? = null
    private val encoderScope = CoroutineScope(Dispatchers.Default)

    private var isRunning = false

    companion object {
        private const val TAG = "VideoEncoder"

        /**
         * Automatically queries the device's hardware capabilities to prefer H.265 (HEVC)
         * over H.264 (AVC) due to H.265's superior visual encoding efficiency and lower network requirements.
         */
        fun selectMimeType(): String {
            try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (codecInfo in codecList.codecInfos) {
                    if (!codecInfo.isEncoder) continue
                    for (type in codecInfo.supportedTypes) {
                        if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                            Log.i(TAG, "Hardware H.265/HEVC Encoder detected and selected for mirroring.")
                            return MediaFormat.MIMETYPE_VIDEO_HEVC
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking H.265 codec support, falling back to H.264", e)
            }
            Log.i(TAG, "Standard H.264/AVC Encoder selected for mirroring.")
            return MediaFormat.MIMETYPE_VIDEO_AVC
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        Log.i(TAG, "Initializing hardware video encoder ($mimeType): ${width}x${height} @ ${fps}FPS, ${bitrate / 1_000_000f} Mbps")

        // Create MediaFormat configured for maximum performance & ultra-low latency
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            // Surface input is required for high-efficiency virtual display capture
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            
            // Low Key-frame interval for fast desktop syncing
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            
            // Ultra-low-latency and realtime performance optimizations
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) // Constant Bitrate
            setInteger(MediaFormat.KEY_LATENCY, 0) // Minimum possible internal codec buffering
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority scheduling

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1) // Low latency encoding mode
            }

            // Intra-refresh reduces bandwidth spikes by refreshing frames incrementally
            setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, fps / 2) // Slice refresh cycle
            
            // Profile and level configurations
            if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
            }

            // Vendor-specific low-latency configurations for Qualcomm, MediaTek, Exynos
            setInteger("vendor.rtc-ext-dec-low-latency-enable", 1)
            setInteger("vendor.qti-ext-enc.low-latency.enable", 1)
        }

        try {
            val codec = MediaCodec.createEncoderByType(mimeType)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            mediaCodec = codec

            // Start polling encoded packets in a separate background thread loop
            encoderJob = encoderScope.launch {
                runEncoderLoop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaCodec hardware encoder", e)
            throw e
        }
    }

    private suspend fun runEncoderLoop() {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        var frameCount = 0
        var totalBytesInSecond = 0L
        var lastStatsTimeMs = System.currentTimeMillis()

        while (isRunning && encoderScope.isActive) {
            try {
                // Poll output buffer with low-latency timeout (1000us / 1ms)
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 1000L)

                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val chunkBytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunkBytes)

                        // Forward the raw Annex-B byte stream slice to service broadcast handlers
                        onEncodedFrame(chunkBytes, bufferInfo.flags, bufferInfo.presentationTimeUs)

                        // Performance & Bandwidth analytics accumulation
                        frameCount++
                        totalBytesInSecond += bufferInfo.size

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastStatsTimeMs >= 1000) {
                            val currentBitrateKbps = (totalBytesInSecond * 8) / 1024
                            onStatsUpdate(frameCount, currentBitrateKbps)
                            
                            frameCount = 0
                            totalBytesInSecond = 0L
                            lastStatsTimeMs = currentTime
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // This event delivers the SPS/PPS headers (Codec-Specific-Data)
                    Log.i(TAG, "Encoder output format changed. New format: ${codec.outputFormat}")
                    val sps = codec.outputFormat.getByteBuffer("csd-0")
                    val pps = codec.outputFormat.getByteBuffer("csd-1")
                    if (sps != null) {
                        val headerSize = sps.remaining() + (pps?.remaining() ?: 0)
                        val header = ByteArray(headerSize)
                        sps.get(header, 0, sps.remaining())
                        pps?.get(header, sps.remaining(), pps.remaining())
                        onEncodedFrame(header, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0L)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception encountered in MediaCodec frame extraction loop", e)
                break
            }
        }
    }

    /**
     * Request a fresh Key Frame instantly from the encoder.
     * Crucial to recover video feeds when a desktop client first connects or encounters packet loss.
     */
    fun requestKeyFrame() {
        mediaCodec?.let { codec ->
            try {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
                codec.setParameters(params)
                Log.d(TAG, "Dynamic Sync-Frame requested from MediaCodec encoder.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dynamically request synch-frame", e)
            }
        }
    }

    /**
     * Dynamically adjusts encoding bitrate on the fly.
     * Allows real-time optimization to match connection quality changes without restarting stream.
     */
    fun updateBitrate(newBitrate: Int) {
        if (bitrate == newBitrate) return
        bitrate = newBitrate
        mediaCodec?.let { codec ->
            try {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
                }
                codec.setParameters(params)
                Log.i(TAG, "Dynamic bitrate updated to: ${newBitrate / 1_000_000f} Mbps")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dynamically adjust video bitrate", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        encoderJob?.cancel()
        encoderJob = null

        mediaCodec?.let { codec ->
            try {
                codec.stop()
                codec.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaCodec resources", e)
            }
        }
        mediaCodec = null
        inputSurface?.release()
        inputSurface = null
        Log.i(TAG, "Hardware encoder shutdown completed.")
    }
}
