#!/usr/bin/env python3
"""Compile the marked production Kotlin logic with the already-cached compiler, never Gradle."""
from pathlib import Path
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
SOURCE = REPO / "app/src/main/java/com/RobinNotBad/BiliClient/ui/widget/PhotoViewpager.kt"
ACTIVITY = REPO / "app/src/main/java/com/RobinNotBad/BiliClient/activity/ImageViewerActivity.kt"
LAYOUT = REPO / "app/src/main/res/layout/activity_image_viewer.xml"
WINDOW = REPO / "app/src/main/res/values/image_preview.xml"
BUILD = HERE / ".build"


def check_wiring(source):
    """Source/XML invariants only; these are explicitly NOT Android behavior tests."""
    native = source.split("private fun interceptHorizontalEdgeDrag", 1)[1].split(
        '@SuppressLint("ClickableViewAccessibility")', 1
    )[0]
    assert native.index("cancelNativePreparation(ev)") < native.index("val down = singlePointerDown")
    assert "gesture.previousEventTime" in native
    assert "withNativeEvent(ev) { super.onInterceptTouchEvent(it) }" in native
    assert "withNativeEvent(ev) { super.onTouchEvent(it) }" in source
    assert "MotionEvent.obtainNoHistory(ev)" in source
    pointer_down = source.split("private fun singlePointerDown", 1)[1].split("private fun dispatchToPhoto", 1)[0]
    assert "getPointerProperties(index, properties)" in pointer_down
    assert "getPointerCoords(index, coords)" in pointer_down
    assert "arrayOf(properties), arrayOf(coords)" in pointer_down
    assert "ev.deviceId, ev.edgeFlags, ev.source, ev.flags" in pointer_down
    forward = source.split("private fun dispatchToPhoto", 1)[1].split("private fun currentPhotoView", 1)[0]
    # downTime 是只读属性（android.jar 只有 getDownTime()），必须走完整 obtain 重载携带它，
    # 否则交接后的手势会被 PhotoView 当成新手势（单击/双击/拖动全失效）。
    assert "val downTime = if (forwardedPhoto === photo) forwardedDownTime else ev.downTime" in forward
    assert "MotionEvent.obtain(\n            downTime, ev.eventTime, ev.action, ev.pointerCount" in forward
    assert "photo.dispatchTouchEvent(local)" in forward
    assert "local.offsetLocation" in forward
    dispatch = source.split("override fun dispatchTouchEvent", 1)[1].split("private fun touchAction", 1)[0]
    assert dispatch.index("super.dispatchTouchEvent(ev)") < dispatch.rindex("cancelNativePreparation(ev)")
    # PhotoView 2.3.0 在 ACTION_DOWN 里就 requestDisallowInterceptTouchEvent(true)，
    # 父级此后不再收到 onInterceptTouchEvent —— 边界交接必须自己在 dispatch 阶段放行。
    assert "if (shouldReleasePhotoIntercept(action))" in dispatch
    assert dispatch.index("requestDisallowInterceptTouchEvent(false)") < dispatch.index("val photo = forwardedPhoto")
    release = source.split("private fun shouldReleasePhotoIntercept", 1)[1].split("override fun onInterceptTouchEvent", 1)[0]
    assert "PhotoPagerTouchAction.MOVE" in release and "canHandOffToPager()" in release
    cancel = source.split("private fun cancelPagerDrag", 1)[1].split("private fun startForwardedPhotoGesture", 1)[0]
    assert "super.onTouchEvent(cancel)" in cancel
    assert "snapBackToStart(ev)" in cancel
    assert "super.onTouchEvent(down)" in cancel
    assert "super.onInterceptTouchEvent(down)" in cancel
    assert "scrollTo(photo.left - paddingLeft, 0)" in cancel

    activity = ACTIVITY.read_text(encoding="utf-8")
    assert activity.index("setTheme(R.style.Theme_BiliClient_ImagePreview)") < activity.index(
        "super.onCreate(savedInstanceState)"
    )
    assert "theme.applyStyle(R.style.ImagePreviewWindow, true)" in activity
    assert "WindowCompat.setDecorFitsSystemWindows(window, true)" in activity
    assert ".show(WindowInsetsCompat.Type.systemBars())" in activity
    assert "WindowManager.LayoutParams.FLAG_FULLSCREEN" in activity
    assert "View.SYSTEM_UI_FLAG_HIDE_NAVIGATION" in activity
    assert "if (hasFocus) showSystemBars()" in activity
    assert "const val MIN_SCALE = 1f" in activity and "const val MAX_SCALE = 8f" in activity
    assert "const val MEDIUM_SCALE = 2.5f" in activity          # 双击中档
    assert "setScaleLevels(MIN_SCALE, MEDIUM_SCALE, MAX_SCALE)" in activity
    assert "photoView.setAllowParentInterceptOnEdge(true)" in activity
    assert "setZoomTransitionDuration(ZOOM_TRANSITION_MS)" in activity
    assert "photoView.setOnDoubleTapListener(" not in activity
    assert "photoView.setOnViewTapListener" in activity
    assert "setTopbarExit()" in activity
    toggle = activity.split("private fun toggleOverlay", 1)[1].split('@SuppressLint("SetTextI18n")', 1)[0]
    assert "View.INVISIBLE" in toggle and "showSystemBars" not in toggle
    assert "WindowInsetsController" not in toggle
    # 单击切浮层必须同时管三样东西：顶栏、下载按钮、页码；且浮层显隐不得改布局尺寸。
    for holder in ("R.id.top", "R.id.btn_download", "R.id.text_page"):
        assert holder in toggle
    assert "View.GONE" not in toggle
    # 页码：进页面立刻写一次 + onPageSelected 更新（不能只在 onPageScrolled 里浮点比较）。
    assert "setPageText(pageText, 1, imageList.size)" in activity
    assert "override fun onPageSelected(position: Int)" in activity
    assert "setPageText(pageText, position + 1, imageList.size)" in activity
    # 下载按钮：3 秒内连点两次才下载，且下载当前页 URL 到图片目录。
    assert "timeNow - longClickTimestamp < DOUBLE_CLICK_WINDOW_MS" in activity
    assert "viewPager.currentItem" in activity
    assert "timeNow - longClickTimestamp < DOUBLE_CLICK_WINDOW_MS" in activity
    assert 'Intent(this, DownloadActivity::class.java)' in activity
    assert "FileUtil.getPicturePath()" in activity

    styles = ET.parse(WINDOW).getroot().findall("style")
    expected = {
        "android:windowFullscreen": "false",
        "android:windowSwipeToDismiss": "false",
        "android:windowTranslucentStatus": "false",
        "android:windowTranslucentNavigation": "false",
        "android:windowDrawsSystemBarBackgrounds": "true",
    }
    for name in ("Theme.BiliClient.ImagePreview", "ImagePreviewWindow"):
        style = next(style for style in styles if style.attrib["name"] == name)
        items = {item.attrib["name"]: item.text for item in style.findall("item")}
        assert all(items.get(key) == value for key, value in expected.items())
        assert "colorPrimary" not in items and "colorSecondary" not in items
    layout = ET.parse(LAYOUT).getroot()
    ns = "{http://schemas.android.com/apk/res/android}"
    assert ns + "theme" not in layout.attrib  # Keep the Activity's selected accent.
    pager = next(node for node in layout if node.attrib.get(ns + "id") == "@+id/viewPager")
    assert pager.attrib[ns + "layout_width"] == "match_parent"
    assert pager.attrib[ns + "layout_height"] == "match_parent"
    # 悬浮工具层必须画在图片之上：XML 里后声明的兄弟节点在上层，点击优先给顶层。
    top = next(node for node in layout if node.attrib.get(ns + "id") == "@+id/top")
    assert list(layout).index(pager) < list(layout).index(top)
    assert ns + "clickable" in top.attrib and top.attrib[ns + "clickable"] == "true"
    print("PASS source/XML wiring invariants (not UI execution)", flush=True)


def check_bug8_handoff(source):
    """bug 8：放大态「滑不到下一张」的交接链路不变量（源码顺序，不是真机行为）。"""
    intercept = source.split("override fun onInterceptTouchEvent", 1)[1].split(
        "private fun interceptHorizontalEdgeDrag", 1
    )[0]
    # 放大时也必须把 DOWN 交给原生 Pager，否则下一次手势会带着上一次的指针/拖动状态。
    assert "MotionEvent.ACTION_DOWN ->" in intercept
    assert "if (super.onInterceptTouchEvent(ev)) cancelPagerDrag(ev)" in intercept
    assert "MotionEvent.ACTION_MOVE -> interceptHorizontalEdgeDrag(ev)" in intercept
    # UP/CANCEL：已接手翻页时不能提前把原生拖动清掉，必须等原生 onTouchEvent 结算完。
    up_branch = intercept.split("MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->", 1)[1]
    assert "if (!gesture.pagerDragging) cancelNativePreparation(ev)" in up_branch
    # 交接只认当前页真实显示矩形 + 视口宽度，不能退回「scale > minimumScale」的粗判据。
    handoff = source.split("private fun canHandOffToPager", 1)[1].split("private fun cancelNativePreparation", 1)[0]
    assert "page.displayRect" in handoff
    assert "page.width - page.paddingLeft - page.paddingRight" in handoff
    # 判据不能退回「放大就拒绝翻页」的旧一刀切（注释里说明历史做法不算代码）。
    assert "page.minimumScale" not in source
    assert "isCurrentPageZoomed" not in source
    # 先把「禁止父级拦截」撤掉，再交给 super.dispatchTouchEvent：
    # 之后 ViewGroup 才能走 onInterceptTouchEvent → 由它自己给 PhotoView 补发 ACTION_CANCEL。
    # 我们**不**手工给 PhotoView 发 CANCEL（重复 CANCEL 会让 PhotoView 的检测器提前复位）。
    dispatch = source.split("override fun dispatchTouchEvent", 1)[1].split("private fun touchAction", 1)[0]
    assert dispatch.index("requestDisallowInterceptTouchEvent(false)") < dispatch.index("super.dispatchTouchEvent(ev)")
    assert "dispatchToPhoto(photo, cancelMotionEvent" not in source


def check_photoview_evidence():
    """PhotoView 2.3.0 / ViewPager 的字节码证据仍在（结论的事实来源）。"""
    photo = (HERE / "photoview-bytecode.txt").read_text(encoding="utf-8")
    # ACTION_DOWN 分支（tableswitch 0 -> 52）在 58~66 就 requestDisallowInterceptTouchEvent(true)。
    down = photo.split("    Code:", 1)[1].split("  public boolean onTouch", 1)[0]
    assert "tableswitch   { // 0 to 3" in photo
    assert "android/view/ViewParent.requestDisallowInterceptTouchEvent:(Z)V" in photo
    assert "iconst_1" in photo
    # 父级拦截只在 onDrag 的边界分支放行（requestDisallowInterceptTouchEvent(false)）。
    assert photo.count("requestDisallowInterceptTouchEvent:(Z)V") >= 2
    detector = (HERE / "photoview-detector-bytecode.txt").read_text(encoding="utf-8")
    # 手势检测器：UP(1) 才结算 onFling，CANCEL(3) 只复位指针并 recycle VelocityTracker。
    assert "                     1: 258" in detector
    assert "                     3: 230" in detector
    assert "VelocityTracker.recycle" in detector


def main():
    source = SOURCE.read_text(encoding="utf-8")
    start = "// PHOTO_PAGER_LOGIC_START"
    end = "// PHOTO_PAGER_LOGIC_END"
    assert source.count(start) == 1 and source.count(end) == 1
    logic = source.split(start, 1)[1].split(end, 1)[0].split("\n", 1)[1]
    assert "android." not in logic
    check_wiring(source)
    check_bug8_handoff(source)
    check_photoview_evidence()

    env_home = REPO.parent / "env/gradle-home"
    libs = list(env_home.glob("wrapper/dists/gradle-7.4-bin/*/gradle-7.4/lib"))
    assert len(libs) == 1, f"Expected exactly one cached Gradle 7.4 lib directory, found {len(libs)}"
    lib = libs[0]
    assert list(lib.glob("kotlin-compiler-embeddable-*.jar")), "Cached K2JVMCompiler unavailable"
    stdlib = lib / "kotlin-stdlib-1.5.31.jar"
    annotations = lib / "annotations-20.1.0.jar"
    assert stdlib.is_file() and annotations.is_file()
    java = shutil.which("java")
    if java is None:
        java = str(Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-arm64")) / "bin/java")
    BUILD.mkdir(exist_ok=True)
    production = BUILD / "PhotoPagerProductionLogic.kt"
    production.write_text("package com.RobinNotBad.BiliClient.ui.widget\nimport kotlin.math.abs\n" + logic, encoding="utf-8")
    classes = BUILD / "classes"
    classes.mkdir(exist_ok=True)
    compile_classpath = os.pathsep.join((str(stdlib), str(annotations)))
    subprocess.run([
        java, "-Xmx256m", "-cp", str(lib / "*"),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "1.8", "-classpath", compile_classpath,
        "-d", str(classes), str(production), str(HERE / "PhotoPagerRegression.kt"),
    ], check=True)
    runtime_classpath = os.pathsep.join((str(classes), str(stdlib)))
    subprocess.run([
        java, "-cp", runtime_classpath,
        "com.RobinNotBad.BiliClient.ui.widget.PhotoPagerRegressionKt",
    ], check=True)


if __name__ == "__main__":
    main()
