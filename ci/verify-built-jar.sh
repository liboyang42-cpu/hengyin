#!/usr/bin/env bash
# 构建产物必须自报「我来自哪个 commit」,且等于本次要部署的那个。
#
# 抽自 .github/workflows/deploy.yml 的「Verify the built jar declares the commit…」(2026-08-26)。
#
# 这条把「构建产物 ↔ commit」钉死;deploy-push.sh 里的 sha256 指纹比对把
# 「生产的 jar ↔ 构建产物」钉死。两条相乘,才敢说「生产上跑的就是这个 commit」。
# 少任何一条,「已部署」就只是回执,不是观测。
#
# 用法:bash ci/verify-built-jar.sh <期望的 commit sha>
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1
. ci/deploy-lib.sh

DEPLOY_SHA="${1:?缺少期望的 commit sha}"
JAR=chengyinhub-admin/target/chengyinhub-admin.jar

built_sha="$(jar_commit_id "$JAR")"

if [ -z "$built_sha" ]; then
  die "jar 里没有 git.properties —— git-commit-id-plugin 没跑或产物不对,无法证明它来自哪个 commit"
fi
if [ "$built_sha" != "$DEPLOY_SHA" ]; then
  die "jar 自报 commit=$built_sha,但本次要部署的是 $DEPLOY_SHA"
fi

log "Built jar declares commit $built_sha"
