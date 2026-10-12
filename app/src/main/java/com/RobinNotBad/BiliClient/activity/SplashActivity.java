package com.RobinNotBad.BiliClient.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.widget.TextView;

import com.RobinNotBad.BiliClient.BiliTerminal;
import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.activity.settings.setup.SetupUIActivity;
import com.RobinNotBad.BiliClient.activity.video.RecommendActivity;
import com.RobinNotBad.BiliClient.activity.video.local.LocalListActivity;
import com.RobinNotBad.BiliClient.api.AppInfoApi;
import com.RobinNotBad.BiliClient.api.CookieRefreshApi;
import com.RobinNotBad.BiliClient.api.CookiesApi;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

//启动页面
//一切的一切的开始

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends Activity {

    private TextView splashTextView;
    private String splashText = "欢迎使用\n哔哩终端";
    /** 防止重复收尾（IO 回调与 UI 回调可能同时到达） */
    private final java.util.concurrent.atomic.AtomicBoolean splashFinished =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(BiliTerminal.getFitDisplayContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_BiliClient);
        setContentView(R.layout.activity_splash);
        Log.e("debug", "进入应用");

        splashTextView = findViewById(R.id.splashText);
        splashText = SharedPreferencesUtil.getString("ui_splashtext", "欢迎使用\n哔哩终端");

        // 开屏动画已关闭：不再逐字打出文字（原实现每 100ms 触发一次 UI 更新，
        // 在启动这段最吃 IO/CPU 的时间里纯属额外负担），直接整段显示。
        splashTextView.setText(splashText);

        CenterThreadPool.run(() -> {

            //FileUtil.clearCache(this);  //先清个缓存（为了防止占用过大）
            //不需要了，我把大部分图片的硬盘缓存都关闭了，只有表情包保留，这样既可以缩减缓存占用又能在一定程度上减少流量消耗

            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.setup, false)) {//判断是否设置完成
                try {
                    // 未登录时请求bilibili.com
                    if (SharedPreferencesUtil.getLong("mid", 0) != 0) {
                        checkCookieRefresh();
                    }

                    CookiesApi.checkCookies();

                    String firstActivity = null;
                    String sortConf = SharedPreferencesUtil.getString(SharedPreferencesUtil.MENU_SORT, "");
                    if (!TextUtils.isEmpty(sortConf)) {
                        String[] splitName = sortConf.split(";");
                        for (String name : splitName) {
                            if (!MenuActivity.btnNames.containsKey(name)) {
                                for (Map.Entry<String, Pair<String, Class<? extends InstanceActivity>>> entry : MenuActivity.btnNames.entrySet()) {
                                    firstActivity = entry.getKey();
                                    break;
                                }
                            } else {
                                firstActivity = name;
                            }
                            break;
                        }
                    } else {
                        for (Map.Entry<String, Pair<String, Class<? extends InstanceActivity>>> entry : MenuActivity.btnNames.entrySet()) {
                            firstActivity = entry.getKey();
                            break;
                        }
                    }

                    Class<? extends InstanceActivity> activityClass = Objects.requireNonNull(MenuActivity.btnNames.get(firstActivity)).second;

                    Intent intent = new Intent();
                    intent.setClass(SplashActivity.this, (activityClass != null ? activityClass : RecommendActivity.class));
                    intent.putExtra("from", firstActivity);

                    interruptSplash();

                    // 动画已关闭：不再等待文字动画，立即进入目标页面（缩短启动耗时）
                    startActivity(intent);
                    CenterThreadPool.run(() -> AppInfoApi.check(SplashActivity.this));
                    finish();

                } catch (IOException e) {
                    runOnUiThread(() -> {
                        MsgUtil.err(e);
                        interruptSplash();
                        splashTextView.setText("网络错误");
                        if (SharedPreferencesUtil.getBoolean("setup", false)) {
                            splashTextView.postDelayed(() -> {
                                Intent intent = new Intent();
                                intent.setClass(SplashActivity.this, LocalListActivity.class);
                                startActivity(intent);
                                finish();
                            }, 150);
                        }
                    });
                } catch (JSONException e) {
                    runOnUiThread(() -> MsgUtil.err(e));
                    Intent intent = new Intent();
                    intent.setClass(SplashActivity.this, LocalListActivity.class);
                    startActivity(intent);
                    interruptSplash();
                    finish();
                }
            } else {
                Intent intent = new Intent();
                intent.setClass(SplashActivity.this, SetupUIActivity.class);   //没登录，去初次设置
                startActivity(intent);
                interruptSplash();
                finish();
            }

        });
    }

    private void checkCookieRefresh() throws IOException {
        try {
            JSONObject cookieInfo = CookieRefreshApi.cookieInfo();
            if (cookieInfo.optBoolean("refresh")) {
                Log.e("Cookies", "需要刷新");
                if (!Objects.equals(SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, ""), "")) {
                    String correspondPath = CookieRefreshApi.getCorrespondPath(cookieInfo.getLong("timestamp"));
                    Log.e("CorrespondPath", correspondPath);
                    String refreshCsrf = CookieRefreshApi.getRefreshCsrf(correspondPath);
                    Log.e("RefreshCsrf", refreshCsrf);
                    if (CookieRefreshApi.refreshCookie(refreshCsrf)) {
                        MsgUtil.showMsg("Cookies已刷新");
                    } else {
                        MsgUtil.showMsgLong("登录信息过期，请重新登录！");
                        resetLogin();
                    }
                }
            }
        } catch (JSONException e) {
            MsgUtil.showMsgLong("登录信息过期，请重新登录！");
            resetLogin();
        }
    }

    private void resetLogin() {
        SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, 0L);
        SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, "");
        SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, "");
        SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, "");
        NetWorkUtil.refreshHeaders();
    }

    /**
     * 结束开屏（动画已关闭，这里只保证文字完整显示，可重复调用）。
     * 原先这里要 cancel 打字机定时器，现在没有定时器了，只做幂等保护。
     */
    private void interruptSplash() {
        if (!splashFinished.compareAndSet(false, true))
            return;
        runOnUiThread(() -> splashTextView.setText(splashText));
    }
}