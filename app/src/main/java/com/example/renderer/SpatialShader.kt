package com.example.renderer

import android.opengl.GLES30
import android.util.Log

/**
 * Compiles and links vertex and fragment shaders with full Cook-Torrance PBR shading pipeline.
 */
object SpatialShader {

  private const val TAG = "SpatialShader"

  const val VERTEX_SHADER_CODE = """#version 300 es
    layout(location = 0) in vec3 aPosition;
    layout(location = 1) in vec3 aNormal;
    
    uniform mat4 uMVPMatrix;
    uniform mat4 uModelMatrix;
    uniform mat4 uNormalMatrix;
    
    out vec3 vFragPos;
    out vec3 vNormal;
    
    void main() {
      vFragPos = vec3(uModelMatrix * vec4(aPosition, 1.0));
      vNormal = normalize(mat3(uNormalMatrix) * aNormal);
      gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
    }
  """

  const val FRAGMENT_SHADER_CODE = """#version 300 es
    precision highp float;
    
    in vec3 vFragPos;
    in vec3 vNormal;
    
    uniform vec3 uViewPos;
    uniform vec3 uLightPos;
    uniform vec3 uLightColor;
    uniform vec3 uBaseColor;
    uniform float uMetallic;
    uniform float uRoughness;
    uniform vec3 uEmissive;
    uniform float uAmbientIntensity;
    uniform int uIsGrid;
    uniform int uIsUnlit;
    
    out vec4 FragColor;
    
    const float PI = 3.14159265359;
    
    // Distribution function (Trowbridge-Reitz GGX)
    float DistributionGGX(vec3 N, vec3 H, float roughness) {
      float a = roughness * roughness;
      float a2 = a * a;
      float NdotH = max(dot(N, H), 0.0);
      float NdotH2 = NdotH * NdotH;
      
      float nom = a2;
      float denom = (NdotH2 * (a2 - 1.0) + 1.0);
      denom = PI * denom * denom;
      return nom / max(denom, 0.0001);
    }
    
    // Geometry function (Schlick-GGX)
    float GeometrySchlickGGX(float NdotV, float roughness) {
      float r = (roughness + 1.0);
      float k = (r * r) / 8.0;
      float nom = NdotV;
      float denom = NdotV * (1.0 - k) + k;
      return nom / max(denom, 0.0001);
    }
    
    float GeometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
      float NdotV = max(dot(N, V), 0.0);
      float NdotL = max(dot(N, L), 0.0);
      float ggx2 = GeometrySchlickGGX(NdotV, roughness);
      float ggx1 = GeometrySchlickGGX(NdotL, roughness);
      return ggx1 * ggx2;
    }
    
    // Fresnel function (Fresnel-Schlick)
    vec3 fresnelSchlick(float cosTheta, vec3 F0) {
      return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
    }
    
    void main() {
      if (uIsGrid == 1) {
        // Spatial coordinate grid plane shader with anti-aliased procedural lines
        vec2 coord = vFragPos.xz * 3.5;
        vec2 grid = abs(fract(coord - 0.5) - 0.5) / fwidth(coord);
        float line = min(grid.x, grid.y);
        float alpha = 1.0 - min(line, 1.0);
        float distFade = clamp(1.0 - length(vFragPos.xz) * 0.16, 0.0, 1.0);
        FragColor = vec4(0.18, 0.76, 0.98, alpha * 0.55 * distFade);
        return;
      }
      
      if (uIsUnlit == 1) {
        FragColor = vec4(uBaseColor + uEmissive, 1.0);
        return;
      }
      
      vec3 N = normalize(vNormal);
      vec3 V = normalize(uViewPos - vFragPos);
      vec3 L = normalize(uLightPos - vFragPos);
      vec3 H = normalize(L + V);
      
      // Calculate reflectance at normal incidence F0
      vec3 F0 = vec3(0.04);
      F0 = mix(F0, uBaseColor, uMetallic);
      
      // Cook-Torrance BRDF Specular
      float NDF = DistributionGGX(N, H, max(uRoughness, 0.05));
      float G = GeometrySmith(N, V, L, max(uRoughness, 0.05));
      vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);
      
      vec3 numerator = NDF * G * F;
      float denominator = 4.0 * max(dot(N, V), 0.0) * max(dot(N, L), 0.0) + 0.0001;
      vec3 specular = numerator / denominator;
      
      // kS is equal to Fresnel
      vec3 kS = F;
      // For energy conservation, diffuse and specular can't be above 1.0
      vec3 kD = vec3(1.0) - kS;
      kD *= 1.0 - uMetallic;
      
      // Scale light by NdotL
      float NdotL = max(dot(N, L), 0.0);
      
      // Outgoing radiance Lo
      vec3 Lo = (kD * uBaseColor / PI + specular) * uLightColor * NdotL;
      
      // Ambient lighting + Hemisphere sky bounce
      vec3 ambient = (vec3(0.12, 0.18, 0.28) * uAmbientIntensity) * uBaseColor;
      
      // Subtle rim glow for spatial cyber aesthetic
      float rim = 1.0 - max(dot(V, N), 0.0);
      vec3 rimGlow = pow(rim, 3.5) * vec3(0.2, 0.8, 1.0) * 0.35 * (1.0 - uRoughness * 0.4);
      
      vec3 color = ambient + Lo + uEmissive + rimGlow;
      
      // ACES / Reinhard Tone Mapping
      color = color / (color + vec3(1.0));
      // Gamma correction
      color = pow(color, vec3(1.0 / 2.2));
      
      FragColor = vec4(color, 1.0);
    }
  """

  fun createProgram(): Int {
    val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
    val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)
    val program = GLES30.glCreateProgram()
    GLES30.glAttachShader(program, vertexShader)
    GLES30.glAttachShader(program, fragmentShader)
    GLES30.glLinkProgram(program)

    val linkStatus = IntArray(1)
    GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
    if (linkStatus[0] == 0) {
      Log.e(TAG, "Shader link error: ${GLES30.glGetProgramInfoLog(program)}")
      GLES30.glDeleteProgram(program)
      return 0
    }
    return program
  }

  private fun loadShader(type: Int, shaderCode: String): Int {
    val shader = GLES30.glCreateShader(type)
    GLES30.glShaderSource(shader, shaderCode)
    GLES30.glCompileShader(shader)

    val compileStatus = IntArray(1)
    GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
    if (compileStatus[0] == 0) {
      Log.e(TAG, "Shader compilation error: ${GLES30.glGetShaderInfoLog(shader)}")
      GLES30.glDeleteShader(shader)
      return 0
    }
    return shader
  }
}
