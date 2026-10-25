/*
 * Bilibili Protobuf Danmaku Parser
 * 用于解析新版 protobuf 格式的弹幕数据
 */

package master.flame.danmaku.danmaku.parser.android;

import android.graphics.Color;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuFactory;
import master.flame.danmaku.danmaku.model.android.Danmakus;
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser;
import master.flame.danmaku.danmaku.util.DanmakuUtils;

/**
 * Bilibili 新版 Protobuf 格式弹幕解析器
 *
 * 说明（性能优化）：
 * 原实现每解析一条弹幕都会调用一次 Class.getField(...)（一次弹幕 5~6 次反射查找），
 * 一个几分钟的视频就有上万次反射查找，这在低端机上非常耗时且发热。
 * 这里改为按 Class 缓存 Field 对象，只做一次查找，后续直接 get/getInt，
 * 解析耗时大幅下降，行为与原实现完全一致。
 *
 * 同时新增增量解析接口（parseSegment / parseElem），
 * 用于网络波动时“分段补拉”：先渲染已拿到的分段，缺失分段稍后补上时直接追加，
 * 避免因某一段请求失败导致整段弹幕永久丢失。
 */
public class BiliProtobufDanmakuParser extends BaseDanmakuParser {

    /** 反射字段缓存：Class -> (字段名 -> Field)，避免重复 getField 查找 */
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 弹幕模型字段读取器（由应用层注入）。
     *
     * 为什么必须要有它：本解析器原先完全依赖 `Class.getField("elems"/"content"/...)`
     * 按**字段名**反射读取应用层模型（DmSegMobileReply / DanmakuElem）。开启 R8 混淆后
     * 字段名会被改写成 a/b/c…，getField 直接返回 null，于是**全部 protobuf 弹幕被静默丢弃**
     * ——v5.0.2-alpha3 用户反馈的「正常视频播放器弹幕不显示」就是这个原因。
     *
     * 注入读取器后字段访问在应用层是编译期解析的普通属性访问，与混淆无关；
     * 未注入时仍保留原来的反射路径作兜底（debug 包/未混淆场景行为不变）。
     */
    public interface ElemReader {
        /** 取一个分段里的弹幕列表 */
        List<?> elems(Object segment);

        int progress(Object elem);

        int mode(Object elem);

        int fontsize(Object elem);

        int color(Object elem);

        String content(Object elem);
    }

    private static volatile ElemReader sElemReader;

    /** 由应用层注入字段读取器（全局一次即可，重复调用覆盖旧值） */
    public static void setElemReader(ElemReader reader) {
        sElemReader = reader;
    }

    public static ElemReader getElemReader() {
        return sElemReader;
    }

    private float mDispScaleX;
    private float mDispScaleY;
    private List<?> mDanmakuSegments; // 存储 DmSegMobileReply 列表

    public BiliProtobufDanmakuParser() {
    }

    /**
     * 设置弹幕分段数据
     *
     * @param segments DmSegMobileReply 列表
     */
    public void setDanmakuSegments(List<?> segments) {
        this.mDanmakuSegments = segments;
    }

    /**
     * 追加一个分段（用于网络恢复后补拉缺失分段，增量渲染）
     *
     * @param segment DmSegMobileReply 对象
     * @return 该分段解析出的弹幕集合，永不为 null
     */
    public Danmakus parseSegment(Object segment) {
        Danmakus result = new Danmakus();
        if (segment == null) {
            return result;
        }
        try {
            List<?> elems = elemsOf(segment);
            if (elems == null) {
                return result;
            }
            int index = 0;
            for (Object elemObj : elems) {
                BaseDanmaku danmaku = parseElem(elemObj, index++);
                if (danmaku != null) {
                    result.addItem(danmaku);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    @Override
    public Danmakus parse() {
        Danmakus result = new Danmakus();
        if (mDanmakuSegments == null || mDanmakuSegments.isEmpty()) {
            return result;
        }

        int index = 0;
        // 遍历所有分段
        for (Object segmentObj : mDanmakuSegments) {
            try {
                List<?> elems = elemsOf(segmentObj);
                if (elems == null) {
                    continue;
                }
                // 遍历该分段中的所有弹幕
                for (Object elemObj : elems) {
                    BaseDanmaku danmaku = parseElem(elemObj, index++);
                    if (danmaku != null) {
                        result.addItem(danmaku);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return result;
    }

    /**
     * 解析单条弹幕元素
     *
     * @param elemObj DanmakuElem 对象
     * @param index   弹幕索引
     * @return BaseDanmaku 对象，解析失败或该类型不显示时返回 null
     */
    public BaseDanmaku parseElem(Object elemObj, int index) {
        if (elemObj == null || mContext == null) {
            return null;
        }
        try {
            Class<?> elemClass = elemObj.getClass();

            // 优先走应用层注入的编译期访问器；只有没注入时才退回按字段名反射
            // （R8 混淆会改字段名，反射路径在 release 包里必然取不到值）
            ElemReader reader = sElemReader;
            int progress = reader != null
                    ? reader.progress(elemObj)
                    : getIntField(elemClass, elemObj, "progress", 0);
            int mode = reader != null
                    ? reader.mode(elemObj)
                    : getIntField(elemClass, elemObj, "mode", 1);
            int fontsize = reader != null
                    ? reader.fontsize(elemObj)
                    : getIntField(elemClass, elemObj, "fontsize", 25);
            int color = reader != null
                    ? reader.color(elemObj)
                    : getIntField(elemClass, elemObj, "color", Color.WHITE);
            String content;
            if (reader != null) {
                content = reader.content(elemObj);
            } else {
                Object contentValue = getFieldValue(elemClass, elemObj, "content");
                content = contentValue == null ? null : String.valueOf(contentValue);
            }

            if (content == null || content.isEmpty()) {
                return null;
            }

            if (mode == 7 || mode == 8) {
                return null;
            }

            if (sharedPreferences != null && mode != 7 && mode != 8
                    && sharedPreferences.getBoolean("player_danmaku_forceR2L", false)) {
                mode = 1;
            }

            // 字号缺失时兜底为标准字号，避免解析异常时弹幕“隐身”
            if (fontsize <= 0) {
                fontsize = 25;
            }

            BaseDanmaku item = mContext.mDanmakuFactory.createDanmaku(mode, mContext);
            if (item != null) {
                item.setTime(progress);

                DanmakuUtils.fillText(item, content);
                item.index = index;

                item.textSize = fontsize * (mDispDensity - 0.6f);

                item.textColor = color | 0xFF000000;
                item.textShadowColor = (color | 0xFF000000) <= Color.BLACK ? Color.WHITE : Color.BLACK;

                item.setTimer(mTimer);
            }

            return item;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** 取分段里的弹幕列表：优先注入的读取器，其次按字段名反射兜底 */
    private static List<?> elemsOf(Object segment) {
        if (segment == null) {
            return null;
        }
        ElemReader reader = sElemReader;
        if (reader != null) {
            try {
                List<?> list = reader.elems(segment);
                if (list != null) {
                    return list;
                }
            } catch (Exception ignored) {
            }
        }
        Field field = fieldOf(segment.getClass(), "elems");
        if (field == null) {
            return null;
        }
        try {
            Object value = field.get(segment);
            return value instanceof List ? (List<?>) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Field fieldOf(Class<?> clazz, String name) {
        Map<String, Field> cache = FIELD_CACHE.get(clazz);
        if (cache == null) {
            cache = new ConcurrentHashMap<>();
            FIELD_CACHE.put(clazz, cache);
        }
        Field field = cache.get(name);
        if (field != null) {
            return field;
        }
        try {
            field = clazz.getField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
        cache.put(name, field);
        return field;
    }

    private static int getIntField(Class<?> clazz, Object target, String name, int def) {
        Field field = fieldOf(clazz, name);
        if (field == null) {
            return def;
        }
        try {
            return field.getInt(target);
        } catch (Exception e) {
            return def;
        }
    }

    private static Object getFieldValue(Class<?> clazz, Object target, String name) {
        Field field = fieldOf(clazz, name);
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public BaseDanmakuParser setDisplayer(IDisplayer disp) {
        super.setDisplayer(disp);
        mDispScaleX = mDispWidth / DanmakuFactory.BILI_PLAYER_WIDTH;
        mDispScaleY = mDispHeight / DanmakuFactory.BILI_PLAYER_HEIGHT;
        return this;
    }
}
