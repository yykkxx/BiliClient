package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.article.OpusInfoActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.settings.login.SpecialLoginActivity
import com.RobinNotBad.BiliClient.api.ConfInfoApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.util.ArrayList
import java.util.Objects

import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource

class TestActivity : BaseActivity() {

    var sw_wbi: SwitchMaterial? = null
    var sw_post: SwitchMaterial? = null
    var input_link: EditText? = null
    var input_data: EditText? = null
    var output: EditText? = null
    var btn_crash: MaterialCardView? = null
    var btn_request: MaterialCardView? = null
    var btn_cookies: MaterialCardView? = null
    var btn_opus: MaterialCardView? = null

    var conversation: JSONArray? = null

    @SuppressLint("MutatingSharedPrefs", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        sw_wbi = findViewById(R.id.switch_wbi)
        sw_post = findViewById(R.id.switch_post)
        input_link = findViewById(R.id.input_link)
        input_data = findViewById(R.id.input_data)
        output = findViewById(R.id.output_json)
        btn_crash = findViewById(R.id.crash)
        btn_opus = findViewById(R.id.opus)

        input_link!!.setText(SharedPreferencesUtil.getString("dev_test_link", ""))

        sw_post!!.setOnCheckedChangeListener { compoundButton, checked ->
            input_data!!.visibility = if (checked) View.VISIBLE else View.GONE
        }

        btn_request = findViewById(R.id.request)

        btn_request!!.setOnClickListener { view -> CenterThreadPool.run(Runnable {
            try {
                var url = input_link!!.text.toString()
                if (!url.startsWith("https://") && !url.startsWith("http://"))
                    url = "https://" + url

                if (sw_wbi!!.isChecked) url = ConfInfoApi.signWBI(url)

                runOnUiThread(Runnable {
                    output!!.setText("")
                    MsgUtil.showMsg("发出请求！")
                })
                val result: String
                if (sw_post!!.isChecked) {
                    val data = input_data!!.text.toString()
                    result = Objects.requireNonNull(NetWorkUtil.post(url, data).body)!!.string()
                } else {
                    result = Objects.requireNonNull(NetWorkUtil.get(url).body)!!.string()
                }

                runOnUiThread(Runnable {
                    output!!.setText(result)
                    MsgUtil.showMsg("请求成功！")
                })
            } catch (e: Exception) {
                runOnUiThread(Runnable {
                    output!!.setText(e.toString())
                    MsgUtil.showMsg("请求失败！")
                })
                e.printStackTrace()
            }
        }) }

        btn_cookies = findViewById(R.id.cookies)
        btn_cookies!!.setOnClickListener { view ->
            val intent = Intent(this, SpecialLoginActivity::class.java)
            intent.putExtra("login", false)
            startActivity(intent)
        }


        btn_opus!!.setOnClickListener { v -> startActivity(Intent(this, OpusInfoActivity::class.java).putExtra("id", 781871626480254985L)) }


        //我为什么要加这个？
        btn_crash!!.setOnClickListener { v ->
            input_data!!.visibility = View.VISIBLE
            sw_wbi!!.text = "使用R1"
            sw_post!!.visibility = View.GONE
            btn_cookies!!.visibility = View.GONE
            btn_request!!.visibility = View.GONE
            btn_opus!!.visibility = View.GONE
            val desc = findViewById<TextView>(R.id.desc)
            desc.text = getString(R.string.dev_catgirl_desc)

            CenterThreadPool.run(Runnable {
                try {
                    if (conversation == null) {
                        conversation = JSONArray()
                        val prompt = JSONObject()
                        try {
                            prompt.put("role", "system")
                            prompt.put("content", getString(R.string.dev_catgirl_prompt))
                        } catch (e: JSONException) {
                            e.printStackTrace()
                        }
                        conversation!!.put(prompt)
                        runOnUiThread(Runnable { input_link!!.setText(SharedPreferencesUtil.getString("dev_catgirl_apikey", "")) })
                        return@Runnable
                    }

                    val api_key = input_link!!.text.toString()
                    if (api_key.isEmpty()) {
                        MsgUtil.showMsg("请在链接栏填写API KEY！")
                        return@Runnable
                    } else {
                        SharedPreferencesUtil.putString("dev_catgirl_apikey", api_key)
                    }
                    val deepseekHeaders = ArrayList<String>()
                    deepseekHeaders.add("Content-Type")
                    deepseekHeaders.add("application/json")
                    deepseekHeaders.add("Authorization")
                    deepseekHeaders.add("Bearer $api_key")
                    deepseekHeaders.add("Accept")
                    deepseekHeaders.add("text/event-stream")

                    val input_str = input_data!!.text.toString()
                    if (input_str.isEmpty()) {
                        MsgUtil.showMsg("请在POST数据栏填写文字！")
                        return@Runnable
                    }
                    val input_json = JSONObject()
                    input_json.put("role", "user")
                    input_json.put("content", input_str)
                    conversation!!.put(input_json)

                    val requestJson = JSONObject()
                    val model = if (sw_wbi!!.isChecked) "reasoner" else "chat"
                    requestJson.put("model", "deepseek-$model")
                    requestJson.put("stream", true)
                    requestJson.put("messages", conversation)

                    MsgUtil.showMsg("发出请求，请等待回应！")
                    runOnUiThread(Runnable {
                        btn_crash!!.isEnabled = false
                        output!!.setText("")
                        input_link!!.clearFocus()
                        input_data!!.clearFocus()
                        output!!.clearFocus()
                        input_link!!.isEnabled = false
                        input_data!!.isEnabled = false
                        output!!.isEnabled = false
                    })

                    val response = NetWorkUtil.postJson("https://api.deepseek.com/chat/completions",
                            requestJson.toString(),
                            deepseekHeaders)
                    val body: ResponseBody? = response.body
                    if (body == null) return@Runnable
                    val source = body.source()

                    MsgUtil.showMsg("得到响应，请继续等待！")

                    var reasoning = sw_wbi!!.isChecked

                    val contentBuilder = StringBuilder()

                    while (!source.exhausted()) {
                        val line = source.readUtf8Line()
                        if (line == null) break
                        Log.d("debug-deepseek", line)

                        if (line.startsWith("data:")) {
                            val jsonData = line.substring(6).trim { it <= ' ' }
                            if ("[DONE]" == jsonData) break

                            val data = JSONObject(jsonData)
                            val choices = data.getJSONArray("choices")
                            val delta = choices.getJSONObject(0).getJSONObject("delta")

                            val deltaContent: String
                            if (!delta.isNull("reasoning_content")) {
                                deltaContent = delta.optString("reasoning_content")
                            } else if (!delta.isNull("content")) {
                                if (reasoning) {
                                    reasoning = false
                                    runOnUiThread(Runnable { output!!.append("\n\n*思考结束*\n\n") })
                                }
                                deltaContent = delta.optString("content")
                            } else deltaContent = ""

                            if (!reasoning) contentBuilder.append(deltaContent)
                            runOnUiThread(Runnable { output!!.append(deltaContent) })
                        }
                    }

                    response.close()

                    val output_str = contentBuilder.toString()
                    if (output_str.isNotEmpty()) {
                        val output_json = JSONObject()
                        output_json.put("role", "assistant")
                        output_json.put("content", output_str)
                        conversation!!.put(output_json)
                    }

                    MsgUtil.showMsg("响应结束，请查看下方文本框！")
                } catch (e: Exception) {
                    report(e)
                }

                runOnUiThread(Runnable {
                    btn_crash!!.isEnabled = true
                    output!!.isEnabled = true
                    input_link!!.isEnabled = true
                    input_data!!.isEnabled = true
                })
            })

        }
    }

    override fun onDestroy() {
        if (conversation == null)
            SharedPreferencesUtil.putString("dev_test_link", input_link!!.text.toString())
        super.onDestroy()
    }
}
