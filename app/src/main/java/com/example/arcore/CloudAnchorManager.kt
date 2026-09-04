package com.example.arcore

import android.content.Context
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import java.util.UUID

/**
 * Cloud Anchor Lifecycle State Machine according to official Google ARCore specification.
 */
enum class CloudAnchorLifecycleState {
  NONE,
  HOSTING_IN_PROGRESS,
  HOSTED_SUCCESS,
  RESOLVING_IN_PROGRESS,
  RESOLVED_SUCCESS,
  ERROR_NOT_AUTHORIZED,
  ERROR_RESOURCE_EXHAUSTED,
  ERROR_LOCALIZATION_FAILED,
  ERROR_TIMEOUT,
  ERROR_SDK_UNSUPPORTED
}

/**
 * Shared Spatial AR State synchronized between host and client devices.
 */
data class SharedSpatialExhibit(
  val sessionCode: String,
  val cloudAnchorId: String,
  val hostDeviceId: String,
  val modelId: String,
  val modelScale: Float,
  val poseTranslation: FloatArray,
  val poseRotation: FloatArray,
  val syncTimestampMs: Long
)

data class CloudAnchorRecord(
  val cloudAnchorId: String,
  val anchor: Anchor?,
  val state: CloudAnchorLifecycleState,
  val errorMessage: String? = null,
  val hostTimeMs: Long = System.currentTimeMillis(),
  val ttlDays: Int = 1,
  val retryCount: Int = 0
)

/**
 * Production-Grade ARCore Cloud Anchors & Shared Spatial Content Manager.
 * Implements end-to-end workflow:
 * Create -> Host -> Cloud ID -> Local Cache / Share -> Resolve -> Retry & Recovery -> Synchronize State.
 */
class CloudAnchorManager(context: Context? = null) {

  companion object {
    private const val TAG = "CloudAnchorManager"
    private const val MAX_RESOLVE_RETRIES = 3
    private const val PREFS_NAME = "arcore_cloud_anchors_cache"
  }

  private val sharedPrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val activeRecords = mutableMapOf<String, CloudAnchorRecord>()

  var activeSharedExhibit: SharedSpatialExhibit? = null
    private set

  val cloudAnchorsCount: Int
    get() = activeRecords.size

  /**
   * Hosts a local ARCore Anchor into Google Cloud AR Services.
   */
  fun hostCloudAnchor(
    session: Session,
    localAnchor: Anchor,
    sessionCode: String = "EXHIBIT-${(1000..9999).random()}",
    modelId: String = "primary_exhibit_model",
    modelScale: Float = 1.0f,
    ttlDays: Int = 1,
    onStatusChange: (CloudAnchorRecord) -> Unit
  ) {
    val tempId = "pending_${UUID.randomUUID()}"
    val initialRecord = CloudAnchorRecord(
      cloudAnchorId = tempId,
      anchor = localAnchor,
      state = CloudAnchorLifecycleState.HOSTING_IN_PROGRESS,
      ttlDays = ttlDays
    )
    activeRecords[tempId] = initialRecord
    onStatusChange(initialRecord)

    try {
      session.hostCloudAnchorAsync(localAnchor, ttlDays) { cloudAnchorId, state ->
        val stateName = state.name
        Log.i(TAG, "ARCore Cloud Anchor Host callback: state=$stateName, id=$cloudAnchorId")

        when (state) {
          Anchor.CloudAnchorState.SUCCESS -> {
            if (cloudAnchorId != null) {
              activeRecords.remove(tempId)
              val record = CloudAnchorRecord(
                cloudAnchorId = cloudAnchorId,
                anchor = localAnchor,
                state = CloudAnchorLifecycleState.HOSTED_SUCCESS,
                ttlDays = ttlDays
              )
              activeRecords[cloudAnchorId] = record

              // Persist anchor ID locally
              sharedPrefs?.edit()?.putString(sessionCode, cloudAnchorId)?.apply()

              // Create shared spatial session payload
              val pose = localAnchor.pose
              activeSharedExhibit = SharedSpatialExhibit(
                sessionCode = sessionCode,
                cloudAnchorId = cloudAnchorId,
                hostDeviceId = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}",
                modelId = modelId,
                modelScale = modelScale,
                poseTranslation = floatArrayOf(pose.tx(), pose.ty(), pose.tz()),
                poseRotation = floatArrayOf(pose.qx(), pose.qy(), pose.qz(), pose.qw()),
                syncTimestampMs = System.currentTimeMillis()
              )

              onStatusChange(record)
            }
          }
          Anchor.CloudAnchorState.ERROR_NOT_AUTHORIZED -> {
            val record = initialRecord.copy(
              state = CloudAnchorLifecycleState.ERROR_NOT_AUTHORIZED,
              errorMessage = "Google Cloud API key unauthorized for ARCore Cloud Anchors."
            )
            activeRecords[tempId] = record
            onStatusChange(record)
          }
          Anchor.CloudAnchorState.ERROR_RESOURCE_EXHAUSTED -> {
            val record = initialRecord.copy(
              state = CloudAnchorLifecycleState.ERROR_RESOURCE_EXHAUSTED,
              errorMessage = "ARCore Cloud Anchor quota exceeded."
            )
            activeRecords[tempId] = record
            onStatusChange(record)
          }
          else -> {
            val record = initialRecord.copy(
              state = CloudAnchorLifecycleState.ERROR_LOCALIZATION_FAILED,
              errorMessage = "Failed to host anchor: $stateName"
            )
            activeRecords[tempId] = record
            onStatusChange(record)
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Exception initiating hostCloudAnchor: ${e.message}", e)
      val errRecord = initialRecord.copy(
        state = CloudAnchorLifecycleState.ERROR_SDK_UNSUPPORTED,
        errorMessage = e.message
      )
      onStatusChange(errRecord)
    }
  }

  /**
   * Resolves a shared Cloud Anchor using its persistent Cloud Anchor ID or session code.
   */
  fun resolveCloudAnchor(
    session: Session,
    cloudAnchorId: String,
    retryCount: Int = 0,
    onStatusChange: (CloudAnchorRecord) -> Unit
  ) {
    val initialRecord = CloudAnchorRecord(
      cloudAnchorId = cloudAnchorId,
      anchor = null,
      state = CloudAnchorLifecycleState.RESOLVING_IN_PROGRESS,
      retryCount = retryCount
    )
    activeRecords[cloudAnchorId] = initialRecord
    onStatusChange(initialRecord)

    try {
      session.resolveCloudAnchorAsync(cloudAnchorId) { anchor, state ->
        val stateName = state.name
        Log.i(TAG, "ARCore Cloud Anchor Resolve callback: state=$stateName, id=$cloudAnchorId")

        when (state) {
          Anchor.CloudAnchorState.SUCCESS -> {
            val successRecord = CloudAnchorRecord(
              cloudAnchorId = cloudAnchorId,
              anchor = anchor,
              state = CloudAnchorLifecycleState.RESOLVED_SUCCESS,
              retryCount = retryCount
            )
            activeRecords[cloudAnchorId] = successRecord
            onStatusChange(successRecord)
          }
          Anchor.CloudAnchorState.ERROR_RESOLVING_LOCALIZATION_NO_MATCH -> {
            if (retryCount < MAX_RESOLVE_RETRIES) {
              Log.w(TAG, "Localization no match. Retrying resolve in 2s (attempt ${retryCount + 1})...")
              android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                resolveCloudAnchor(session, cloudAnchorId, retryCount + 1, onStatusChange)
              }, 2000L)
            } else {
              val failRecord = initialRecord.copy(
                state = CloudAnchorLifecycleState.ERROR_LOCALIZATION_FAILED,
                errorMessage = "Could not resolve physical room feature matching after $MAX_RESOLVE_RETRIES attempts."
              )
              activeRecords[cloudAnchorId] = failRecord
              onStatusChange(failRecord)
            }
          }
          else -> {
            val failRecord = initialRecord.copy(
              state = CloudAnchorLifecycleState.ERROR_LOCALIZATION_FAILED,
              errorMessage = "Failed to resolve anchor: $stateName"
            )
            activeRecords[cloudAnchorId] = failRecord
            onStatusChange(failRecord)
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Exception resolving cloud anchor: ${e.message}", e)
      val errRecord = initialRecord.copy(
        state = CloudAnchorLifecycleState.ERROR_SDK_UNSUPPORTED,
        errorMessage = e.message
      )
      onStatusChange(errRecord)
    }
  }

  fun getCachedAnchorId(sessionCode: String): String? {
    return sharedPrefs?.getString(sessionCode, null)
  }

  fun clearAll() {
    activeRecords.values.forEach { it.anchor?.detach() }
    activeRecords.clear()
    activeSharedExhibit = null
  }
}
