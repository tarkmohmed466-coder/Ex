package com.example.renderer

import android.opengl.GLES30
import android.util.Log

/**
 * Compiles and links vertex and fragment shaders with error diagnostics.
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
    
    out vec4 FragColor;
    
    void main() {
      if (uIsGrid == 1) {
        // Spatial coordinate grid plane shader
        vec2 coord = vFragPos.xz * 4.0;
        vec2 grid = abs(fract(coord - 0.5) - 0.5) / fwidth(coord);
        float line = min(grid.x, grid.y);
        float alpha = 1.0 - min(line, 1.0);
        float distFade = clamp(1.0 - length(vFragPos.xz) * 0.18, 0.0, 1.0);
        FragColor = vec4(0.22, 0.74, 0.97, alpha * 0.45 * distFade);
        return;
      }
      
      vec3 N = normalize(vNormal);
      vec3 V = normalize(uViewPos - vFragPos);
      vec3 L = normalize(uLightPos - vFragPos);
      vec3 H = normalize(L + V);
      
      // Ambient Component
      vec3 ambient = uAmbientIntensity * uBaseColor * vec3(0.18, 0.25, 0.35);
      
      // Diffuse NdotL
      float diff = max(dot(N, L), 0.0);
      vec3 diffuse = diff * uBaseColor * uLightColor;
      
      // Specular Microfacet Approximation (PBR metallic/roughness)
      float specPower = mix(128.0, 8.0, uRoughness);
      float specFactor = pow(max(dot(N, H), 0.0), specPower);
      vec3 specularColor = mix(vec3(0.04), uBaseColor, uMetallic);
      vec3 specular = specFactor * specularColor * uLightColor * (1.0 - uRoughness * 0.5);
      
      // Fresnel Rim Glow for futuristic spatial presence
      float rim = 1.0 - max(dot(V, N), 0.0);
      vec3 rimGlow = pow(rim, 3.0) * vec3(0.2, 0.8, 1.0) * 0.4;
      
      // Final Composite Color with Tone Mapping
      vec3 result = ambient + diffuse + specular + uEmissive + rimGlow;
      
      // Reinhard Tone Mapping
      result = result / (result + vec3(1.0));
      // Gamma correction
      result = pow(result, vec3(1.0 / 2.2));
      
      FragColor = vec4(result, 1.0);
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
