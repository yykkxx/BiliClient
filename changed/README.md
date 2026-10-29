# changed/ —— 特殊渠道包构建目录

> **本目录及其脚本不会修改主线的任何代码或构建配置。**
> 所有改动都发生在一个临时副本（默认 `/tmp/biliflavorsrc`）里，主线工作区保持干净。

## 用途

为同一个应用主体构建**渠道特化**的 release APK，目前提供两个变体：

| 变体 | 说明 |
| --- | --- |
| `qqmusic` | 第一个包：改 **包名**、**图标**、**应用名**、**版本号** |
| `qqmusic-hidden` | 第二个包：在第一个包的基础上，删除启动 Activity 的 `MAIN` + `LAUNCHER`，使其**从桌面（启动器）消失**（应用仍可正常启动，只能由其它组件/暗码/`am start` 拉起） |

## 用法

```bash
# 第一个包（默认 versionCode=20260606，versionName=QQ音乐）
bash changed/build_flavor.sh qqmusic

# 第二个包（隐藏启动器入口）
bash changed/build_flavor.sh qqmusic-hidden

# 自定义版本号 / 版本名
bash changed/build_flavor.sh qqmusic 20260606 "QQ音乐"
```

产物默认输出到仓库根目录的 `releases/`；**发版时请用 `FLAVOR_RELEASE_DIR` 指到版本子目录**
（否则每次构建都会覆盖 `releases/` 根目录的历史产物，让工作区一直有未提交改动）：

```bash
FLAVOR_ENV=/root/workspaces/BiliClient/env/env.sh FLAVOR_RELEASE_DIR=$PWD/releases/5.0.2-alpha6 \
  bash changed/build_flavor.sh qqmusic
```

```
releases/5.0.2-alpha6/com.tencent.qqmusicwatchs_20260606.apk
releases/5.0.2-alpha6/com.tencent.qqmusicwatchs_20260606_hidden.apk
```

可用环境变量覆盖默认值：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `FLAVOR_WORK_ROOT` | `/tmp/biliflavorsrc` | 临时副本目录 |
| `FLAVOR_RELEASE_DIR` | `<repo>/releases` | 产物输出目录 |
| `FLAVOR_PACKAGE` | `com.tencent.qqmusicwatchs` | 渠道包包名 |
| `FLAVOR_ENV` | 空 | 构建环境脚本，如 `/root/workspaces/BiliClient/env/env.sh`（设置 `JAVA_HOME` / SDK / `GRADLE_USER_HOME`） |
| `FLAVOR_WORKERS` | `1` | Gradle 并行 worker 数 |
| `FLAVOR_XMX` | `1280m` | 构建 JVM 最大堆 |
| `FLAVOR_DAEMON` | `0` | `1` = 常驻 Gradle daemon（更快，但更吃内存） |

> **内存提示**：本机常驻应用会占用大量内存，`gradle` 默认的 2G 堆 + 独立 Kotlin
> daemon 很容易把设备打爆重启。脚本默认采用保守配置（1 个 worker、1280m 堆、
> `--no-daemon`、Kotlin 进程内编译）；确认内存充裕时可用
> `FLAVOR_WORKERS=2 FLAVOR_XMX=2048m FLAVOR_DAEMON=1` 加速。
>
> 若构建被中断（例如设备重启），Gradle 的 build-script 缓存可能损坏并报
> `Could not load compiled classes for build file` —— 删除
> `$GRADLE_USER_HOME/caches/jars-9` 与 `caches/<版本>/scripts` 后重试即可。

## 实现方式

1. 把主线的源码与构建脚本复制到临时副本（**排除 `app/build/`**，避免复用过期产物）；
2. 在副本里打补丁：
   - 用 `changed/icon.png` 覆盖 `res/mipmap-nodpi/icon.png`；
   - 改写 `strings.xml` 的 `app_name`；
   - 改写 `AndroidManifest.xml` 里的 FileProvider `authorities`（贴齐新包名）；
   - `qqmusic-hidden` 额外删除 `SplashActivity` 的 `MAIN` + `LAUNCHER` intent-filter；
   - 改写副本的 `app/build.gradle`：`applicationId` / `versionCode` / `versionName`，并给 `buildTypes.release` 指定 debug 签名（主线未配置 release 签名，否则产物是 unsigned 包，无法直接安装）；
3. 在副本里执行 `./gradlew :app:assembleRelease`（`--max-workers=2` 限制并行度）；
4. 把产物复制到 `releases/`。

## 注意事项

- `applicationId` 必须在**配置阶段之前**改好（AGP 不允许在 `projectsEvaluated` 之后再改），因此脚本改的是副本里的 `build.gradle`，而不是用 init script 注入。
- 主线 `app/build.gradle` 里保留了 `//noinspection ExpiredTargetSdkVersion`，否则 `assembleRelease` 的 `lintVitalRelease` 会因 `targetSdk 30` 直接失败。
- 渠道包使用 **debug 签名**，与主线正式签名无关；仅用于内部/测试分发。
- 复制 `changed/icon.png` 可作为替换图标的入口；包名、应用名可按需修改脚本顶部的默认值。
