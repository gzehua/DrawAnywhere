package com.drawanywhere.drawing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DrawingEngineTest {

    @Test
    fun `initial state should have empty strokes and default values`() {
        val engine = DrawingEngine()

        assertTrue(engine.strokes.isEmpty())
        assertEquals(DrawTool.PEN, engine.currentTool)
        assertEquals(0xFFFF0000.toInt(), engine.currentColor)
        assertEquals(7.0f, engine.currentStrokeWidth)
        assertFalse(engine.canUndo)
        assertFalse(engine.canRedo)
    }

    @Test
    fun `add stroke should append to strokes list`() {
        val engine = DrawingEngine()
        val stroke = Stroke(mutableListOf(DrawingPoint(10f, 20f)))

        engine.addStroke(stroke)

        assertEquals(1, engine.strokes.size)
    }

    @Test
    fun `strokes getter returns deep copy not original references`() {
        val engine = DrawingEngine()
        val stroke = Stroke(mutableListOf(DrawingPoint(10f, 20f)))

        engine.addStroke(stroke)

        // strokes getter 返回深拷贝，与原始对象不同引用
        assertNotSame(stroke, engine.strokes[0])
    }

    @Test
    fun `undo should revert strokes to previous state`() {
        val engine = DrawingEngine()
        val stroke = Stroke(mutableListOf(DrawingPoint(10f, 20f)))

        engine.addStroke(stroke)
        assertTrue(engine.canUndo)

        engine.undo()

        assertTrue(engine.strokes.isEmpty())
        assertFalse(engine.canUndo)
        assertTrue(engine.canRedo)
    }

    @Test
    fun `redo should reapply undone stroke`() {
        val engine = DrawingEngine()
        val stroke = Stroke(mutableListOf(DrawingPoint(10f, 20f)))

        engine.addStroke(stroke)
        engine.undo()
        assertTrue(engine.canRedo)

        engine.redo()

        assertEquals(1, engine.strokes.size)
        assertFalse(engine.canRedo)
        assertTrue(engine.canUndo)
    }

    @Test
    fun `setTool should switch current tool`() {
        val engine = DrawingEngine()

        engine.setTool(DrawTool.RECT)

        assertEquals(DrawTool.RECT, engine.currentTool)
    }

    @Test
    fun `setColor should switch current color`() {
        val engine = DrawingEngine()

        engine.setColor(0xFFD60A.toInt())

        assertEquals(0xFFD60A.toInt(), engine.currentColor)
    }

    @Test
    fun `setStrokeWidth should switch stroke width`() {
        val engine = DrawingEngine()

        engine.setStrokeWidth(8.0f)

        assertEquals(8.0f, engine.currentStrokeWidth)
    }

    @Test
    fun `clear should remove all strokes and allow undo`() {
        val engine = DrawingEngine()
        engine.addStroke(Stroke(mutableListOf(DrawingPoint(10f, 20f))))
        engine.addStroke(Stroke(mutableListOf(DrawingPoint(30f, 40f))))
        assertEquals(2, engine.strokes.size)

        engine.clear()

        assertTrue(engine.strokes.isEmpty())
        assertTrue(engine.canUndo)
    }

    @Test
    fun `createStroke should use current tool color and width`() {
        val engine = DrawingEngine()
        engine.setTool(DrawTool.RECT)
        engine.setColor(0xFFD60A.toInt())
        engine.setStrokeWidth(12.0f)

        val stroke = engine.createStroke(listOf(DrawingPoint(10f, 10f), DrawingPoint(100f, 100f)))

        assertEquals(DrawTool.RECT, stroke.tool)
        assertEquals(0xFFD60A.toInt(), stroke.color)
        assertEquals(12.0f, stroke.width)
        assertEquals(2, stroke.points.size)
    }

    @Test
    fun `eraseAt should remove strokes at the touch point`() {
        val engine = DrawingEngine()
        val stroke = Stroke(
            points = mutableListOf(DrawingPoint(100f, 100f)),
            color = 0xFFFF0000.toInt(),
            width = 4.0f,
            tool = DrawTool.PEN
        )
        engine.addStroke(stroke)
        assertEquals(1, engine.strokes.size)

        engine.eraseAt(100f, 100f)

        assertEquals(0, engine.strokes.size)
        assertTrue(engine.canUndo)
    }

    @Test
    fun `eraseAt should not remove strokes far from touch point`() {
        val engine = DrawingEngine()
        engine.addStroke(Stroke(
            points = mutableListOf(DrawingPoint(100f, 100f)),
            color = 0xFFFF0000.toInt(),
            width = 4.0f,
            tool = DrawTool.PEN
        ))

        engine.eraseAt(1000f, 1000f)

        assertEquals(1, engine.strokes.size)
    }

    @Test
    fun `undo after multiple strokes should step back one at a time`() {
        val engine = DrawingEngine()
        val stroke1 = Stroke(mutableListOf(DrawingPoint(10f, 10f)))
        val stroke2 = Stroke(mutableListOf(DrawingPoint(20f, 20f)))
        val stroke3 = Stroke(mutableListOf(DrawingPoint(30f, 30f)))

        engine.addStroke(stroke1)
        engine.addStroke(stroke2)
        engine.addStroke(stroke3)
        assertEquals(3, engine.strokes.size)

        engine.undo()
        assertEquals(2, engine.strokes.size)

        engine.undo()
        assertEquals(1, engine.strokes.size)

        engine.undo()
        assertEquals(0, engine.strokes.size)
        assertFalse(engine.canUndo)
    }

    @Test
    fun `erasePath erases all strokes along path a single undo step`() {
        val engine = DrawingEngine()
        // 两条笔画，起点都在轨迹附近
        engine.addStroke(Stroke(
            points = mutableListOf(DrawingPoint(10f, 10f), DrawingPoint(10f, 100f)),
            color = 0xFFFF0000.toInt(), width = 4.0f, tool = DrawTool.PEN
        ))
        engine.addStroke(Stroke(
            points = mutableListOf(DrawingPoint(30f, 30f), DrawingPoint(30f, 130f)),
            color = 0xFFFF0000.toInt(), width = 4.0f, tool = DrawTool.PEN
        ))

        // 沿轨迹擦除（轨迹内两点同时命中两条笔画）
        engine.erasePath(listOf(
            DrawingPoint(20f, 20f),
            DrawingPoint(20f, 40f),
            DrawingPoint(20f, 60f)
        ), radius = 40f)

        assertEquals(0, engine.strokes.size)

        // 整段轨迹应合并为「一条」撤销记录：一次 undo 即可恢复全部两条笔画
        engine.undo()
        assertEquals(2, engine.strokes.size)
        // 若旧实现（对轨迹每个点各 push 一条 undo）存在，则一条 undo 只能撤销一个点
        // 命中的笔画；此处一次即恢复两条，证明并被合并为一步。剩余可撤销次数
        // = 添加笔画时的 2 次快照（addStroke 各 push 一次），故仍可继续 undo 到空。
        engine.undo()
        assertEquals(1, engine.strokes.size)
        engine.undo()
        assertEquals(0, engine.strokes.size)
        assertFalse(engine.canUndo)
    }

    @Test
    fun `erasePath only removes strokes within radius`() {
        val engine = DrawingEngine()
        engine.addStroke(Stroke(
            points = mutableListOf(DrawingPoint(100f, 100f)), tool = DrawTool.PEN
        ))
        engine.addStroke(Stroke(
            points = mutableListOf(DrawingPoint(300f, 300f)), tool = DrawTool.PEN
        ))

        engine.erasePath(listOf(DrawingPoint(100f, 100f)), radius = 30f)

        assertEquals(1, engine.strokes.size)
    }

    @Test
    fun `undo stack is capped to max depth`() {
        val engine = DrawingEngine()
        // 连续添加超过上限的笔画（每次 add 都会 push 一条 undo 快照）
        repeat(80) {
            engine.addStroke(Stroke(
                points = mutableListOf(DrawingPoint(it.toFloat(), 0f)),
                tool = DrawTool.PEN
            ))
        }

        // 撤销深度被限制，不会无限增长，因此可以一直撤销到初始空状态
        var steps = 0
        while (engine.canUndo) {
            engine.undo()
            steps++
        }
        // 上限 50：只有最近 50 次变更可撤销
        assertEquals(50, steps)
        assertEquals(30, engine.strokes.size)
    }

    @Test
    fun `strokes getter is consistent snapshot during mutation`() {
        // 模拟 ZUI 双窗口并发：读快照与写并发时读到的都是完整一致状态
        val engine = DrawingEngine()
        engine.addStroke(Stroke(
            points = mutableListOf(DrawingPoint(0f, 0f)), tool = DrawTool.PEN
        ))

        // 从一个线程连续 undo/redo，同时从主线程读取 —— 不应抛出 ConcurrentModificationException
        val reader = Thread {
            repeat(1000) {
                val snap = engine.strokes
                // 任何时刻快照都应是合法且一致（可被完整遍历）
                for (s in snap) {
                    @Suppress("UNUSED_EXPRESSION")
                    s.points.size
                }
                engine.canUndo
            }
        }
        val writer = Thread {
            repeat(500) {
                engine.addStroke(Stroke(mutableListOf(DrawingPoint(5f, 5f))))
                engine.undo()
            }
        }
        reader.start()
        writer.start()
        reader.join()
        writer.join()
        // 无异常即通过；终态任意，不必断言具体值
        assertTrue(true)
    }
}
