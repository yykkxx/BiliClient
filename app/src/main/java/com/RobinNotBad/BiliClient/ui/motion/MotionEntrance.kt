package com.RobinNotBad.BiliClient.ui.motion

import android.view.ViewTreeObserver

import androidx.recyclerview.widget.RecyclerView

/**
 * 列表「依次浮现」进场动画（v5.3.6）。
 *
 * # 触发点为什么是「首次布局出子项」而不是「数据回来了」
 * 数据回来的时机在各个页面里有几十种写法（回调、协程、EventBus、缓存命中…），
 * 想在那里统一插一脚等于要改几十个页面，而且每漏一处就是「那个页面没有进场动画」。
 * 而**所有**列表最终都会经过同一个 `RecyclerView` 布局，所以在布局完成、
 * 且第一次拿到子项时触发，一次覆盖全 App，且与数据来源完全解耦。
 *
 * # 为什么只动首屏可见的有限项
 * 进场动画本质是「一屏内容的呈现节奏」。长列表（例如 500 条历史记录）如果全量动，
 * 等于一次性拉起几百个 Animator：手表上会明显掉帧、发热，而用户根本看不到屏幕外的部分。
 * 所以只对前 [Motion.ENTRANCE_MAX_ITEMS] 个已存在的子项做；超出的立刻复位成正常态 —
 * 必须复位，否则那些项会带着 `alpha = 0` 留在列表里，滚动上去就是一片空白。
 *
 * # 监听器必须一次性
 * `OnGlobalLayoutListener` 在每次布局（滚动、键盘、刷新）都会触发。
 * 不摘掉就会变成「每次布局都重放进场」，列表一直在抖。
 * 所以这里把它存进 [listeners]（WeakHashMap，不阻止页面被回收），
 * 跑完立刻按同一个引用摘掉。
 */
object MotionEntrance {

    /** RecyclerView → 已挂上的布局监听器（弱引用键，页面销毁后可被回收） */
    private val listeners = java.util.WeakHashMap<RecyclerView, ViewTreeObserver.OnGlobalLayoutListener>()

    /**
     * 已经播过进场的列表（弱引用键）。
     *
     * 为什么不用 `View.setTag`：带 key 的 `setTag(int, Object)` 要求 key 是
     * **应用资源 id**（`getResources().getIdentifier` / `R.id.xxx` 那类高字节非 0 的值），
     * 手写的字符串或 `View.generateViewId()`（高字节为 0）传进去会直接抛
     * IllegalArgumentException。为此专门去改 values 资源加一个 id，收益不抵复杂度。
     */
    private val played = java.util.WeakHashMap<RecyclerView, Boolean>()

    /**
     * 给 RecyclerView 装上「首次呈现」的依次浮现。
     *
     * 幂等：同一个列表重复安装直接返回，不会叠加两套监听器。
     */
    @JvmStatic
    fun install(list: RecyclerView) {
        if (listeners.containsKey(list)) return
        if (played.containsKey(list)) return

        // 监听器里**不能**强引用 list。
        // WeakHashMap 的键是弱引用，但值是强引用 —— 值一旦强引用键，
        // 键就永远不可能被回收，条目也不会被清除，等于泄漏（且是整棵视图树）。
        // 所以回调里只拿 WeakReference，用的时候再判 null。
        val ref = java.lang.ref.WeakReference(list)
        val observer = ViewTreeObserver.OnGlobalLayoutListener {
            val target = ref.get()
            if (target == null) {
                // 视图已被回收：WeakHashMap 会自行清掉该条目（value 不再强引用 key），
                // 这里什么都不用做，直接返回。
                return@OnGlobalLayoutListener
            }
            if (played.containsKey(target)) {
                // 已经跑过：这只可能是延迟到达的布局回调，先摘再走
                detach(target)
                return@OnGlobalLayoutListener
            }
            if (target.childCount <= 0) {
                // 数据还没到。保持订阅，等下一次布局再试 —— 这正是「慢接口页面」需要的。
                return@OnGlobalLayoutListener
            }
            played[target] = true
            play(target)
            detach(target)
        }
        listeners[list] = observer
        list.viewTreeObserver.addOnGlobalLayoutListener(observer)
    }

    /** 摘掉监听器（跑完 / 已跑过 / 视图失效时调用，自身幂等） */
    private fun detach(list: RecyclerView) {
        val observer = listeners.remove(list) ?: return
        try {
            list.viewTreeObserver.removeOnGlobalLayoutListener(observer)
        } catch (ignored: Exception) {
            // viewTreeObserver 在视图已 detach 时会失效；此时监听器本来也不会再触发
        }
    }

    /**
     * 跑一次进场。开关关闭时什么都不改（**不做**「设成 1f 再不动」的多余操作，
     * 保持与旧版逐像素一致）。
     */
    private fun play(list: RecyclerView) {
        if (!Motion.listEnabled()) return

        val total = list.childCount
        if (total <= 0) return
        val animated = total.coerceAtMost(Motion.ENTRANCE_MAX_ITEMS)
        val offset = Motion.dp(Motion.ENTRANCE_TRANSLATE_DP).toFloat()
        val duration = Motion.duration(Motion.DURATION_ENTRANCE)

        for (i in 0 until animated) {
            val child = list.getChildAt(i) ?: continue
            // 先摆成初始态再由动画拉回，避免「先闪一下再动」
            child.alpha = 0f
            child.translationY = offset
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(i * Motion.ENTRANCE_STAGGER)
                .setDuration(duration)
                .setInterpolator(Motion.easeOut())
                .setListener(null)
                .start()
        }
        // 超出上限的项必须显式复位：它们没被动画接管，若沿用上一次的初始态会是空的
        for (i in animated until total) {
            val child = list.getChildAt(i) ?: continue
            child.alpha = 1f
            child.translationY = 0f
        }
    }
}
