package com.algorigo.algorigoblelibrary

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.descendants
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onIdle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * targetSdk 35 에서 edge-to-edge 가 강제되어 샘플 앱의 주요 UI 가 상태바, 액션바, 내비게이션 바에 가려지던 문제 테스트.
 */
@RunWith(AndroidJUnit4::class)
class EdgeToEdgeLayoutTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun startActivity_contentIsNotCoveredByBars() {
        assertContentNotCovered(Intent(context, StartActivity::class.java))
    }

    @Test
    fun mainActivity_contentIsNotCoveredByBars() {
        assertContentNotCovered(Intent(context, MainActivity::class.java))
    }

    @Test
    fun deviceActivity_contentIsNotCoveredByBars() {
        assertContentNotCovered(
            Intent(context, DeviceActivity::class.java)
                .putExtra(DeviceActivity.DEVICE_MAC_ADDRESS, "00:11:22:33:44:55")
        )
    }

    private fun assertContentNotCovered(intent: Intent) {
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            onIdle()
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val bars = WindowInsetsCompat.toWindowInsetsCompat(decor.rootWindowInsets, decor)
                    .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
                val obstacles = listOf(
                    "status bar" to Rect(0, 0, decor.width, bars.top),
                    "navigation bar/keyboard" to Rect(0, decor.height - bars.bottom, decor.width, decor.height),
                    "left bar" to Rect(0, 0, bars.left, decor.height),
                    "right bar" to Rect(decor.width - bars.right, 0, decor.width, decor.height),
                    "action bar" to activity.findViewById<View>(androidx.appcompat.R.id.action_bar_container).windowRect(),
                ).filter { !it.second.isEmpty }

                // 레이아웃 루트(setContentView 로 넣은 뷰) 아래에서 화면에 보이는 뷰가 막대 영역과 겹치면 안 된다
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                val covered = root.descendants
                    .filter { it.isShown && it.width > 0 && it.height > 0 }
                    .flatMap { view ->
                        obstacles
                            .filter { (_, rect) -> Rect.intersects(rect, view.windowRect()) }
                            .map { (name, _) -> "${view.idName()} ${view.windowRect().toShortString()} is covered by $name" }
                    }
                    .toList()
                assertTrue("${activity.javaClass.simpleName}: $covered", covered.isEmpty())
            }
        }
    }

    private fun View.windowRect(): Rect {
        val location = IntArray(2).also { getLocationInWindow(it) }
        return Rect(location[0], location[1], location[0] + width, location[1] + height)
    }

    private fun View.idName(): String =
        if (id == View.NO_ID) javaClass.simpleName else resources.getResourceEntryName(id)
}
