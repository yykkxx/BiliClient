package com.RobinNotBad.BiliClient.util

import android.content.Context
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.util.Pools
import androidx.core.view.LayoutInflaterCompat
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AsyncLayoutInflaterX(@NonNull context: Context) {
    private val mRequestPool = Pools.SynchronizedPool<InflateRequest>(10)

    @JvmField
    val mInflater: LayoutInflater = BasicInflater(context)

    @JvmField
    val mHandler: Handler

    @JvmField
    val mDispatcher: Dispather = Dispather()

    private var mHandlerCallback: Handler.Callback? = object : Handler.Callback {
        override fun handleMessage(msg: Message): Boolean {
            val request = msg.obj as InflateRequest
            if (request.view == null) {
                request.view = mInflater.inflate(
                    request.resid, request.parent, false
                )
            }
            request.callback!!.onInflateFinished(
                request.view!!, request.resid, request.parent
            )
            releaseRequest(request)
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
    }

    class Dispather {

        fun enqueue(request: InflateRequest) {
            THREAD_POOL_EXECUTOR.execute(InflateRunnable(request))
        }

        companion object {
            //获得当前CPU的核心数
            private val CPU_COUNT = Runtime.getRuntime().availableProcessors()

            //设置线程池的核心线程数2-4之间,但是取决于CPU核数
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
            try {
                request.view = request.inflater!!.mInflater.inflate(
                    request.resid, request.parent, false
                )
            } catch (ex: Throwable) {
                // Probably a Looper failure, retry on the UI thread
                Log.w(
                    TAG, "Failed to inflate resource in the background! Retrying on the UI"
                            + " thread", ex
                )
            }
            Message.obtain(request.inflater!!.mHandler, 0, request)
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
        return obj
    }

    fun releaseRequest(obj: InflateRequest) {
        obj.callback = null
        obj.inflater = null
        obj.parent = null
        obj.resid = 0
        obj.view = null
        mRequestPool.release(obj)
    }

    fun cancel() {
        mHandler.removeCallbacksAndMessages(null)
        mHandlerCallback = null
    }

    companion object {
        private const val TAG = "AsyncLayoutInflatePlus"
    }
}
