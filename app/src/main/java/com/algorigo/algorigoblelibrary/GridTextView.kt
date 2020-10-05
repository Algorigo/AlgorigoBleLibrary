package com.algorigo.algorigoblelibrary

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridTextView(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : View(context, attrs, defStyleAttr) {

    private enum class Rotate {
        NORMAL,
        CW_90,
        CW_180,
        CW_270
    }

    constructor(context: Context?, attrs: AttributeSet?): this(context, attrs, 0)
    constructor(context: Context?): this(context, null)

    private var gridWidth = 8
    private var gridHeight = 8
    private var gridReverse = false
    private var gridRotate = Rotate.NORMAL
    private val gridArray: IntArray
    private val paint = Paint().apply {
        color = Color.RED
        textSize = 40f
        textAlign = Paint.Align.CENTER
        setStyle(Paint.Style.FILL)
        setAntiAlias(true)
    }

    init {
        context?.theme?.obtainStyledAttributes(
            attrs,
            R.styleable.GridTextView,
            0, 0)?.apply {

            try {
                gridWidth = getInteger(R.styleable.GridTextView_grid_width, 8)
                gridHeight = getInteger(R.styleable.GridTextView_grid_height, 8)
                gridReverse = getBoolean(R.styleable.GridTextView_grid_reverse, false)
                gridRotate = Rotate.values()[getInteger(R.styleable.GridTextView_grid_rotate, Rotate.values().indexOf(Rotate.NORMAL))]
            } finally {
                recycle()
            }
        }
        gridArray = IntArray(gridWidth * gridHeight)
    }

    fun setData(intArray: IntArray) {
        intArray.copyInto(gridArray, 0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        for (index in 0 until gridArray.size) {
            var x = 0
            var y = 0
            when (gridRotate) {
                Rotate.NORMAL -> {
                    x = (index % gridWidth).let { if (gridReverse) gridWidth - 1 - it else it }
                    y = index / gridHeight
                }
                Rotate.CW_90 -> {
                    x = gridHeight - 1 - index / gridHeight
                    y = (index % gridWidth).let { if (gridReverse) gridWidth - 1 - it else it }
                }
                Rotate.CW_180 -> {
                    x = (index % gridWidth).let { if (gridReverse) it else gridWidth - 1 - it }
                    y = gridHeight - 1 - index / gridHeight
                }
                Rotate.CW_270 -> {
                    x = index / gridHeight
                    y = (index % gridWidth).let { if (gridReverse) it else gridWidth - 1 - it }
                }
            }
            canvas?.drawText("${gridArray[index]}", (x + 0.5f)*width/gridWidth, (y + 0.5f)*height/gridHeight, paint)
        }
    }
}