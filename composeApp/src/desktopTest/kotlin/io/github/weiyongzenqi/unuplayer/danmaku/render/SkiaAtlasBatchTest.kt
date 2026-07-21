package io.github.weiyongzenqi.unuplayer.danmaku.render

import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.jetbrains.skia.VertexMode

class SkiaAtlasBatchTest {
    @Test
    fun `drawVertices 可以按 atlas UV 批量采样`() {
        Surface.makeRasterN32Premul(2, 1).use { atlasSurface ->
            Paint().use { red ->
                red.color = 0xFFFF0000.toInt()
                atlasSurface.canvas.drawRect(0f, 0f, 1f, 1f, red)
            }
            Paint().use { green ->
                green.color = 0xFF00FF00.toInt()
                atlasSurface.canvas.drawRect(1f, 0f, 2f, 1f, green)
            }
            atlasSurface.makeImageSnapshot().use { atlas ->
                Surface.makeRasterN32Premul(2, 1).use { target ->
                    val shader = atlas.makeShader(
                        FilterTileMode.CLAMP,
                        FilterTileMode.CLAMP,
                        FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE),
                        Matrix33.IDENTITY,
                    )
                    Paint().use { paint ->
                        paint.shader = shader
                        target.canvas.drawVertices(
                            VertexMode.TRIANGLES,
                            floatArrayOf(0f, 0f, 2f, 0f, 2f, 1f, 0f, 1f),
                            null,
                            floatArrayOf(0f, 0f, 2f, 0f, 2f, 1f, 0f, 1f),
                            shortArrayOf(0, 1, 2, 0, 2, 3),
                            BlendMode.SRC_OVER,
                            paint,
                        )
                    }
                    shader.close()
                    target.makeImageSnapshot().use { output ->
                        Bitmap().also { bitmap ->
                            bitmap.allocN32Pixels(2, 1, false)
                            output.readPixels(bitmap)
                            assertEquals(0xFFFF0000.toInt(), bitmap.getColor(0, 0))
                            assertEquals(0xFF00FF00.toInt(), bitmap.getColor(1, 0))
                            bitmap.close()
                        }
                    }
                }
            }
        }
    }
}
