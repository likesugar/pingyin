package com.pinyin.typing

import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.SeekBar
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.pinyin.typing.databinding.ActivityMainBinding
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var current: Fragment? = null
    private var navBarHeight = 0
    private var keyboardHeight = 0
    private var topInset = 0
    private var bottomBarVisible = true
    private lateinit var insetsController: WindowInsetsControllerCompat
    private var holdSystemBars = false
    private val visibleRect = Rect()
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    /** 当前所在页面对应的底部导航项，代码侧自行跟踪，避免依赖框架内部选中态 */
    private var activeItemId = R.id.nav_train
    /** 页面切换后新页的入场方向：0 无动画，1 从右进，-1 从左进 */
    private var pendingSlide = 0
    /** 本次手势是否被控件占用（输入框、滑块、开关上不翻页） */
    private var swipeSuppressed = false

    private val navItems = intArrayOf(
        R.id.nav_train, R.id.nav_random, R.id.nav_jiujian, R.id.nav_settings
    )

    /** 首页左右滑动切页：只在横向位移明显时触发，避免与纵向列表滚动打架 */
    private val swipeDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < dp(72) || abs(dx) < abs(dy) * 1.5f || abs(vx) < dp(200)) return false
                val step = if (dx < 0) 1 else -1
                val next = navItems.getOrNull(navIndex(activeItemId) + step) ?: return false
                selectNav(next, slide = step)
                return false
            }
        })
    }

    private val trainFragment: TrainFragment
        get() = supportFragmentManager.findFragmentByTag(TAG_TRAIN) as? TrainFragment
            ?: TrainFragment()
    private val randomFragment: RandomFragment
        get() = supportFragmentManager.findFragmentByTag(TAG_RANDOM) as? RandomFragment
            ?: RandomFragment()
    private val jiujianFragment: JiuJianFragment
        get() = supportFragmentManager.findFragmentByTag(TAG_JIUJIAN) as? JiuJianFragment
            ?: JiuJianFragment()
    private val settingsFragment: SettingsFragment
        get() = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment
            ?: SettingsFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupInsets()
        setupNav()
        setupBackNavigation()
        watchKeyboard()

        Executors.newSingleThreadExecutor().execute {
            runCatching { PinyinDict.load(this) }
            runCatching { Corpus.get(this) }
            runCatching { RandomPool.load(this) }
            runOnUiThread {
                (supportFragmentManager.findFragmentByTag(TAG_TRAIN) as? TrainFragment)?.onDataReady()
            }
        }

        if (savedInstanceState != null) {
            val restored = listOf(trainFragment, randomFragment, jiujianFragment, settingsFragment)
                .firstOrNull { it.isAdded && !it.isHidden }
            if (restored != null) {
                current = restored
                val itemId = when (restored) {
                    is RandomFragment -> R.id.nav_random
                    is JiuJianFragment -> R.id.nav_jiujian
                    is SettingsFragment -> R.id.nav_settings
                    else -> R.id.nav_train
                }
                selectNav(itemId)
            }
        }
        if (current == null) switchFragment(R.id.nav_train)
    }

    /** 窗口级配置：必须在 setContentView 之前改，挖孔模式才能在建布局阶段生效 */
    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // 挖孔屏：允许内容绘制到刘海/挖孔区域，否则系统会在顶部留出一条未填充的空白带
        window.attributes = window.attributes.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }

    /** 隐藏系统栏并处理「状态栏 / 挖孔 / 导航栏」内边距 */
    private fun setupInsets() {
        insetsController = WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            hide(WindowInsetsCompat.Type.systemBars())
        }

        // 顶部内边距取「挖孔安全区」，系统栏隐藏后仍留一点呼吸空间，内容不会被切边
        ViewCompat.setOnApplyWindowInsetsListener(binding.container) { _, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            topInset = max(max(cut.top, dp(12)), 0)
            updateContentPadding()
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            navBarHeight = max(bars.bottom, cut.bottom)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, max(navBarHeight, dp(6)))
            updateContentPadding()
            insets
        }
    }

    private fun setupNav() {
        // 监听器只负责切换页面，绝不再回写 selectedItemId，避免递归回调
        binding.bottomNav.setOnItemSelectedListener { item ->
            activeItemId = item.itemId
            switchFragment(item.itemId, pendingSlide)
            pendingSlide = 0
            true
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 训练中不翻页（避免误触退出练习）；落在输入框 / 滑块 / 开关上也不翻页
                val practicing = (current as? TrainFragment)?.isPracticing() == true
                swipeSuppressed = practicing || hitInteractive(ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> swipeSuppressed = true
        }
        if (!swipeSuppressed) swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** 触点是否落在需要自己处理横向手势的控件上 */
    private fun hitInteractive(rawX: Float, rawY: Float): Boolean {
        val root = window.decorView as? ViewGroup ?: return false
        val rect = Rect()
        return walkHit(root, rawX, rawY, rect)
    }

    private fun walkHit(v: View, rawX: Float, rawY: Float, rect: Rect): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                if (walkHit(v.getChildAt(i), rawX, rawY, rect)) return true
            }
        }
        if (v is EditText || v is SeekBar || v is CompoundButton) {
            if (v.getGlobalVisibleRect(rect) && rect.contains(rawX.toInt(), rawY.toInt())) return true
        }
        return false
    }

    private fun navIndex(@IdRes itemId: Int): Int = navItems.indexOf(itemId).coerceAtLeast(0)

    /** 返回键统一回首页：训练中 -> 退出训练；其它页 -> 回到「训练」页；已在首页 -> 交给系统 */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            val train = supportFragmentManager.findFragmentByTag(TAG_TRAIN) as? TrainFragment
            if (train?.goHome() == true) return@addCallback
            if (activeItemId != R.id.nav_train) {
                selectNav(R.id.nav_train)
                return@addCallback
            }
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    /** 仅执行 Fragment 切换（可安全地被选中项回调调用） */
    private fun switchFragment(@IdRes itemId: Int, slide: Int = 0) {
        val (target, tag) = when (itemId) {
            R.id.nav_random -> randomFragment to TAG_RANDOM
            R.id.nav_jiujian -> jiujianFragment to TAG_JIUJIAN
            R.id.nav_settings -> settingsFragment to TAG_SETTINGS
            else -> trainFragment to TAG_TRAIN
        }
        if (current === target) {
            if (target is RandomFragment) target.refresh()
            return
        }
        supportFragmentManager.beginTransaction().apply {
            current?.let { hide(it) }
            if (target.isAdded) show(target) else add(R.id.container, target, tag)
        }.commitAllowingStateLoss()
        current = target
        if (target is RandomFragment) target.refresh()
        if (slide != 0) animateSlideIn(target, slide)
    }

    /** 左右滑动切页时给新页面一个短距离滑入，手势方向与动画方向一致 */
    private fun animateSlideIn(target: Fragment, slide: Int) {
        // 事务提交后才创建 view，延一帧再拿
        binding.container.postDelayed({
            val v = target.view ?: return@postDelayed
            if (!v.isShown) return@postDelayed
            v.animate().cancel()
            v.translationX = slide * dp(56).toFloat()
            v.alpha = 0.55f
            v.animate().translationX(0f).alpha(1f).setDuration(170).start()
        }, 32)
    }

    /** 代码侧切换底部导航（只触发一次页面切换，不会递归） */
    private fun selectNav(@IdRes itemId: Int, slide: Int = 0) {
        if (activeItemId == itemId && current != null) {
            pendingSlide = 0
            return
        }
        if (slide != 0) pendingSlide = slide
        activeItemId = itemId
        // 设选中项 -> 触发监听器 -> switchFragment；再显式同步一次勾选态，
        // 避免个别版本监听器消费事件后底部导航没有高亮
        binding.bottomNav.selectedItemId = itemId
        binding.bottomNav.menu.findItem(itemId)?.isChecked = true
    }

    /**
     * 通过可见区域变化推算键盘高度：
     * 系统已 resize 窗口时差值自然为 0；全屏未 resize 时差值即键盘高度，
     * 两种情况都能得出正确的底部避让距离。
     */
    private fun watchKeyboard() {
        val decor = window.decorView
        layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            decor.getWindowVisibleDisplayFrame(visibleRect)
            val h = decor.height
            if (h <= 0) return@OnGlobalLayoutListener
            val diff = h - visibleRect.bottom
            keyboardHeight = if (diff > h * 0.15) diff else 0
            updateContentPadding()
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    private fun updateContentPadding() {
        val bottom = max(keyboardHeight, if (bottomBarVisible) 0 else navBarHeight)
        binding.container.setPadding(0, topInset, 0, bottom)
    }

    fun setBottomBarVisible(visible: Boolean) {
        bottomBarVisible = visible
        // 训练中（可能正在打字）不再反复隐藏系统栏，避免把输入法一起收掉
        holdSystemBars = !visible
        binding.bottomNav.visibility = if (visible) View.VISIBLE else View.GONE
        updateContentPadding()
    }

    fun startLesson(lesson: Lesson) {
        // 程序化切回训练页：设置选中项 -> 触发监听器 -> switchFragment
        selectNav(R.id.nav_train)
        if (current !is TrainFragment) switchFragment(R.id.nav_train)
        // 确保训练页视图已创建（首次从其它页跳入时事务可能尚未执行）
        runCatching { supportFragmentManager.executePendingTransactions() }
        trainFragment.startLesson(lesson)
    }

    fun startCustomLesson(text: String) {
        val clean = text.replace("\n", " ").trim()
        if (clean.isBlank()) return
        startLesson(Lesson("自定义文本", listOf(Segment(clean, PinyinDict.annotate(clean)))))
    }

    /** 九键教学页的「去九键练习」：切到九键 + 简易输入，并生成一段随机字 */
    fun startJiuJianPractice() {
        Prefs.codeMode = Prefs.CODE_NINE
        Prefs.simpleInput = true
        if (runCatching { RandomPool.size() }.getOrDefault(0) == 0) {
            runCatching { RandomPool.load(this) }
        }
        val text = runCatching { RandomPool.randomText(20) }.getOrDefault("")
        if (text.isNotBlank()) {
            startLesson(Lesson("九键练习", listOf(Segment(text, RandomPool.syllablesOf(text)))))
        } else {
            selectNav(R.id.nav_train)
        }
    }

    private fun hideSystemBars() {
        if (holdSystemBars || keyboardHeight > 0) return
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** 需要输入时先停止隐藏系统栏，防止与输入法抢焦点 */
    fun requestInputFocus() {
        holdSystemBars = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        layoutListener?.let { window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        layoutListener = null
        // 真正退出时释放语音引擎；配置变更（如切换主题）只停止朗读
        if (isFinishing) Speech.release() else Speech.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG_TRAIN = "train"
        private const val TAG_RANDOM = "random"
        private const val TAG_JIUJIAN = "jiujian"
        private const val TAG_SETTINGS = "settings"
    }
}
