#!/usr/bin/env bash
# =============================================================================
# BiliClient 新增代码门禁：扫描 git diff「新增行」里的历史反模式
#
# 背景：这是代码审计里点名过的 4 类历史债务（runOnUiThread / org.json / Thread.sleep / !!）。
# 策略不是运动式重写，而是「老代码按文件接触时迁移 + 新代码一律不许再加」。
# 本脚本只负责后者：它看的是 **diff 的新增行**，基线里已有的历史代码永远不会被它拦。
#
# 用法（在仓库任意位置跑都可以，脚本自己找仓库根）：
#   bash tools/quality/check_new_code.sh                      # 检查工作区未提交改动（默认 --base HEAD）
#   bash tools/quality/check_new_code.sh --base origin/shortvideo   # 检查某分支以来的新增行
#   bash tools/quality/check_new_code.sh --base HEAD~3        # 检查最近 3 个提交
#   bash tools/quality/check_new_code.sh --strict             # findViewById( 也直接失败
#   bash tools/quality/check_new_code.sh --no-untracked       # 不扫描未跟踪的新 .kt 文件
#   ./gradlew checkNewCode -PbaseRef=origin/shortvideo        # 走 Gradle 任务（等价调用）
#
# 规则（只扫 .kt）：
#   硬失败：runOnUiThread( 、org.json. 、Thread.sleep( 、!! （非空断言）
#   提示级：findViewById(  —— 默认只警告，--strict 才失败
#   豁免：  该行任意位置写 // quality-allow 即可跳过；纯注释行（// * /* 开头）自动跳过，
#           所以可以在注释里讨论这些反模式而不被拦。
#
# 与 detekt 的分工：detekt 卡「规则」违规（复杂度/风格/潜在 bug），本脚本卡「审计点名的反模式」。
# 详见 tools/quality/README.md。
# =============================================================================
set -uo pipefail

BASE="HEAD"
STRICT=0
UNTRACKED=1

while [ $# -gt 0 ]; do
    case "$1" in
        --base)
            [ $# -ge 2 ] || { echo "错误：--base 需要一个 ref 参数" >&2; exit 2; }
            BASE="$2"; shift 2 ;;
        --base=*)
            BASE="${1#--base=}"; shift ;;
        --strict)
            STRICT=1; shift ;;
        --no-untracked)
            UNTRACKED=0; shift ;;
        -h|--help)
            sed -n '2,40p' "$0"; exit 0 ;;
        *)
            echo "错误：未知参数 $1（用 --help 查看用法）" >&2; exit 2 ;;
    esac
done

if ! REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"; then
    echo "错误：当前目录不在 git 仓库内" >&2
    exit 2
fi
if ! git -C "$REPO_ROOT" rev-parse --verify --quiet "${BASE}^{commit}" >/dev/null; then
    echo "错误：base ref 不存在：$BASE" >&2
    echo "       提示：本仓库主分支是 shortvideo，可用 git log --oneline -3 查看可用 ref" >&2
    exit 2
fi

exec python3 - "$REPO_ROOT" "$BASE" "$STRICT" "$UNTRACKED" <<'PY'
import re
import subprocess
import sys

repo, base, strict, untracked = sys.argv[1], sys.argv[2], sys.argv[3] == '1', sys.argv[4] == '1'

# (pattern_id, 正则, 是否硬失败, 提示文案)
RULES = [
    # 注意要同时覆盖 runOnUiThread( ... ) 和尾随 lambda 写法 runOnUiThread { ... }
    # —— 本仓库 371 处里绝大多数是尾随 lambda 写法
    ('runOnUiThread(', re.compile(r'runOnUiThread\s*[({]'), True,
     '新增代码不要用 runOnUiThread，用 CoroutineScopes.launchUi { } / lifecycleScope'),
    ('org.json.', re.compile(r'org\.json\.'), True,
     '新增网络/接口解析请用 model + Gson/kotlinx.serialization，不要上手写 org.json 解析'),
    ('Thread.sleep(', re.compile(r'Thread\.sleep\s*\('), True,
     '新增代码不要 Thread.sleep，用 suspend fun / delay() 或 CoroutineScopes.io { }'),
    ('!!', re.compile(r'[A-Za-z0-9_)\]]!!'), True,
     '新增代码不要 !!，用 ?. / ?: / requireNotNull() / checkNotNull() 并给出可读错误'),
    ('findViewById(', re.compile(r'findViewById\s*(<[^>]*>)?\s*\('), False,
     'findViewById 建议收敛到 ViewBinding / 显式 val 缓存（提示级，--strict 才失败）'),
]

# 纯注释行自动跳过（可以在注释里讨论反模式）；行内任意位置 // quality-allow 表示主动豁免
COMMENT_LINE = re.compile(r'^\s*(//|\*|/\*)')
ALLOW_MARK = re.compile(r'//\s*quality-allow')


def git(*args):
    return subprocess.run(['git', '-C', repo, *args], capture_output=True, text=True,
                          errors='replace').stdout


hits = []          # (file, line, rule, code, is_hard)
scanned_added = 0  # 统计：新增行数
scanned_files = set()

# ---- 1) 已跟踪文件的改动：git diff -U0 <base> 的新增行 ----
diff = git('diff', '-U0', '--no-color', '--no-ext-diff', base, '--', '*.kt')
cur_file, new_line = None, 0
for raw in diff.splitlines():
    if raw.startswith('diff --git '):
        cur_file, new_line = None, 0
    elif raw.startswith('+++ '):
        path = raw[4:].strip()
        cur_file = None if path == '/dev/null' else path[2:] if path.startswith('b/') else path
    elif raw.startswith('@@ '):
        m = re.search(r'\+(\d+)', raw)
        new_line = int(m.group(1)) if m else 0
    elif raw.startswith('+') and not raw.startswith('+++'):
        code = raw[1:]
        if cur_file:
            scanned_added += 1
            scanned_files.add(cur_file)
            _scan = True
            if COMMENT_LINE.match(code) or ALLOW_MARK.search(code):
                _scan = False
            if _scan:
                for name, rx, hard, hint in RULES:
                    if rx.search(code):
                        hits.append((cur_file, new_line, name, code.strip(), hard, hint))
        new_line += 1
    elif raw.startswith('\\ '):
        pass

# ---- 2) 未跟踪的新 .kt 文件：所有行都算新增 ----
if untracked:
    others = git('ls-files', '--others', '--exclude-standard', '--', '*.kt').splitlines()
    for f in others:
        path = f.strip()
        if not path:
            continue
        scanned_files.add(path)
        try:
            with open(repo + '/' + path, encoding='utf-8', errors='replace') as fh:
                lines = fh.read().splitlines()
        except OSError:
            continue
        for i, code in enumerate(lines, 1):
            scanned_added += 1
            if COMMENT_LINE.match(code) or ALLOW_MARK.search(code):
                continue
            for name, rx, hard, hint in RULES:
                if rx.search(code):
                    hits.append((path, i, name, code.strip(), hard, hint))

# ---- 3) 输出 ----
hard_hits = [h for h in hits if h[4]]
soft_hits = [h for h in hits if not h[4]]


def emit(rows, label):
    print(f'--- {label}（{len(rows)} 处）---')
    for f, ln, name, code, hard, hint in sorted(rows, key=lambda x: (x[0], x[1])):
        print(f'{f}:{ln}: {name}: {code}')
        print(f'    ↳ {hint}')
        print(f'    ↳ 确属例外时在该行末尾加 // quality-allow 并说明原因')


print('=== BiliClient 新增代码门禁 (check_new_code.sh) ===')
print(f'repo      : {repo}')
print(f'base ref  : {base}')
print(f'扫描范围  : {len(scanned_files)} 个 .kt 文件 / {scanned_added} 行新增'
      f'{"" if untracked else "（未跟踪文件已跳过）"}')
print()

if hits:
    if hard_hits:
        emit(hard_hits, '硬失败：审计禁止新增的反模式')
        print()
    if soft_hits:
        emit(soft_hits, f'提示级：建议收敛{"" if strict else "（默认不失败）"}')
        print()

failed = bool(hard_hits) or (strict and bool(soft_hits))
if failed:
    print('结果：FAILED —— 新增代码里出现审计禁止的反模式。')
    print('      处理方式：a) 按提示改写；b) 确属特例时该行加 // quality-allow 并写明原因。')
    print('      历史代码不要求改（detekt baseline 与 git 历史已兜住），只要求本次新增行合规。')
    sys.exit(1)
else:
    print('结果：PASS —— 新增行未发现禁用的历史反模式。')
    if soft_hits:
        print(f'      （提示级 {len(soft_hits)} 处 findViewById，建议顺手收敛，不阻塞）')
    sys.exit(0)
PY
