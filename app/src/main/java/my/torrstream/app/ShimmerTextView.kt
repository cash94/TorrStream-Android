package my.torrstream.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.ResourcesCompat

/**
 * Надпись экрана загрузки в стиле значка приложения: градиент от розового к бирюзовому,
 * тёмная «подложка» со сдвигом вниз (глубина), неоновое свечение и блик, который
 * пробегает по буквам. Анимация идёт, только пока надпись видна.
 */
class ShimmerTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val depthPx = 3f * density
    private val glowRadius = 14f * density

    private var face: Shader? = null
    private var depth: Shader? = null
    private var shine: LinearGradient? = null
    private val shineMatrix = Matrix()
    private var shineX = -1f

    private val animator = ValueAnimator.ofFloat(-0.5f, 1.5f).apply {
        duration = 2400
        startDelay = 300
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            shineX = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        ResourcesCompat.getFont(context, R.font.montserrat_bold)?.let { typeface = it }
        // Тень объявляем самому TextView, а не только кисти: по её радиусу он расширяет
        // область отрисовки, иначе свечение обрезалось бы прямоугольником вокруг текста
        setShadowLayer(glowRadius, 0f, 0f, GLOW_COLOR)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val width = w.toFloat().coerceAtLeast(1f)
        face = LinearGradient(0f, 0f, width, 0f, FACE_COLORS, null, Shader.TileMode.CLAMP)
        depth = LinearGradient(0f, 0f, width, 0f, DEPTH_COLOR, DEPTH_COLOR, Shader.TileMode.CLAMP)
        shine = LinearGradient(
            0f, 0f, width * 0.3f, 0f, SHINE_COLORS, null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val p = paint
        // 1. Глубина: та же надпись тёмным цветом чуть ниже — буквы «стоят» над фоном
        p.shader = depth
        p.clearShadowLayer()
        canvas.save()
        canvas.translate(0f, depthPx)
        super.onDraw(canvas)
        canvas.restore()
        // 2. Лицевая сторона с неоновым свечением
        p.shader = face
        p.setShadowLayer(glowRadius, 0f, 0f, GLOW_COLOR)
        super.onDraw(canvas)
        p.clearShadowLayer()
        // 3. Блик, пробегающий слева направо
        shine?.let {
            shineMatrix.setTranslate(shineX * width, 0f)
            it.setLocalMatrix(shineMatrix)
            p.shader = it
            super.onDraw(canvas)
        }
        p.shader = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }

    private fun updateAnimation() {
        if (isAttachedToWindow && isShown) {
            if (!animator.isStarted) animator.start()
        } else if (animator.isStarted) {
            animator.cancel()
        }
    }

    private companion object {
        val FACE_COLORS = intArrayOf(0xFFF654EC.toInt(), 0xFFB27CF4.toInt(), 0xFF3CE8F6.toInt())
        val SHINE_COLORS = intArrayOf(0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF)
        const val DEPTH_COLOR = 0xFF2A0A55.toInt()
        const val GLOW_COLOR = 0x88B040FF.toInt()
    }
}
