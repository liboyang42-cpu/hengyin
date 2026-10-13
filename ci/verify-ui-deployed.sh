#!/usr/bin/env bash
# 部署后核对:公网上的后台前端,确实是本次构建的那一版,而且对浏览器是完整的。
# 在 runner 上跑(不在生产机上跑)—— 要的就是「从外面看进去」这个视角。
#
# ★ 为什么不能只比 version.json:那个文件是我们自己 build 时写进去的。只读它、只比 SHA,
#   离「把 DEPLOY_SHA 写进生产机再读回来跟 DEPLOY_SHA 比」那种恒真断言只有一步之遥。
#   它能回答的仅仅是「产物有没有铺到 nginx 正在服务的目录」—— 有用,但不够。
#   所以第 2 步换个问法:让生产把它自己的 index.html 吐出来,照着**它**引用的资源逐个拉。
#   清单来自生产,不是来自我们手里的 dist —— 少一个 hash 文件、rsync 半路死、
#   nginx 指向别的目录,都会在这里红。这才是「问生产,不是问自己」。
#
# 用法:bash ci/verify-ui-deployed.sh <base-url> <期望commit>
set -euo pipefail

BASE="${1:?缺少 base url,例:https://houtai.chengyinhub.com}"
WANT_COMMIT="${2:?缺少期望 commit}"

# 每次部署 sha 都不同,拿它当 cache-buster;再叠一个 no-cache 头。
# 不这么做,读到 nginx/中间层缓存的旧副本就是一次假绿。
CB="$WANT_COMMIT"
curl_get() { curl -fsS --max-time 20 -H 'Cache-Control: no-cache' -H 'Pragma: no-cache' "$1"; }

echo "== 1/2 产物已铺到 nginx 正在服务的目录 =="
live_json=$(curl_get "$BASE/version.json?cb=$CB") || {
  echo "❌ 拉不到 $BASE/version.json —— 产物没到位,或 nginx 根不是部署目标"; exit 1; }
live_commit=$(printf '%s' "$live_json" | sed -n 's/.*"commit":"\([0-9a-f]*\)".*/\1/p')
if [ "$live_commit" != "$WANT_COMMIT" ]; then
  echo "❌ 线上自报 commit=${live_commit:-无} 期望=$WANT_COMMIT"
  echo "   原文:$live_json"
  exit 1
fi
echo "   ✅ $live_commit"

echo "== 2/2 生产吐出的 index.html,它引用的资源全部可取 =="
html=$(curl_get "$BASE/index.html?cb=$CB") || { echo "❌ 拉不到 index.html"; exit 1; }

# 清单从生产的 html 里现挖,不用本地 dist 的。
# ★ 末尾的 `|| true` 不能省:set -euo pipefail 下,grep 匹配不到会返回 1,pipefail 把它
#   传导成整条赋值失败,脚本当场静默退出 —— 下面那句「一个资源都没引用」永远打不出来,
#   变成死代码。实测过:退出码确实是 1(红对了),但运维只看到空白,不知道为什么红。
assets=$(printf '%s' "$html" \
  | grep -oE '(src|href)=["'"'"']?/static/[^"'"'"' >]+\.(js|css)' \
  | sed -E 's/^(src|href)=["'"'"']?//' | sort -u || true)

count=$(printf '%s' "$assets" | grep -c . || true)
if [ "$count" -lt 1 ]; then
  echo "❌ 线上 index.html 里一个 /static 资源都没引用 —— 它不像 vue 构建产物,链路有问题"
  exit 1
fi

missing=0
while IFS= read -r a; do
  [ -n "$a" ] || continue
  # 这里**不能**加 -f:我们要的是 http_code 本身,而 -f 会在 404 时既打印 404 又非零退出,
  # 于是 `|| echo 000` 一起追加,变成 "404000"。不带 -f 时 404 照样 exit 0、如实吐 404;
  # 真连不上才走 || 分支拿 000。(deploy-restart.sh 里记过同源的坑)
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 \
           -H 'Cache-Control: no-cache' "$BASE$a?cb=$CB" 2>/dev/null || echo 000)
  if [ "$code" != "200" ]; then
    echo "   ❌ $a → HTTP $code"
    missing=1
  fi
done <<< "$assets"

if [ "$missing" -ne 0 ]; then
  echo "❌ 线上 index.html 引用的资源有缺失 —— 用户现在打开后台就是白屏"
  exit 1
fi
echo "   ✅ $count 个资源全部 200"

echo "线上确认在跑:$WANT_COMMIT"
