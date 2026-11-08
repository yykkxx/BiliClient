package com.RobinNotBad.BiliClient.activity.reply

/** 评论列表持有 Web mode；只有进入 pn/ps API 时才转换为 APP sort。 */
object ReplySortMode {
    const val TIME = 2
    const val HOT = 3

    fun appSort(webMode: Int): Int = if (webMode == TIME) 0 else 2

    fun label(webMode: Int): String = if (webMode == TIME) "时间排序" else "热度排序"

    fun switched(webMode: Int): Int = if (webMode == TIME) HOT else TIME

    /**
     * 切换排序后要用的 seek（0 = 不定位）。
     *
     * seek_rpid 的语义是「定位到某一条评论」，它和排序是两件事：排序一变，
     * 用户想看的已经是「最新/最热的第一条」，而不是继续停在旧评论附近。
     * 更糟的是 cursor 端点带 seek_rpid 时两种 mode 都会返回同一条评论的上下文，
     * 表现就是「点了排序按钮、按钮文字变了、列表却几乎没变」——这是本页报障的成因之一。
     * 所以切换排序必须**丢弃** seek；只有「从别人分享的评论链接进入」那种一次性定位才保留。
     */
    const val SEEK_NONE = 0L

    /** 切换排序时应使用的 seek（语义化别名，避免调用点直接写 0）。 */
    fun seekAfterSwitch(): Long = SEEK_NONE
}

/**
 * 排序切换事务：决定「失败时回到哪个排序」。
 *
 * 排序切换的流程是「清空列表 → 请求新排序 → 成功则展示新数据」。清空必须发生在请求之前
 * （否则下拉刷新会把新排序的数据追加在旧数据后面），代价就是**请求失败时用户会停在
 * 空列表 + 新排序标签的半截状态**。所以切换前要把「切换前的排序」记下来，失败时原样还原。
 *
 * 连续快速切换（热度→时间→热度）时不能层层套备份：只保留**最早那份**排序与列表，
 * 目标排序则跟着用户最后一次点击走。这样任一请求失败都回到用户点按钮之前的状态，
 * 而不是回到「上一次点击」的半截排序。
 */
class ReplySortSwitch {
    /** 切换前的排序（最早一份）。 */
    var from: Int = 0
        private set

    /** 用户当前想要的排序（最后一次点击）。 */
    var target: Int = 0
        private set

    var active: Boolean = false
        private set

    /**
     * 开始（或更新）一次切换。
     *
     * @return true 表示这是本轮第一下，调用方需要现在备份当前列表；false 表示已有多轮切换在途，
     *         沿用最早的备份，只把目标排序换成这次点击的结果。
     */
    fun begin(fromSort: Int, targetSort: Int): Boolean {
        val first = !active
        if (first) from = fromSort
        target = targetSort
        active = true
        return first
    }

    /** 新排序的数据成功展示后调用：此后失败不再回滚（旧列表已经没用了）。 */
    fun commit() {
        active = false
    }

    /** 目标换成别的评论/类型时放弃本轮切换：旧列表备份对新目标没有意义。 */
    fun cancel() {
        active = false
        target = 0
    }

    /** 失败回滚时该恢复的排序；失败请求不是当前目标的、或没有备份时返回 null（不回滚）。 */
    fun failureSort(failedSort: Int): Int? {
        if (!active || failedSort != target) return null
        active = false
        return from
    }
}

/**
 * 仅由主线程操作的评论请求状态。网络线程只拿不可变 Request，不能提交共享分页状态。
 * 每次刷新（包括相同 oid/sort）都换世代，晚到的旧刷新/下一页/错误一律不能改变当前状态。
 */
class ReplyLoadState {
    data class Key(val oid: Long, val type: Int, val sort: Int, val seek: Long)

    data class Request(
        val generation: Long,
        val key: Key,
        val pagination: String,
        val page: Int,
        val refresh: Boolean
    )

    var key: Key? = null
        private set
    var pagination = ""
        private set
    var bottom = false
        private set
    var page = 1
        private set

    private var generation = 0L
    private var active: Request? = null
    private var firstPageLoaded = false

    val isLoading: Boolean
        get() = active != null
    val needsRefresh: Boolean
        get() = !firstPageLoaded

    fun refresh(key: Key): Request {
        invalidate()
        this.key = key
        val request = Request(generation, key, "", 1, true)
        active = request
        return request
    }

    fun next(key: Key): Request? {
        if (active != null || !firstPageLoaded) return null
        if (bottom || this.key != key) return null
        val request = Request(generation, key, pagination, page + 1, false)
        active = request
        return request
    }

    fun isCurrent(request: Request): Boolean = active === request && request.generation == generation

    /** 失败只释放请求，不吞掉 offset 或推进页数；下一次下拉/上拉仍可重试。 */
    fun finish(request: Request, result: Int, nextPagination: String): Boolean {
        if (!isCurrent(request)) return false
        active = null
        if (result != -1) {
            pagination = nextPagination
            bottom = result == 1
            page = request.page
            firstPageLoaded = true
        }
        return true
    }

    fun invalidate() {
        generation++
        active = null
        key = null
        pagination = ""
        bottom = false
        page = 1
        firstPageLoaded = false
    }

    /**
     * 排序切换失败后的状态回滚：目标键换回切换前的键，但 `firstPageLoaded` 保持 false。
     *
     * 为什么不清空列表：页面此刻显示的是切换前的旧列表（由 ReplyFragment 从备份还原），
     * 用户下一次下拉/上拉会重新请求**同一个旧键**，那时 `previousKey == key` 不再重建 adapter，
     * 新数据到位才替换，失败则继续保留旧列表。
     */
    fun restore(key: Key) {
        invalidate()
        this.key = key
    }
}
