package com.algorigo.algorigoblelibrary

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridTextView(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : View(context, attrs, defStyleAttr) {

    constructor(context: Context?, attrs: AttributeSet?): this(context, attrs, 0)
    constructor(context: Context?): this(context, null)

    private val gridArray = IntArray(64)

    fun setData(intArray: IntArray) {
        intArray.copyInto(gridArray, 0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        val paint = Paint().apply {
            color = Color.RED
            textSize = 40f
            textAlign = Paint.Align.CENTER
            setStyle(Paint.Style.FILL)
            setAntiAlias(true)
        }
        for (index in 0 until gridArray.size) {
            val x = index % 8 + 0.5f
            val y = index / 8 + 0.5f
            canvas?.drawText("${gridArray[index]}", x*width/8, y*height/8, paint)
        }
    }
}