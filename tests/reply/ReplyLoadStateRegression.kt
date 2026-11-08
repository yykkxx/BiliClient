package com.RobinNotBad.BiliClient.activity.reply

/** 无 Android/JUnit 依赖；与实际 ReplyLoadState.kt 一起用 Kotlin/JVM 编译并执行。 */
object ReplyLoadStateRegression {
    @JvmStatic
    fun main(args: Array<String>) {
        sortMapping()
        refreshOutOfOrder()
        sortAndOidSwitch()
        loadMoreOutOfOrder()
        retryAndBottomReset()
        destroyedView()
        sortSwitchDiscardsSeek()
        sortSwitchFailureRollback()
        sortSwitchRapidClicksKeepEarliestBackup()
        restoreKeepsOldKeyWithoutRebuild()
        sortButtonWiring()
        println("ReplyLoadStateRegression: 10 scenarios + wiring PASS")
    }

    private fun sortMapping() {
        check(ReplySortMode.appSort(ReplySortMode.TIME) == 0)
        check(ReplySortMode.appSort(ReplySortMode.HOT) == 2)
        check(ReplySortMode.label(2) == "时间排序")
        check(ReplySortMode.label(3) == "热度排序")
        check(ReplySortMode.switched(2) == 3)
        check(ReplySortMode.switched(3) == 2)
        check(ReplySortMode.label(-1) == "热度排序") // header 不再按未知值越界索引
    }

    private fun refreshOutOfOrder() {
        val state = ReplyLoadState()
        val key = ReplyLoadState.Key(1L, 1, 3, 0L)
        val old = state.refresh(key)
        check(state.next(key) == null)
        val newest = state.refresh(key)
        check(!state.finish(old, 1, "old"))
        check(state.isLoading)
        check(state.finish(newest, 0, "new"))
        check(!state.finish(old, -1, ""))
        check(state.pagination == "new")
        check(!state.bottom)
    }

    private fun sortAndOidSwitch() {
        val state = ReplyLoadState()
        val hotKey = ReplyLoadState.Key(1L, 1, 3, 0L)
        val timeKey = hotKey.copy(sort = 2)
        val hot = state.refresh(hotKey)
        val time = state.refresh(timeKey)
        check(time.pagination.isEmpty() && state.page == 1 && !state.bottom)
        check(!state.finish(hot, 1, ""))
        check(state.finish(time, 0, "2"))
        check(state.next(hotKey) == null)
        val oldPage = requireNotNull(state.next(timeKey))
        val anotherOid = state.refresh(timeKey.copy(oid = 2L))
        check(!state.finish(oldPage, 0, "3"))
        check(state.finish(anotherOid, 1, ""))
        check(state.key?.oid == 2L && state.bottom)
        val anotherType = state.refresh(timeKey.copy(type = 17, seek = 123L))
        check(anotherType.key.type == 17 && anotherType.key.seek == 123L)
        check(anotherType.pagination.isEmpty() && !state.bottom)
    }

    private fun loadMoreOutOfOrder() {
        val state = ReplyLoadState()
        val key = ReplyLoadState.Key(1L, 1, 3, 0L)
        check(state.finish(state.refresh(key), 0, "offset-2"))
        val next = requireNotNull(state.next(key))
        check(next.pagination == "offset-2" && next.page == 2 && !next.refresh)
        check(state.next(key) == null) // 同一页不能并发请求
        val refresh = state.refresh(key)
        check(!state.finish(next, 1, "stale"))
        check(state.isCurrent(refresh) && state.pagination.isEmpty() && state.page == 1)
        check(state.finish(refresh, 0, "fresh"))
        check(!state.finish(next, -1, "")) // 旧错误也不能关闭新请求/改页数
        check(state.pagination == "fresh" && !state.bottom)
    }

    private fun retryAndBottomReset() {
        val state = ReplyLoadState()
        val key = ReplyLoadState.Key(1L, 1, 2, 0L)
        val failedRefresh = state.refresh(key)
        check(state.finish(failedRefresh, -1, "ignored"))
        check(!state.isLoading && state.needsRefresh)
        check(state.page == 1 && state.pagination.isEmpty() && !state.bottom)
        check(state.finish(state.refresh(key), 0, "2"))
        val failedNext = requireNotNull(state.next(key))
        check(state.finish(failedNext, -1, "ignored"))
        check(state.pagination == "2" && state.page == 1 && !state.bottom)
        val retry = requireNotNull(state.next(key))
        check(retry.page == 2 && retry.pagination == "2")
        check(!state.finish(failedNext, 1, "")) // 相同世代重试也须验证请求身份
        check(state.isLoading)
        check(state.finish(retry, 1, ""))
        check(state.bottom && state.next(key) == null)
        val reopened = state.refresh(key)
        check(!state.bottom && state.page == 1 && state.pagination.isEmpty())
        check(state.finish(reopened, 0, "cursor-or-page-2"))
        check(state.next(key)?.pagination == "cursor-or-page-2")
    }

    private fun destroyedView() {
        val state = ReplyLoadState()
        val key = ReplyLoadState.Key(1L, 1, 3, 0L)
        val old = state.refresh(key)
        state.invalidate()
        check(!state.finish(old, 0, "late"))
        check(!state.isLoading && state.key == null)
        val newView = state.refresh(key)
        check(!state.finish(old, -1, ""))
        check(state.isCurrent(newView))
        check(state.finish(newView, 1, ""))
    }

    /**
     * 切换排序必须换一个 Key（否则 ReplyFragment.startRefresh 的 `previousKey != key`
     * 不会重建列表，用户看到的就是「按钮点了、标签变了、列表没变」）。
     * 同时 seek 必须被丢弃：seek_rpid 的语义是「定位到某条评论」，
     * 带着它换 mode 时两种排序都会返回同一条评论的上下文。
     */
    private fun sortSwitchDiscardsSeek() {
        check(ReplySortMode.seekAfterSwitch() == 0L)
        val hotKey = ReplyLoadState.Key(1L, 1, ReplySortMode.HOT, 123L)
        val timeKey = hotKey.copy(sort = ReplySortMode.switched(hotKey.sort), seek = ReplySortMode.seekAfterSwitch())
        check(timeKey != hotKey)                       // → 触发重建列表
        check(timeKey.sort == ReplySortMode.TIME)
        check(timeKey.seek == 0L)
        val back = timeKey.copy(sort = ReplySortMode.switched(timeKey.sort), seek = ReplySortMode.seekAfterSwitch())
        check(back.sort == ReplySortMode.HOT && back != timeKey) // 来回切换每一轮都是新 Key
        // 两个方向都不能退化回同一个 Key（退化就等于「切换无效」）
        check(ReplySortMode.switched(ReplySortMode.TIME) != ReplySortMode.TIME)
        check(ReplySortMode.switched(ReplySortMode.HOT) != ReplySortMode.HOT)
        val state = ReplyLoadState()
        val refreshed = state.refresh(timeKey)
        check(state.finish(refreshed, 0, "cursor"))
        val afterSwitch = state.refresh(back)
        check(!state.finish(refreshed, 1, ""))          // 旧排序的晚到响应不能污染新排序
        check(state.isCurrent(afterSwitch))
        check(state.key?.sort == ReplySortMode.HOT)
    }

    /**
     * 切换失败必须回到切换前的排序，并且不能停在「空列表 + 新排序标签」。
     * 判定条件是「失败请求的 sort 等于当前目标 sort」——晚到的旧请求失败不能触发回滚。
     */
    private fun sortSwitchFailureRollback() {
        val switch = ReplySortSwitch()
        check(switch.failureSort(ReplySortMode.HOT) == null)      // 还没开始切换，无备份可回滚
        check(switch.begin(ReplySortMode.HOT, ReplySortMode.TIME))
        check(switch.active && switch.from == ReplySortMode.HOT && switch.target == ReplySortMode.TIME)
        check(switch.failureSort(ReplySortMode.HOT) == null)      // 旧排序的晚到失败不回滚
        check(switch.active)                                      // 也不会误清在途状态
        check(switch.failureSort(ReplySortMode.TIME) == ReplySortMode.HOT)
        check(!switch.active)
        check(switch.failureSort(ReplySortMode.TIME) == null)     // 已回滚，不会重复回滚
        // 成功路径：commit 之后失败不再回滚（旧列表已被新数据替换，回滚反而是错的）
        check(switch.begin(ReplySortMode.HOT, ReplySortMode.TIME))
        switch.commit()
        check(!switch.active && switch.failureSort(ReplySortMode.TIME) == null)
        // 换评论/换类型：备份对新目标无意义，cancel 后失败不回滚
        check(switch.begin(ReplySortMode.TIME, ReplySortMode.HOT))
        switch.cancel()
        check(!switch.active && switch.failureSort(ReplySortMode.HOT) == null)
    }

    /** 连续快速切换（热→时→热）只备份最早那份排序，目标跟着最后一次点击走。 */
    private fun sortSwitchRapidClicksKeepEarliestBackup() {
        val switch = ReplySortSwitch()
        check(switch.begin(ReplySortMode.HOT, ReplySortMode.TIME))   // 第一下：备份 HOT
        check(!switch.begin(ReplySortMode.TIME, ReplySortMode.HOT))  // 第二下：沿用最早备份
        check(switch.from == ReplySortMode.HOT && switch.target == ReplySortMode.HOT)
        check(switch.failureSort(ReplySortMode.TIME) == null)        // 中途目标的失败不回滚
        check(switch.failureSort(ReplySortMode.HOT) == ReplySortMode.HOT) // 回到点按钮之前
        // 三下（热→时→热→时）同理，备份始终是 HOT
        val again = ReplySortSwitch()
        check(again.begin(ReplySortMode.HOT, ReplySortMode.TIME))
        check(!again.begin(ReplySortMode.TIME, ReplySortMode.HOT))
        check(!again.begin(ReplySortMode.HOT, ReplySortMode.TIME))
        check(again.from == ReplySortMode.HOT && again.target == ReplySortMode.TIME)
        check(again.failureSort(ReplySortMode.TIME) == ReplySortMode.HOT)
    }

    /**
     * 回滚后的状态：键换回旧排序、列表持有旧数据、下一次下拉仍会重新请求旧键。
     * restore 与 invalidate 一样换世代（晚到的旧响应不能提交），但保留 key 供上拉使用。
     */
    private fun restoreKeepsOldKeyWithoutRebuild() {
        val state = ReplyLoadState()
        val hotKey = ReplyLoadState.Key(1L, 1, ReplySortMode.HOT, ReplySortMode.seekAfterSwitch())
        val timeKey = ReplyLoadState.Key(1L, 1, ReplySortMode.TIME, ReplySortMode.seekAfterSwitch())
        check(state.finish(state.refresh(hotKey), 0, "hot-cursor"))
        val failed = state.refresh(timeKey)
        check(state.finish(failed, -1, ""))
        check(!state.isLoading && state.needsRefresh)
        state.restore(hotKey)
        check(state.key == hotKey)
        check(state.needsRefresh)                 // 列表里是旧数据，仍需一次刷新才算加载完成
        check(state.next(hotKey) == null)         // 未加载完首屏前不允许上拉，避免用旧 cursor 翻页
        val retry = state.refresh(hotKey)
        check(state.finish(retry, 0, "hot-cursor-2"))
        check(state.pagination == "hot-cursor-2" && state.page == 1 && !state.bottom)
    }

    /**
     * 源码接线检查（不是行为测试）：
     * 1) 详情页不能留「点了没反应」的排序按钮 —— 服务端 `/x/v2/reply/reply` 对 sort=0/1/2
     *    实测返回相同结果，假按钮必须藏掉；
     * 2) 列表页切换排序必须同时做「换 sort、丢 seek、清列表重建、可见反馈」；
     * 3) 切换失败必须回滚到旧排序与旧列表。
     */
    private fun sortButtonWiring() {
        val adapter = repoFile("app/src/main/java/com/RobinNotBad/BiliClient/adapter/ReplyAdapter.kt")
        check(adapter.contains("if (isDetail)"))
        check(adapter.contains("writeReply.sort.visibility = View.GONE"))
        check(adapter.contains("writeReply.sort.setOnClickListener(null)")) // 藏起来还不够，必须解绑点击
        check(adapter.contains("writeReply.sort.text = ReplySortMode.label(sort)"))
        check(adapter.contains("listener?.onItemClick(0)"))
        check(adapter.contains("writeReply.count_label.text = \"共\" + count + \"条评论\""))
        check(!adapter.contains("未知排序")) // 旧实现用 0/1 索引的假文案，越界会显示「未知排序」

        val detail = repoFile("app/src/main/java/com/RobinNotBad/BiliClient/activity/reply/ReplyInfoActivity.kt")
        check(detail.contains("private val sort = 0"))     // 固定时间序，不再来回切
        check(!detail.contains("setOnSortSwitchListener")) // 详情页不再挂切换监听
        check(!detail.contains("sort = if (sort == 0) 1 else 0"))

        val fragment = repoFile("app/src/main/java/com/RobinNotBad/BiliClient/activity/reply/ReplyFragment.kt")
        val switchSort = fragment.split("private fun switchSort()")[1].split("override fun onDestroyView")[0]
        check(switchSort.contains("ReplySortMode.switched(sort)"))
        check(switchSort.contains("seek = ReplySortMode.seekAfterSwitch()")) // 换排序必须丢 seek
        check(switchSort.contains("sortSwitch.begin(fromSort, sort)"))
        check(switchSort.contains("sortSwitchBackup = replyList?.let { ArrayList(it) }"))
        check(switchSort.contains("replyList?.clear()"))
        check(switchSort.contains("bindReplyAdapter()"))                  // 重建 adapter：旧数据不会续在后面
        check(switchSort.contains("MsgUtil.showMsg("))                    // 可见反馈
        check(switchSort.indexOf("replyList?.clear()") < switchSort.indexOf("refresh(aid)"))
        check(fragment.contains("sortSwitch.failureSort(request.key.sort)"))
        check(fragment.contains("loadState.restore(failedKey.copy(sort = restoreSort, seek = seek))"))
        check(fragment.contains("seek = sortSwitchSeekBackup"))  // 回滚也要还原切换前的 seek
        check(fragment.contains("sortSwitch.cancel()"))                   // 换 oid/type 时放弃备份
        check(fragment.contains("sortSwitch.commit()"))                   // 成功即提交
    }

    private fun repoFile(relative: String): String {
        // 测试从 tests/reply 目录向上两级回到仓库根；不依赖 Android，纯文本读取。
        val root = System.getProperty("biliclient.repo")
            ?: error("需通过 -Dbiliclient.repo=<repo> 指定仓库根目录")
        return java.io.File(root, relative).readText()
    }
}
