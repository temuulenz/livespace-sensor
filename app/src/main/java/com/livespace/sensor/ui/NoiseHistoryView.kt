package com.livespace.sensor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Sixty completed one-second dBFS windows only; never holds PCM audio. */
class NoiseHistoryView(context: Context,attrs: AttributeSet?) : View(context,attrs) {
    private val levels = FloatArray(60)
    private var next = 0
    private var count = 0
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(85,219,193); strokeWidth = 2*resources.displayMetrics.density }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.LTGRAY; textSize=12*resources.displayMetrics.scaledDensity }
    fun add(dbfs: Double) { levels[next]=dbfs.toFloat(); next=(next+1)%levels.size; count=minOf(count+1,levels.size); invalidate() }
    fun clear() { next=0; count=0; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText("Noise history • dBFS • last $count s",8f,label.textSize,label)
        val top=label.textSize+12; val bottom=height-label.textSize-4
        canvas.drawText("−120",8f,height-4f,label); canvas.drawText("0 dBFS",width-70*resources.displayMetrics.density,height-4f,label)
        if (count < 2 || bottom <= top) return
        val first=(next-count+levels.size)%levels.size
        fun x(i: Int)=8f+i*(width-16f)/59
        fun y(i: Int)=bottom-(levels[(first+i)%levels.size]+120).coerceIn(0f,120f)/120*(bottom-top)
        for (i in 1 until count) canvas.drawLine(x(i-1),y(i-1),x(i),y(i),line)
    }
}
