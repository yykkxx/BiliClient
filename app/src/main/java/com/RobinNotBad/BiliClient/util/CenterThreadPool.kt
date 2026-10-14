package com.RobinNotBad.BiliClient.util

import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.util.Consumer
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * @author silent碎月
 * 核心运行线程池（Kotlin 重构版，原 CenterThreadPool.java）
 * BuildersKt.launch 系列调用可以在java端调起协程,更加轻量
 *
 * 所有方法 @JvmStatic 保持 Java 端 CenterThreadPool.run(...) 的调用方式不变。
 */
object CenterThreadPool {

    private val MAIN_THREAD_HANDLER = Handler(Looper.getMainLooper())

    //与原Java静态块一致：API<17 无协程可用，退回原生线程池；API>=17 使用协程(Dispatchers.IO)
    private val COROUTINE_SCOPE: CoroutineScope? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) CoroutineScope(Dispatchers.IO) else null

    private val THREAD_POOL: AtomicReference<ExecutorService?>? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) AtomicReference(null) else null

    private fun getThreadPoolInstance(): ExecutorService? {
        val poolRef = THREAD_POOL ?: return null
        val bestThreadPoolSize = Runtime.getRuntime().availableProcessors()
        while (poolRef.get() == null) {
            poolRef.compareAndSet(
                null,
                ThreadPoolExecutor(
                    bestThreadPoolSize / 2,
                    bestThreadPoolSize * 2,
                    60,
                    TimeUnit.SECONDS,
                    ArrayBlockingQueue(20)
                )
            )
        }
        return poolRef.get()
    }

    /**
     * 在后台运行, 用于网络请求等耗时操作
     *
     * @param runnable 要运行的任务
     */
    @JvmStatic
    fun run(runnable: Runnable) {
        try {
            //能用协程用协程
            val scope = COROUTINE_SCOPE
            if (scope != null) {
                scope.launch { runnable.run() }
                //协程不可用时尝试以原生线程池运行
            } else if (THREAD_POOL != null) {
                val service = getThreadPoolInstance()
                if (service != null) service.submit(runnable)
                else Thread(runnable).start()
            } else {
                //都不可用再开线程
                Thread(runnable).start()
            }
        } catch (e: Throwable) {
            //最后再放手一博
            e.printStackTrace()
        }
    }

    /**
     * 在后台运行, 用于网络请求等耗时操作, 有返回值,
     * 在fragment, activity等位置使用LiveData.observe()获取返回值, 会自动切到主线程,不需要再runOnUiThread().
     *
     * @param supplier 要运行的任务
     * @param <T>      返回值类型
     * @return LiveData包装的返回值
     */
    @JvmStatic
    fun <T> supplyAsyncWithLiveData(supplier: Callable<T>): LiveData<Result<T>> {
        val retval = MutableLiveData<Result<T>>()
        //注意：必须显式写 Runnable。裸写 run { ... } 会被解析成标准库 kotlin.run 作用域函数，
        //导致任务在当前线程同步执行（视频详情页因此报"网络错误/获取信息失败"）。
        run(Runnable {
            try {
                val res = supplier.call()
                retval.postValue(Result.success(res))
            } catch (e: Exception) {
                retval.postValue(Result.failure(e))
                MsgUtil.err(e)
            }
        })
        return retval
    }

    /**
     * 在后台运行， 有返回值
     * 使用 CenterThreadPool.observe方法对返回值进行观察
     *
     * @param supplier 一个带返回值的lambda表达式或Supplier的实现类
     * @param <T>      要观察的类型
     * @return 返回一个可供CenterThreadPool观察的Future对象
     */
    @JvmStatic
    fun <T> supplyAsyncWithFuture(supplier: Callable<T>): Future<T> {
        val ftask = FutureTask(supplier)
        run(ftask)
        return ftask
    }

    /**
     * 对Deferred 对象进行观察， 无需切换线程， 自动在ui线程进行观察
     *
     * @param deferred 一个将要在未来返回一个 T 类型对象的对象
     * @param consumer 对T进行观察的lambda表达式或者类
     * @param <T>      要观察的类型
     */
    @JvmStatic
    fun <T> observe(deferred: Future<T>, consumer: Consumer<T>) {
        run(Runnable {
            try {
                val value = deferred.get()
                runOnUiThread { consumer.accept(value) }
            } catch (ignored: Throwable) {
            }
        })
    }

    @JvmStatic
    fun <T> observe(future: Future<T>, consumer: Consumer<T>, onFailure: Consumer<Throwable>) {
        run(Runnable {
            try {
                val value = future.get()
                runOnUiThread { consumer.accept(value) }
            } catch (e: Exception) {
                onFailure.accept(e)
            }
        })
    }

    /**
     * 在主线程运行, 用于更新UI, 例如Toast, Snackbar等
     *
     * @param runnable 要运行的任务
     */
    @JvmStatic
    fun runOnUiThread(runnable: Runnable) {
        MAIN_THREAD_HANDLER.post(runnable)
    }

    @JvmStatic
    fun runOnUIThreadAfter(time: Long, unit: TimeUnit, runnable: Runnable) {
        val millis = TimeUnit.MILLISECONDS.convert(time, unit)
        MAIN_THREAD_HANDLER.postDelayed(runnable, millis)
    }

    @JvmStatic
    fun runOnUIThreadAfter(time: Long, runnable: Runnable) {
        MAIN_THREAD_HANDLER.postDelayed(runnable, time)
    }
}
