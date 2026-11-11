#!/bin/bash
# usage: matrix.sh <prefix> [looks...]; 每个玩法:故事流内嵌(滚到底)+整屏,四种外观 → shots/<prefix><LOOK>-<kind>-{inline,full}.jpg
. "$(dirname "$0")/env.sh"; cd "$(dirname "$0")"
P=$1; shift; LOOKS=${@:-STARDUST PAPER SURVIVAL CYBER}
PG='getCurrentPages().slice(-1)[0]'
FULL="(()=>{const p=$PG;p.setData({playKit:{show:true,pop:false,kit:p.data.nodes[0].previewKit}});return 1})()"
ALB="(()=>{const p=$PG;p.setData({album:{show:true,cur:0,title:'旧照片',images:[{url:'/pages/play/images/d_topic1.png',line:'第 1 张'},{url:'/pages/play/images/d_topic2.png',line:'第 2 张'}]}});return 1})()"
DOWN='{"scroll":600},{"wait":1200},{"scroll":600},{"wait":1200},{"scroll":600},{"wait":1200},{"scroll":600},{"wait":2500}'
for L in $LOOKS; do (
  for K in qa qaType coinFlip diceRoll ballShake compare random; do
    LOOK=$L timeout 200 node fs.mjs $K "$P$L-$K-" "[{\"tap\":\"开始游戏\"},{\"wait\":4500},$DOWN,{\"shot\":\"inline\"},{\"eval\":\"$FULL\"},{\"wait\":2800},{\"shot\":\"full\"}]" 2>&1 | grep -E "^(ERR|Error)" | grep -v "ERR \[\]"
  done
  LOOK=$L timeout 200 node fs.mjs album "$P$L-album-" "[{\"tap\":\"开始游戏\"},{\"wait\":3500},{\"eval\":\"$ALB\"},{\"wait\":2500},{\"shot\":\"full\"}]" 2>&1 | grep -E "^Error"
  LOOK=$L timeout 200 node fs.mjs random "$P$L-draw-" "[{\"tap\":\"开始游戏\"},{\"wait\":3500},{\"eval\":\"$FULL\"},{\"wait\":2500},{\"tap\":\"换一张\"},{\"wait\":2500},{\"shot\":\"full\"},{\"tap\":\"就这张\"},{\"wait\":2500},{\"shot\":\"detail\"}]" 2>&1 | grep -E "^Error"
) & done; wait; echo done
