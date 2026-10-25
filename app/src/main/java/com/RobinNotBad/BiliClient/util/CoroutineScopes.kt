package com.RobinNotBad.BiliClient.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 全局协程作用域与线程切换工具 —— 新代码的「后台干活 / 主线程更新 UI」统一入口。
 *
 * 为什么要它：项目历史代码用 `runOnUiThread { }` 回主线程、用裸 `Thread`/`Timer` 跑后台，
 * 既没有结构化并发、也无法统一取消。新代码一律改用 kotlinx.coroutines，
 * 并通过本对象提供的入口拿到统一的 scope / 调度器。
 *
 * 使用约定（详见 `tools/quality/README.md`，`check_new_code.sh` 会拦新增的 runOnUiThread）：
 * - 后台做事 + 回主线程刷新：
 * ```
 * CoroutineScopes.launchUi {
 *     val data = CoroutineScopes.io { api.load() }   // 切到 IO，跑完自动切回主线程
 *     textView.text = data.title
 * }
 * ```
 * - 只是一段后台逻辑、不碰 UI：`CoroutineScopes.launchIo { ... }`
 * - 在已有 suspend 函数里临时切线程：`val s = CoroutineScopes.io { readFile() }`
 *
 * 生命周期注意：
 * - [mainScope] / [ioScope] 是 **应用级** 作用域，活到进程结束；它们**不持有** Activity 引用，
 *   但协程体里如果捕获了 Activity，长任务就会把 Activity 一起留住（泄漏）。
 *   → 超过一两秒的任务必须自己持有 [Job]，并在 `onDestroy` 里 `job.cancel()`（示范见
 *   `activity/DialogActivity.kt` 的倒计时）。
 * - 需要跟某个页面同生共死时，用 `lifecycleScope`（androidx）而不是这里；
 *   本对象只负责“全局后台/主线程调度”这一层。
 * - 退出应用或检测到严重内存压力时可以调 [cancelAll] 兜底取消所有在飞的全局任务。
 *
 * 线程模型：`mainScope` 用 `Dispatchers.Main`（Android 主线程），`ioScope` 用
 * `Dispatchers.IO`；两个都是 `SupervisorJob` —— 单个子协程失败不会连带取消其它任务。
 */
object CoroutineScopes {

    /**
     * 应用级主线程作用域（Main + SupervisorJob）。
     *
     * 直接 `mainScope.launch { }` 也可以，但推荐用 [launchUi]（自带默认参数、语义更清楚）。
     */
    val mainScope: CoroutineScope = MainScope()

    /**
     * 应用级后台作用域（IO + SupervisorJob），用于不产生返回值的后台任务。
     *
     * 有返回值的场景请用 [io]（suspend，可 await，异常会正常抛回调用方）。
     */
    val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 在主线程启动一个协程（等价于原来的 `runOnUiThread { }`，但可取消、可结构化）。
     *
     * @param context 追加到主线程调度器上的协程上下文，例如 `CoroutineName("xxx")`
     *                （也可以传 `CoroutineStart.UNDISPATCHED` 之外的 Job/Dispatcher，谨慎使用）
     * @param block 协程体，运行在主线程，可以直接操作 UI；内部可用 [io] 切后台
     * @return 该协程的 [Job]，长任务请持有它并在页面销毁时 `cancel()`
     */
    fun launchUi(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit
    ): Job = mainScope.launch(context) { block() }

    /**
     * 在 IO 线程启动一个后台协程（不碰 UI 的后台逻辑用这个）。
     *
     * @param context 追加的协程上下文
     * @param block 协程体，运行在 IO 线程；**不要**在这里直接改 UI（需要回主线程请用 [launchUi]）
     * @return 该协程的 [Job]
     */
    fun launchIo(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit
    ): Job = ioScope.launch(context) { block() }

    /**
     * 在 IO 线程执行 [block] 并返回结果；调用方若是协程/主线程，回来时会自动切回原调度器
     * （主线程里的调用回来后继续在主线程，可以直接更新 UI）。
     *
     * 与 [launchIo] 的区别：本函数是 **suspend** 的、**有返回值**、**异常直接抛出**，
     * 适合“读文件/查数据库/网络请求”这类需要拿到结果的场景。
     */
    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /**
     * 在已有协程（比如 IO 里）临时切回主线程执行一小段代码。
     *
     * 注意：不要在非协程的普通函数里“裸调”——它是 suspend 的。
     */
    suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    /**
     * 取消所有在飞的全局任务（只取消子任务，[mainScope]/[ioScope] 本身仍可继续使用）。
     *
     * 用于：应用退出、低内存兜底、或测试里做隔离。**不要**在普通页面 onDestroy 里调用
     * ——那会连别的页面正在跑的任务一起取消；页面级的取消请用自己持有的 Job 或 lifecycleScope。
     */
    fun cancelAll() {
        mainScope.coroutineContext[Job]?.cancelChildren()
        ioScope.coroutineContext[Job]?.cancelChildren()
    }
}
