package com.example.arcore

import android.content.Context
import android.util.Log
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import java.io.File

/**
 * Production-Grade Google ARCore Recording and Playback Manager.
 * Implements the official ARCore Recording & Playback API:
 * 1. Records live AR session (Camera frames, IMU sensors, 6DoF tracking, depth, metadata) to standard MP4 dataset.
 * 2. Replays recorded AR datasets through ARCore VIO engine as if live camera feed.
 * 3. Supports dataset inspection, pause, resume, and playback progress telemetry.
 */
data class RecordingTelemetry(
  val isRecording: Boolean = false,
  val isPlayingBack: Boolean = false,
  val recordingStatus: String = "STOPPED",
  val playbackStatus: String = "NONE",
  val datasetFilePath: String? = null,
  val recordedDurationSeconds: Float = 0f,
  val recordingFileSizeMb: Float = 0f
)

class ArCoreRecordingPlaybackManager(private val context: Context) {

  companion object {
    private const val TAG = "ArCoreRecPlayback"
  }

  var telemetry: RecordingTelemetry = RecordingTelemetry()
    private set

  private var recordingStartTimeMs: Long = 0L
  private var currentRecordingFile: File? = null

  /**
   * Starts recording the current ARCore session into a local MP4 dataset file.
   */
  fun startRecording(session: Session, customFileName: String? = null): Boolean {
    return try {
      val fileName = customFileName ?: "ar_session_${System.currentTimeMillis()}.mp4"
      val datasetFile = File(context.cacheDir, fileName)
      currentRecordingFile = datasetFile

      val recordingConfig = RecordingConfig(session)
        .setMp4DatasetFilePath(datasetFile.absolutePath)
        .setAutoStopOnPause(true)

      session.startRecording(recordingConfig)
      recordingStartTimeMs = System.currentTimeMillis()

      telemetry = telemetry.copy(
        isRecording = true,
        recordingStatus = "RECORDING",
        datasetFilePath = datasetFile.absolutePath,
        recordedDurationSeconds = 0f,
        recordingFileSizeMb = 0f
      )
      Log.i(TAG, "ARCore recording started -> ${datasetFile.absolutePath}")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start ARCore recording: ${e.message}", e)
      telemetry = telemetry.copy(isRecording = false, recordingStatus = "ERROR: ${e.message}")
      false
    }
  }

  /**
   * Stops active ARCore session recording.
   */
  fun stopRecording(session: Session): File? {
    return try {
      session.stopRecording()
      val recordedFile = currentRecordingFile
      val fileSizeMb = (recordedFile?.length() ?: 0L).toFloat() / (1024f * 1024f)

      telemetry = telemetry.copy(
        isRecording = false,
        recordingStatus = "STOPPED",
        recordingFileSizeMb = fileSizeMb
      )
      Log.i(TAG, "ARCore recording stopped successfully (${fileSizeMb}MB).")
      recordedFile
    } catch (e: Exception) {
      Log.e(TAG, "Failed to stop ARCore recording: ${e.message}", e)
      telemetry = telemetry.copy(isRecording = false, recordingStatus = "STOP_FAILED")
      null
    }
  }

  /**
   * Configures ARCore session to replay a recorded MP4 dataset file instead of live camera.
   * Note: ARCore requires session to be in PAUSED state when setting playback dataset.
   */
  fun setPlaybackDataset(session: Session, datasetFile: File): Boolean {
    return try {
      if (!datasetFile.exists() || datasetFile.length() == 0L) {
        Log.e(TAG, "Playback dataset file does not exist or is empty: ${datasetFile.absolutePath}")
        return false
      }

      session.setPlaybackDataset(datasetFile.absolutePath)
      telemetry = telemetry.copy(
        isPlayingBack = true,
        playbackStatus = "DATASET_SET",
        datasetFilePath = datasetFile.absolutePath
      )
      Log.i(TAG, "ARCore playback dataset configured: ${datasetFile.absolutePath}")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed setting playback dataset: ${e.message}", e)
      false
    }
  }

  /**
   * Updates recording & playback telemetry on every frame update.
   */
  fun updateFrameState(session: Session) {
    try {
      val recStatus = session.recordingStatus
      val playStatus = session.playbackStatus

      val isCurrentlyRecording = (recStatus == RecordingStatus.OK)
      val isCurrentlyPlaying = (playStatus == PlaybackStatus.OK)

      val durationSec = if (isCurrentlyRecording && recordingStartTimeMs > 0) {
        (System.currentTimeMillis() - recordingStartTimeMs) / 1000f
      } else {
        telemetry.recordedDurationSeconds
      }

      val fileSize = currentRecordingFile?.let {
        it.length().toFloat() / (1024f * 1024f)
      } ?: telemetry.recordingFileSizeMb

      telemetry = telemetry.copy(
        isRecording = isCurrentlyRecording,
        isPlayingBack = isCurrentlyPlaying,
        recordingStatus = recStatus.name,
        playbackStatus = playStatus.name,
        recordedDurationSeconds = durationSec,
        recordingFileSizeMb = fileSize
      )
    } catch (e: Exception) {
      // Ignored during transitions
    }
  }
}
