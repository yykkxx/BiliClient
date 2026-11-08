package com.RobinNotBad.BiliClient.util

/**
 * 「同一个账号在同一端口下并存多份凭据」的存储层（v5.3.0 用户需求）。
 *
 * # 用户原话与它意味着什么
 * 「支持 web 端口（其他端口也是）存储**同个账号的多个凭据**（可供切换）」
 *
 * 在此之前，[AccountStore] 的模型是「(端口, mid) → **一套**凭据槽」：
 * `account_mgmt_<port>_<mid>_{cookies,csrf,access_token,refresh_token,name,face}`。
 * 于是同一个号在同一端口只能有一份凭据 —— 再登录一次就把旧的覆盖掉，
 * 而用户实际会遇到的情况是**同一个 B 站账号有多份有效凭据**：
 * - web：多个浏览器/设备各自扫码，SESSDATA 各不相同，彼此的续期时间线也不同；
 * - APP 三端：同一个号可能既有「TV 密钥签发」的 token、又有历史遗留的另一份；
 * - 切换设备后想保留旧凭据做对比/回退。
 * 旧模型下这些只能留一份，切回去就要重新扫码。
 *
 * # 为什么单开一个文件，而不是塞进 AccountStore
 * 1. `AccountStore` 已经 815 行、且被 detekt 的 `LargeClass` 盯着（已登记 baseline）。
 *    再加「凭据列表 + 迁移 + 指纹」会让它更难维护，也会撞 baseline 签名匹配的坑。
 * 2. 职责本就不同：`AccountStore` 管「有哪些账号、谁是激活账号」，
 *    本文件管「某个账号有几份凭据、用哪一份」。
 * 3. 本文件**不发起任何网络请求**、**不打印任何凭据**，是纯存储 + 纯计算，
 *    可以单独审。
 *
 * # 键结构（本文件是唯一定义处）
 * ```
 * account_cred_<port>_<mid>_list           = "cid1,cid2,..."     有序、去重
 * account_cred_<port>_<mid>_active         = "cid1"              当前激活的凭据
 * account_cred_<port>_<mid>_<cid>_cookies          ┐
 * account_cred_<port>_<mid>_<cid>_csrf             │
 * account_cred_<port>_<mid>_<cid>_access_token     ├ 与账号槽同名同义的 6 个字段
 * account_cred_<port>_<mid>_<cid>_refresh_token    │
 * account_cred_<port>_<mid>_<cid>_name             │
 * account_cred_<port>_<mid>_<cid>_face             ┘
 * account_cred_<port>_<mid>_<cid>_label      = "2026-11-07 18:30 登录"
 * account_cred_<port>_<mid>_<cid>_saved_at   = 1794050000000
 * ```
 *
 * # 与「账号槽 / 激活槽位」的关系（本设计最关键的一条，改动前务必读）
 * - **账号槽** `account_mgmt_<port>_<mid>_*` 的语义**保持不变**：
 *   它始终是「该账号当前**激活**的那份凭据的镜像」。`AccountStore.credentialFields`
 *   继续读它，导出逻辑零改动。
 * - **激活槽位**（web 的 `cookies/mid/csrf`、APP 的 `app_rcmd_<id>_*`）也**完全不动**，
 *   所以 `AccountManager` / `NetWorkUtil.webHeaders` / `AppRcmdApi.getAccessToken`
 *   这些读取路径一行都不用改 —— 这是 v5.1.5 定下的红线。
 * - 本文件只在 [AccountStore] 已经完成「账号级」快照/写入之后，再补一步「凭据级」快照/写入。
 *   换句话说：**账号槽是真源，凭据槽是它的历史版本堆**。
 *
 * # 老数据零迁移成本
 * 老用户只有账号槽、没有任何 `account_cred_*` 键。第一次访问某账号的凭据时，
 * [ensureMigrated] 会**惰性**用账号槽当前的值生成一条 `C_DEFAULT`
 * （label「默认凭据」）。惰性而不是启动时全量迁移：启动路径越薄越好，
 * 而且绝大多数用户只有一个账号，没必要为从没打开过账号管理页的人写一堆键。
 *
 * # 安全
 * 本文件**绝不打印凭据**。[fingerprintOf] 是唯一对外暴露的展示形态，
 * 它只输出「字段名 + 长度」，连前后缀都不给 —— 手表屏幕很小，
 * 前后缀既帮不上辨认，还会让截图泄露时留下可用信息。
 */
object AccountCredentialStore {

    private const val TAG = "account-cred"

    // ==================== 键前缀（本文件唯一定义处） ====================

    private const val PREFIX = "account_cred_"

    private const val SUFFIX_LIST = "list"
    private const val SUFFIX_ACTIVE = "active"
    private const val SUFFIX_LABEL = "label"
    private const val SUFFIX_SAVED_AT = "saved_at"

    /** 凭据的 6 个字段（与 [AccountStore] 的账号槽同后缀、同含义） */
    private const val F_COOKIES = "cookies"
    private const val F_CSRF = "csrf"
    private const val F_ACCESS_TOKEN = "access_token"
    private const val F_REFRESH_TOKEN = "refresh_token"
    private const val F_NAME = "name"
    private const val F_FACE = "face"

    private val ALL_FIELDS = listOf(
        F_COOKIES, F_CSRF, F_ACCESS_TOKEN, F_REFRESH_TOKEN, F_NAME, F_FACE
    )

    /** 迁移/首次保存时使用的固定 id，保证幂等（反复调用不会造出多条默认凭据） */
    const val C_DEFAULT = "c_default"

    /** 一条凭据的展示投影（**不含完整凭据**，可以安全地传给 UI / 日志） */
    class CredentialEntry(
        /** 端口（web / android / hd / tv） */
        @JvmField val port: String,
        /** 账号 mid */
        @JvmField val mid: Long,
        /** 凭据 id（稳定，切换靠它定位） */
        @JvmField val id: String,
        /** 用户可改的显示名 */
        @JvmField val label: String,
        /** 保存时刻（墙钟毫秒），仅用于排序与展示 */
        @JvmField val savedAt: Long,
        /** 简要指纹（字段名 + 长度，**永不含凭据内容**） */
        @JvmField val fingerprint: String,
        /** 是否是当前激活的那一份 */
        @JvmField val active: Boolean
    )

    // ==================== 键拼装（集中在此，别处不要拼） ====================

    private fun baseKey(port: String, mid: Long): String = PREFIX + port + "_" + mid + "_"

    private fun listKey(port: String, mid: Long): String = baseKey(port, mid) + SUFFIX_LIST

    private fun activeKey(port: String, mid: Long): String = baseKey(port, mid) + SUFFIX_ACTIVE

    private fun fieldKey(port: String, mid: Long, cid: String, field: String): String =
        baseKey(port, mid) + cid + "_" + field

    // ==================== 读 ====================

    /**
     * 该账号的全部凭据（按保存顺序）。
     *
     * 会在返回前做一次惰性迁移：老账号第一次调用时自动生成 `C_DEFAULT`，
     * 所以调用方**永远不需要**判「这个账号有没有凭据」——返回值至少 1 条
     * （除非该账号连账号槽都是空的，那说明它本来就不该出现在账号列表里）。
     */
    @JvmStatic
    @JvmSuppressWildcards
    fun credentials(port: String, mid: Long): List<CredentialEntry> {
        if (mid <= 0L) return emptyList()
        ensureMigrated(port, mid)
        val activeId = activeIdOf(port, mid)
        val result = ArrayList<CredentialEntry>()
        for (cid in idsOf(port, mid)) {
            result.add(
                CredentialEntry(
                    port, mid, cid,
                    labelOf(port, mid, cid),
                    savedAtOf(port, mid, cid),
                    fingerprintOf(port, mid, cid),
                    cid == activeId
                )
            )
        }
        return result
    }

    /** 该账号的凭据数量（惰性迁移后至少 1） */
    @JvmStatic
    fun credentialCount(port: String, mid: Long): Int {
        if (mid <= 0L) return 0
        ensureMigrated(port, mid)
        return idsOf(port, mid).size
    }

    /** 当前激活凭据 id；空串 = 该账号没有任何凭据 */
    @JvmStatic
    fun activeId(port: String, mid: Long): String {
        if (mid <= 0L) return ""
        ensureMigrated(port, mid)
        return activeIdOf(port, mid)
    }

    /**
     * 当前激活凭据是否**天生没有** `refresh_token`（而不是「曾经有过但被轮换掉」）。
     *
     * 为什么要单独判断：B 站只在**登录成功**的回包里下发 web `refresh_token`，
     * 它是 Web 自续期的唯一前提。缺它有两种成因，处置完全不同：
     * - 这份凭据登录时就没拿到（老版本未保存 / 扫码回包没带）→ 只能重新扫码；
     *   用户重扫一次之后就永久具备自动续期能力；
     * - 曾经有过、后来被轮换/清空（`updateActiveFromAccount` 会整份镜像账号槽）→
     *   token 已失效，同样只能重新登录。
     *
     * 两者在**本机**看起来都是「空串」，所以这里不假装能区分成因，只如实回答
     * 「当前这份凭据有没有 token」，并把「为什么没有」的决定权交给调用方：
     * 空串即是「本机这份凭据没有 token）——这正是 [AccountStore.loginWeb] 整份替换语义
     * （F2）保护的状态：不会因为上一份凭据有 token 就伪造出一份混配的凭据。
     */
    @JvmStatic
    fun activeHasRefreshToken(port: String, mid: Long): Boolean {
        val cid = activeId(port, mid)
        if (cid.isEmpty()) return false
        return !fieldsOf(port, mid, cid)[F_REFRESH_TOKEN].isNullOrBlank()
    }

    /**
     * 某条凭据的原始字段（**含完整凭据，调用方绝不允许打印**）。
     *
     * 返回的键就是 [ALL_FIELDS] 里的名字，与 [AccountStore.credentialFields] 的
     * 导出字段名保持一致 —— 导出的每条凭据直接复用同一套字段名，导入时不用转义。
     */
    @JvmStatic
    @JvmSuppressWildcards
    fun fieldsOf(port: String, mid: Long, cid: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (mid <= 0L || cid.isEmpty()) return result
        for (field in ALL_FIELDS) {
            val value = SharedPreferencesUtil.getString(fieldKey(port, mid, cid, field), "") ?: ""
            if (value.isNotEmpty()) result[field] = value
        }
        return result
    }

    // ==================== 写 ====================

    /**
     * 把**账号槽当前的值**保存为该账号的一条凭据，并**设为激活**。
     *
     * 这是「登录成功后入库」「用户点『保存当前凭据』」两条路径的共同入口：
     * 账号槽一直是激活凭据的镜像，所以「保存当前」= 读账号槽 → 写一条新凭据。
     *
     * # 为什么必须同时把激活指针指过去（这条是 v5.3.0 的实测修正，别改回去）
     * 账号槽此刻装的就是**这份新凭据**的内容。如果只追加列表、不移动激活指针，
     * 就会出现「账号槽 = 新凭据内容，但激活指针还指着旧凭据」的错位。
     * 下一次 [setActive] 会照例先 `snapshotCurrentIntoActive` —— 那一步会把账号槽
     * （= 新内容）写进**旧凭据**，于是旧凭据被覆盖成新值，**用户刚登录前的旧凭据彻底丢失**。
     * 这正是「支持多份凭据并存」这个需求最不能出的错，所以保存后必须同步激活指针。
     *
     * @param label 显示名；传空串会按时间自动生成
     * @return 新凭据的 id；账号槽为空（没有任何凭据内容）时返回 null
     */
    @JvmStatic
    fun saveCurrent(port: String, mid: Long, label: String): String? {
        return synchronized(AccountStore) { saveCurrentLocked(port, mid, label) }
    }

    private fun saveCurrentLocked(port: String, mid: Long, label: String): String? {
        if (mid <= 0L) return null
        val snapshot = AccountStore.credentialFields(port, mid)
        // 一条凭据都没有（既没 cookie 也没 token）就不该入库：那只会造出空条目
        if (snapshot[F_COOKIES].isNullOrEmpty() && snapshot[F_ACCESS_TOKEN].isNullOrEmpty()) {
            return null
        }
        ensureMigrated(port, mid)
        // 首次入库时 `ensureMigrated` 刚刚用**同一个账号槽**生成了 C_DEFAULT ——
        // 那一条的内容和这次要存的完全相同，再存一份就是重复条目（用户会看到两个一模一样的
        // 「默认凭据」和「扫码登录 …」）。所以内容完全相同时直接复用 C_DEFAULT，只更新名字。
        //
        // 注意判据必须是**内容比较**，不能用「saved_at 还是 0」这类代理：
        // 惰性迁移后 saved_at 会一直是 0，直到某次真正保存；而这段时间里账号槽可能已经
        // 被新登录改写成完全不同的凭据 —— 那时若按「刚迁移」复用它，就会把旧内容覆盖掉，
        // 恰好毁掉本需求要保护的东西。
        if (contentEqualsActiveDefault(port, mid, snapshot)) {
            putLabel(port, mid, C_DEFAULT, label.ifBlank { defaultLabel() })
            SharedPreferencesUtil.putLong(
                baseKey(port, mid) + C_DEFAULT + "_" + SUFFIX_SAVED_AT, System.currentTimeMillis()
            )
            Logu.d(TAG, "内容与默认凭据相同，复用之 port=" + port + " mid=" + mid)
            return C_DEFAULT
        }
        val cid = nextId(port, mid)
        writeFields(port, mid, cid, snapshot)
        putLabel(port, mid, cid, label.ifBlank { defaultLabel() })
        SharedPreferencesUtil.putLong(baseKey(port, mid) + cid + "_" + SUFFIX_SAVED_AT,
            System.currentTimeMillis())
        appendId(port, mid, cid)
        // 账号槽此刻的内容就是这份凭据 —— 指针必须跟过来（理由见上面的 KDoc）
        SharedPreferencesUtil.putString(activeKey(port, mid), cid)
        Logu.d(TAG, "保存凭据 port=" + port + " mid=" + mid + " cid=" + cid
                + " fields=" + snapshot.size)
        return cid
    }

    /**
     * 账号槽当前内容是否与 `C_DEFAULT` 那条**逐字段完全相同**。
     *
     * 用途只有一个：首次登录时不要造出一条和迁移占位完全一样的重复凭据。
     * 只在「列表里恰好只有 C_DEFAULT 一条」时才可能返回 true —— 用户主动保存过第二份之后
     * 就不会再走这条捷径（那时他要的是再来一份快照，而不是又复用第一份）。
     */
    private fun contentEqualsActiveDefault(
        port: String,
        mid: Long,
        snapshot: Map<String, String>
    ): Boolean {
        val ids = idsOf(port, mid)
        if (ids.size != 1 || ids.first() != C_DEFAULT) return false
        val existing = fieldsOf(port, mid, C_DEFAULT)
        if (existing.isEmpty()) return false
        for (field in ALL_FIELDS) {
            if ((existing[field] ?: "") != (snapshot[field] ?: "")) return false
        }
        return true
    }

    /**
     * 切换到某条凭据。
     *
     * 执行顺序**不能颠倒**（这是本类最容易写错的一处）：
     * 1. 先把「当前激活凭据」用账号槽的最新值刷新一遍 —— 否则用户切走前刚续期的
     *    cookie 会停在旧值上（v5.1.5 的账号级切换踩过同一个坑）；
     * 2. 再把目标凭据的字段写进账号槽；
     * 3. 最后更新 `active` 指针。
     *
     * @return false = 该凭据不存在（或参数非法），此时不做任何写入
     */
    @JvmStatic
    fun setActive(port: String, mid: Long, cid: String): Boolean {
        return synchronized(AccountStore) { setActiveLocked(port, mid, cid) }
    }

    private fun setActiveLocked(port: String, mid: Long, cid: String): Boolean {
        if (mid <= 0L || cid.isEmpty()) return false
        ensureMigrated(port, mid)
        if (!idsOf(port, mid).contains(cid)) return false
        if (cid == activeIdOf(port, mid)) return true

        snapshotCurrentIntoActive(port, mid)
        val target = fieldsOf(port, mid, cid)
        AccountStore.writeCredentialFields(port, mid, target)
        SharedPreferencesUtil.putString(activeKey(port, mid), cid)
        Logu.d(TAG, "切换凭据 port=" + port + " mid=" + mid + " cid=" + cid)
        return true
    }

    /** 重命名一条凭据（只改显示名，不碰凭据内容） */
    @JvmStatic
    fun rename(port: String, mid: Long, cid: String, label: String): Boolean {
        if (mid <= 0L || cid.isEmpty()) return false
        if (!idsOf(port, mid).contains(cid)) return false
        putLabel(port, mid, cid, label.trim().ifBlank { defaultLabel() })
        return true
    }

    /**
     * 删除一条凭据。
     *
     * **只剩一条时拒绝删除**：删掉最后一条等于让这个账号「有账号但没有凭据」，
     * `AccountStore.credentialFields` 会返回空 map，导出/请求全部静默失效，
     * 用户还得重新登录才能恢复 —— 这种半死状态比「不让删」糟得多。
     * 真要清空这个号，走「退出账号」（[AccountStore.logout]）。
     *
     * 删的是**激活**那条时，会自动把列表里剩下的第一条设为激活并写进账号槽，
     * 避免账号槽指向一份已经不存在的凭据。
     */
    @JvmStatic
    fun delete(port: String, mid: Long, cid: String): Boolean {
        return synchronized(AccountStore) { deleteLocked(port, mid, cid) }
    }

    private fun deleteLocked(port: String, mid: Long, cid: String): Boolean {
        if (mid <= 0L || cid.isEmpty()) return false
        ensureMigrated(port, mid)
        val ids = idsOf(port, mid)
        if (ids.size <= 1 || !ids.contains(cid)) return false

        val wasActive = cid == activeIdOf(port, mid)
        for (field in ALL_FIELDS) {
            SharedPreferencesUtil.removeValue(fieldKey(port, mid, cid, field))
        }
        SharedPreferencesUtil.removeValue(baseKey(port, mid) + cid + "_" + SUFFIX_LABEL)
        SharedPreferencesUtil.removeValue(baseKey(port, mid) + cid + "_" + SUFFIX_SAVED_AT)
        val remaining = ArrayList(ids)
        remaining.remove(cid)
        putIds(port, mid, remaining)
        if (wasActive) {
            // 激活那条被删了：顺延到剩下的第一条，并把它的内容写进账号槽
            val next = remaining.first()
            SharedPreferencesUtil.putString(activeKey(port, mid), next)
            AccountStore.writeCredentialFields(port, mid, fieldsOf(port, mid, next))
        }
        Logu.d(TAG, "删除凭据 port=" + port + " mid=" + mid + " cid=" + cid)
        return true
    }

    /**
     * 导入一条凭据（v5.3.0 导出格式 v3 的落地点）。
     *
     * 与 [saveCurrent] 的区别：那个读的是**账号槽**（本机当前状态），这个读的是**文件内容**
     * （别的设备导出的历史状态）。所以本函数直接写给定的字段，不碰账号槽。
     *
     * @param savedAt 原文件的保存时间；<= 0 时用当前时刻（老文件没这个字段）
     * @return 新凭据 id；字段全空时返回 null（不写废数据）
     */
    @JvmStatic
    @JvmSuppressWildcards
    fun importCredential(
        port: String,
        mid: Long,
        fields: Map<String, String>,
        label: String,
        savedAt: Long
    ): String? {
        if (mid <= 0L || fields.isEmpty()) return null
        if (fields[F_COOKIES].isNullOrEmpty() && fields[F_ACCESS_TOKEN].isNullOrEmpty()) return null
        ensureMigrated(port, mid)
        val cid = nextId(port, mid)
        writeFields(port, mid, cid, fields)
        putLabel(port, mid, cid, label.ifBlank { defaultLabel() })
        SharedPreferencesUtil.putLong(
            baseKey(port, mid) + cid + "_" + SUFFIX_SAVED_AT,
            if (savedAt > 0L) savedAt else System.currentTimeMillis()
        )
        appendId(port, mid, cid)
        return cid
    }

    /** 续期轮换当前凭据，不增加失效快照；调用者与切号共用 AccountStore 锁。 */
    fun updateActiveFromAccount(port: String, mid: Long) {
        synchronized(AccountStore) {
            ensureMigrated(port, mid)
            val cid = activeIdOf(port, mid)
            if (cid.isEmpty()) return
            val fields = AccountStore.credentialFields(port, mid)
            for (field in ALL_FIELDS) {
                SharedPreferencesUtil.putString(fieldKey(port, mid, cid, field), fields[field] ?: "")
            }
            SharedPreferencesUtil.putLong(baseKey(port, mid) + cid + "_" + SUFFIX_SAVED_AT,
                System.currentTimeMillis())
        }
    }

    // ==================== 惰性迁移 ====================

    /**
     * 老数据 → 一条 `C_DEFAULT`（幂等）。
     *
     * 判据是「列表键不存在」。注意**不能**用「列表为空」判断 —— 用户可能真的删到过 0 条
     * （虽然 [delete] 拦住了，但历史脏数据/手改偏好仍可能留下空列表），
     * 那时再用账号槽生成一条是对的（恢复可用状态），所以这里的语义是
     * 「列表里一条都没有 → 用账号槽补一条」。
     */
    private fun ensureMigrated(port: String, mid: Long) {
        if (mid <= 0L) return
        if (idsOf(port, mid).isNotEmpty()) return
        val snapshot = AccountStore.credentialFields(port, mid)
        if (snapshot.isEmpty()) return
        writeFields(port, mid, C_DEFAULT, snapshot)
        putLabel(port, mid, C_DEFAULT, "默认凭据")
        SharedPreferencesUtil.putLong(baseKey(port, mid) + C_DEFAULT + "_" + SUFFIX_SAVED_AT, 0L)
        putIds(port, mid, listOf(C_DEFAULT))
        SharedPreferencesUtil.putString(activeKey(port, mid), C_DEFAULT)
        Logu.d(TAG, "惰性迁移凭据 port=" + port + " mid=" + mid + " → " + C_DEFAULT)
    }

    // ==================== 内部：列表 / 字段读写 ====================

    private fun idsOf(port: String, mid: Long): List<String> {
        val raw = SharedPreferencesUtil.getString(listKey(port, mid), "") ?: ""
        if (raw.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        for (part in raw.split(",")) {
            val id = part.trim()
            if (id.isNotEmpty() && !result.contains(id)) result.add(id)
        }
        return result
    }

    private fun putIds(port: String, mid: Long, ids: List<String>) {
        SharedPreferencesUtil.putString(listKey(port, mid), ids.joinToString(","))
    }

    private fun appendId(port: String, mid: Long, cid: String) {
        val ids = idsOf(port, mid).toMutableList()
        if (!ids.contains(cid)) ids.add(cid)
        putIds(port, mid, ids)
    }

    /** 当前激活 id；指针指向一条已不存在的凭据时，回退到列表第一条（自愈） */
    private fun activeIdOf(port: String, mid: Long): String {
        val ids = idsOf(port, mid)
        if (ids.isEmpty()) return ""
        val stored = SharedPreferencesUtil.getString(activeKey(port, mid), "") ?: ""
        if (stored.isNotEmpty() && ids.contains(stored)) return stored
        // 指针脏了（手改偏好 / 删除时中断）：顺延到第一条并修回指针，避免到处判空
        putIds(port, mid, ids)
        SharedPreferencesUtil.putString(activeKey(port, mid), ids.first())
        return ids.first()
    }

    /**
     * 把账号槽的最新值刷进「当前激活凭据」。
     *
     * 只在切换前调用。账号槽为空时**不覆盖**已有凭据（空值写进去等于把凭据弄丢）。
     */
    private fun snapshotCurrentIntoActive(port: String, mid: Long) {
        val activeId = activeIdOf(port, mid)
        if (activeId.isEmpty()) return
        val snapshot = AccountStore.credentialFields(port, mid)
        if (snapshot.isEmpty()) return
        writeFields(port, mid, activeId, snapshot)
    }

    private fun writeFields(port: String, mid: Long, cid: String, fields: Map<String, String>) {
        for (field in ALL_FIELDS) {
            val value = fields[field]
            if (!value.isNullOrEmpty()) {
                SharedPreferencesUtil.putString(fieldKey(port, mid, cid, field), value)
            }
        }
    }

    private fun labelOf(port: String, mid: Long, cid: String): String {
        val raw = SharedPreferencesUtil.getString(baseKey(port, mid) + cid + "_" + SUFFIX_LABEL, "") ?: ""
        return raw.ifEmpty { cid }
    }

    private fun putLabel(port: String, mid: Long, cid: String, label: String) {
        SharedPreferencesUtil.putString(baseKey(port, mid) + cid + "_" + SUFFIX_LABEL, label)
    }

    private fun savedAtOf(port: String, mid: Long, cid: String): Long =
        SharedPreferencesUtil.getLong(baseKey(port, mid) + cid + "_" + SUFFIX_SAVED_AT, 0L)

    /**
     * 生成新的凭据 id。
     *
     * 用 `c<毫秒时间戳>` 而不是递增序号：同一账号可能在极短时间内连续入库两次
     * （登录成功 + 用户立刻点「保存当前」），时间戳天然唯一，序号要靠读-改-写，
     * 有并发覆盖风险。时间戳也便于用户对照「哪份是刚存的」。
     */
    private fun nextId(port: String, mid: Long): String {
        val ids = idsOf(port, mid)
        var stamp = System.currentTimeMillis()
        var candidate = "c" + stamp
        // 同一毫秒内重复（几乎不可能，但并发保存时可能）：顺延到下一个可用值
        while (ids.contains(candidate)) {
            stamp += 1
            candidate = "c" + stamp
        }
        return candidate
    }

    private fun defaultLabel(): String {
        val now = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        return now + " 保存"
    }

    // ==================== 指纹（唯一对外展示形态） ====================

    /**
     * 凭据指纹：**只给字段名 + 长度**，例如 `cookies(1030) · csrf(32)`。
     *
     * 为什么连前后缀都不给（这里是有意为之，别"优化"成 `SESSDATA…a1b2`）：
     * 1. 手表屏幕窄，前后缀挤在一行反而盖住真正有用的「哪份更新」；
     * 2. 用户会截图发 issue / 群里求助，任何真实字符都是泄露面；
     * 3. 用户真正需要的是「区分两份」——字段名 + 长度 + 保存时间已经够了。
     */
    private fun fingerprintOf(port: String, mid: Long, cid: String): String {
        val sb = StringBuilder()
        for (field in ALL_FIELDS) {
            val value = SharedPreferencesUtil.getString(fieldKey(port, mid, cid, field), "") ?: ""
            if (value.isEmpty()) continue
            // 每个字段只露长度；用 · 分隔，圆屏窄宽下也能读
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(field).append('(').append(value.length).append(')')
        }
        return sb.toString()
    }
}
