package com.livespace.sensor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.livespace.sensor.detection.*

class DetectionOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var people: List<Person> = emptyList()
    var frame = Box(0f, 0f, 0f, 0f)
    var area = Box(0f, 0f, 1f, 1f)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(85,219,193); style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * resources.displayMetrics.scaledDensity; setShadowLayer(3f,0f,0f,Color.BLACK) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = frame.right - frame.left; val h = frame.bottom - frame.top
        if (w <= 0 || h <= 0) return
        outline.alpha = 90
        canvas.drawRect(frame.left + area.left*w, frame.top + area.top*h, frame.left + area.right*w, frame.top + area.bottom*h, outline)
        outline.alpha = 255
        for (person in people) {
            val b = person.box
            val l = frame.left + b.left*w; val t = frame.top + b.top*h
            canvas.drawRect(l,t,frame.left+b.right*w,frame.top+b.bottom*h,outline)
            canvas.drawText("Person ${"%.0f".format(person.confidence*100)}%",l,(t-6).coerceAtLeast(frame.top+text.textSize),text)
        }
    }
}
