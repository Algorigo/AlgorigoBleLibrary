package com.algorigo.algorigoblelibrary

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * targetSdk 35 이상이면 Android 15+ 기기에서 edge-to-edge 가 강제되어, 콘텐츠가 시스템 바 뒤까지 그려진다.
 * 이 뷰에 시스템 바, 디스플레이 컷아웃, 키보드 inset 만큼 padding 을 더해서 콘텐츠가 가려지지 않게 한다.
 */
fun View.applySystemBarsPadding() {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        )
        view.updatePadding(
            left = initialLeft + insets.left,
            top = initialTop + insets.top,
            right = initialRight + insets.right,
            bottom = initialBottom + insets.bottom,
        )
        WindowInsetsCompat.CONSUMED
    }
}
