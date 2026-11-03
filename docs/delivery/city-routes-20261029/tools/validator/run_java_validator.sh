#!/bin/sh
# 用仓库里 AdvancedGameConfigValidator 的原文（javac 单独编译，桩见 java-sources.bundle.txt；不跑 Maven、不起服务）校验配置。
# 用法：run_java_validator.sh configs.json [--quiet]
# 环境：REPO（默认本仓库根）、JARS（含 jackson 2.x 与 commons-lang3 的目录，默认 /opt/gradle-8.14.3/lib）
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=${REPO:-$(cd "$HERE/../../../../.." && pwd)}
JARS=${JARS:-/opt/gradle-8.14.3/lib}
OUT=${OUT:-${TMPDIR:-/tmp}/cy-validator-classes}
CP=$(ls "$JARS"/jackson-annotations-*.jar "$JARS"/jackson-core-*.jar "$JARS"/jackson-databind-*.jar "$JARS"/commons-lang3-*.jar | tr '\n' ':')
if [ ! -f "$OUT/RunValidator.class" ]; then
  mkdir -p "$OUT/src"
  # 桩与入口以文本包形式存放（避免仓库里出现游离的 .java 文件），这里解包
  awk -v dir="$OUT/src" '/^=== .* ===$/ {f=dir"/"$2; sub(/\/[^\/]*$/,"",f); system("mkdir -p \""f"\""); out=dir"/"$2; next} {print > out}' "$HERE/java-sources.bundle.txt"
  SP="$OUT/src/javastub:$REPO/chengyinhub-system/src/main/java:$REPO/chengyinhub-common/src/main/java"
  javac -nowarn -encoding UTF-8 -proc:none -d "$OUT" -cp "$CP" -sourcepath "$SP" "$OUT/src/javarunner/RunValidator.java" >/dev/null 2>&1 || \
  javac -encoding UTF-8 -proc:none -d "$OUT" -cp "$CP" -sourcepath "$SP" "$OUT/src/javarunner/RunValidator.java"
fi
exec java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$OUT:$CP" RunValidator "$@"
