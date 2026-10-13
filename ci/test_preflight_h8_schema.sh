#!/usr/bin/env bash
# ci/preflight-h8-schema.sh 的负控契约:纯本地、零数据库、零 ssh。
#
# 观测方式:PATH shim 掉 systemctl / mysql,再用假 /proc 目录喂 environ ——
# 跑的是**真脚本的主流程**,不是复制一份逻辑。每一条负控都必须真红:
#   ① 取不到 MainPID  ② environ 里没有 CHENGYIN_DB_PASSWORD  ③ 没有 mysql/mariadb 客户端
#   ④ 5 列不齐        ⑤ 仍有 creator_rate 为 NULL / 非 0 的 active 规则
#   ⑥ 查询空输出(连不上)⑦ 口令进了 argv 或被打印
#   ⑧ deploy.yml 接线被摘掉 / 注释掉 / 复制一份 / 排到换 jar 之后
#   ⑨ 接线还在、顺序也对,但【红了不会停】:行尾 `|| true`、`set +e` 包围、`if false` 包住,
#     以及被调的 retry_ssh_stdin 吞掉退出码、step 挂 continue-on-error / 覆写 shell
# 全满足时必须绿 —— 否则这道闸会挡住所有部署,那是比不设闸更坏的故障。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GATE="$SCRIPT_DIR/preflight-h8-schema.sh"
DEPLOY_WORKFLOW="$REPO_DIR/.github/workflows/deploy.yml"
# 2026-08-26:接线本体从 deploy.yml 搬进了脚本(为了与云效 Flow 共用同一份逻辑)。
# 于是这道闸从「一个 YAML」变成盯【三个文件】,少盯一个就有一层保险悄悄失守:
#   · WIRING_FILE   五条腿的顺序 / errexit / 有没有被包进复合命令
#   · RETRY_LIB     retry_ssh_stdin 把非 255 退出码原样上抛
#   · DEPLOY_WORKFLOW  那一步有没有挂 continue-on-error / 覆写 shell(仍是 YAML 概念)
WIRING_FILE="$REPO_DIR/ci/deploy-push.sh"
RETRY_LIB="$REPO_DIR/ci/deploy-lib.sh"

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

SECRET='pa55w0rd-!$#-fixture'
FAKE_PID=4242

pass=0; fail=0
check() { # check <名字> <错误串>
  if [ -n "$2" ]; then echo "FAIL $1:$2"; fail=$((fail + 1)); else pass=$((pass + 1)); fi
}

# ── shim 环境:一个只含假 systemctl / 假 mysql 的 PATH ────────────────────────
make_sandbox() { # make_sandbox <名字> <systemctl 输出> <mysql 输出> <是否装 mysql:yes|no>
  local name=$1 pid_out=$2 query_out=$3 with_client=$4
  local box="$TEMP_DIR/$name"
  mkdir -p "$box/bin" "$box/proc/$FAKE_PID"
  printf '%s' "$pid_out" > "$box/bin/.pid_out"
  cat > "$box/bin/systemctl" <<'SHIM'
#!/usr/bin/env bash
cat "$(dirname "$0")/.pid_out"
SHIM
  chmod +x "$box/bin/systemctl"
  if [ "$with_client" = yes ]; then
    printf '%s' "$query_out" > "$box/bin/.query_out"
    cat > "$box/bin/mysql" <<'SHIM'
#!/usr/bin/env bash
# 把自己的 argv 原样留痕:口令绝不允许出现在这里。
printf '%s\n' "$@" > "$(dirname "$0")/.argv"
# 同时留痕 defaults-extra-file 的内容 —— 只有这样才能断言【真正送到客户端的那个口令】
# 是哪一个,而不是"没崩就算过"。它是本沙箱里的 fixture 口令,不是任何真实凭据。
for arg in "$@"; do
  case "$arg" in
    --defaults-extra-file=*) cat "${arg#--defaults-extra-file=}" > "$(dirname "$0")/.defaults" ;;
  esac
done
cat "$(dirname "$0")/.query_out"
SHIM
    chmod +x "$box/bin/mysql"
  fi
  # awk/tr/sed/mktemp/chmod/printf 等仍需真实存在,故保留系统 PATH 里的核心目录。
  printf '%s\n' "$box"
}

write_environ() { # write_environ <box> <是否含口令:yes|no>
  local box=$1 with_password=$2
  {
    printf 'PATH=/usr/bin\0'
    printf 'CHENGYIN_DRUID_PASSWORD=irrelevant\0'
    if [ "$with_password" = yes ]; then
      printf 'CHENGYIN_DB_PASSWORD=%s\0' "$SECRET"
    fi
    printf 'HOME=/root\0'
  } > "$box/proc/$FAKE_PID/environ"
}

write_dropin() { # write_dropin <box> <文件名> <内容> [mode,默认 600]
  local box=$1 name=$2 body=$3 mode=${4:-600}
  local dir="$box/etc/systemd/system/fake-service.service.d"
  mkdir -p "$dir"
  printf '%s\n' "$body" > "$dir/$name"
  chmod "$mode" "$dir/$name"
}

# 生产上密钥文件是 600 root:root(uid 0)。测试进程不是 root,故把「期望属主」做成 seam,
# 默认仍是 0 —— 传入别的 uid 即可单独负控「属主不对必须红」这一条。
run_gate() { # run_gate <box> [期望属主 uid,默认当前用户] ; 回显合并输出,返回退出码
  local box=$1 owner=${2:-$(id -u)}
  (
    PATH="$box/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    H8_PREFLIGHT_PROC_ROOT="$box/proc" \
    H8_PREFLIGHT_SERVICE=fake-service \
    H8_PREFLIGHT_DROPIN_ROOT="$box/etc/systemd/system" \
    H8_PREFLIGHT_SECRET_OWNER_UID="$owner" \
    bash "$GATE" 2>&1
  )
}

# ── ⑥→① 各条负控 ──────────────────────────────────────────────────────────

# 正向:MainPID 有、口令有、客户端有、5 列齐、0 条未收敛 ⇒ 必须绿
box="$(make_sandbox happy "$FAKE_PID" "5	0" yes)"
write_environ "$box" yes
err=""
if out="$(run_gate "$box")"; then :; else err=" 全满足却红了:$out"; fi
check happy_path_is_green "$err"

# ⑦ 口令既不能进 argv,也不能出现在任何输出里
err=""
grep -qF "$SECRET" "$box/bin/.argv" && err="$err 口令进了 mysql 的 argv"
printf '%s' "$out" | grep -qF "$SECRET" && err="$err 口令被打印到输出里"
check secret_never_leaks "$err"

# defaults-extra-file 必须是第一个参数(mysql 要求),且指向一个 0600 文件
err=""
head -n 1 "$box/bin/.argv" | grep -q -- '--defaults-extra-file=' \
  || err="$err 第一个参数不是 --defaults-extra-file"
check defaults_file_is_first_arg "$err"

# ① 进程真值与磁盘真源【两处都取不到】才红(服务没在跑,且 drop-in 里也没有该变量)
box="$(make_sandbox no_pid "0" "5	0" yes)"
write_environ "$box" yes
err=""
if out="$(run_gate "$box")"; then err=" 两处都拿不到口令却放行了"; fi
check no_process_and_no_dropin_is_red "$err"

# ── ⑩ 口令回落:服务没在跑时仍必须能部署(否则修坏 jar 的那版 jar 永远发不出去)──────
# 磁盘真源 = systemd drop-in 的 Environment= 行(ci/deploy-restart.sh:12-16 与
# docs/CICD工作流说明_GitHub-first.md:244-247 记载:600 root:root,7 个 CHENGYIN_*)。
DROPIN_SECRET='dropin-pa55 w0rd#x'

# 服务停机 + drop-in 有口令 ⇒ 必须绿,且用的必须是 drop-in 里那个口令
box="$(make_sandbox service_down "0" "5	0" yes)"
write_environ "$box" no
write_dropin "$box" secrets.conf "[Service]
Environment=\"CHENGYIN_DB_PASSWORD=$DROPIN_SECRET\""
err=""
if out="$(run_gate "$box")"; then :; else err=" 服务没在跑、drop-in 有口令,却红了:$out"; fi
check service_down_falls_back_to_dropin "$err"

err=""
grep -qF "password=\"$DROPIN_SECRET\"" "$box/bin/.defaults" \
  || err=" 送进客户端的不是 drop-in 里那个口令(带空格/井号的值被解析坏了?)"
check dropin_password_is_parsed_verbatim "$err"

err=""
grep -qF "$DROPIN_SECRET" "$box/bin/.argv" && err="$err 口令进了 mysql 的 argv"
printf '%s' "$out" | grep -qF "$DROPIN_SECRET" && err="$err 口令被打印到输出里"
check dropin_secret_never_leaks "$err"

# 进程活着时仍以【进程真值】为准:drop-in 可能已改而进程还在用旧值,要问在跑的那个
box="$(make_sandbox proc_wins "$FAKE_PID" "5	0" yes)"
write_environ "$box" yes
write_dropin "$box" secrets.conf "[Service]
Environment=\"CHENGYIN_DB_PASSWORD=$DROPIN_SECRET\""
err=""
if out="$(run_gate "$box")"; then :; else err=" 全满足却红了:$out"; fi
grep -qF "password=\"$SECRET\"" "$box/bin/.defaults" \
  || err="$err MainPID 活着却没有优先用进程环境里的口令"
check running_process_environ_wins_over_dropin "$err"

# ★ 回落【只】为「进程根本不存在」而设。MainPID 有效时,进程环境里没有这个变量必须红,
#   不许拿 drop-in 顶上 —— 那说明在跑的进程与 drop-in 已经不一致,回落会把这个信号抹掉。
box="$(make_sandbox proc_alive_no_var "$FAKE_PID" "5	0" yes)"
write_environ "$box" no
write_dropin "$box" secrets.conf "[Service]
Environment=\"CHENGYIN_DB_PASSWORD=$DROPIN_SECRET\""
err=""
if out="$(run_gate "$box")"; then err=" 进程活着却回落磁盘放行了 —— 信号被抹掉"; fi
check running_process_missing_var_never_falls_back "$err"

# 同理:MainPID 有效但 environ 读不到(以 root 都读不了 /proc)也必须红,不许回落。
box="$(make_sandbox proc_alive_unreadable "$FAKE_PID" "5	0" yes)"
rm -f "$box/proc/$FAKE_PID/environ"
write_dropin "$box" secrets.conf "[Service]
Environment=\"CHENGYIN_DB_PASSWORD=$DROPIN_SECRET\""
err=""
if out="$(run_gate "$box")"; then err=" environ 读不到却回落磁盘放行了"; fi
check running_process_unreadable_environ_never_falls_back "$err"

# 无引号形式(systemd 也接受)同样要认
box="$(make_sandbox dropin_bare "0" "5	0" yes)"
write_environ "$box" no
write_dropin "$box" secrets.conf "[Service]
Environment=CHENGYIN_DB_PASSWORD=$SECRET"
err=""
if out="$(run_gate "$box")"; then
  grep -qF "password=\"$SECRET\"" "$box/bin/.defaults" || err=" 无引号形式解析错了"
else
  err=" 无引号形式却红了:$out"
fi
check dropin_bare_assignment_is_accepted "$err"

# ── 回落路径的负控:恶意 / 歧义 / 缺失,一律红 ────────────────────────────────
dropin_is_red() { # dropin_is_red <名字> <文件内容> [mode] [期望属主 uid]
  local name=$1 body=$2 mode=${3:-600} owner=${4:-}
  local sandbox
  sandbox="$(make_sandbox "$name" "0" "5	0" yes)"
  write_environ "$sandbox" no
  write_dropin "$sandbox" secrets.conf "$body" "$mode"
  local message=""
  if message="$(run_gate "$sandbox" "$owner")"; then
    check "$name" " 该拒的 drop-in 却放行了"
  else
    check "$name" ""
  fi
}

# 权限过宽:组/其他可读即拒(密钥文件被 chmod 松过 = 可能已泄露/被替换)
dropin_is_red dropin_world_readable_is_red "[Service]
Environment=\"CHENGYIN_DB_PASSWORD=$SECRET\"" 644

# 受理集合必须与文案一致:文案写"恰好 600 或 400",700 就不许放行
dropin_is_red dropin_mode_outside_600_400_is_red "[Service]
Environment=\"CHENGYIN_DB_PASSWORD=$SECRET\"" 700

# 属主不对:生产上必须是 root(uid 0)
dropin_is_red dropin_wrong_owner_is_red "[Service]
Environment=\"CHENGYIN_DB_PASSWORD=$SECRET\"" 600 4294967294

# 歧义:同一份 drop-in 里两处赋值,不猜用哪个
dropin_is_red dropin_duplicate_assignment_is_red "[Service]
Environment=\"CHENGYIN_DB_PASSWORD=$SECRET\"
Environment=\"CHENGYIN_DB_PASSWORD=other\""

# 歧义:一行里多个赋值,按空格切会切错 —— 拒绝解析,不做危险猜测
dropin_is_red dropin_multi_assignment_line_is_red "[Service]
Environment=\"CHENGYIN_JWT_SECRET=a\" \"CHENGYIN_DB_PASSWORD=$SECRET\""

# 形状异常:引号不闭合
dropin_is_red dropin_unbalanced_quote_is_red "[Service]
Environment=\"CHENGYIN_DB_PASSWORD=$SECRET"

# 形状异常:含反斜杠(systemd 在双引号里把它当转义引导符)—— 不猜它代表几个字符
dropin_is_red dropin_backslash_is_red '[Service]
Environment="CHENGYIN_DB_PASSWORD=a\b"'

# 注释掉的赋值不是生效配置
dropin_is_red dropin_commented_assignment_is_red "[Service]
# Environment=\"CHENGYIN_DB_PASSWORD=$SECRET\""

# 空值:拒绝继续(与 write_defaults_file 的空口令判据同向)
dropin_is_red dropin_empty_value_is_red '[Service]
Environment="CHENGYIN_DB_PASSWORD="'

# 目录在、文件在,但压根没有这个变量
dropin_is_red dropin_without_the_variable_is_red '[Service]
Environment="CHENGYIN_DEEPSEEK_KEY=irrelevant"'

# ② environ 里没有 CHENGYIN_DB_PASSWORD ⇒ 不许跳过校验
box="$(make_sandbox no_password "$FAKE_PID" "5	0" yes)"
write_environ "$box" no
err=""
if out="$(run_gate "$box")"; then err=" 拿不到口令却放行了 —— 正是要杀的静默跳过"; fi
check missing_password_is_red "$err"

# ③ 没有 mysql / mariadb 客户端 ⇒ fail-closed,不回退成跳过
box="$(make_sandbox no_client "$FAKE_PID" "5	0" no)"
write_environ "$box" yes
err=""
if out="$(run_gate "$box")"; then err=" 没有客户端却放行了"; fi
check missing_client_is_red "$err"

# ④ 5 列不齐(ORDER 1/2 没跑)
box="$(make_sandbox missing_columns "$FAKE_PID" "4	0" yes)"
write_environ "$box" yes
err=""
if out="$(run_gate "$box")"; then err=" 只有 4 列却放行了"; fi
check missing_columns_is_red "$err"

# ⑤ 仍有 creator_rate 非 0 / NULL 的 active 规则(ORDER 2/2 没跑或没收敛干净)
box="$(make_sandbox unconverged "$FAKE_PID" "5	1" yes)"
write_environ "$box" yes
err=""
if out="$(run_gate "$box")"; then err=" 仍有未收敛规则却放行了 —— 部署后支付回调会整笔回滚"; fi
check unconverged_rule_is_red "$err"

# ⑥ 查询空输出(连不上 / 权限不足)⇒ 不许当成通过
box="$(make_sandbox empty_result "$FAKE_PID" "" yes)"
write_environ "$box" yes
err=""
if out="$(run_gate "$box")"; then err=" 查询无输出却放行了"; fi
check empty_query_result_is_red "$err"

# ── ⑧ deploy.yml 接线:必须存在,且必须排在换 jar 之前 ──────────────────────
#
# ★ 判据是【可执行行】,不是「文件里出现过这个串」。三条正则都把行首锚死成
#   「除空白外直接是调用本体」,于是注释掉的那一行不再算接线 —— 一个 '#' 就静默关掉
#   这道闸而契约照绿,是本仓最省事的关闸方式(旧判据 grep 子串 + head -n 1 正是这样)。
# ★ 且必须【恰好一条】:只取首个命中行时,把调用复制一份到换 jar 之后照样判绿,
#   而那次多余调用跑在 jar 已经上去之后,「不过就停、jar 未被替换」已经不成立。
#   上下游两条(传 jar / 换 jar)同样按可执行行 + 恰好一条认,免得注释里提一句
#   deploy-restart.sh 就把顺序断言的基准挪走。
# ★ 行尾也必须锚死。只锚行首时,` || true` / `; true` / ` &` 都能保住"行首直接是调用本体"
#   而把失败吞掉 —— 那比注释掉更隐蔽,还会留下一行看似正常的日志。
PREFLIGHT_CALL='^[[:space:]]*retry_ssh_stdin ci/preflight-h8-schema\.sh ssh "\$\{ssh_opts\[@\]\}" "\$target" .bash -s.[[:space:]]*$'
# 只锚行首的旧判据,单独留着做【成因分辨】:两种改法都让严格判据 0 命中,
# 但"整行没了"和"整行还在、被加了后缀"必须各自红在自己那句话上,否则两条负控互相顶替。
PREFLIGHT_CALL_LOOSE='^[[:space:]]*retry_ssh_stdin ci/preflight-h8-schema\.sh '
JAR_UPLOAD_CALL='^[[:space:]]*retry_jar_upload "\$stage_jar"[[:space:]]*$'
JAR_RESTART_CALL='^[[:space:]]*retry_ssh_stdin ci/deploy-restart\.sh '
# workflow 侧现在只剩一行接线:调 ci/deploy-push.sh。它断了,五条腿再整齐也不会被执行。
PUSH_CALL='^[[:space:]]*bash ci/deploy-push\.sh "\$DEPLOY_SHA"[[:space:]]*$'
# 下面两条只用来钉住「切出来的 step 就是承载这几条腿的那一个」,不参与顺序判据。
JAR_VERIFY_CALL='^[[:space:]]*retry_ssh_stdin ci/verify-deployed\.sh '
SHA_READBACK_CALL='^[[:space:]]*retry_ssh_stdin "\$sha_file" ssh '

sole_executable_line() { # sole_executable_line <文件> <ERE> <说明> → 回显唯一命中的行号
  local hits count
  hits="$(grep -nE "$2" "$1" | cut -d: -f1 || true)"
  count="$(printf '%s' "$hits" | grep -c '^' || true)"
  if [ "$count" -ne 1 ]; then
    echo "$3:可执行行必须恰好 1 条,实得 $count 条"
    return 1
  fi
  printf '%s\n' "$hits"
}

# 「存在 + 恰好一条 + 排在传 jar 之前」证明不了【它红了这一步就停】。补两道判据:
#   ① run 块里不得关掉 errexit(GitHub Actions 默认 shell 就是 `bash -e {0}`,
#      所以要挡的是"被主动关掉",不是"有没有写 set -e");
#   ② 把块体截到 preflight 那一行喂 `bash -n` —— 它是真解析器,这一行被 if/while/{/(
#      包住时,截断处必然留下未闭合的复合命令 → 语法错误。行首行尾都锚死也看不见这一层。
# ⚠️ 这是文本代理,不是真执行:它证明"这一行在块顶层且 errexit 没被关",
#    不证明 GitHub 那一步的运行时行为。真实执行仍只发生在部署当天。
run_block_prefix() { # run_block_prefix <workflow> <行号> → 回显该 run: | 块体截到该行
  awk -v target="$2" '
    NR <= target && /^[[:space:]]*run: \|[[:space:]]*$/ { start = NR; indent = match($0, /[^ ]/) }
    { line[NR] = $0 }
    END {
      if (start == 0 || start >= target) exit 1
      for (i = start + 1; i <= target; i++) {
        if (line[i] !~ /^[[:space:]]*$/ && match(line[i], /[^ ]/) <= indent) exit 1
        print line[i]
      }
    }' "$1"
}

# 接线搬进脚本后,「块体」就是脚本本身:前缀 = 第 1 行截到 preflight 那一行。
script_prefix() { # script_prefix <脚本> <行号> → 回显该脚本截到该行的前缀
  awk -v target="$2" 'NR <= target { print }' "$1"
}

assert_step_aborts_on_preflight() { # assert_step_aborts_on_preflight <wiring 脚本> <preflight 行号>
  local prefix="$TEMP_DIR/wiring_prefix.sh"
  script_prefix "$1" "$2" > "$prefix"
  # ★ 判据比 YAML 时代更强,而且必须更强:
  #   在 deploy.yml 里 errexit 由「GitHub Actions 默认 shell 是 bash -e {0}」兜底,
  #   所以那时只挡「被主动关掉」。搬进脚本后没有任何默认兜底 —— 脚本自己不写 set -e
  #   就真的不会停,而这一点从调用方一侧完全看不出来。故这里改成【必须显式声明】。
  if ! grep -qE '^[[:space:]]*set[[:space:]]+-[a-zA-Z]*e' "$prefix"; then
    echo "wiring 脚本没有显式 set -e —— 搬出 workflow 后没有默认 -e 兜底,preflight 红了也不会停"
    return 1
  fi
  if grep -qE 'set[[:space:]]+\+([a-zA-Z]*e|o[[:space:]]+errexit)' "$prefix"; then
    echo "run 块里关掉了 errexit —— preflight 红了也不会停"; return 1
  fi
  if ! bash -n "$prefix" 2>/dev/null; then
    echo "preflight 接线被包在复合命令里 —— 它可能一次都不执行"; return 1
  fi
}

# ── 「红即停」还压在另外两层上,少一层都不成立 ────────────────────────────────
# 上面三道判据(整行 / 顺序 / 块顶层且 errexit 未被主动关掉)钉的全是【调用行长什么样】。
# 真正扛住「这一步红了就停」的还有两层,而它们此前一条契约都没有:
#   ① 被调的 retry_ssh_stdin —— 它必须把非 255 的退出码原样上抛。改成 `return 0` 与
#      ` || true` 同价(一行),却一次吞掉 preflight、deploy-restart、verify-deployed、
#      SHA 回读四条腿的退出码 —— 连仓库唯一那层「问生产你在跑哪个 commit」的观测也一起哑掉。
#   ② step 级的兄弟键 —— 挂 `continue-on-error: true` 时这一步仍会中止,但 job 判绿,
#      观测面直接说谎;`shell: bash {0}` 则把「GitHub Actions 默认 shell 自带 -e」这条前提
#      抽走,而上面那道判据明确选了「只挡被主动关掉」而不是「必须写 set -e」,理由正是这条
#      前提。今天块首确实写着 set -euo pipefail,所以覆写 shell 单独一条还拆不掉闸;但它把
#      两道保险变成一道,与「顺手删掉块首那句 set」一组合就真拆得掉。
# ⚠️ 仍是文本代理:证明的是「那句上抛还在、这一步没挂逃生键」,不是 GitHub 上的运行时行为。

# 精确切出 <函数名> 的函数体(`<名>() {` → 同缩进的 `}`)。必须切函数体,不能全文件 grep:
# retry_ssh 里有一模一样的一行,全文件 grep 会被它顶住 —— 只改 _stdin 那份照样绿。
fn_body() { # fn_body <文件> <函数名> → 回显函数体;定义不唯一 / 找不到同缩进闭合即非零
  awk -v fn="$2" '
    $0 ~ "^[[:space:]]*" fn "\\(\\) [{][[:space:]]*$" { seen++; start = NR; indent = match($0, /[^ ]/) }
    { line[NR] = $0 }
    END {
      if (seen != 1) exit 1
      for (i = start; i <= NR; i++) {
        print line[i]
        if (i > start && line[i] ~ /^[[:space:]]*\}[[:space:]]*$/ && match(line[i], /[^ ]/) == indent) exit 0
      }
      exit 1
    }' "$1"
}

# 承载 preflight 的那个 step 的整块:先认它的 `run: |`(step 的键层),据此推出列表项 `- `
# 的缩进,往上取到该 step 的第一行,往下停在下一个同缩进的列表项之前。缩进全部从文件自身
# 推出来、不写死数字 —— 整份 workflow 换缩进也仍然切得准。
deploy_step_block() { # deploy_step_block <workflow> <step 内某行号> → 回显该 step 整块
  awk -v target="$2" '
    { line[NR] = $0 }
    NR <= target && /^[[:space:]]*run: \|[[:space:]]*$/ { run_line = NR }
    END {
      if (run_line == 0) exit 1
      item = match(line[run_line], /[^ ]/) - 3   # run: 是 step 的键;键缩进 - 2 = 列表项缩进
      if (item < 0) exit 1
      for (i = run_line; i >= 1; i--)
        if (line[i] ~ /^[[:space:]]*-[[:space:]]/ && match(line[i], /[^ ]/) - 1 == item) { start = i; break }
      if (start == 0) exit 1
      for (end = start + 1; end <= NR; end++)
        if (line[end] ~ /^[[:space:]]*-[[:space:]]/ && match(line[end], /[^ ]/) - 1 == item) break
      end--
      if (target < start || target > end) exit 1
      for (i = start; i <= end; i++) print line[i]
    }' "$1"
}

# 块里必须【恰好一个】run: | —— 兼做上边界断言(切多了就会看见第二个)与键层缩进的来源。
sole_run_key_indent() { # sole_run_key_indent <step 块> → 回显 run: 的缩进空格数
  local hits pad
  hits="$(grep -cE '^[[:space:]]*run: \|[[:space:]]*$' "$1" || true)"
  [ "$hits" -eq 1 ] || return 1
  pad="$(grep -E '^[[:space:]]*run: \|[[:space:]]*$' "$1")"
  pad="${pad%%[![:space:]]*}"
  printf '%s\n' "${#pad}"
}

RETRY_RC_LINE='^[[:space:]]*if \[ "\$rc" -ne 255 \]; then return "\$rc"; fi[[:space:]]*$'

assert_callee_propagates_rc() { # assert_callee_propagates_rc <workflow>
  local body="$TEMP_DIR/retry_ssh_stdin.body" hits
  if ! fn_body "$1" retry_ssh_stdin > "$body"; then
    echo "ci/deploy-lib.sh 里没有唯一一个 retry_ssh_stdin() 函数体 —— 无从判断它会不会上抛退出码"; return 1
  fi
  hits="$(grep -cE "$RETRY_RC_LINE" "$body" || true)"
  if [ "$hits" -ne 1 ]; then
    echo "retry_ssh_stdin 没有原样上抛非 255 退出码(整行判据实得 $hits 条)—— 四条腿一起被吞"
    return 1
  fi
}

assert_step_has_no_escape_key() { # assert_step_has_no_escape_key <workflow> <preflight 行号>
  local block="$TEMP_DIR/deploy_step.yml" keys leg
  if ! deploy_step_block "$1" "$2" > "$block"; then
    echo "切不出承载 preflight 的那个 step —— 无从判断它挂没挂 step 级的逃生键"; return 1
  fi
  # 下边界:切出来的必须就是调 ci/deploy-push.sh 的那一个。五条腿已经不在 YAML 里了,
  # 它们的完整性改由 assert_legs_present 在 wiring 脚本上盯(见那里)。
  grep -qE "$PUSH_CALL" "$block" \
    || { echo "切出来的 step 不含 ci/deploy-push.sh 调用 —— 边界没切准"; return 1; }
  keys="$(sole_run_key_indent "$block")" \
    || { echo "切出来的 step 里不是恰好一个 run: | —— 边界没切准"; return 1; }
  # sequence item 的第一个键可以直接写在 `- ` 后面(本文件的 `- name:` / `- id:` 就是这么写的),
  # 那与另起一行写同一个键完全等价,字面缩进却差两个字符。先把列表项的 `- ` 换成两个空格 ——
  # 列宽不变,键的列号一字不差 —— 之后用同一个锚点判,两种写法一起盖住,精度也不放宽。
  local flat="$TEMP_DIR/deploy_step.flat.yml"
  sed -E 's/^( *)- /\1  /' "$block" > "$flat"
  # 只认【与 run: 同一层】的键:再深就只是 run 块里的脚本文本,YAML 上根本不生效。
  if grep -qE "^ {$keys}continue-on-error[[:space:]]*:" "$flat"; then
    echo "这一步挂了 continue-on-error —— 闸红了这一步会停,但 job 照样判绿,告警丢失"; return 1
  fi
  if grep -qE "^ {$keys}shell[[:space:]]*:" "$flat"; then
    echo "这一步覆写了 shell —— 「默认 shell 自带 -e」的前提没了,上面那道 errexit 判据跟着退化"
    return 1
  fi
}

# 五条腿必须齐:少一条就不是完整接线。原先这条压在 YAML step 的下边界断言里,
# 接线搬进脚本后跟着搬过来 —— 丢了它,「切准了没有」就没人证明了。
assert_legs_present() { # assert_legs_present <wiring 脚本>
  local leg
  for leg in "$PREFLIGHT_CALL" "$JAR_UPLOAD_CALL" "$JAR_RESTART_CALL" "$JAR_VERIFY_CALL" "$SHA_READBACK_CALL"; do
    grep -qE "$leg" "$1" || { echo "wiring 脚本少了某条腿($leg)—— 接线不完整"; return 1; }
  done
}

assert_wiring() { # assert_wiring <wiring 脚本> <workflow> <retry lib>
  local wiring=$1 workflow=$2 retrylib=$3
  local preflight upload restart push
  if ! grep -qE "$PREFLIGHT_CALL" "$wiring" \
     && grep -qE "$PREFLIGHT_CALL_LOOSE" "$wiring"; then
    echo "deploy.yml 的 preflight 接线不是整行调用:行尾有后缀,失败会被吞掉"; return 1
  fi
  preflight="$(sole_executable_line "$wiring" "$PREFLIGHT_CALL" "deploy.yml 的 preflight 接线")" || {
    echo "$preflight"; return 1; }
  upload="$(sole_executable_line "$wiring" "$JAR_UPLOAD_CALL" "deploy.yml 的传 jar 调用")" || {
    echo "$upload"; return 1; }
  restart="$(sole_executable_line "$wiring" "$JAR_RESTART_CALL" "deploy.yml 的换 jar/重启调用")" || {
    echo "$restart"; return 1; }
  [ "$preflight" -lt "$upload" ] || { echo "preflight 排在传 jar 之后 —— 失败时 jar 已经上去了"; return 1; }
  [ "$preflight" -lt "$restart" ] || { echo "preflight 排在换 jar/重启之后"; return 1; }
  assert_legs_present "$wiring" || return 1
  assert_step_aborts_on_preflight "$wiring" "$preflight" || return 1
  assert_callee_propagates_rc "$retrylib" || return 1
  # ★ workflow 侧还剩一行接线(调 ci/deploy-push.sh)。它断了、或那一步挂了逃生键,
  #   脚本里五条腿排得再整齐也白搭 —— 这是搬出 YAML 之后【新增】的一层失守面。
  push="$(sole_executable_line "$workflow" "$PUSH_CALL" "deploy.yml 里调 ci/deploy-push.sh 的接线")" || {
    echo "$push"; return 1; }
  assert_step_has_no_escape_key "$workflow" "$push" || return 1
}

err=""
assert_wiring "$WIRING_FILE" "$DEPLOY_WORKFLOW" "$RETRY_LIB" >/dev/null 2>&1 \
  || err=" $(assert_wiring "$WIRING_FILE" "$DEPLOY_WORKFLOW" "$RETRY_LIB" 2>&1 || true)"
check deploy_wiring_present_and_ordered "$err"

# ★ 负控只断言「红了」是不够的:相邻的几道判据会互相接住,红可能红在别处
#   (实测:把"恰好一条"退化成"至少一条"时,顺序断言会因为拿到两行行号做整数比较而报错,
#    照样非零 —— 于是"复制一份"的负控看上去仍绿,而它要保护的那条判据其实已经没了)。
#   故每条负控都必须指明【是哪道闸红的】,红在别处一律判负控失效。
assert_wiring_red_because() { # <wiring> <workflow> <retrylib> <期望文案片段>
  local message
  if message="$(assert_wiring "$1" "$2" "$3" 2>&1)"; then
    printf ' 变异后仍判通过'
    return
  fi
  case "$message" in
    *"$4"*) ;;
    *) printf ' 红了但不是因为「%s」,实得:%s' \
         "$4" "$(printf '%s' "$message" | tr '\n' ' ')" ;;
  esac
}

# 接线拆成三个文件之后,每条负控必须说清【变异的是哪一个】,其余两个用真文件 ——
# 否则一次变异同时动两个槽,红了也分不清是哪道判据接住的。
wiring_red_because()   { assert_wiring_red_because "$1" "$DEPLOY_WORKFLOW" "$RETRY_LIB" "$2"; }
workflow_red_because() { assert_wiring_red_because "$WIRING_FILE" "$1" "$RETRY_LIB" "$2"; }
retrylib_red_because() { assert_wiring_red_because "$WIRING_FILE" "$DEPLOY_WORKFLOW" "$1" "$2"; }

# 变异用的接线原文一律从真文件里取。手抄一份的话,deploy.yml 的引号/参数一改,
# 抄本就不再匹配严格判据 —— 负控会红在"不是整行调用"上,而它要保护的那道判据其实没被测到。
PREFLIGHT_LINE="$(grep -E "$PREFLIGHT_CALL" "$WIRING_FILE" | head -n 1)"
err=""
[ -n "$PREFLIGHT_LINE" ] || err=' 取不到 preflight 接线原文,下面各条负控会变成空跑'
check deploy_wiring_mutation_source_found "$err"

# 负控之一:把接线整行摘掉必须红,且必须红在「条数不对」这道判据上
mutated="$TEMP_DIR/deploy_dropped.sh"
grep -v 'ci/preflight-h8-schema\.sh' "$WIRING_FILE" > "$mutated"
err=""
if cmp -s "$WIRING_FILE" "$mutated"; then
  err=" 变异未生效,负控本身是假绿"
else
  err="$(wiring_red_because "$mutated" '实得 0 条')"
fi
check deploy_wiring_removal_is_red "$err"

# 负控之二:接线还在、但被挪到换 jar 之后也必须红,且必须红在【顺序】那道判据上
mutated="$TEMP_DIR/deploy_reordered.sh"
{
  grep -v 'ci/preflight-h8-schema\.sh' "$WIRING_FILE"
  printf '%s\n' "$PREFLIGHT_LINE"
} > "$mutated"
err="$(wiring_red_because "$mutated" '排在传 jar 之后')"
check deploy_wiring_order_is_red "$err"

# 负控之三:把接线整行【注释掉】必须红。这是最省事、也最可能发生的关闸方式
# (「临时注释解个急,然后忘了改回来」)——一个 '#' 就能让「迁移必须先于 jar」这条
# 唯一的机器观测点静默失效,而 CI 全绿、契约全绿、没有任何信号。
mutated="$TEMP_DIR/deploy_commented.sh"
sed -E 's|^([[:space:]]*)(retry_ssh_stdin ci/preflight-h8-schema\.sh )|\1# \2|' \
  "$WIRING_FILE" > "$mutated"
err=""
if cmp -s "$WIRING_FILE" "$mutated"; then
  err=" 变异未生效,负控本身是假绿"
else
  err="$(wiring_red_because "$mutated" '实得 0 条')"
fi
check deploy_wiring_commented_out_is_red "$err"

# 负控之四:接线还在、但被【复制】一份到换 jar 之后必须红,且必须红在「条数不对」上。
# 只取首个命中行会放过它,而多出来的那次调用跑在 jar 已经上去之后 ——
# 「不过就停、jar 未被替换」已不成立。
mutated="$TEMP_DIR/deploy_duplicated.sh"
{
  cat "$WIRING_FILE"
  printf '%s\n' "$PREFLIGHT_LINE"
} > "$mutated"
err="$(wiring_red_because "$mutated" '实得 2 条')"
check deploy_wiring_duplicate_is_red "$err"

# ── 负控五~七:「存在 + 顺序」证明不了【它红了这一步就停】────────────────────
# 下面三种拆闸方式都保住了"行首直接是调用本体、恰好一条、排在传 jar 之前",
# 而闸要么失败被吞、要么压根不执行。它们与「注释掉」同族、同样省事:
# `|| true` 8 个字符,`set +e` 6 个,`if false; then` 一行 —— 都是"临时绕一下,然后忘了改回来"。
wrap_preflight_line() { # wrap_preflight_line <前置行|空> <后置行|空> <行尾追加|空>
  PL="$PREFLIGHT_LINE" BEFORE="$1" AFTER="$2" SUFFIX="$3" awk '
    BEGIN { line = ENVIRON["PL"]; before = ENVIRON["BEFORE"]
            after = ENVIRON["AFTER"]; suffix = ENVIRON["SUFFIX"] }
    $0 == line {
      if (before != "") print before
      print $0 suffix
      if (after != "") print after
      next
    }
    { print }' "$WIRING_FILE"
}

mutate_and_check() { # mutate_and_check <检查名> <文件名> <期望文案> <前置> <后置> <行尾>
  local mutated="$TEMP_DIR/$2"
  wrap_preflight_line "$4" "$5" "$6" > "$mutated"
  local err=""
  if cmp -s "$WIRING_FILE" "$mutated"; then
    err=" 变异未生效,负控本身是假绿"
  else
    err="$(wiring_red_because "$mutated" "$3")"
  fi
  check "$1" "$err"
}

# 负控之五:行尾追加 `|| true` —— 失败被吞掉,后面照样传 jar、换 jar。
# 必须红在【整行锚定】那道判据上,不能与"整行摘掉"共用同一句话,否则谁都证明不了自己还在。
mutate_and_check deploy_wiring_trailing_or_true_is_red deploy_or_true.sh \
  '不是整行调用' '' '' ' || true'

# 负控之六:用 set +e 把它包起来 —— errexit 被关掉,闸红了也不停。
mutate_and_check deploy_wiring_errexit_disabled_is_red deploy_errexit_off.sh \
  '关掉了 errexit' 'set +e' 'set -e' ''

# 负控之七:用 if false 包住 —— 顺序、条数、整行全都满足,而这一行一次都不执行。
mutate_and_check deploy_wiring_nested_in_conditional_is_red deploy_nested.sh \
  '被包在复合命令里' 'if false; then' 'fi' ''

# ── 负控八~十二:另外那两层的负控 ───────────────────────────────────────────
# 三条拆闸负控各自红在自己那句话上;另加两条【精度】负控:把同样的改法落到别的函数 /
# 别的 step 上必须【保持绿】—— 否则这两道判据只是全文件 grep,红得再响也证明不了它钉的是哪。
PUSH_LINE_NO="$(grep -nE "$PUSH_CALL" "$DEPLOY_WORKFLOW" | head -n 1 | cut -d: -f1)"
OTHER_RUN_LINE="$(grep -nE '^[[:space:]]*run: \|[[:space:]]*$' "$DEPLOY_WORKFLOW" | head -n 1 | cut -d: -f1)"
err=""
[ -n "$PUSH_LINE_NO" ] && [ -n "$OTHER_RUN_LINE" ] && [ "$OTHER_RUN_LINE" -lt "$PUSH_LINE_NO" ] \
  || err=' 取不到 deploy-push 接线行号 / 别的 step 的 run:,下面五条负控会变成空跑'
check deploy_step_mutation_source_found "$err"

# 只改指定函数体里那句上抛 —— 精度负控靠切换函数名复用同一个变异器。
mutate_fn_swallow_rc() { # mutate_fn_swallow_rc <函数名> → 回显变异后的 workflow
  awk -v fn="$1" '
    $0 ~ "^[[:space:]]*" fn "\\(\\) [{][[:space:]]*$" { inside = 1; indent = match($0, /[^ ]/) }
    inside && /^[[:space:]]*\}[[:space:]]*$/ && match($0, /[^ ]/) == indent { inside = 0 }
    inside && /^[[:space:]]*if \[ "\$rc" -ne 255 \]; then return "\$rc"; fi[[:space:]]*$/ {
      sub(/return "\$rc"/, "return 0") }
    { print }' "$RETRY_LIB"
}

# 键插在该 step 的 `run:` 之【前】——真要挂逃生键的人就挂在这。插在 run: 之后会把块标量
# 截断,那是另一种改动,会红在别的判据上,证明不了这一条。
insert_step_key() { # insert_step_key <step 内某行号> <键文本> → 回显变异后的 workflow
  awk -v target="$1" -v key="$2" '
    { line[NR] = $0 }
    NR <= target && /^[[:space:]]*run: \|[[:space:]]*$/ { run_line = NR }
    END {
      if (run_line == 0) exit 1
      pad = match(line[run_line], /[^ ]/) - 1
      for (i = 1; i <= NR; i++) {
        if (i == run_line) printf "%*s%s\n", pad, "", key
        print line[i]
      }
    }' "$DEPLOY_WORKFLOW"
}

# 同一个键写成 sequence item 的【第一个键】(与 `- ` 同一行)—— YAML 上与上面那种写法完全
# 等价,而且 deploy.yml 自己的 `- name:` / `- id:` 就是这么写的,是这份文件里更自然的风格。
# 字面缩进锚点对它对不上,故单独给它一个变异器:把原来的首键挤下去一行,自己顶上破折号。
insert_step_item_key() { # insert_step_item_key <step 内某行号> <键文本> → 回显变异后的 workflow
  awk -v target="$1" -v key="$2" '
    { line[NR] = $0 }
    NR <= target && /^[[:space:]]*run: \|[[:space:]]*$/ { run_line = NR }
    END {
      if (run_line == 0) exit 1
      item = match(line[run_line], /[^ ]/) - 3
      if (item < 0) exit 1
      for (i = run_line; i >= 1; i--)
        if (line[i] ~ /^[[:space:]]*-[[:space:]]/ && match(line[i], /[^ ]/) - 1 == item) { start = i; break }
      if (start == 0) exit 1
      for (i = 1; i <= NR; i++) {
        if (i == start) {
          printf "%*s- %s\n", item, "", key
          # 原首键就地降格成普通键:`- ` 换成两个空格,列号一字不差 ⇒ 仍是同一个 mapping。
          line[i] = sprintf("%*s  %s", item, "", substr(line[i], item + 3))
        }
        print line[i]
      }
    }' "$DEPLOY_WORKFLOW"
}

mutated_is_red_because() { # <检查名> <变异后的 workflow> <期望文案>
  local err=""
  if cmp -s "$DEPLOY_WORKFLOW" "$2"; then
    err=" 变异未生效,负控本身是假绿"
  else
    err="$(workflow_red_because "$2" "$3")"
  fi
  check "$1" "$err"
}

# 负控之八:retry_ssh_stdin 把非 255 退出码吞成 0 —— 四条腿(含唯一那层生产回读观测)一起哑掉
mutated="$TEMP_DIR/deploy_callee_swallows.sh"
mutate_fn_swallow_rc retry_ssh_stdin > "$mutated"
err=""
if cmp -s "$RETRY_LIB" "$mutated"; then
  err=" 变异未生效,负控本身是假绿"
else
  err="$(retrylib_red_because "$mutated" '没有原样上抛非 255 退出码')"
fi
check deploy_callee_swallows_rc_is_red "$err"

# 精度:同一句上抛在 retry_ssh 里也有一份,只改它必须【保持绿】。否则上面那条就是全文件
# grep,改哪个函数都红,证明不了它钉住的是 retry_ssh_stdin。
# (retry_ssh 只承载 deploy_start 取值,不在本契约范围;它被吞码也不静默 —— deploy_start
#  变空,后面 verify-deployed.sh 当场红。)
mutated="$TEMP_DIR/deploy_sibling_fn.sh"
mutate_fn_swallow_rc retry_ssh > "$mutated"
err=""
if cmp -s "$RETRY_LIB" "$mutated"; then
  err=" 变异未生效,负控本身是假绿"
elif ! assert_wiring "$WIRING_FILE" "$DEPLOY_WORKFLOW" "$mutated" >/dev/null 2>&1; then
  err=" 改的是 retry_ssh 却红了 —— 那条判据没切准函数体,是全文件 grep"
fi
check deploy_callee_assertion_is_scoped_to_stdin "$err"

# 负控之九:step 挂 continue-on-error: true —— 闸仍中止这一步,但整个 job 报绿,告警丢失。
mutated="$TEMP_DIR/deploy_continue_on_error.yml"
insert_step_key "$PUSH_LINE_NO" 'continue-on-error: true' > "$mutated"
mutated_is_red_because deploy_step_continue_on_error_is_red "$mutated" '挂了 continue-on-error'

# 负控之十:step 覆写 shell —— 上面那道 errexit 判据所依赖的「默认自带 -e」前提被抽掉。
mutated="$TEMP_DIR/deploy_shell_override.yml"
insert_step_key "$PUSH_LINE_NO" 'shell: bash {0}' > "$mutated"
mutated_is_red_because deploy_step_shell_override_is_red "$mutated" '覆写了 shell'

# 精度:同样的键挂到【别的 step】上必须【保持绿】—— 别的 step 合法地挂 continue-on-error
# 与这道闸无关,全文件 grep 会把它一并判红(既误报,又证明不了目标 step 被钉住)。
mutated="$TEMP_DIR/deploy_other_step_key.yml"
insert_step_key "$OTHER_RUN_LINE" 'continue-on-error: true' > "$mutated"
err=""
if cmp -s "$DEPLOY_WORKFLOW" "$mutated"; then
  err=" 变异未生效,负控本身是假绿"
elif ! grep -qE '^[[:space:]]*continue-on-error[[:space:]]*:' "$mutated"; then
  err=" 键没插进去,精度负控是空跑"
elif ! assert_wiring "$WIRING_FILE" "$mutated" "$RETRY_LIB" >/dev/null 2>&1; then
  err=" 别的 step 挂了键却把本契约判红 —— 判据没切准 step,是全文件 grep"
fi
check deploy_step_assertion_is_scoped_to_that_step "$err"

# ── 负控十一~十二:同两个键、同一个 step,只换成「与列表破折号同一行」的写法 ────
# 上面两条负控把键插成【独立一行】,判据靠的是字面缩进锚点。但 YAML 里 sequence item 的第一个
# 键可以直接跟在 `- ` 后面(deploy.yml 自己的 `- name:` / `- id:` 就是),语义一模一样、字面
# 却差两个字符。少了下面这两条,「这一步没挂逃生键」只证到了其中一种写法,另一种静默全绿。
mutated="$TEMP_DIR/deploy_inline_continue_on_error.yml"
insert_step_item_key "$PUSH_LINE_NO" 'continue-on-error: true' > "$mutated"
mutated_is_red_because deploy_step_inline_continue_on_error_is_red "$mutated" '挂了 continue-on-error'

mutated="$TEMP_DIR/deploy_inline_shell_override.yml"
insert_step_item_key "$PUSH_LINE_NO" 'shell: bash {0}' > "$mutated"
mutated_is_red_because deploy_step_inline_shell_override_is_red "$mutated" '覆写了 shell'

# 精度:同行写法挂到【别的 step】上必须【保持绿】。归一化是对整块做的,做过头(比如对整份
# workflow 归一、或把锚点放宽成任意缩进)就会把别的 step 一并判红 —— 那样上面两条红得再响,
# 钉住的也不再是这一个 step。
mutated="$TEMP_DIR/deploy_other_step_inline_key.yml"
insert_step_item_key "$OTHER_RUN_LINE" 'continue-on-error: true' > "$mutated"
err=""
if cmp -s "$DEPLOY_WORKFLOW" "$mutated"; then
  err=" 变异未生效,精度负控是假绿"
elif ! grep -qE '^[[:space:]]*-[[:space:]]+continue-on-error[[:space:]]*:' "$mutated"; then
  err=" 键没插成同行写法,精度负控是空跑"
elif ! assert_wiring "$WIRING_FILE" "$mutated" "$RETRY_LIB" >/dev/null 2>&1; then
  err=" 别的 step 用同行写法挂了键却把本契约判红 —— 归一化做过头,判据不再钉这一个 step"
fi
check deploy_step_inline_key_assertion_is_scoped_to_that_step "$err"

# ── 负控十三~十四:2026-08-26 接线搬进脚本后【新增】的两层失守面 ──────────────
# 这两条断言是这次改造新加的,必须自带负控 —— 新加的判据没负控,就是新加的恒真。

# 负控之十三:workflow 侧那一行接线被摘掉。脚本里五条腿排得再整齐,没人调它就全白搭。
# 这是搬出 YAML 之后凭空多出来的一层:以前接线即执行,现在接线与执行分了家。
mutated="$TEMP_DIR/deploy_push_call_dropped.yml"
grep -v 'bash ci/deploy-push\.sh' "$DEPLOY_WORKFLOW" > "$mutated"
err=""
if cmp -s "$DEPLOY_WORKFLOW" "$mutated"; then
  err=" 变异未生效,负控本身是假绿"
else
  err="$(workflow_red_because "$mutated" '实得 0 条')"
fi
check deploy_push_call_removal_is_red "$err"

# 负控之十四:wiring 脚本自己不写 set -e。
# 在 deploy.yml 里这条由「GitHub Actions 默认 shell 是 bash -e {0}」兜底,所以旧判据只挡
# 「被主动关掉」。搬进脚本后那个兜底没了 —— 删掉块首那句 set,preflight 红了也照样往下传 jar,
# 而调用方一侧完全看不出来。
mutated="$TEMP_DIR/deploy_no_errexit.sh"
grep -v '^set -euo pipefail' "$WIRING_FILE" > "$mutated"
err=""
if cmp -s "$WIRING_FILE" "$mutated"; then
  err=" 变异未生效,负控本身是假绿"
else
  err="$(wiring_red_because "$mutated" '没有显式 set -e')"
fi
check deploy_wiring_missing_errexit_is_red "$err"

# ── ⑨ 本契约自己也必须被 CI 真跑,否则它是一份没人执行的文件 ──────────────────
CI_WORKFLOW="$REPO_DIR/.github/workflows/ci.yml"
count_ci_wiring() { grep -c '^[[:space:]]*run: bash ci/test_preflight_h8_schema\.sh$' "$1" || true; }

err=""
[ "$(count_ci_wiring "$CI_WORKFLOW")" -eq 1 ] \
  || err=' ci.yml 里没有恰好一条 "bash ci/test_preflight_h8_schema.sh" 接线'
check ci_wiring_present "$err"

mutated="$TEMP_DIR/ci_dropped.yml"
grep -v '^[[:space:]]*run: bash ci/test_preflight_h8_schema\.sh$' "$CI_WORKFLOW" > "$mutated"
err=""
if cmp -s "$CI_WORKFLOW" "$mutated"; then
  err=" 变异未生效,负控本身是假绿"
elif [ "$(count_ci_wiring "$mutated")" -eq 1 ]; then
  err=" 摘掉 ci.yml 接线却仍判通过"
fi
check ci_wiring_removal_is_red "$err"

# 与 deploy.yml 同一条纪律:CI 自接线也只认【可执行行】,注释掉必须红。
mutated="$TEMP_DIR/ci_commented.yml"
sed -E 's|^([[:space:]]*)(run: bash ci/test_preflight_h8_schema\.sh)$|\1# \2|' \
  "$CI_WORKFLOW" > "$mutated"
err=""
if cmp -s "$CI_WORKFLOW" "$mutated"; then
  err=" 变异未生效,负控本身是假绿"
elif [ "$(count_ci_wiring "$mutated")" -eq 1 ]; then
  err=" ci.yml 接线被注释掉却仍判通过"
fi
check ci_wiring_commented_out_is_red "$err"

echo "通过 $pass,失败 $fail"
[ "$fail" -eq 0 ]
