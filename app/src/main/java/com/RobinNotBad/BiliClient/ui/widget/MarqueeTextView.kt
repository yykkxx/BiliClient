package com.RobinNotBad.BiliClient.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.widget.TextView
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

@SuppressLint("AppCompatCustomView")
class MarqueeTextView : TextView {
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        setMarquee()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        setMarquee()
    }

    constructor(context: Context) : super(context) {
        setMarquee()
    }

    fun setMarquee() {
        if (!isInEditMode) {
            if (SharedPreferencesUtil.getBoolean("marquee_enable", true)) {
                isSelected = true
                ellipsize = TextUtils.TruncateAt.MARQUEE
                setSingleLine()
                marqueeRepeatLimit = -1
                isFocusable = true
                isFocusableInTouchMode = true
            } else {
                ellipsize = TextUtils.TruncateAt.END
                setSingleLine()
            }
        }
    }
}
