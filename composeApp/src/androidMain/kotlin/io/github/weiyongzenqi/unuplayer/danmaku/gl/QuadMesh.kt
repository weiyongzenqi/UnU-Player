package io.github.weiyongzenqi.unuplayer.danmaku.gl

import android.opengl.GLES30
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * 单位四边形 VAO：4 顶点（两个三角形），用于实例化渲染。
 *
 * 顶点布局：(-1,-1) (1,-1) (-1,1) (1,1) — 即本地坐标 [0,1] 范围，
 * vertex shader 内将其映射到实例 scale × 实例 pos 的屏幕矩形。
 *
 * VBO 0 (static): 单位四边形顶点位置
 * EBO  (static): 索引 [0,1,2, 1,3,2] (GL_UNSIGNED_SHORT)
 */
internal class QuadMesh {

    val vaoId: Int
    val vertexCount: Int = 4
    val indexCount: Int = 6

    init {
        val vao = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        vaoId = vao[0]
        GLES30.glBindVertexArray(vaoId)

        // VBO 0: 单位四边形顶点（static）
        val vertices = floatArrayOf(
            -1f, -1f,   // 左下
             1f, -1f,   // 右下
            -1f,  1f,   // 左上
             1f,  1f,   // 右上
        )
        val vboArr = IntArray(1)
        GLES30.glGenBuffers(1, vboArr, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboArr[0])
        val fb = FloatBuffer.wrap(vertices)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glVertexAttribPointer(QUAD_POS_LOCATION, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glEnableVertexAttribArray(QUAD_POS_LOCATION)

        // EBO: 索引（static）
        val indices = shortArrayOf(0, 1, 2, 1, 3, 2)
        val eboArr = IntArray(1)
        GLES30.glGenBuffers(1, eboArr, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, eboArr[0])
        val sb = ShortBuffer.wrap(indices)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, sb, GLES30.GL_STATIC_DRAW)

        GLES30.glBindVertexArray(0)
    }

    fun bind() {
        GLES30.glBindVertexArray(vaoId)
    }

    fun unbind() {
        GLES30.glBindVertexArray(0)
    }

    fun delete() {
        val arr = intArrayOf(vaoId)
        GLES30.glDeleteVertexArrays(1, arr, 0)
    }

    companion object {
        /** 单位四边形顶点在 VAO 中的 attribute location。 */
        const val QUAD_POS_LOCATION = 0
    }
}
