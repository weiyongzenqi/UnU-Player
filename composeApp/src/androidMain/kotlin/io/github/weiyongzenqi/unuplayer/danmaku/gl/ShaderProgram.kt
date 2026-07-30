package io.github.weiyongzenqi.unuplayer.danmaku.gl

import android.opengl.GLES30
import android.util.Log

/**
 * OpenGL ES 3.0 shader 程序工具：编译 vertex/fragment shader、链接 program、查询 attribute/uniform 位置。
 *
 * 所有 GL 调用必须在 GL 线程（GLSurfaceView.Renderer 回调）中执行。
 */
internal class ShaderProgram(
    val programId: Int,
    private val attributeLocations: Map<String, Int>,
    private val uniformLocations: Map<String, Int>,
) {
    /** 获取 attribute location，未找到返回 -1。 */
    fun attrib(name: String): Int = attributeLocations[name] ?: -1

    /** 获取 uniform location，未找到返回 -1。 */
    fun uniform(name: String): Int = uniformLocations[name] ?: -1

    /** 激活此 program 用于后续 draw call。 */
    fun use() {
        GLES30.glUseProgram(programId)
    }

    /** 删除 shader program，释放 GPU 资源。 */
    fun delete() {
        GLES30.glDeleteProgram(programId)
    }

    companion object {
        /**
         * 编译 vertex + fragment shader 并链接为 program。
         * 编译 vertex + fragment shader 并链接为 program。
         *
         * @param vertexSource GLSL vertex shader 源码（#version 300 es）
         * @param fragmentSource GLSL fragment shader 源码（#version 300 es）
         * @param attributes 需要查询 location 的 attribute 名称列表
         * @param uniforms 需要查询 location 的 uniform 名称列表
         * @return 编译链接成功的 [ShaderProgram]，失败返回 null
         */
        fun compile(
            vertexSource: String,
            fragmentSource: String,
            attributes: List<String> = emptyList(),
            uniforms: List<String> = emptyList(),
        ): ShaderProgram? {
            val vertId = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource) ?: return null
            val fragId = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource) ?: run {
                GLES30.glDeleteShader(vertId)
                return null
            }

            val programId = GLES30.glCreateProgram().also { id ->
                GLES30.glAttachShader(id, vertId)
                GLES30.glAttachShader(id, fragId)
                GLES30.glLinkProgram(id)
            }

            // 链接后可释放 shader 对象
            GLES30.glDeleteShader(vertId)
            GLES30.glDeleteShader(fragId)

            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(programId)
                Log.e("ShaderProgram", "Program link failed: $log")
                GLES30.glDeleteProgram(programId)
                return null
            }

            val attribMap = attributes.associateWith { GLES30.glGetAttribLocation(programId, it) }
            val uniformMap = uniforms.associateWith { GLES30.glGetUniformLocation(programId, it) }

            return ShaderProgram(programId, attribMap, uniformMap)
        }

        private fun compileShader(type: Int, source: String): Int? {
            val shaderId = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shaderId, source)
            GLES30.glCompileShader(shaderId)

            val compileStatus = IntArray(1)
            GLES30.glGetShaderiv(shaderId, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shaderId)
                val typeName = if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"
                Log.e("ShaderProgram", "$typeName shader compile failed: $log")
                GLES30.glDeleteShader(shaderId)
                return null
            }
            return shaderId
        }
    }
}
