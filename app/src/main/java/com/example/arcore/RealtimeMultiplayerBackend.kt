package com.example.arcore

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Connected user in a shared spatial session.
 */
data class MultiplayerUser(
  val userId: String = UUID.randomUUID().toString().substring(0, 8),
  val displayName: String = "User_${userId.take(4)}",
  val isHost: Boolean = false,
  val avatarColorRgb: Int = 0x38BDF8,
  val joinedTimestampMs: Long = System.currentTimeMillis()
)

/**
 * Real-time synchronized 3D object transform in a shared room.
 * Decoupled from Cloud Anchors (optionally carries a cloudAnchorId if anchored spatially).
 */
data class SyncedTransform(
  val objectId: String,
  val modelId: String,
  val cloudAnchorId: String? = null,
  val posX: Float = 0f,
  val posY: Float = 0f,
  val posZ: Float = 0f,
  val rotX: Float = 0f,
  val rotY: Float = 0f,
  val rotZ: Float = 0f,
  val rotW: Float = 1f,
  val scale: Float = 1f,
  val version: Long = 1L,
  val timestampMs: Long = System.currentTimeMillis(),
  val updatedByUserId: String = ""
)

/**
 * Shared multi-user room state.
 */
data class MultiplayerRoom(
  val roomId: String,
  val roomName: String,
  val hostUserId: String,
  val connectedUsers: Map<String, MultiplayerUser> = emptyMap(),
  val syncedObjects: Map<String, SyncedTransform> = emptyMap(),
  val isLocked: Boolean = false,
  val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Connection states for the real-time networking backend.
 */
enum class BackendConnectionState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  RECONNECTING,
  ERROR
}

/**
 * Events emitted by the real-time multiplayer backend.
 */
sealed class MultiplayerEvent {
  data class UserJoined(val user: MultiplayerUser) : MultiplayerEvent()
  data class UserLeft(val userId: String) : MultiplayerEvent()
  data class ObjectCreated(val transform: SyncedTransform) : MultiplayerEvent()
  data class ObjectUpdated(val transform: SyncedTransform) : MultiplayerEvent()
  data class ObjectDeleted(val objectId: String) : MultiplayerEvent()
  data class RoomStateSynced(val room: MultiplayerRoom) : MultiplayerEvent()
  data class ConnectionChanged(val state: BackendConnectionState) : MultiplayerEvent()
  data class ConflictResolved(val objectId: String, val winner: SyncedTransform) : MultiplayerEvent()
  data class ServerAck(val action: String, val ackId: String) : MultiplayerEvent()
}

/**
 * Production-Grade Real-Time Spatial Synchronization Backend.
 * Strictly separates Realtime Multiplayer from ARCore Cloud Anchors.
 *
 * Uses actual OkHttp WebSocket network transport.
 * Responsibilities:
 * 1. Users management (join/leave/identities).
 * 2. Rooms management (create/join/leave/lock).
 * 3. Realtime 6DoF object transform synchronization.
 * 4. Object creation & deletion broadcasts.
 * 5. Deterministic Conflict Resolution (Last-Write-Wins with version precedence).
 * 6. Never reports multiplayer as active when backend transport is disconnected.
 */
class RealtimeMultiplayerBackend {

  companion object {
    private const val TAG = "RealtimeMultiplayer"
    private const val RECONNECT_BASE_DELAY_MS = 1500L
  }

  private val mainHandler = Handler(Looper.getMainLooper())

  private val httpClient = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(15, TimeUnit.SECONDS)
    .build()

  private var activeWebSocket: WebSocket? = null
  private var currentEndpoint: String = ""
  private var isExplicitDisconnect: Boolean = false
  private var reconnectAttempts: Int = 0

  private val _connectionState = MutableStateFlow(BackendConnectionState.DISCONNECTED)
  val connectionState: StateFlow<BackendConnectionState> = _connectionState.asStateFlow()

  private val _currentRoom = MutableStateFlow<MultiplayerRoom?>(null)
  val currentRoom: StateFlow<MultiplayerRoom?> = _currentRoom.asStateFlow()

  var localUser: MultiplayerUser = MultiplayerUser()
    private set

  val isBackendConnected: Boolean
    get() = _connectionState.value == BackendConnectionState.CONNECTED

  /**
   * NEVER report multiplayer as active when the backend is disconnected!
   */
  val isMultiplayerActive: Boolean
    get() = isBackendConnected && _currentRoom.value != null

  var onEvent: ((MultiplayerEvent) -> Unit)? = null

  /**
   * Connects to the real-time websocket relay server.
   * Connection state only becomes CONNECTED upon genuine network onOpen socket callback.
   */
  fun connect(user: MultiplayerUser = localUser, backendEndpoint: String = "wss://spatial.session.relay") {
    localUser = user
    currentEndpoint = backendEndpoint
    isExplicitDisconnect = false
    _connectionState.value = BackendConnectionState.CONNECTING
    Log.i(TAG, "Connecting to real-time WebSocket backend at $backendEndpoint for user ${user.displayName}...")

    val request = try {
      Request.Builder()
        .url(backendEndpoint)
        .addHeader("X-User-ID", user.userId)
        .addHeader("X-User-Name", user.displayName)
        .build()
    } catch (e: Exception) {
      Log.w(TAG, "Invalid WebSocket URL '$backendEndpoint': ${e.message}. Marking ERROR state.")
      _connectionState.value = BackendConnectionState.ERROR
      onEvent?.invoke(MultiplayerEvent.ConnectionChanged(BackendConnectionState.ERROR))
      return
    }

    activeWebSocket?.cancel()
    activeWebSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        reconnectAttempts = 0
        mainHandler.post {
          _connectionState.value = BackendConnectionState.CONNECTED
          onEvent?.invoke(MultiplayerEvent.ConnectionChanged(BackendConnectionState.CONNECTED))
          Log.i(TAG, "Real WebSocket connection established with spatial relay.")

          // Send authentication and identity handshake payload
          val authPayload = JSONObject().apply {
            put("type", "AUTH")
            put("userId", localUser.userId)
            put("displayName", localUser.displayName)
            put("isHost", localUser.isHost)
            put("timestampMs", System.currentTimeMillis())
          }
          webSocket.send(authPayload.toString())
        }
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        mainHandler.post {
          handleIncomingMessage(text)
        }
      }

      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        mainHandler.post {
          _connectionState.value = BackendConnectionState.DISCONNECTED
          onEvent?.invoke(MultiplayerEvent.ConnectionChanged(BackendConnectionState.DISCONNECTED))
          Log.i(TAG, "WebSocket closed (code=$code, reason=$reason)")
        }
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        mainHandler.post {
          Log.w(TAG, "WebSocket connection failed: ${t.message}")
          if (!isExplicitDisconnect) {
            scheduleReconnect()
          } else {
            _connectionState.value = BackendConnectionState.DISCONNECTED
            onEvent?.invoke(MultiplayerEvent.ConnectionChanged(BackendConnectionState.DISCONNECTED))
          }
        }
      }
    })
  }

  private fun scheduleReconnect() {
    if (isExplicitDisconnect) return
    reconnectAttempts++
    if (reconnectAttempts > 5) {
      _connectionState.value = BackendConnectionState.ERROR
      onEvent?.invoke(MultiplayerEvent.ConnectionChanged(BackendConnectionState.ERROR))
      Log.e(TAG, "WebSocket reconnect exceeded maximum attempts. Marking ERROR.")
      return
    }

    _connectionState.value = BackendConnectionState.RECONNECTING
    onEvent?.invoke(MultiplayerEvent.ConnectionChanged(BackendConnectionState.RECONNECTING))
    val delayMs = RECONNECT_BASE_DELAY_MS * (1 shl (reconnectAttempts - 1)).coerceAtMost(8)
    Log.i(TAG, "Scheduling WebSocket reconnect in ${delayMs}ms (attempt #$reconnectAttempts)...")
    mainHandler.postDelayed({
      if (!isExplicitDisconnect && _connectionState.value == BackendConnectionState.RECONNECTING) {
        connect(localUser, currentEndpoint)
      }
    }, delayMs)
  }

  /**
   * Parses and applies incoming JSON messages from real-time relay.
   */
  private fun handleIncomingMessage(jsonStr: String) {
    try {
      val json = JSONObject(jsonStr)
      when (json.optString("type")) {
        "ACK" -> {
          val action = json.optString("action")
          val ackId = json.optString("ackId")
          onEvent?.invoke(MultiplayerEvent.ServerAck(action, ackId))
        }
        "USER_JOIN" -> {
          val user = MultiplayerUser(
            userId = json.getString("userId"),
            displayName = json.optString("displayName", "User"),
            isHost = json.optBoolean("isHost", false),
            avatarColorRgb = json.optInt("avatarColorRgb", 0x38BDF8)
          )
          val room = _currentRoom.value
          if (room != null) {
            val updatedUsers = room.connectedUsers + (user.userId to user)
            _currentRoom.value = room.copy(connectedUsers = updatedUsers)
          }
          onEvent?.invoke(MultiplayerEvent.UserJoined(user))
        }
        "USER_LEFT" -> {
          val userId = json.getString("userId")
          val room = _currentRoom.value
          if (room != null) {
            val updatedUsers = room.connectedUsers - userId
            _currentRoom.value = room.copy(connectedUsers = updatedUsers)
          }
          onEvent?.invoke(MultiplayerEvent.UserLeft(userId))
        }
        "OBJECT_CREATED" -> {
          val obj = parseSyncedTransform(json.getJSONObject("transform"))
          val room = _currentRoom.value
          if (room != null) {
            val updatedObjects = room.syncedObjects + (obj.objectId to obj)
            _currentRoom.value = room.copy(syncedObjects = updatedObjects)
          }
          onEvent?.invoke(MultiplayerEvent.ObjectCreated(obj))
        }
        "OBJECT_UPDATED" -> {
          val obj = parseSyncedTransform(json.getJSONObject("transform"))
          val room = _currentRoom.value
          if (room != null) {
            val existing = room.syncedObjects[obj.objectId]
            val resolved = if (existing != null) resolveConflict(existing, obj) else obj
            val updatedObjects = room.syncedObjects + (resolved.objectId to resolved)
            _currentRoom.value = room.copy(syncedObjects = updatedObjects)
            onEvent?.invoke(MultiplayerEvent.ObjectUpdated(resolved))
          }
        }
        "OBJECT_DELETED" -> {
          val objectId = json.getString("objectId")
          val room = _currentRoom.value
          if (room != null) {
            val updatedObjects = room.syncedObjects - objectId
            _currentRoom.value = room.copy(syncedObjects = updatedObjects)
          }
          onEvent?.invoke(MultiplayerEvent.ObjectDeleted(objectId))
        }
      }
    } catch (e: Exception) {
      Log.d(TAG, "Non-critical JSON message parse error: ${e.message}")
    }
  }

  private fun parseSyncedTransform(json: JSONObject): SyncedTransform {
    return SyncedTransform(
      objectId = json.getString("objectId"),
      modelId = json.getString("modelId"),
      cloudAnchorId = if (json.isNull("cloudAnchorId")) null else json.optString("cloudAnchorId"),
      posX = json.optDouble("posX", 0.0).toFloat(),
      posY = json.optDouble("posY", 0.0).toFloat(),
      posZ = json.optDouble("posZ", 0.0).toFloat(),
      rotX = json.optDouble("rotX", 0.0).toFloat(),
      rotY = json.optDouble("rotY", 0.0).toFloat(),
      rotZ = json.optDouble("rotZ", 0.0).toFloat(),
      rotW = json.optDouble("rotW", 1.0).toFloat(),
      scale = json.optDouble("scale", 1.0).toFloat(),
      version = json.optLong("version", 1L),
      timestampMs = json.optLong("timestampMs", System.currentTimeMillis()),
      updatedByUserId = json.optString("updatedByUserId", "")
    )
  }

  /**
   * Disconnects from the real-time transport backend.
   */
  fun disconnect() {
    isExplicitDisconnect = true
    reconnectAttempts = 0
    val prevRoom = _currentRoom.value
    if (prevRoom != null) {
      leaveRoom()
    }
    activeWebSocket?.close(1000, "Client disconnect")
    activeWebSocket = null
    _connectionState.value = BackendConnectionState.DISCONNECTED
    onEvent?.invoke(MultiplayerEvent.ConnectionChanged(BackendConnectionState.DISCONNECTED))
    Log.i(TAG, "Disconnected from real-time multiplayer backend.")
  }

  /**
   * Creates and hosts a new multiplayer room.
   */
  fun createRoom(roomId: String, roomName: String = "Room_$roomId"): MultiplayerRoom? {
    if (!isBackendConnected) {
      Log.w(TAG, "Cannot create room: Real-time backend is disconnected.")
      return null
    }

    val hostUser = localUser.copy(isHost = true)
    localUser = hostUser

    val room = MultiplayerRoom(
      roomId = roomId,
      roomName = roomName,
      hostUserId = hostUser.userId,
      connectedUsers = mapOf(hostUser.userId to hostUser),
      syncedObjects = emptyMap()
    )

    _currentRoom.value = room
    onEvent?.invoke(MultiplayerEvent.RoomStateSynced(room))

    // Transmit room creation frame over network
    val payload = JSONObject().apply {
      put("type", "CREATE_ROOM")
      put("roomId", roomId)
      put("roomName", roomName)
      put("userId", hostUser.userId)
    }
    activeWebSocket?.send(payload.toString())

    Log.i(TAG, "Created real-time multiplayer room '$roomId' hosted by ${hostUser.userId}")
    return room
  }

  /**
   * Joins an existing multiplayer room.
   */
  fun joinRoom(roomId: String, hostUserHint: MultiplayerUser? = null): Boolean {
    if (!isBackendConnected) {
      Log.w(TAG, "Cannot join room: Real-time backend is disconnected.")
      return false
    }

    val joiningUser = localUser.copy(isHost = false)
    localUser = joiningUser

    val current = _currentRoom.value
    val baseUsers = mutableMapOf<String, MultiplayerUser>()
    if (hostUserHint != null) {
      baseUsers[hostUserHint.userId] = hostUserHint
    }
    if (current != null) {
      baseUsers.putAll(current.connectedUsers)
    }
    baseUsers[joiningUser.userId] = joiningUser

    val room = MultiplayerRoom(
      roomId = roomId,
      roomName = "Room_$roomId",
      hostUserId = hostUserHint?.userId ?: baseUsers.values.firstOrNull { it.isHost }?.userId ?: "remote_host",
      connectedUsers = baseUsers,
      syncedObjects = current?.syncedObjects ?: emptyMap()
    )

    _currentRoom.value = room
    onEvent?.invoke(MultiplayerEvent.RoomStateSynced(room))
    onEvent?.invoke(MultiplayerEvent.UserJoined(joiningUser))

    // Transmit join message over network
    val payload = JSONObject().apply {
      put("type", "JOIN_ROOM")
      put("roomId", roomId)
      put("userId", joiningUser.userId)
      put("displayName", joiningUser.displayName)
    }
    activeWebSocket?.send(payload.toString())

    Log.i(TAG, "Joined real-time multiplayer room '$roomId' as ${joiningUser.displayName}")
    return true
  }

  /**
   * Leaves the active room.
   */
  fun leaveRoom() {
    val room = _currentRoom.value ?: return
    val userId = localUser.userId

    val payload = JSONObject().apply {
      put("type", "LEAVE_ROOM")
      put("roomId", room.roomId)
      put("userId", userId)
    }
    activeWebSocket?.send(payload.toString())

    onEvent?.invoke(MultiplayerEvent.UserLeft(userId))
    _currentRoom.value = null
    Log.i(TAG, "Left multiplayer room '${room.roomId}'")
  }

  /**
   * Broadcasts creation of a new 3D spatial exhibit.
   */
  fun createObject(transform: SyncedTransform) {
    if (!isMultiplayerActive) return
    val room = _currentRoom.value ?: return

    val updatedObjects = room.syncedObjects + (transform.objectId to transform)
    val updatedRoom = room.copy(syncedObjects = updatedObjects)
    _currentRoom.value = updatedRoom
    onEvent?.invoke(MultiplayerEvent.ObjectCreated(transform))

    val payload = JSONObject().apply {
      put("type", "OBJECT_CREATE")
      put("roomId", room.roomId)
      put("transform", JSONObject().apply {
        put("objectId", transform.objectId)
        put("modelId", transform.modelId)
        put("cloudAnchorId", transform.cloudAnchorId)
        put("posX", transform.posX.toDouble())
        put("posY", transform.posY.toDouble())
        put("posZ", transform.posZ.toDouble())
        put("rotX", transform.rotX.toDouble())
        put("rotY", transform.rotY.toDouble())
        put("rotZ", transform.rotZ.toDouble())
        put("rotW", transform.rotW.toDouble())
        put("scale", transform.scale.toDouble())
        put("version", transform.version)
        put("timestampMs", transform.timestampMs)
        put("updatedByUserId", transform.updatedByUserId)
      })
    }
    activeWebSocket?.send(payload.toString())
  }

  /**
   * Broadcasts update to an existing 3D spatial exhibit transform.
   * Performs deterministic Last-Write-Wins (LWW) conflict resolution.
   */
  fun updateObjectTransform(incoming: SyncedTransform) {
    if (!isMultiplayerActive) return
    val room = _currentRoom.value ?: return

    val existing = room.syncedObjects[incoming.objectId]
    val resolved = if (existing != null) {
      resolveConflict(existing, incoming)
    } else {
      incoming
    }

    val updatedObjects = room.syncedObjects + (resolved.objectId to resolved)
    _currentRoom.value = room.copy(syncedObjects = updatedObjects)
    onEvent?.invoke(MultiplayerEvent.ObjectUpdated(resolved))

    val payload = JSONObject().apply {
      put("type", "OBJECT_UPDATE")
      put("roomId", room.roomId)
      put("transform", JSONObject().apply {
        put("objectId", resolved.objectId)
        put("modelId", resolved.modelId)
        put("cloudAnchorId", resolved.cloudAnchorId)
        put("posX", resolved.posX.toDouble())
        put("posY", resolved.posY.toDouble())
        put("posZ", resolved.posZ.toDouble())
        put("rotX", resolved.rotX.toDouble())
        put("rotY", resolved.rotY.toDouble())
        put("rotZ", resolved.rotZ.toDouble())
        put("rotW", resolved.rotW.toDouble())
        put("scale", resolved.scale.toDouble())
        put("version", resolved.version)
        put("timestampMs", resolved.timestampMs)
        put("updatedByUserId", resolved.updatedByUserId)
      })
    }
    activeWebSocket?.send(payload.toString())
  }

  /**
   * Broadcasts deletion of a 3D spatial exhibit.
   */
  fun deleteObject(objectId: String) {
    if (!isMultiplayerActive) return
    val room = _currentRoom.value ?: return

    val updatedObjects = room.syncedObjects - objectId
    _currentRoom.value = room.copy(syncedObjects = updatedObjects)
    onEvent?.invoke(MultiplayerEvent.ObjectDeleted(objectId))

    val payload = JSONObject().apply {
      put("type", "OBJECT_DELETE")
      put("roomId", room.roomId)
      put("objectId", objectId)
    }
    activeWebSocket?.send(payload.toString())
  }

  /**
   * Deterministic conflict resolution:
   * 1. Higher version wins.
   * 2. If equal versions, higher timestamp (Last-Write-Wins) wins.
   * 3. If timestamps match, tie-break deterministically using user ID.
   */
  fun resolveConflict(existing: SyncedTransform, incoming: SyncedTransform): SyncedTransform {
    val winner = when {
      incoming.version > existing.version -> incoming
      incoming.version < existing.version -> existing
      incoming.timestampMs > existing.timestampMs -> incoming
      incoming.timestampMs < existing.timestampMs -> existing
      incoming.updatedByUserId >= existing.updatedByUserId -> incoming
      else -> existing
    }

    if (winner != incoming) {
      Log.d(TAG, "Conflict resolved: Kept object ${existing.objectId} (v${existing.version}) over incoming (v${incoming.version})")
      onEvent?.invoke(MultiplayerEvent.ConflictResolved(existing.objectId, winner))
    }
    return winner
  }
}
