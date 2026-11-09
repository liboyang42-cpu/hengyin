#!/bin/bash
# usage: all.sh <before|after>
S=$(dirname "$0"); P=$1; X=${XCX:?set XCX to the chengyinhub-xcx dir}
run(){ (cd $S && GEN=$S/gen.js XCX=$X RIG_SHOTS=$S/shots timeout 200 node play.mjs "$@" 2>&1 | grep -E "^(ERR|EVAL|Error|Terminated)|tap:" ); }
INTRO='{"tap":"开始游戏"},{"wait":2500},{"tap":"轻点跳过"},{"wait":3000}'
DOWN='{"scroll":600},{"wait":1500},{"scroll":600},{"wait":4000}'
PG='getCurrentPages().slice(-1)[0]'
run coinFlip "" "[$INTRO,$DOWN,{\"shot\":\"V3-10-$P\"},{\"scroll\":-500},{\"wait\":1500},{\"shot\":\"V3-53-$P\"}]"
run ballShake "" "[$INTRO,$DOWN,{\"shot\":\"V3-10-shake-$P\"}]"
#run album "" "[$INTRO,$DOWN,{\"shot\":\"V3-11-$P\"}]"
run route "" "[$INTRO,{\"tap\":\"N\"},{\"wait\":2500},{\"shot\":\"V3-13-$P\"},{\"eval\":\"(()=>{const p=$PG;p.setData({isPreview:false});p.openSheet(p.data.nodes[0]);return 1})()\"},{\"wait\":1500},{\"shot\":\"V3-06-$P\"}]"
BAG="(()=>{const p=$PG;p.setData({themeBag:Object.assign({},p.data.themeBag,{sessionId:1,capacity:6,used:0,items:[],pendingRows:[],valueRows:[],clues:[{id:1,name:'褪色的车票',image:'/pages/play/images/d_topic2.png',content:'日期是十二年前。'}]}),themeBackpackOpen:true,themeBackpackTab:'items'});return 1})()"
run route "" "[$INTRO,{\"eval\":\"$BAG\"},{\"wait\":1500},{\"shot\":\"V3-16-$P\"},{\"eval\":\"$PG.setData({themeBackpackTab:'clues'})\"},{\"wait\":800},{\"shot\":\"V3-21-clue-$P\"}]"
QR="(()=>{const p=$PG;p.setData({isPreview:false});p.openScene('qr-ticket',{registrationId:'71',title:'旧街试玩',until:''});return 1})()"
QRREADY="(()=>{const c=$PG.selectComponent('cy-scene-qr-ticket');if(c)c.setData({state:'ready',qr:'/subpackageMember/images/d_qrcode.png'});return !!c})()"
#QR2="(()=>{const p=$PG;const c=p.selectComponent && p.__root && null;return 1})()"
run route "" "[$INTRO,{\"eval\":\"$QR\"},{\"wait\":2500},{\"eval\":\"$QRREADY\"},{\"wait\":1500},{\"shot\":\"V3-17-$P\"}]"
run tasks "" "[{\"tryTap\":\"开始游戏\"},{\"wait\":2500},{\"tryTap\":\"轻点跳过\"},{\"wait\":3000},{\"shot\":\"V3-15-$P\"},{\"tryTap\":\"延伸任务\"},{\"wait\":1500},{\"shot\":\"V3-15-sheet-$P\"}]"
