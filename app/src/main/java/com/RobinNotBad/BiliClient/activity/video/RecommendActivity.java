package com.RobinNotBad.BiliClient.activity.video;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.RefreshMainActivity;
import com.RobinNotBad.BiliClient.activity.settings.setup.SetupUIActivity;
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter;
import com.RobinNotBad.BiliClient.api.AppInfoApi;
import com.RobinNotBad.BiliClient.api.CookieRefreshApi;
import com.RobinNotBad.BiliClient.api.CookiesApi;
import com.RobinNotBad.BiliClient.api.RecommendApi;
import com.RobinNotBad.BiliClient.helper.TutorialHelper;
import com.RobinNotBad.BiliClient.model.VideoCard;
import com.RobinNotBad.BiliClient.util.CenterThreadPool;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.NetWorkUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

//推荐页面
//2023-07-13
//现已作为启动页（原 SplashActivity 已删除）：打开应用直接进入推荐，
//原开屏页的 Cookie 刷新检查 / 初次设置跳转 / 版本检查等启动逻辑搬到这里。

public class RecommendActivity extends RefreshMainActivity {

    private List<VideoCard> videoCardList;
    private VideoCardAdapter videoCardAdapter;
    private boolean firstRefresh = true;
    private boolean startWorkDone = false;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setMenuClick();
        Log.e("debug", "进入推荐页");

        setOnRefreshListener(this::refreshRecommend);
        setOnLoadMoreListener(page -> addRecommend());

        setPageName("推荐");

        recyclerView.setHasFixedSize(true);

        TutorialHelper.showTutorialList(this, R.array.tutorial_recommend, 0);

        //原 SplashActivity 的启动收尾工作（Cookie 检查等），异步执行，不阻塞推荐页加载
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.setup, false)) {
            CenterThreadPool.run(this::doStartWork);
        } else {
            //没完成初次设置，先去设置页
            Intent intent = new Intent();
            intent.setClass(this, SetupUIActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        refreshRecommend();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //从设置页完成设置返回时补做启动工作
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.setup, false) && !startWorkDone) {
            CenterThreadPool.run(this::doStartWork);
        }
    }

    /**
     * 原 SplashActivity 中的启动逻辑：Cookie 刷新检查 + 应用版本检查
     */
    private void doStartWork() {
        startWorkDone = true;
        try {
            // 已登录时检查 Cookie 是否需要刷新
            if (SharedPreferencesUtil.getLong("mid", 0) != 0) {
                checkCookieRefresh();
            }
            CookiesApi.checkCookies();
        } catch (Exception e) {
            MsgUtil.err(e);
        }
        CenterThreadPool.run(() -> AppInfoApi.check(this));
    }

    private void checkCookieRefresh() {
        try {
            org.json.JSONObject cookieInfo = CookieRefreshApi.cookieInfo();
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
        } catch (Exception e) {
            MsgUtil.err(e);
        }
    }

    private void resetLogin() {
        SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, 0L);
        SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, "");
        SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, "");
        SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, "");
        NetWorkUtil.refreshHeaders();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refreshRecommend() {
        Log.e("debug", "刷新");
        if (firstRefresh) {
            videoCardList = new ArrayList<>();
        } else {
            int last = videoCardList.size();
            videoCardList.clear();
            videoCardAdapter.notifyItemRangeRemoved(0, last);
        }

        addRecommend();
    }

    private void addRecommend() {
        Log.e("debug", "加载下一页");
        CenterThreadPool.run(() -> {
            try {
                List<VideoCard> list = new ArrayList<>();
                RecommendApi.getRecommend(list);
                setRefreshing(false);

                runOnUiThread(() -> {
                    videoCardList.addAll(list);
                    if (firstRefresh) {
                        firstRefresh = false;
                        videoCardAdapter = new VideoCardAdapter(this, videoCardList);
                        setAdapter(videoCardAdapter);
                    } else {
                        videoCardAdapter.notifyItemRangeInserted(videoCardList.size() - list.size(), list.size());
                    }
                });
            } catch (Exception e) {
                loadFail(e);
            }
        });
    }
}
