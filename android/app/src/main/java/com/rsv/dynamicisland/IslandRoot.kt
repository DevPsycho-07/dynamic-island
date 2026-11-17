package com.rsv.dynamicisland

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import kotlin.math.max

/**
 * The overlay window's content: the island (drawn) plus a real EditText that only appears
 * while you type an inline reply.
 */
@SuppressLint("ViewConstructor")
class IslandRoot(ctx: Context, host: IslandView.Host) : FrameLayout(ctx), IslandView.ReplyField {
    val island = IslandView(ctx, host, this)
    private val imm: InputMethodManager? = ctx.getSystemService(InputMethodManager::class.java)
    private var wantIme = false
    private val d = resources.displayMetrics.density

    private val input = EditText(ctx).apply {
        background = null
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF8A8A8A.toInt())
        hint = "Reply…"
        textSize = 15f
        isSingleLine = true
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
        setPadding((14 * d).toInt(), 0, (10 * d).toInt(), 0)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = EditorInfo.IME_ACTION_SEND
        setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) {
                island.sendReply()
                true
            } else false
        }
        visibility = GONE
    }

    init {
        addView(island, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(input, LayoutParams(1, 1))
    }

    override fun showField(centerDx: Int, top: Int, w: Int, h: Int) {
        val lp = input.layoutParams as LayoutParams
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.topMargin = top
        lp.leftMargin = max(0, centerDx)
        lp.rightMargin = max(0, -centerDx)
        input.layoutParams = lp
        input.setText("")
        input.visibility = VISIBLE
        wantIme = true
        post { focusField() }
    }

    private fun focusField() {
        if (!wantIme) return
        input.requestFocus()
        try { imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) } catch (_: Exception) { }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && wantIme) postDelayed({ focusField() }, 60)
    }

    override fun hideField() {
        wantIme = false
        try { imm?.hideSoftInputFromWindow(input.windowToken, 0) } catch (_: Exception) { }
        input.clearFocus()
        input.visibility = GONE
    }

    override fun fieldText(): String = input.text?.toString().orEmpty()

    override fun fieldHasWindowFocus(): Boolean = hasWindowFocus()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) island.cancelReply()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            island.onOutsideTouch()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
