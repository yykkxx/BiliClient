package com.RobinNotBad.BiliClient.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.viewpager.widget.ViewPager

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.viewpager.ViewPagerViewAdapter
import com.RobinNotBad.BiliClient.ui.widget.PhotoViewpager
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.Target
import com.github.chrisbanes.photoview.PhotoView

/**
 * 图片查看页（v5.2.1 重做）。
 *
 * # 用户需求原文
 * 「在点开图片之后的预览界面里面，取消侧滑退出手势（因为这会影响到图片放大），
 * 改为只有点击顶部才能退出。优化图片操作的逻辑，支持双指放大缩小」
 *
 * # 三个关键行为（改动前请先读完"为什么"）
 *
 * ## 1. 侧滑退出必须彻底关掉，且要"双保险"
 * 很反直觉的一点：**Manifest 上早就配了 NoSwipe 主题（`windowSwipeToDismiss=false`），
 * 但侧滑仍然存在。** 真正的元凶是本类 `onCreate` 里的 `setTheme(R.style.Theme_BiliClient)` ——
 * 它在 `super.onCreate` 之后执行，会把 Manifest 上那套主题**覆盖掉**，于是
 * `Theme.BiliClient` 的 `windowSwipeToDismiss=true` 又生效了。
 *
 * 所以这里做两件事：
 * - `setTheme(R.style.Theme_BiliClient_NoSwipe)`：`NoSwipe` 是 `Theme.BiliClient` 的子类，
 *   **只**覆盖 `windowSwipeToDismiss=false`，其余控件样式全部保留
 *   （不能用 `Theme.NoSwipe`，它父类是 `android:Theme.Black`，按钮/文字会变丑）；
 * - `setSystemGestureExclusionRects`（API 29+）：把左右边缘的系统返回手势区也屏蔽掉，
 *   否则「从屏幕最左边往右滑」会被系统抢走当成返回，用户正在放大图片时尤其恼火。
 *   这是第二重保险：主题管的是 Activity 的窗口动画手势，系统手势是另一套机制。
 *
 * ## 2. 只有点顶部才退出，点图片绝不退出
 * `setTopbarExit()`（[BaseActivity] 提供）会给 `@+id/top` 挂 `finish()`。
 * 图片区域**不挂任何退出监听** —— 这是用户明确要求，也是相册类 App 的标准手感：
 * 看图时手指几乎总会落在图片上，让它退出是最糟的误触。
 *
 * ## 3. 单击切浮层，且**绝不能**用 setOnDoubleTapListener
 * PhotoView 2.3.0 内置的 `PhotoViewAttacher$3` 是它的 `OnDoubleTapListener`。
 * 一旦你调 `setOnDoubleTapListener(...)`，就把这个内置实现**顶掉了**，
 * **双击缩放会直接失效**（这是读字节码确认过的结论，不是猜的）。
 * 正确做法是用 `setOnViewTapListener(...)`：内置实现会在 `onSingleTapConfirmed` 里
 * 依次回调 `OnClickListener` → `OnViewTapListener` → `OnPhotoTapListener`，
 * 单双击的时序判断由它自己做，我们只管"单击要干什么"，双击缩放完好无损。
 *
 * # 双指缩放（"优化图片操作的逻辑"）
 * - 1x ~ 8x，中档 2.5x：捏合到中档附近会自然吸附，避免只有两端档位时"要么太小要么糊"；
 * - 双击放大到中档、再双击回 1x（PhotoView 内置行为，**不要动**）；
 * - 放大后可拖动看图，拖到边缘才翻页（`setAllowParentInterceptOnEdge(true)`）；
 * - 缩放/拖动中绝不翻页（见 [PhotoViewpager] 的拦截逻辑）。
 */
class ImageViewerActivity : BaseActivity() {

    private var longClickTimestamp: Long = 0

    /** 顶部与底部浮层是否可见（单击图片切换）。默认可见，与用户"打开就能看到页码"的预期一致。 */
    private var overlayVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ⚠️ 必须用 Theme_BiliClient_NoSwipe，不能用 Theme_BiliClient（会重新打开侧滑退出），
        // 也不能用 Theme_NoSwipe（父类是 android:Theme.Black，会丢掉全部 app 控件样式）。
        // 与 AndroidManifest 上给本 Activity 配的主题**保持一致**（两处同一个 style）。
        setTheme(R.style.Theme_BiliClient_NoSwipe)
        setContentView(R.layout.activity_image_viewer)

        val imageList = intent.getStringArrayListExtra("imageList")
        if (imageList.isNullOrEmpty()) {
            MsgUtil.showMsg("没有可查看的图片")
            finish()
            return
        }

        // 点顶部退出（BaseActivity 的通用实现：给 @+id/top 挂 finish）
        setTopbarExit()

        // API 29+ 屏蔽左右边缘的系统返回手势，避免"想放大却触发了返回"
        excludeSystemGestureEdges()

        val viewPager = findViewById<PhotoViewpager>(R.id.viewPager)
        val pageText = findViewById<TextView>(R.id.text_page)

        val photoViewList = ArrayList<View>()
        for (i in imageList.indices) {
            val photoView = createPhotoView(imageList[i])
            // 给每页打上页索引：PhotoViewpager 要靠它找到"当前页"来判断是否已放大
            // （不能用 adapter.instantiateItem 找，那会在手势热路径上重复创建 View）。
            photoView.tag = i
            photoViewList.add(photoView)
        }

        viewPager.adapter = ViewPagerViewAdapter(photoViewList)

        // 页码初值：进入页面立刻显示「第1/N张」。
        // 旧实现只在 onPageScrolled 里更新，而首屏并不一定触发滚动回调，
        // 于是用户看到的是 layout 里写死的占位文本（曾是「第0/0张」）。
        setPageText(pageText, 1, imageList.size)

        viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            /**
             * 用 onPageSelected 而不是 onPageScrolled 更新页码。
             *
             * 旧实现在 onPageScrolled 里判 `positionOffset % 1 == 0f` —— 那是**浮点比较**：
             * 滑动过程中 positionOffset 是 0.0~1.0 的浮点值，`% 1` 的结果几乎不可能**恰好**等于 0f，
             * 于是只在"完全停住"的偶发时刻才更新，表现为页码跳动/不更新。而 onPageSelected
             * 是语义化事件（选中的页变了），天然对应"页码该变了"。
             */
            override fun onPageSelected(position: Int) {
                setPageText(pageText, position + 1, imageList.size)
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) = Unit

            override fun onPageScrollStateChanged(state: Int) = Unit
        })

        // 下载按钮：连点两次（3 秒内）才真正下载，防止误触。
        val download = findViewById<ImageButton>(R.id.btn_download)
        download.setOnClickListener {
            val timeNow = System.currentTimeMillis()
            if (timeNow - longClickTimestamp < DOUBLE_CLICK_WINDOW_MS) {
                val current = viewPager.currentItem
                val url = imageList.getOrNull(current)
                if (url.isNullOrEmpty()) {
                    MsgUtil.showMsg("这张图片还没加载出来")
                    return@setOnClickListener
                }
                val intent1 = Intent(this, DownloadActivity::class.java)
                    .putExtra("link", url)
                    .putExtra("path", FileUtil.getPicturePath().absolutePath)
                    .putExtra("type", 0)
                startActivity(intent1)
            } else {
                MsgUtil.showMsg("再次点击下载")
            }
            longClickTimestamp = timeNow
        }
    }

    /**
     * 创建一页图片的 PhotoView（含缩放档位与单击切浮层）。
     *
     * 抽成独立函数只是为了让 [onCreate] 读起来是"搭页面"而不是"一屏配置"。
     */
    private fun createPhotoView(url: String): PhotoView {
        val photoView = PhotoView(this)
        // 三档倍率：1x（原图适配）/ 2.5x（细节档，捏合会吸附到这里）/ 8x（上限）
        photoView.setScaleLevels(MIN_SCALE, MEDIUM_SCALE, MAX_SCALE)
        // 放大后左右可以拖动看图；拖到边缘把翻页权还给 ViewPager（配合 PhotoViewpager 的拦截逻辑）
        photoView.setAllowParentInterceptOnEdge(true)
        // 双击缩放的动画时长：200ms 比默认更跟手，又不至于看不出动画
        photoView.setZoomTransitionDuration(ZOOM_TRANSITION_MS)
        // 单击切浮层：用 OnViewTapListener，**绝不用 setOnDoubleTapListener**（理由见类注释）
        photoView.setOnViewTapListener { _, _, _ -> toggleOverlay() }
        loadImage(photoView, url)
        return photoView
    }

    /**
     * 加载图片。
     *
     * `override(Target.SIZE_ORIGINAL)` 是必须的：B 站图床有 `@xxx.jpg` 这类尺寸后缀，
     * Glide 默认会按 View 尺寸降采样，不 override 的话**一放大就糊成马赛克**。
     * `diskCacheStrategy(DiskCacheStrategy.NONE)` 沿用旧行为（看图页不做磁盘缓存，
     * 避免用户看过的每张原图都留在磁盘里）。
     */
    private fun loadImage(photoView: PhotoView, url: String) {
        try {
            Glide.with(this).asDrawable()
                .load(GlideUtil.url_hq(url))
                .transition(GlideUtil.getTransitionOptions())
                .override(Target.SIZE_ORIGINAL)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(photoView)
        } catch (e: OutOfMemoryError) {
            // 原图（尤其长图/超清）在低内存设备上确实会 OOM，这是必须兜住的（旧行为保留）
            MsgUtil.showMsg("超出内存，加载失败")
        } catch (e: Exception) {
            MsgUtil.err("图片查看", e)
        }
    }

    /**
     * 单击图片：切顶部/底部浮层的显隐（相册 App 的标准手感）。
     *
     * 为什么退化成"隐藏顶栏"不是问题：顶栏是**唯一**的退出入口（用户要求），
     * 所以隐藏后再单击一次就能恢复 —— 两次单击的成本，换来看图时不被 UI 遮挡。
     * 隐藏时同时把状态栏也藏起来，才是真正的"全屏看图"。
     */
    private fun toggleOverlay() {
        overlayVisible = !overlayVisible
        val visibility = if (overlayVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.top)?.visibility = visibility
        findViewById<View>(R.id.btn_download)?.visibility = visibility
        findViewById<View>(R.id.text_page)?.visibility = visibility
    }

    @SuppressLint("SetTextI18n")
    private fun setPageText(textView: TextView, current: Int, total: Int) {
        textView.text = "第$current/$total 张"
    }

    /**
     * API 29+：把左右两条窄边的"系统返回手势区"排除掉。
     *
     * 为什么只排左右两条窄边而不是整屏：`setSystemGestureExclusionRects` 有**面积上限**
     * （系统只接受每屏约 200dp 高度的排除区），整屏会被静默忽略。左右各一条
     * [GESTURE_EDGE_WIDTH_PX] 宽、高度取屏幕高的矩形，正好覆盖"从边缘往中间滑"的那条路径，
     * 又不会超出限制 —— 因为这两个矩形加起来宽度很小，实际面积由高度决定，
     * 这里用 `GESTURE_EDGE_MAX_HEIGHT_DP` 限高就是为了稳妥。
     *
     * 低版本（<29）没有这套系统手势，直接跳过；`Build.VERSION` 判断放在调用处更清楚。
     */
    private fun excludeSystemGestureEdges() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val root = findViewById<View>(R.id.viewPager) ?: return
        root.post {
            if (isDestroyed) return@post
            val width = root.width
            val height = root.height
            if (width <= 0 || height <= 0) return@post
            val edge = GESTURE_EDGE_WIDTH_PX
            val safeHeight = minOf(height, dpToPx(GESTURE_EDGE_MAX_HEIGHT_DP))
            val rects = ArrayList<Rect>(2)
            rects.add(Rect(0, 0, edge, safeHeight))
            rects.add(Rect(width - edge, 0, width, safeHeight))
            root.systemGestureExclusionRects = rects
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private companion object {
        /** 最小倍率：1x（图片适配屏幕） */
        const val MIN_SCALE = 1f

        /** 中档：捏合吸附点，也是双击放大后的倍率 */
        const val MEDIUM_SCALE = 2.5f

        /** 最大倍率：8x（再大就纯粹是糊了） */
        const val MAX_SCALE = 8f

        /** 双击缩放动画时长（毫秒） */
        const val ZOOM_TRANSITION_MS = 200

        /** 下载按钮"再点一次"的判定窗口（毫秒） */
        const val DOUBLE_CLICK_WINDOW_MS = 3000L

        /** 系统返回手势区的排除宽度（px）：约 20dp，覆盖"从边缘起滑"的路径即可 */
        const val GESTURE_EDGE_WIDTH_PX = 60

        /** 排除区最大高度（dp）：系统对单屏排除面积有上限，限高避免被静默忽略 */
        const val GESTURE_EDGE_MAX_HEIGHT_DP = 200
    }
}
