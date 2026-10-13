#!/usr/bin/env bash
# 生产机部署:换掉后台前端(chengyinhub-ui)的静态产物。
# 由 .github/workflows/deploy-ui.yml 经 ssh 管道喂进来执行(bash -s -- <tar路径> <sha>)。
#
# ★ web 根不是我们独占的目录 —— 宝塔和证书链路也往里放东西。裸 rsync --delete 会把它们
#   一起清掉,其中 .well-known 被删 = ACME 续期失败 = 几十天后 HTTPS 静默过期。
#   这个仓库有过「证书过期全站看不了」的前科,而且这类故障零告警、延迟爆炸,
#   等发现时早已忘了是哪次部署干的。故 KEEP 里的东西既不同步、也不删。
#
# ⚠️ .user.ini 被宝塔 chattr +i 锁着,rsync 想改它会直接报错中断部署 —— 必须在 KEEP 里。
# ⚠️ 404.html 是宝塔放的,dist/ 里没有对应文件 —— 不 KEEP 就会被 --delete 清掉。
#
# 为什么是两次 rsync 而不是一次:vue build 出来的 static/ 文件名带 hash,新旧不撞名。
#   先铺资源(不删)、再切 index.html(带 --delete-after 清旧),这样 index.html 落地时,
#   它引用的新 js/css 一定已经在位。一次 rsync 做不到 —— 字母序 index.html 排在 static/
#   前面,会开一个「新 html 引用着还没传到的新资源」的白屏窗口。
#
# 用法:bash ci/deploy-ui.sh /path/to/dist.tar.gz <期望commit>
set -euo pipefail

# 2026-07-26:后台前端迁到独立域名 houtai.chengyinhub.com,把 www 让给官网。
#   www 站点仍保留 /prod-api/ 反代(小程序 app.js 写死了它),两个站点各自反代到同一个 8080。
WEB_ROOT=/www/wwwroot/houtai.chengyinhub.com
BACKUP_DIR=/www/wwwroot/ui-backups
KEEP_BACKUPS=10
TAR="${1:?缺少 tar 路径}"
WANT_COMMIT="${2:?缺少期望 commit}"
STAGE=

# 这些是别人的东西,我们只负责绕开。改这个列表前先想清楚:漏一个 = 删生产的东西。
KEEP=(.well-known .user.ini .htaccess 404.html)

cleanup() { [ -n "$STAGE" ] && rm -rf "$STAGE"; }
trap cleanup EXIT

[ -s "$TAR" ] || { echo "找不到或空的 tar:$TAR"; exit 1; }
[ -d "$WEB_ROOT" ] || { echo "找不到 web 根:$WEB_ROOT"; exit 1; }

echo "== 1/5 解压到暂存区 =="
STAGE=$(mktemp -d)
# --no-same-owner:tar 里带的是 runner 那台 Mac 的 uid(501),原样解出来生产上没这个用户,
# 目录 owner 会变成「501 games」—— 现在生产上就是这副样子,正是手动部署留下的。
tar xzf "$TAR" -C "$STAGE" --no-same-owner

echo "== 2/5 核对包里自报的 commit =="
# 传错包 / 传了半截包,在这里就要红,不能等它铺到 web 根之后。
got_commit=$(sed -n 's/.*"commit":"\([0-9a-f]*\)".*/\1/p' "$STAGE/version.json" 2>/dev/null || true)
if [ "$got_commit" != "$WANT_COMMIT" ]; then
  echo "❌ 包自报 commit=${got_commit:-无} 期望=$WANT_COMMIT"
  exit 1
fi
[ -s "$STAGE/index.html" ] || { echo "❌ 包里没有 index.html,不像个完整的前端产物"; exit 1; }
echo "   ✅ $got_commit"

echo "== 3/5 备份现网(放在 web 根外)=="
# ⚠️ 备份绝不能落在 WEB_ROOT 里 —— 那样就是公网可下载的归档,
#   生产上那个 chengyinhub-ui-web-20260705.tar.gz(HTTP 200,3.7MB)就是这么来的。
# ★ 这里不吞错、也不 `|| true`:下一步就是 rsync --delete,备份没成还往下走 =
#   在没有回滚点的情况下删生产文件。备份失败就该停在这。
mkdir -p "$BACKUP_DIR"
BACKUP="$BACKUP_DIR/webroot-$(date +%Y%m%d%H%M%S).tar.gz"
if ! tar czf "$BACKUP" -C "$WEB_ROOT" .; then
  echo "❌ 备份失败 —— 没有回滚点就不动生产,中止"
  exit 1
fi
echo "   备份:$BACKUP"

# 只留最近 KEEP_BACKUPS 份。不留保留策略的下场就在隔壁:/www/wwwroot/bin/backups
# 攒了 112 个 jar = 11GB(占整机已用空间四成),没人会回滚到 112 次之前那一版。
# 前端每份才 ~3.7MB,10 份 = 37MB,回滚绰绰有余。
# tail -n +N+1 取「第 N 个之后的」= 最老的那些;xargs -r 保证空列表时不执行 rm。
# 末尾 `|| true` 是有意的:清旧备份是非关键路径 —— 本次备份已经存好了,清不掉历史
# 只是占磁盘,不影响这次部署的正确性,不该让一次成功的部署变红。
# (另:set -euo pipefail 下 ls 无匹配会非零退出并被 pipefail 传导,没有这层就会直接杀掉脚本。)
ls -1t "$BACKUP_DIR"/webroot-*.tar.gz 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) \
  | xargs -r rm -f || true

chown -R www:www "$STAGE"

excludes=()
for k in "${KEEP[@]}"; do excludes+=(--exclude="/$k"); done

# ★ 必须 --checksum:rsync 默认拿 mtime+size 做快速判断,一样就跳过不传。
#   static/ 下的文件名带 hash,新旧不同名,无所谓;但 index.html 和 version.json 是
#   同名可变的 —— 尤其 version.json,内容是「40 位 hex + 固定格式时间戳」,
#   **size 恒定不变**,它到底有没有被更新完全押在 mtime 上。押中了也是运气。
#   -c 改成比内容,3.7MB 的站点算一遍校验和的开销可以忽略,换掉一个隐含假设很值。
echo "== 4/5 先铺新资源(不删任何东西)=="
rsync -ac "${excludes[@]}" --exclude='/index.html' --exclude='/index.html.gz' \
  "$STAGE/" "$WEB_ROOT/"

echo "== 5/5 再切 index.html,并清掉旧资源与历史残留 =="
# --delete-after:先传完再删,删除动作发生在所有新文件就位之后。
# 这一步顺带清掉手动部署年代的垃圾:._* (macOS AppleDouble)、嵌套的 dist/、
# 以及躺在 web 根里公网可下的 tar 包。
rsync -ac --delete-after "${excludes[@]}" "$STAGE/" "$WEB_ROOT/"

echo "部署完成:$WANT_COMMIT"
