# 代码质量门禁（T2）

本项目**不做运动式重写**。策略是两条腿走路：

1. **卡新增**：新增代码必须满足规则，不许再引入审计点名的历史反模式；
2. **老代码按文件接触时迁移**：碰到的文件顺手改，改动过的文件可逐步从 detekt baseline 里减少条目。

这套门禁由两部分组成，分工如下：

| 门禁 | 入口 | 拦什么 | 现有历史债务怎么处理 |
| --- | --- | --- | --- |
| **detekt**（规则级） | `bash /tmp/bili_build.sh :app:detekt` | 复杂度/风格/潜在 bug（默认规则集，`detekt.yml` 调参） | 全部进 `app/detekt-baseline.xml`，基线内不拦 |
| **check_new_code.sh**（反模式级） | `bash tools/quality/check_new_code.sh` 或 `./gradlew checkNewCode` | 审计点名的 4 类反模式：`runOnUiThread(`、`org.json.`、`Thread.sleep(`、`!!`（+ 提示级 `findViewById(`） | 只看 **diff 新增行**，历史代码天然不在扫描范围内 |

> 两者都**没有**挂到 `check` / `assemble` / 发版构建链路上（`checkNewCode` 是独立任务，
> detekt 任务也不会被 `check` 拉起），所以发版构建不会被拖慢；发版前手动各跑一次即可。

---

## 一、detekt（新增规则违规）

- 版本：**detekt 1.22.0**（兼容 Gradle 7.4 / AGP 7.3.1 / Kotlin 1.7.20）。
- 声明位置：根 `build.gradle` 的 `plugins { id 'io.gitlab.arturbosch.detekt' version '1.22.0' apply false }`；
  只在 `app` 模块应用（`app/build.gradle`）。
- 配置：仓库根目录 **`detekt.yml`**（`buildUponDefaultConfig = true`，只写与官方默认规则集的差异）。
- 基线：**`app/detekt-baseline.xml`**（当前收录 1279 条已有的历史违规，已随仓库提交）。
- 单线程：`parallel = false`，且统一走 `/tmp/bili_build.sh`（`flock` 串行 + `-Xmx1280m`）——
  本机内存紧张，**绝对不要**直接并行跑多个 Gradle 构建。

```bash
bash /tmp/bili_build.sh :app:detekt            # 门禁：有未进 baseline 的新违规 → FAILED
bash /tmp/bili_build.sh :app:detektBaseline    # 重新生成 baseline（慎用：会把现存违规全部吞掉）
bash /tmp/bili_build.sh :app:detektGenerateConfig   # 生成完整默认配置做参考
```

**注意：不要随便重跑 `detektBaseline`。** 它会把当前所有违规写进基线，等于把这一轮新增的债务
也洗白。正确姿势是：新违规 → 改代码；确实该豁免 → 加到 `detekt.yml` 里（写明理由）或
`app/detekt-baseline.xml` 的 `<ManuallySuppressedIssues>`（仅限单条、需注释原因）。

**接触文件时减少基线**：改某个文件时如果顺手修掉了该文件的历史违规，可以删掉 baseline 里
对应 `<ID>`（`<ID>规则名:文件名$符号$签名</ID>`）；删多了会让 `:app:detekt` 报「违规又出现了」，
所以删完要跑一次门禁确认。

---

## 二、check_new_code.sh（新增反模式）

### 用法

```bash
# 1) 检查工作区未提交改动（默认 --base HEAD，最常用）
bash tools/quality/check_new_code.sh

# 2) 检查某分支/提交以来的新增行
bash tools/quality/check_new_code.sh --base origin/shortvideo
bash tools/quality/check_new_code.sh --base HEAD~3

# 2b) 发版前固定用「上一个 release tag」做基线：
#     --base HEAD 在已提交的工作区里 diff 为空 → 永远 PASS，等于没检查
bash tools/quality/check_new_code.sh --base v5.0.2-alpha5

# 3) 走 Gradle 任务（等价，参数名不同）
./gradlew checkNewCode                                  # base=HEAD
./gradlew checkNewCode -PbaseRef=origin/shortvideo
./gradlew checkNewCode -Pstrict=true                    # findViewById( 也失败

# 4) 其他开关
bash tools/quality/check_new_code.sh --strict           # findViewById( 提示级 → 失败级
bash tools/quality/check_new_code.sh --no-untracked     # 不扫描未跟踪的新 .kt（默认会扫）
bash tools/quality/check_new_code.sh --help
```

### 规则（只扫描 `.kt`）

| 模式 | 级别 | 替代方案 |
| --- | --- | --- |
| `runOnUiThread(` | **硬失败** | `CoroutineScopes.launchUi { }`（见 `util/CoroutineScopes.kt`） |
| `org.json.` | **硬失败** | `model/` 下的数据类 + Gson/serialization；解析收敛到 `api/`/`util/JsonUtil` |
| `Thread.sleep(` | **硬失败** | `suspend fun` / `delay()` / `CoroutineScopes.io { }` |
| `!!`（非空断言，如 `obj!!.x`） | **硬失败** | `?.`、`?:` 兜底、`requireNotNull(x) { "原因" }`、`checkNotNull` |
| `findViewById(` | 提示级（`--strict` 才失败） | ViewBinding / 收敛到 `val` 缓存 |

扫描范围：`git diff -U0 <base> -- '*.kt'` 的**新增行** + 未跟踪的新 `.kt` 文件（整文件视为新增）。

### 豁免方式

- 行内任意位置写 **`// quality-allow`** 即可跳过该行（建议同时写原因）：

  ```kotlin
  val v = statusBuilder!!.build() // quality-allow: Service 里 statusBuilder 必定已初始化
  ```

- **纯注释行**（`//`、`*`、`/*` 开头）自动跳过——所以可以在注释里讨论这些反模式而不被误拦。
- 注意豁免是**按行**的：不要为了让一条豁免给整个文件加标记。

### 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | PASS：新增行合规（提示级项只警告） |
| `1` | FAILED：命中硬失败模式（或 `--strict` 下命中提示级） |
| `2` | 用法/环境错误（参数错、不在 git 仓库、base ref 不存在） |

### 输出示例

```
app/src/main/java/com/RobinNotBad/BiliClient/service/DownloadService.kt:122: !!: statusBuilder!!.build(),
    ↳ 新增代码不要 !!，用 ?. / ?: / requireNotNull() / checkNotNull() 并给出可读错误
    ↳ 确属例外时在该行末尾加 // quality-allow 并说明原因
```

---

## 三、推荐流程

改完代码、提交前：

```bash
bash tools/quality/check_new_code.sh --base origin/shortvideo   # 新增行反模式（秒级）
bash /tmp/bili_build.sh :app:detekt                             # 规则级（首次要下依赖，之后 ~1-3 分钟）
```

约定：**老代码不追着改**；只有在「本轮任务本来就要碰这个文件」时，才把该文件里的
`runOnUiThread` / `!!` / 手写 json 顺手迁掉（并相应缩减 detekt baseline）。这样债务只减不增。

---

## 四、已知限制

- detekt 未开 `typeResolution`（需要编译 classpath，构建更重、本机内存吃紧），因此
  `unused-imports`、`UnusedPrivateMember` 一类依赖类型信息的规则行为有限；
  需要时再单独评估。
- `check_new_code.sh` 是**文本级**扫描：字符串常量里出现 `org.json.` 会误报，用
  `// quality-allow` 豁免；`!!` 只匹配「标识符/`]`/`)` 后紧跟 `!!`」的形式，以避开
  中文注释里的感叹号。
- 脚本依赖 `python3`（设备已自带，仅用标准库）。
