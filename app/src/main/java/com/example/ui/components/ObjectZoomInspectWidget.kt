package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Floating Zoom & Inspect Control Widget for 3D Object Mode.
 * Provides on-screen Zoom In (+), Zoom Out (-), Center Reset, Auto-Rotate, and Studio Grid toggles.
 */
@Composable
fun ObjectZoomInspectWidget(
  zoomPercentage: Int,
  isAutoRotating: Boolean,
  isGridVisible: Boolean,
  onZoomIn: () -> Unit,
  onZoomOut: () -> Unit,
  onResetView: () -> Unit,
  onToggleAutoRotate: () -> Unit,
  onToggleGrid: () -> Unit,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(24.dp))
      .background(Color(0xFF0F172A).copy(alpha = 0.85f))
      .border(1.dp, Color(0xFF334155).copy(alpha = 0.6f), RoundedCornerShape(24.dp))
      .padding(vertical = 8.dp, horizontal = 6.dp)
      .testTag("object_zoom_inspect_widget")
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      // 1. Zoom In (+) Button
      ZoomIconButton(
        icon = Icons.Default.Add,
        contentDescription = "Zoom In",
        tag = "zoom_in_button",
        onClick = onZoomIn
      )

      // 2. Zoom Level Badge
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .padding(vertical = 2.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(Color(0xFF1E293B))
          .padding(horizontal = 6.dp, vertical = 3.dp)
          .testTag("zoom_level_badge")
      ) {
        Text(
          text = "${zoomPercentage}%",
          color = Color(0xFF38BDF8),
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold
        )
      }

      // 3. Zoom Out (-) Button
      ZoomIconButton(
        icon = Icons.Default.Remove,
        contentDescription = "Zoom Out",
        tag = "zoom_out_button",
        onClick = onZoomOut
      )

      Spacer(modifier = Modifier.height(4.dp))

      // 4. Center / Reset View
      ZoomIconButton(
        icon = Icons.Default.CenterFocusStrong,
        contentDescription = "Reset Camera View",
        tag = "reset_view_button",
        tint = Color(0xFFE2E8F0),
        onClick = onResetView
      )

      // 5. Auto-Rotate Toggle
      ZoomIconButton(
        icon = Icons.Default.Autorenew,
        contentDescription = "Toggle Auto-Rotate",
        tag = "toggle_auto_rotate_button",
        tint = if (isAutoRotating) Color(0xFF38BDF8) else Color(0xFF94A3B8),
        onClick = onToggleAutoRotate
      )

      // 6. Studio Grid Floor Toggle
      ZoomIconButton(
        icon = Icons.Default.GridOn,
        contentDescription = "Toggle Grid Floor",
        tag = "toggle_grid_button",
        tint = if (isGridVisible) Color(0xFFFBBF24) else Color(0xFF64748B),
        onClick = onToggleGrid
      )
    }
  }
}

@Composable
private fun ZoomIconButton(
  icon: ImageVector,
  contentDescription: String,
  tag: String,
  tint: Color = Color.White,
  onClick: () -> Unit
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .size(40.dp)
      .clip(CircleShape)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = ripple(bounded = true, color = Color(0xFF38BDF8))
      ) { onClick() }
      .testTag(tag)
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(20.dp)
    )
  }
}
