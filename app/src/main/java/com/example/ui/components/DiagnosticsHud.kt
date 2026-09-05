package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.TelemetryState

@Composable
fun DiagnosticsHud(
  telemetry: TelemetryState,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(16.dp))
      .background(Color(0xFF0F172A).copy(alpha = 0.90f))
      .padding(horizontal = 14.dp, vertical = 10.dp)
      .testTag("diagnostics_hud")
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(8.dp)
              .clip(CircleShape)
              .background(if (telemetry.fps >= 45) Color(0xFF22C55E) else Color(0xFFEAB308))
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "FPS: ${telemetry.fps.toInt()}",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Color.White
          )
        }

        Text(
          text = "DrawCalls: ${telemetry.drawCalls}",
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          color = Color(0xFF94A3B8)
        )

        Text(
          text = "${telemetry.vertexCount} Verts",
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          color = Color(0xFF38BDF8)
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = "SCALE: 1:1 Metric (1u=1m)",
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          color = Color(0xFF4ADE80)
        )
        Text(
          text = "Walk: ${String.format("%.2f", telemetry.walkingDisplacementMeters)}m",
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          color = Color(0xFFA78BFA)
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = "Depth: ${String.format("%.2f", telemetry.depthAvgMeters)}m ${if (telemetry.depthOcclusionDetected) "[OCCLUSION]" else ""}",
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          color = if (telemetry.depthOcclusionDetected) Color(0xFFF43F5E) else Color(0xFF38BDF8)
        )
        Text(
          text = "Thermal: ${telemetry.thermalStatus}",
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          color = Color(0xFFCBD5E1)
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = "Planes: H:${telemetry.horizontalPlanesCount} V:${telemetry.verticalPlanesCount} | Images: ${telemetry.trackedImagesCount} | Anchors: ${telemetry.activeAnchorsCount}",
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          color = Color(0xFF94A3B8)
        )
        Text(
          text = "Light: ${telemetry.lightIntensityLumens.toInt()} lx",
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          color = Color(0xFFFBBF24)
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = "Semantics: ${telemetry.dominantSemanticLabel} | VPS: ${if (telemetry.isGeospatialActive) "Active" else "Local 6DoF"}",
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          color = Color(0xFF38BDF8)
        )
        Text(
          text = "Conf: ${telemetry.depthConfidenceScore.toInt()}% | ${telemetry.deviceTier.substringAfter("TIER_")}",
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          color = Color(0xFF34D399)
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = "Rec: ${telemetry.arRecordingStatus} | Cloud Anchors: ${telemetry.cloudAnchorsCount}",
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          color = if (telemetry.arRecordingStatus == "RECORDING") Color(0xFFEF4444) else Color(0xFF94A3B8)
        )
        Text(
          text = "Mesh: ${telemetry.environmentalMeshTriangles} tris (${"%.1f".format(telemetry.environmentalMeshAreaSqM)}m²)",
          fontFamily = FontFamily.Monospace,
          fontSize = 10.sp,
          color = Color(0xFFA78BFA)
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = "MR: MONOSCOPIC_PASSTHROUGH_STEREOSCOPIC_VIRTUAL (Monoscopic sensor + L/R stereo 3D)",
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          color = Color(0xFF67E8F9)
        )
      }
    }
  }
}
