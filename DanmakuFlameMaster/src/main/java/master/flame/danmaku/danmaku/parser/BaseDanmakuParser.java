/*
 * Copyright (C) 2013 Chen Hui <calmer91@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package master.flame.danmaku.danmaku.parser;

import android.content.SharedPreferences;


import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.danmaku.model.IDanmakus;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;

/**
 *
 */
public abstract class BaseDanmakuParser {

    public interface Listener {
        void onDanmakuAdd(BaseDanmaku danmaku);
    }

    protected IDataSource<?> mDataSource;

    protected DanmakuTimer mTimer;
    protected int mDispWidth;
    protected int mDispHeight;
    protected float mDispDensity;
    protected float mScaledDensity;

    private IDanmakus mDanmakus;

    protected IDisplayer mDisp;
    protected DanmakuContext mContext;
    protected Listener mListener;

    /** 宿主 App 的偏好设置（BiliClient 本地补丁：BiliDanmukuParser 用它读强制滚动等开关） */
    public SharedPreferences sharedPreferences = null;

    /**
     * 解析结果文本标记器（宿主 App 注入，BiliClient 本地补丁 9847ac23）。
     *
     * 用途：由宿主在"解析出弹幕之后、渲染之前"统一改写弹幕文本，
     * 例如把弹幕里的时间戳标成蓝色下划线（BiliClient #2）。
     *
     * 之所以放在解析器层：旧版 XML 弹幕是解析器内部一次性 parse 出来的，
     * 外部的增量追加接口（parseSegment）覆盖不到它；在这里统一做，
     * protobuf / XML 两条路径的行为才能保持一致。
     */
    public interface DanmakuTextMarker {
        void mark(BaseDanmaku danmaku);
    }

    private DanmakuTextMarker mTextMarker;

    public BaseDanmakuParser setTextMarker(DanmakuTextMarker marker) {
        mTextMarker = marker;
        return this;
    }

    /** 对已解析出的弹幕统一应用文本标记（幂等：已标记过的不会被重复包装） */
    protected void applyTextMarker() {
        DanmakuTextMarker marker = mTextMarker;
        if (marker == null || mDanmakus == null || mDanmakus.isEmpty()) {
            return;
        }
        final DanmakuTextMarker finalMarker = marker;
        mDanmakus.forEachSync(new IDanmakus.DefaultConsumer<BaseDanmaku>() {
            @Override
            public int accept(BaseDanmaku danmaku) {
                if (danmaku != null) {
                    try {
                        finalMarker.mark(danmaku);
                    } catch (Exception ignored) {
                    }
                }
                return ACTION_CONTINUE;
            }
        });
    }

    public BaseDanmakuParser setDisplayer(IDisplayer disp){
        mDisp = disp;
    	mDispWidth = disp.getWidth();
        mDispHeight = disp.getHeight();
        mDispDensity = disp.getDensity();
        mScaledDensity = disp.getScaledDensity();
        mContext.mDanmakuFactory.updateViewportState(mDispWidth, mDispHeight, getViewportSizeFactor());
        mContext.mDanmakuFactory.updateMaxDanmakuDuration();
        return this;
    }

    public IDisplayer getDisplayer(){
        return mDisp;
    }

    public BaseDanmakuParser setListener(Listener listener) {
        mListener = listener;
        return this;
    }
    
    /**
     * decide the speed of scroll-danmakus
     * @return
     */
    protected float getViewportSizeFactor() {
        return 1 / (mDispDensity - 0.6f);
    }
    
    public BaseDanmakuParser load(IDataSource<?> source) {
        mDataSource = source;
        return this;
    }
    
    public BaseDanmakuParser setTimer(DanmakuTimer timer) {
        mTimer = timer;
        return this;
    }

    public DanmakuTimer getTimer() {
        return mTimer;
    }
    
    public IDanmakus getDanmakus() {
        if (mDanmakus != null)
            return mDanmakus;
        mContext.mDanmakuFactory.resetDurationsData();
        mDanmakus = parse();
        applyTextMarker();
        releaseDataSource();
        mContext.mDanmakuFactory.updateMaxDanmakuDuration();
        return mDanmakus;
    }
    
    protected void releaseDataSource() {
        if(mDataSource!=null)
            mDataSource.release();
        mDataSource = null;
    }

    protected abstract IDanmakus parse();

    public void release() {
        releaseDataSource();
    }

    public BaseDanmakuParser setConfig(DanmakuContext config) {
        mContext = config;
        return this;
    }
}
