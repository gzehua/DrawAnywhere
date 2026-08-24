package com.drawanywhere.drawing

/**
 * 绘图核心引擎，管理笔画、撤销/重做与擦除。
 *
 * ## 线程安全（关键）
 *
 * 注意：这个类在 ZUI 等厂商 ROM 上可能被多个「窗口 UI 线程」并发访问：
 * ToolPaletteView 与 DrawingCanvasView 分属两个 TYPE_APPLICATION_OVERLAY 窗口，
 * 各自拥有独立的 ViewRootImpl。在联想 ZUI ROM 中，这两个窗口的回调/绘制
 * 线程可能不是同一条线程 —— 工具栏线程调用 undo()/erase() 修改 _strokes，
 * 而画布线程在 onDraw() 里通过 strokes getter/erase 迭代读取 _strokes。
 * 若不加锁，undo 时先 clear() 再 addAll() 的中间态会导致读取线程
 * 抛出 ConcurrentModificationException / IndexOutOfBoundsException 而崩溃。
 *
 * 因此本类内部所有可变状态（_strokes、undoStack、redoStack）的统一契约：
 * 一切写入与「作为整份快照读取」都在同一个 [stateLock] 内完成，保证任意
 * 副本线程读到的永远是一致的完整状态。
 */
class DrawingEngine {

    /** 统一状态锁：保护 _strokes / undoStack / redoStack 的一致性 */
    private val stateLock = Any()

    /** 撤销/重做栈上限，防止长时间会话造成无界内存增长（OOM） */
    private val MAX_UNDO_DEPTH = 50

    private val _strokes = mutableListOf<Stroke>()

    /**
     * 返回当前全部笔画的深拷贝快照。
     *
     * 在锁内整体拷贝一次，因此读取方永远拿到一致的状态，即使另一线程正在 undo/redo。
     */
    val strokes: List<Stroke> get() = synchronized(stateLock) {
        deepCopyStrokes(_strokes)
    }

    var currentTool: DrawTool = DrawTool.PEN
        private set

    var currentColor: Int = 0xFFFF0000.toInt()
        private set

    var currentStrokeWidth: Float = 7.0f
        private set

    private val undoStack = mutableListOf<List<Stroke>>()
    private val redoStack = mutableListOf<List<Stroke>>()

    val canUndo: Boolean get() = synchronized(stateLock) { undoStack.isNotEmpty() }
    val canRedo: Boolean get() = synchronized(stateLock) { redoStack.isNotEmpty() }

    fun addStroke(stroke: Stroke) {
        synchronized(stateLock) {
            // 深拷贝当前状态入 undo 栈
            pushUndoLocked()
            _strokes.add(stroke.deepCopy())
            redoStack.clear()
        }
    }

    fun undo() {
        synchronized(stateLock) {
            if (undoStack.isEmpty()) return
            // 深拷贝当前状态入 redo 栈（同样限深度）
            redoStack.add(deepCopyStrokes(_strokes))
            while (redoStack.size > MAX_UNDO_DEPTH) {
                redoStack.removeAt(0)
            }
            _strokes.clear()
            _strokes.addAll(undoStack.removeLast())
        }
    }

    fun redo() {
        synchronized(stateLock) {
            if (redoStack.isEmpty()) return
            pushUndoLocked()
            _strokes.clear()
            _strokes.addAll(redoStack.removeLast())
        }
    }

    fun setTool(tool: DrawTool) {
        currentTool = tool
    }

    fun setColor(color: Int) {
        currentColor = color
    }

    fun setStrokeWidth(width: Float) {
        currentStrokeWidth = width
    }

    fun clear() {
        synchronized(stateLock) {
            if (_strokes.isEmpty()) return
            pushUndoLocked()
            _strokes.clear()
            redoStack.clear()
        }
    }

    fun createStroke(points: List<DrawingPoint>): Stroke {
        return Stroke(
            points = points.map { it.copy() }.toMutableList(),
            color = currentColor,
            width = currentStrokeWidth,
            tool = currentTool
        )
    }

    /**
     * 以 (x, y) 为圆心、radius 为半径，擦除所有与该点相交的笔画。
     *
     * 单次调用只产生「一条」撤销记录；多次调用则产生多条记录。
     * 供单点擦除使用。大面积擦除请用 [erasePath]，它把整段轨迹合并成一条撤销记录。
     */
    fun eraseAt(x: Float, y: Float, radius: Float = 30f) {
        synchronized(stateLock) {
            val indicesToRemove = collectErasableIndicesLocked(listOf(DrawingPoint(x, y)), radius)
            if (indicesToRemove.isNotEmpty()) {
                pushUndoLocked()
                removeStrokesLocked(indicesToRemove)
                redoStack.clear()
            }
        }
    }

    /**
     * 沿 [points] 轨迹，用 [radius] 擦除所有与之相交的笔画，并把整段轨迹合并为
     * **单条**撤销记录。
     *
     * 修复：旧的实现是在 UI 层对轨迹的每个点调用一次 [eraseAt]，导致一次擦除手势
     * 往 undo 栈塞入成百上千条全量快照（性能 + 撤销层数爆炸）。这里改为在锁内
     * 一次性收集整条轨迹命中的所有笔画并作为一步记录。
     */
    fun erasePath(points: List<DrawingPoint>, radius: Float = 30f) {
        synchronized(stateLock) {
            val collapsed = points.distinct()
            if (collapsed.isEmpty()) return
            val indicesToRemove = collectErasableIndicesLocked(collapsed, radius)
            if (indicesToRemove.isNotEmpty()) {
                pushUndoLocked()
                removeStrokesLocked(indicesToRemove)
                redoStack.clear()
            }
        }
    }

    // ===== 深拷贝工具 =====

    /** 深拷贝一个 Stroke（含 points 列表） */
    fun Stroke.deepCopy(): Stroke {
        return Stroke(
            points = this.points.map { it.copy() }.toMutableList(),
            color = this.color,
            width = this.width,
            tool = this.tool
        )
    }

    /** 深拷贝整个 strokes 列表（每个 Stroke 及其 points 都独立） */
    private fun deepCopyStrokes(source: List<Stroke>): List<Stroke> {
        return source.map { it.deepCopy() }
    }

    /** 在锁内收集所有被 [points] 任一命中（与任一点距离 < radius）的笔画下标 */
    private fun collectErasableIndicesLocked(points: List<DrawingPoint>, radius: Float): List<Int> {
        val radiusSq = radius * radius
        val indicesToRemove = mutableListOf<Int>()
        // 先收集要删除的索引，防止并发修改
        for (i in _strokes.indices) {
            val stroke = _strokes[i]
            if (stroke.points.any { pt ->
                    points.any { q ->
                        val dx = pt.x - q.x
                        val dy = pt.y - q.y
                        dx * dx + dy * dy < radiusSq
                    }
                }
            ) {
                indicesToRemove.add(i)
            }
        }
        return indicesToRemove
    }

    /**
     * 记录当前 _strokes 快照到 undo 栈（锁内调用）。undo 栈有深度上限，
     * 超上限时丢弃最旧的快照，避免无限内存增长。
     */
    private fun pushUndoLocked() {
        undoStack.add(deepCopyStrokes(_strokes))
        while (undoStack.size > MAX_UNDO_DEPTH) {
            undoStack.removeAt(0)
        }
    }

    /** 按下标（升序收集）以倒序删除笔画（锁内调用，下标随删除前先排序） */
    private fun removeStrokesLocked(indices: List<Int>) {
        for (i in indices.sortedDescending()) {
            _strokes.removeAt(i)
        }
    }
}