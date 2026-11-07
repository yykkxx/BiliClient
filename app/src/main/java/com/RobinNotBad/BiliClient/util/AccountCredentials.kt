package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.util.AccountStore.PORT_WEB
import org.json.JSONArray // quality-allow 账号凭据的存储格式自 v5.1.5 起就是 JSON 串（AccountStore 全用它），
//                             这里只是同一序列化设施的延伸；本项目无 Gson 依赖（见 app/build.gradle），
//                             换库会引入反射与「同一个对象两套序列化」。
import org.json.JSONException // quality-allow 同上（异常类型与 JSONObject 配套）
import org.json.JSONObject // quality-allow 同上（导出/导入的载体就是 JSON）

/**
 * 四端统一导出 / 导入凭据（v5.2.1，用户明确要求）。
 *
 * # 为什么要单独一个文件
 * 用户原话：「所有操作登录凭据的地方都放在那里（比如说导出登录凭据的时候，是四端同时导出）」。
 * 凭据的**读写数据源**仍然是 [AccountStore]（v5.1.5 起的四端口账号池，唯一真源，不动）。
 * 本文件只负责「**序列化外形**」：账号池 ↔ JSON 文本。
 *
 * 拆出来的两个直接收益：
 * 1. `AccountStore` 已经 800+ 行（detekt 的 LargeClass 会红），再塞 200 行序列化代码只会更难维护；
 * 2. 「导出格式」是**对外契约**（用户会把这份 JSON 存到别处、换机导入），
 *    它需要独立演进（版本号、字段增删），不该和「账号池内部键结构」耦合在一个文件里。
 *
 * # 旧实现的问题
 * `activity/settings/login/SpecialLoginActivity` 的「导出」只导 web：
 * `jsonObject.put("cookies", ...)` + `jsonObject.put("refresh_token", ...)`。
 * 用户装了 4 个端口的账号，换机/备份时只能救回 web 那一个 —— 另外三端要重新扫三次码。
 * 这既是体验问题，也是「四端同级」这个要求在凭据层面的直接违反。
 *
 * # 格式（本文件定义，唯一真源）
 * ```
 * {
 *   "version": 2,
 *   "exported_at": 1790000000000,
 *   "accounts": {
 *     "web":     [{"mid":123,"name":"..","face":"..","cookies":"..","csrf":"..","refresh_token":".."}],
 *     "android": [{"mid":..,"name":..,"face":..,"access_token":..,"refresh_token":..,"cookies":..}],
 *     "hd":      [...],
 *     "tv":      [...]
 *   }
 * }
 * ```
 * 每端都是**列表**（不是单条）：本项目每个端口本来就支持多账号池，
 * 只导激活账号等于把用户另外几个号扔掉。
 * 字段名与内部槽位后缀的映射由 `AccountStore.credentialFields` 统一提供（本文件不拼键名）。
 *
 * # 兼容旧格式
 * 旧版导出是**扁平**的 `{"cookies":..., "refresh_token":...}`（只有 web）。
 * [importAll] 必须认这个旧格式，否则用户手上的历史备份文件全部作废 —— 那是不可接受的。
 *
 * # 安全
 * 返回值里有完整凭据，**调用方绝不允许写进日志**（本文件内部也只打条数）。
 */
object AccountCredentials {

    /**
     * 导出文件的格式版本。
     *
     * 演进史（导入端必须**同时**认这三种）：
     * - `1`（旧扁平，无 version 键）：顶层直接 `{"cookies":..,"refresh_token":..}`，只有 web 单账号；
     * - `2`：`{"version":2,"accounts":{port:[{mid,name,face,cookies,...}]}}`，每端多账号，
     *   但每个账号只有**一份**凭据；
     * - `3`（v5.3.0）：在 v2 基础上给每个账号加 `credentials[]` + `active_credential`，
     *   顶层 6 字段保留为「激活凭据的镜像」——所以 v3 文件被 v2 版本的 App 读也能正常导入。
     */
    const val EXPORT_VERSION = 3

    private const val TAG = "account-creds"

    /**
     * 四端**同时**导出全部账号凭据（含每个账号的全部多份凭据，v5.3.0 格式 v3）。
     *
     * @return JSON 字符串。没有任何账号时返回 `{"version":3,"accounts":{}}` 而不是空串，
     *         这样调用方可以无脑 put 进 JSONObject 而不用先判空。
     *
     * 注意：**不要**把这个返回值打日志（含 SESSDATA / access_token / refresh_token）。
     */
    @JvmStatic
    fun exportAll(): String {
        val accounts = JSONObject()
        var total = 0
        var credentialsTotal = 0
        for (port in AccountStore.allPorts()) {
            val list = JSONArray()
            for (mid in AccountStore.midList(port)) {
                list.put(entryOf(port, mid))
                total += 1
                credentialsTotal += AccountCredentialStore.credentialCount(port, mid)
            }
            accounts.put(port, list)
        }
        val root = JSONObject()
        root.put("version", EXPORT_VERSION)
        root.put("exported_at", System.currentTimeMillis())
        root.put("accounts", accounts)
        Logu.d(TAG, "导出了 $total 个账号 / $credentialsTotal 份凭据（跨 "
                + AccountStore.allPorts().size + " 个端口）")
        return root.toString()
    }

    /**
     * 组装一个账号的导出条目。
     *
     * v5.3.0 新增两个键：
     * - `credentials`：该账号的**全部**凭据（每份含 id/label/saved_at + 6 个字段）；
     * - `active_credential`：当前激活那份的 id。
     *
     * 顶层的 6 个字段**保留不动**（= 激活凭据的镜像）。理由：
     * 1. v2 的读取方（旧版本 App、用户自己的脚本）继续能读；
     * 2. 导入时若 `credentials` 缺失/损坏，靠顶层字段仍能救回一份；
     * 3. 导出体积增加有限（每账号多几份），换来的是「换机后凭据不丢」。
     */
    private fun entryOf(port: String, mid: Long): JSONObject {
        val entry = JSONObject()
        entry.put("mid", mid)
        entry.put("name", AccountStore.nameOf(port, mid))
        entry.put("face", AccountStore.faceOf(port, mid))
        for ((field, value) in AccountStore.credentialFields(port, mid)) {
            entry.put(field, value)
        }
        // 多凭据（v3）：字段名与顶层同名同义，导入时直接复用同一套映射
        val credList = JSONArray()
        for (cred in AccountCredentialStore.credentials(port, mid)) {
            val item = JSONObject()
            item.put("id", cred.id)
            item.put("label", cred.label)
            item.put("saved_at", cred.savedAt)
            for ((field, value) in AccountCredentialStore.fieldsOf(port, mid, cred.id)) {
                item.put(field, value)
            }
            credList.put(item)
        }
        if (credList.length() > 0) entry.put("credentials", credList)
        val activeId = AccountCredentialStore.activeId(port, mid)
        if (activeId.isNotEmpty()) entry.put("active_credential", activeId)
        return entry
    }

    /**
     * 四端同时导入。**认新旧两种格式**：
     * - 新格式（`version` + `accounts`）：逐端逐账号写进账号槽，每个端口把第一条设为激活；
     * - 旧扁平格式（顶层直接有 `cookies`）：当成 web 单账号导入。
     *
     * @return 每端导入成功的账号数量（0 = 该端没有导入任何东西）。
     *         返回计数而不是布尔，是因为「导入了 3 个成功、1 个坏」这种情况必须让用户看见。
     *
     * 不做的事：
     * - **不校验凭据是否真的有效**（那要联网，导入时不该发 4×N 个请求）。
     *   有效性由用户下次操作时自然暴露。
     * - **不删除已有的其它账号**：导入是**合并**语义。用户换机导入后不该丢掉
     *   这台机上本来就有的号。
     */
    @JvmStatic
    fun importAll(json: String): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        val root = parseImportJson(json) ?: return result
        val accounts = root.optJSONObject("accounts")
        if (accounts == null) {
            // 旧扁平格式：{"cookies": "...", "refresh_token": "..."}，只有 web 一个号
            if (importLegacyFlat(root)) result[PORT_WEB] = 1
            return result
        }
        for (port in AccountStore.allPorts()) {
            val list = accounts.optJSONArray(port) ?: continue
            val count = importPort(port, list)
            if (count > 0) {
                result[port] = count
                activateFirstOf(port, list)
            }
        }
        NetWorkUtil.refreshHeaders()
        Logu.d(TAG, "导入结果（每端账号数）：$result")
        return result
    }

    /** 解析导入 JSON；不是合法 JSON 时返回 null 并记一行日志（不抛给调用方） */
    private fun parseImportJson(json: String): JSONObject? {
        return try {
            JSONObject(json)
        } catch (ignored: JSONException) {
            Logu.e(TAG, "导入失败：不是合法 JSON")
            null
        }
    }

    /**
     * 旧扁平格式（v5.2.0 及更早导出的文件）→ web 单账号。
     *
     * @return 是否真的导入了
     */
    private fun importLegacyFlat(root: JSONObject): Boolean {
        val cookies = root.optString("cookies", "")
        val mid = NetWorkUtil.getInfoFromCookie("DedeUserID", cookies).trim().toLongOrNull() ?: 0L
        if (mid <= 0L) return false
        AccountStore.loginWeb(
            mid, cookies,
            NetWorkUtil.getInfoFromCookie("bili_jct", cookies),
            root.optString("refresh_token", ""),
            root.optString("name", "").ifEmpty { null },
            root.optString("face", "").ifEmpty { null }
        )
        return true
    }

    /** 导入一个端口的全部账号，返回成功条数（只统计真正写进去的） */
    private fun importPort(port: String, list: JSONArray): Int {
        var imported = 0
        for (i in 0 until list.length()) {
            if (importOne(port, list.optJSONObject(i))) imported += 1
        }
        return imported
    }

    /**
     * 导入单个账号条目。
     *
     * v5.3.0：除了写账号（走 loginWeb/loginApp，等于「激活一份」），
     * 还会把条目里的 `credentials[]` 全部补进凭据层 —— 见 [importCredentialList]。
     *
     * @return 是否真的写入了（mid 非法、或一条凭据都没有的条目不写）
     */
    private fun importOne(port: String, entry: JSONObject?): Boolean {
        if (entry == null) return false
        val mid = entry.optLong("mid", 0L)
        if (mid <= 0L) return false
        val cookies = entry.optString("cookies", "")
        val token = entry.optString("access_token", "")
        // 一条凭据都没有的条目是废数据（例如导出时该账号只剩昵称），不写进账号池
        if (cookies.isEmpty() && token.isEmpty()) return false
        val name = entry.optString("name", "").ifEmpty { null }
        val face = entry.optString("face", "").ifEmpty { null }
        val refresh = entry.optString("refresh_token", "")
        if (port == PORT_WEB) {
            AccountStore.loginWeb(mid, cookies, entry.optString("csrf", ""), refresh, name, face)
        } else {
            AccountStore.loginApp(port, mid, token, refresh, cookies, name, face)
        }
        // 再补齐这个账号的多份凭据（v3 才有）。顺序很重要：必须在上面的 login 之后，
        // 因为 login 会先落一份「本次登录」凭据，这里再补历史的那几份。
        importCredentialList(port, mid, entry.optJSONArray("credentials"), entry.optString("active_credential", ""))
        return true
    }

    /**
     * 把 v3 条目里的 `credentials[]` 补进凭据层（v5.3.0）。
     *
     * # 为什么不去重
     * 导入是**合并**语义：本机可能已经有同名/同内容的凭据。按内容比对会引入
     * 「怎么算相同」的判定（长度？前 8 位？完整字符串？）——每一种都可能误合并掉
     * 用户真正想保留的那份。凭据是很小的数据，宁可多一条让用户自己删。
     *
     * # 激活哪一份
     * `active_credential` 指的那份如果导入成功，设为激活。找不到就保持 login 落下的那份
     * （那是本机可用的最新凭据，比随便挑一条更安全）。
     */
    private fun importCredentialList(port: String, mid: Long, list: JSONArray?, activeId: String) {
        if (list == null || list.length() == 0) return
        var activeImportedId: String? = null
        var imported = 0
        for (i in 0 until list.length()) {
            // 一条 null 条目直接当成「没有可导入内容」交给 importOneCredential 处理，
            // 循环体里只留一次判空与一次调用（避免多个 continue，detekt 会拦）
            val item = list.optJSONObject(i)
            val cid = if (item == null) null else importOneCredential(port, mid, item)
            if (cid == null) continue
            imported += 1
            if (activeId.isNotEmpty() && item != null && item.optString("id", "") == activeId) {
                activeImportedId = cid
            }
        }
        // 有明确指定的激活凭据就切过去；切换会顺带把账号槽与激活槽位一起更新
        if (activeImportedId != null) AccountCredentialStore.setActive(port, mid, activeImportedId)
        if (imported > 0) Logu.d(TAG, "导入凭据 port=" + port + " mid=" + mid + " 份数=" + imported)
    }

    /**
     * 导入凭据数组里的**一条**。
     *
     * 从 [importCredentialList] 拆出来是为了控制圈复杂度（detekt CyclomaticComplexMethod 阈值 15）
     * 与「一个循环里多个 continue」（LoopWithTooManyJumpStatements）：
     * 「这条要不要导、怎么导」是一个独立的判断，和「遍历整个数组」本来就不是一件事。
     *
     * @return 新凭据 id；这条是废数据（没有任何凭据内容）时返回 null
     */
    private fun importOneCredential(port: String, mid: Long, item: JSONObject): String? {
        val fields = HashMap<String, String>()
        for (field in CREDENTIAL_FIELDS) {
            val value = item.optString(field, "")
            if (value.isNotEmpty()) fields[field] = value
        }
        // 既没 cookie 也没 token 的条目是废数据（例如导出时该账号只剩昵称），不写进凭据层
        if (fields["cookies"].isNullOrEmpty() && fields["access_token"].isNullOrEmpty()) return null
        return AccountCredentialStore.importCredential(
            port, mid, fields,
            item.optString("label", ""),
            item.optLong("saved_at", 0L)
        )
    }

    /** 凭据的 6 个字段名（与 [AccountStore.credentialFields] / [AccountCredentialStore] 同一套） */
    private val CREDENTIAL_FIELDS = listOf(
        "cookies", "csrf", "access_token", "refresh_token", "name", "face"
    )

    /**
     * 把该端口**第一个有 mid 的条目**设为激活。
     *
     * 为什么必须有激活账号：导入完如果账号池里有号但激活槽是空的，用户打开页面还是
     * 「未登录」，会以为导入失败了。为什么是「第一个」：导入顺序 = 导出顺序 = 账号池顺序，
     * 第一条通常就是用户当时正在用的那个号；即便不是，进账号管理页换一下即可。
     */
    private fun activateFirstOf(port: String, list: JSONArray) {
        for (i in 0 until list.length()) {
            val mid = list.optJSONObject(i)?.optLong("mid", 0L) ?: 0L
            if (mid > 0L) {
                AccountStore.setActive(port, mid)
                return
            }
        }
    }
}
