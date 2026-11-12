package com.RobinNotBad.BiliClient.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Message
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.util.Pools
import androidx.core.view.LayoutInflaterCompat
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台预加载布局 + 主线程交付。
 *
 * # 本类在 v5.3.4 之前的三处真实崩溃路径（用户报的「选日子页面偶发闪退」最终落在这里）
 *
 * 用户的直觉是「view/pool 的问题」。核对下来，**`Pools` 本身没有问题**：
 * 反汇编 `androidx.core.util.Pools$SimplePool.release` 确认它内含
 * `isInPool` 检查、重复 release 会抛 `IllegalStateException("Already in the pool!")`，
 * 而当前代码里每个 request 只有 **一次** `Message.obtain` 投递 + **一次** `releaseRequest`，
 * 找不到能触发第二次释放的路径 —— 所以「双释放」是**机制存在但路径不可达**。
 * 真正会闪退的是下面三处（都不是 Pools 的锅，但都表现为「视图/缓存相关」）：
 *
 * 1. **主线程重试没有兜底**：后台 inflate 失败后 `request.view` 仍是 null，
 *    消息回到主线程后 `mInflater.inflate(...)` **又一次**执行、且**没有 try/catch**。
 *    如果它在主线程也失败（后台失败的最常见原因就是「控件/主题属性没法在非主线程解析」，
 *    换到主线程通常能成功，但偶发仍会失败），异常直接从 `Handler.handleMessage` 抛出去
 *    → 主线程崩溃。**这是「偶发」二字的来源**：只有后台那次失败了才会走到这条路上。
 *    → 现在主线程这一次也包 try/catch，失败就安全降级（打日志 + 放回池子 + 不回调）。
 *
 * 2. **回调打到已经销毁的 Activity**：`BaseActivity.asyncInflate` 是「后台 inflate → 主线程
 *    `setContentView(view)`」，中间没有任何「宿主还在不在」的判断。用户在启动页点完
 *    「上学日/休息日」后紧接着是推荐页（它会立刻拉起教程页），这条链上第一次真正用到
 *    异步 inflate；如果这几十毫秒里宿主被 finish/销毁（返回键、系统回收、连点跳过），
 *    回调照样执行 `setContentView` / `setTopbarExit` / `setRound` ——
 *    对一棵正在被销毁的视图树做操作，就是用户描述的「视图/池子出问题」。
 *    → 现在交付前检查宿主 Activity 是否 `isDestroyed || isFinishing`，是就丢弃这次结果。
 *    **这条最可能就是用户直觉指向的东西**：不是 `Pools`，而是「视图结果泄漏到了已销毁的页面」。
 *
 * 3. **`cancel()` 是空操作**：原来只 `mHandler.removeCallbacksAndMessages(null)` 加把字段置 null，
 *    但 `Handler(mHandlerCallback)` 在构造时**已经**把回调对象存进自己的 `mCallback` 字段，
 *    把外围字段置 null 对 Handler 毫无影响；已经在后台跑的那次 inflate 结束后仍会 `sendToTarget`，
 *    于是消息照旧被处理。→ 现在用 [cancelled] 标志做真正的开关（消息进来先判它）。
 *
 * # 另外补的两处防御（机制存在、当前路径未证实可达，但代价极低）
 * - [releaseRequest] 幂等：`InflateRequest.released` 标记 + 重复释放只打日志不重复入池。
 *   即使将来有人加了第二条投递路径，也不会把同一个实例塞进池子两次。
 * - 回调抛异常时吞掉并打日志（[handleMessage] 里）：布局交付失败是「页面不好看」，
 *   让子类回调里的异常把进程打死是「用户闪退」，后者严重得多。
 *   ⚠️ 代价是掩盖子类 bug —— 所以日志用的是 `Log.e` + 明确的 tag，排查时能看到。
 *
 * # 池子本身
 * [mRequestPool] 是 `Pools.SynchronizedPool<InflateRequest>(10)`，只负责复用 request 外壳；
 * 它的 `release` 返回 false 表示池满（对象被丢弃），这是正常情况，不是错误。
 */
class AsyncLayoutInflaterX(@NonNull context: Context) {

    private val mRequestPool = Pools.SynchronizedPool<InflateRequest>(10)

    @JvmField
    val mInflater: LayoutInflater = BasicInflater(context)

    @JvmField
    val mHandler: Handler

    @JvmField
    val mDispatcher: Dispather = Dispather()

    /**
     * 宿主 Activity（拿不到时为 null —— 例如用 Application context 构造）。
     *
     * 只用于一件事：交付前判断「宿主是不是已经没了」（见 [isHostGone]）。
     * 不持有强引用之外的任何东西，也不会因此泄漏：本对象本身就是随页面创建、随页面丢弃的。
     */
    private val hostActivity: Activity? = unwrapActivity(context)

    /** 真正的取消开关（[cancel] 置位）；消息处理与后台任务都会先看它 */
    private val cancelled = AtomicBoolean(false)

    private var mHandlerCallback: Handler.Callback? = object : Handler.Callback {
        override fun handleMessage(msg: Message): Boolean {
            // 用 as? 而不是 as：历史上这里直接强转，msg.obj 意外为空就是一次 ClassCastException
            val request = msg.obj as? InflateRequest ?: return true
            if (cancelled.get()) {
                // 已取消（页面走了）：把 request 收回池子，别让它泄漏
                releaseRequest(request)
                return true
            }
            // 后台 inflate 失败时 view 仍为 null，这里补做一次 —— **必须兜住异常**（见类注释第 1 条）
            if (request.view == null) {
                request.view = try {
                    mInflater.inflate(request.resid, request.parent, false)
                } catch (ex: Throwable) {
                    Log.e(TAG, "inflate failed on UI thread too, degraded (resid=" + request.resid + ")", ex)
                    null
                }
            }
            val view = request.view
            val callback = request.callback
            if (view == null || callback == null) {
                // 两次都失败 / 回调已被清空：**不回调**（宁可停在加载页，也不要把 null 视图交出去
                // 让子类在 setContentView(null) 或 findViewById 上崩），但池子必须收回来。
                Log.w(TAG, "skip delivery: view=" + (view != null) + ", callback=" + (callback != null))
                releaseRequest(request)
                return true
            }
            if (isHostGone()) {
                // 宿主已销毁/正在关闭：这份视图树已经没人要了（见类注释第 2 条）
                Log.i(TAG, "host gone (destroyed/finishing), drop inflate result")
                releaseRequest(request)
                return true
            }
            try {
                callback.onInflateFinished(view, request.resid, request.parent)
            } catch (ex: Throwable) {
                // 子类回调里抛异常不该把进程带走（理由见类注释「另外补的两处防御」）
                Log.e(TAG, "onInflateFinished threw, swallowed to protect the page", ex)
            } finally {
                releaseRequest(request)
            }
            return true
        }
    }

    init {
        mHandler = Handler(mHandlerCallback)
    }

    @UiThread
    fun inflate(
        @LayoutRes resid: Int, @Nullable parent: ViewGroup?,
        @NonNull callback: OnInflateFinishedListener
    ) {
        // 已取消的实例不再接活（页面走了，谁再调都是多余的）
        if (cancelled.get()) {
            Log.w(TAG, "inflate called after cancel(), ignored")
            return
        }
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.ASYNC_INFLATE_ENABLE, true)) {
            val request = obtainRequest()
            request.inflater = this
            request.resid = resid
            request.parent = parent
            request.callback = callback
            mDispatcher.enqueue(request)
        } else {
            val request = InflateRequest()
            request.inflater = this
            request.resid = resid
            request.parent = parent
            request.callback = callback
            Message.obtain(mHandler, 0, request)
                .sendToTarget()
        }
    }

    fun interface OnInflateFinishedListener {
        fun onInflateFinished(
            @NonNull view: View, @LayoutRes resid: Int,
            @Nullable parent: ViewGroup?
        )
    }

    class InflateRequest {
        @JvmField var inflater: AsyncLayoutInflaterX? = null
        @JvmField var parent: ViewGroup? = null
        @JvmField var resid: Int = 0
        @JvmField var view: View? = null
        @JvmField var callback: OnInflateFinishedListener? = null

        /**
         * 是否已经归还给池子。
         *
         * 存在的意义是让 [releaseRequest] 幂等：`Pools.SynchronizedPool` 对同一实例
         * 重复 release 会抛 `IllegalStateException("Already in the pool!")`，
         * 一条异常就能把主线程带走。当前代码没有第二条释放路径，标记只是**结构上的保险**
         * （将来加路径时不会踩雷），代价是一个 boolean。
         */
        @JvmField
        @Volatile
        var released: Boolean = false
    }

    class Dispather {

        fun enqueue(request: InflateRequest) {
            THREAD_POOL_EXECUTOR.execute(InflateRunnable(request))
        }

        companion object {
            //获得当前CPU的核心数
            private val CPU_COUNT = Runtime.getRuntime().availableProcessors()

            //设置线程池的核心线程数2-4之间，但是取决于CPU核数
            private val CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4))

            //设置线程池的最大线程数为 CPU核数 * 2 + 1
            private val MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1

            //设置线程池空闲线程存活时间30s
            private const val KEEP_ALIVE_SECONDS = 30

            private val sThreadFactory: ThreadFactory = object : ThreadFactory {
                private val mCount = AtomicInteger(1)

                override fun newThread(r: Runnable): Thread {
                    return Thread(r, "AsyncLayoutInflatePlus #" + mCount.getAndIncrement())
                }
            }

            //LinkedBlockingQueue 默认构造器，队列容量是Integer.MAX_VALUE
            private val sPoolWorkQueue: BlockingQueue<Runnable> = LinkedBlockingQueue()

            /**
             * An [Executor] that can be used to execute tasks in parallel.
             */
            @JvmField
            val THREAD_POOL_EXECUTOR: ThreadPoolExecutor

            init {
                Log.i(
                    TAG,
                    "static initializer:  CPU_COUNT = $CPU_COUNT CORE_POOL_SIZE = $CORE_POOL_SIZE MAXIMUM_POOL_SIZE = $MAXIMUM_POOL_SIZE"
                )
                val threadPoolExecutor = ThreadPoolExecutor(
                    CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS.toLong(), TimeUnit.SECONDS,
                    sPoolWorkQueue, sThreadFactory
                )
                threadPoolExecutor.allowCoreThreadTimeOut(true)
                THREAD_POOL_EXECUTOR = threadPoolExecutor
            }
        }
    }

    private class BasicInflater(context: Context) : LayoutInflater(context) {

        init {
            if (context is AppCompatActivity) {
                // 手动setFactory2，兼容AppCompatTextView等控件
                val appCompatDelegate = context.delegate
                if (appCompatDelegate is LayoutInflater.Factory2) {
                    LayoutInflaterCompat.setFactory2(this, appCompatDelegate)
                }
            }
        }

        override fun cloneInContext(newContext: Context): LayoutInflater {
            return BasicInflater(newContext)
        }

        @Throws(ClassNotFoundException::class)
        override fun onCreateView(name: String, attrs: AttributeSet): View? {
            for (prefix in sClassPrefixList) {
                try {
                    val view = createView(name, prefix, attrs)
                    if (view != null) {
                        return view
                    }
                } catch (e: ClassNotFoundException) {
                    // In this case we want to let the base class take a crack
                    // at it.
                }
            }

            return super.onCreateView(name, attrs)
        }

        companion object {
            private val sClassPrefixList = arrayOf(
                "android.widget.",
                "android.webkit.",
                "android.app."
            )
        }
    }

    private class InflateRunnable(private val request: InflateRequest) : Runnable {
        private var isRunning = false

        override fun run() {
            isRunning = true
            // 不写 request.inflater!!：request 可能已经被 [cancel] 那条路径收走（原来这里是唯一的 `!!`）
            val inflater = request.inflater
            if (inflater == null) {
                Log.w(TAG, "request.inflater is null (already released), drop this task")
                return
            }
            try {
                request.view = inflater.mInflater.inflate(
                    request.resid, request.parent, false
                )
            } catch (ex: Throwable) {
                // Probably a Looper failure, retry on the UI thread
                Log.w(
                    TAG, "Failed to inflate resource in the background! Retrying on the UI"
                            + " thread", ex
                )
            }
            // 即使已取消也照常投递：由主线程那条路径统一 release（避免跨线程碰池子/池子重复入池）
            Message.obtain(inflater.mHandler, 0, request)
                .sendToTarget()
        }

        fun isRunning(): Boolean {
            return isRunning
        }
    }

    fun obtainRequest(): InflateRequest {
        var obj = mRequestPool.acquire()
        if (obj == null) {
            obj = InflateRequest()
        }
        // 池子里的对象是复用的：用之前把可能残留的字段清干净（理论上 release 已清过，双保险）
        obj.view = null
        obj.callback = null
        obj.parent = null
        obj.resid = 0
        obj.released = false
        return obj
    }

    /**
     * 归还 request（**幂等**）。
     *
     * 先清字段再置 [InflateRequest.released]，最后入池。重复调用只打一条日志就返回 ——
     * 因为 `Pools.SynchronizedPool.release` 对同一实例第二次调用会抛
     * `IllegalStateException("Already in the pool!")`，那是一条能把主线程打死的异常。
     */
    fun releaseRequest(obj: InflateRequest) {
        if (obj.released) {
            Log.w(TAG, "releaseRequest called twice for the same request, ignored")
            return
        }
        obj.callback = null
        obj.inflater = null
        obj.parent = null
        obj.resid = 0
        obj.view = null
        obj.released = true
        mRequestPool.release(obj)
    }

    /**
     * 取消本实例的一切后续工作（页面销毁时调用）。
     *
     * 原来这里只 `removeCallbacksAndMessages(null)` + 把 [mHandlerCallback] 字段置 null，
     * **实际无效**：`Handler(callback)` 在构造时就把回调对象存进了自己的 `mCallback`，
     * 外围字段置 null 对 Handler 没有影响；而且已经在后台线程跑的那次 inflate
     * 结束后仍会 `sendToTarget`，消息照旧被处理 → 回调打到已销毁的页面。
     * 现在改成 [cancelled] 标志：消息进来先判它，命中就直接把 request 收回池子。
     */
    fun cancel() {
        cancelled.set(true)
        mHandlerCallback = null
        mHandler.removeCallbacksAndMessages(null)
    }

    /** 宿主 Activity 是否已经销毁/正在关闭；拿不到 Activity（Application context）时视为「还在」 */
    private fun isHostGone(): Boolean {
        val activity = hostActivity ?: return false
        return activity.isDestroyed || activity.isFinishing
    }

    private companion object {
        private const val TAG = "AsyncLayoutInflatePlus"

        /** 解包 ContextWrapper 的最大层数（正常只有 1~2 层，给个上界防止异常包装链） */
        private const val MAX_UNWRAP_DEPTH = 5

        /**
         * 从 Context 拿宿主 Activity。
         *
         * `BaseActivity.asyncInflate` 传进来的是 Activity 本身，理论上一次强转就够；
         * 但 Context 有可能是包装层（例如被 `ContextThemeWrapper` 包过），
         * 所以沿 `baseContext` 解包有限层 —— 解不出来就当 null（退化成「不判断宿主」，行为与旧版一致）。
         */
        private fun unwrapActivity(context: Context): Activity? {
            var current: Context? = context
            var depth = 0
            while (current != null && depth < MAX_UNWRAP_DEPTH) {
                if (current is Activity) return current
                current = (current as? ContextWrapper)?.baseContext
                depth += 1
            }
            return null
        }
    }
}
