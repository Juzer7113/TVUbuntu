package com.ubuntucontroller

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/**
 * TV 遥控焦点工具：让控件获得焦点时放大形成明显「光标指示」，失去焦点还原。
 */
fun View.applyTvFocus(scale: Float = 1.06f) {
    isFocusable = true
    isFocusableInTouchMode = true
    setOnFocusChangeListener { v, hasFocus ->
        if (hasFocus) {
            v.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(120)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            v.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}

/**
 * 按钮专用焦点：获得焦点时放大 + 文字加粗（配合 ColorStateList 变白），
 * 失去焦点还原原字重，实现「未选中=原样 / 选中=白字加粗+高亮」。
 */
fun TextView.applyTvButtonFocus(scale: Float = 1.06f) {
    isFocusable = true
    isFocusableInTouchMode = true
    val normalStyle = typeface?.style ?: Typeface.NORMAL
    setOnFocusChangeListener { v, hasFocus ->
        if (hasFocus) {
            setTypeface(typeface, Typeface.BOLD)
            v.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(120)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            setTypeface(typeface, normalStyle)
            v.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}

fun isDpadKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

/**
 * 方向键焦点导航兜底：低性能机顶盒上框架默认焦点搜索可能失效，这里手动驱动。
 * 返回 true 表示已处理该方向键。
 */
fun handleTvDpad(root: View, keyCode: Int): Boolean {
    val direction = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
        else -> return false
    }
    val current = root.findFocus() ?: return false
    val next = current.focusSearch(direction) ?: return false
    if (next !== current) {
        next.requestFocus()
        return true
    }
    return false
}
