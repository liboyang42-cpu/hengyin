#!/usr/bin/env bash
# 把原型帧与玩页帧并排:pairs.sh <protoDir> <mineDir> <outDir> <moment> <look>
# 每个时间点一张 pair-*.jpg(左原型 · 右小程序);另出按 4 个时间点一张的 sheet(缩半)。
set -e
pd=$1; md=$2; od=$3; m=$4; look=$5; mkdir -p "$od"
list=()
for f in "$pd"/m$m-$look-*.jpg; do
  t=$(basename "$f" .jpg); t=${t#m$m-$look-}
  [ -f "$md/m$m-$look-$t.jpg" ] || continue
  convert "$f" "$md/m$m-$look-$t.jpg" -resize x844 -background '#444' -splice 12x0 +append -gravity NorthWest -fill yellow -pointsize 14 -annotate +6+4 "$t  proto | mine" -quality 82 "$od/pair-m$m-$look-$t.jpg"
  list+=("$od/pair-m$m-$look-$t.jpg")
done
i=0; k=1
while [ $i -lt ${#list[@]} ]; do
  convert "${list[@]:$i:4}" -resize 50% +append -quality 82 "$od/sheet-m$m-$look-$k.jpg"; i=$((i+4)); k=$((k+1))
done
echo "${#list[@]} pairs"
