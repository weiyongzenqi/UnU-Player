package io.github.weiyongzenqi.unuplayer.danmaku.render

import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.SamplingMode
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
                        FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
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

    @Test
    fun `drawVertices 顶点色以 MODULATE 调制采样`() {
        // atlas 左白右黑 + 顶点色红: 白×红=红(填充染色), 黑×红=黑(描边保持)——
        // 颜色无关烘焙的调制语义基石。
        Surface.makeRasterN32Premul(2, 1).use { atlasSurface ->
            Paint().use { white ->
                white.color = 0xFFFFFFFF.toInt()
                atlasSurface.canvas.drawRect(0f, 0f, 1f, 1f, white)
            }
            Paint().use { black ->
                black.color = 0xFF000000.toInt()
                atlasSurface.canvas.drawRect(1f, 0f, 2f, 1f, black)
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
                        val vertexColorRed = 0xFFFF0000.toInt()
                        target.canvas.drawVertices(
                            VertexMode.TRIANGLES,
                            floatArrayOf(0f, 0f, 2f, 0f, 2f, 1f, 0f, 1f),
                            IntArray(4) { vertexColorRed },
                            floatArrayOf(0f, 0f, 2f, 0f, 2f, 1f, 0f, 1f),
                            shortArrayOf(0, 1, 2, 0, 2, 3),
                            BlendMode.MODULATE,
                            paint,
                        )
                    }
                    shader.close()
                    target.makeImageSnapshot().use { output ->
                        Bitmap().also { bitmap ->
                            bitmap.allocN32Pixels(2, 1, false)
                            output.readPixels(bitmap)
                            assertEquals(0xFFFF0000.toInt(), bitmap.getColor(0, 0), "白填充×红=红")
                            assertEquals(0xFF000000.toInt(), bitmap.getColor(1, 0), "黑描边×红=黑")
                            bitmap.close()
                        }
                    }
                }
            }
        }
    }

    /**
     * 语义回归测试(2026-08-15 实测证伪结论): skia 的 [Bitmap.makeShader] 对可变位图是
     * **构造时拷贝**(SkImage 不可变, MakeFromBitmap 对可变 bitmap 必须拷贝), 构造后对
     * bitmap 的写入/擦除/notifyPixelsChanged 都不会反映到已创建的 shader——即使 raster
     * 后端也不例外。"可变 Bitmap + 常驻 shader + notifyPixelsChanged" 只在 Android HWUI
     * 的 BitmapShader 语义下成立, skia 下不可行。DesktopAtlasDanmakuEngine 因此保留
     * Surface 脏页快照路径; 若未来要改回 Bitmap 常驻 shader, 本测试就是否决证据。
     */
    @Test
    fun `Bitmap makeShader 对可变位图是构造时快照 后续修改不反映`() {
        val bitmap = Bitmap().also {
            it.allocN32Pixels(1, 1, false)
            it.erase(0)
        }
        bitmap.use {
            Canvas(bitmap).use { bitmapCanvas ->
                Paint().use { red ->
                    red.color = 0xFFFF0000.toInt()
                    bitmapCanvas.drawRect(0f, 0f, 1f, 1f, red)
                }
            }
            val shader = bitmap.makeShader(
                FilterTileMode.CLAMP,
                FilterTileMode.CLAMP,
                SamplingMode.LINEAR,
                Matrix33.IDENTITY,
            )
            shader.use {
                val vertexColorWhite = 0xFFFFFFFF.toInt()

                fun sampleThroughShader(): Int {
                    Surface.makeRasterN32Premul(1, 1).use { target ->
                        Paint().use { paint ->
                            paint.shader = shader
                            target.canvas.drawVertices(
                                VertexMode.TRIANGLES,
                                floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
                                IntArray(4) { vertexColorWhite },
                                floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
                                shortArrayOf(0, 1, 2, 0, 2, 3),
                                BlendMode.MODULATE,
                                paint,
                            )
                        }
                        target.makeImageSnapshot().use { output ->
                            return Bitmap().use { probe ->
                                probe.allocN32Pixels(1, 1, false)
                                output.readPixels(probe)
                                probe.getColor(0, 0)
                            }
                        }
                    }
                }

                assertEquals(0xFFFF0000.toInt(), sampleThroughShader(), "构造时已写入红")

                bitmap.erase(0)
                bitmap.notifyPixelsChanged()
                assertEquals(
                    0xFFFF0000.toInt(),
                    sampleThroughShader(),
                    "擦除+notify 后同 shader 仍采样到构造时的红——skia 语义为构造时拷贝",
                )
            }
        }
    }
}
