#!/bin/bash
# r2-play shots. usage: XCX=<chengyinhub-xcx of the bundled candidate> RIG_WALK=<rig>/docs/.../mpweb/walk.mjs TOPIC=<fixture topic> all.sh <before|after>
# Preview states use the play page's own preview path (gen.js → applyPreviewData), real states use the fixture run (free_orient_theme.py).
S=$(cd "$(dirname "$0")" && pwd); P=$1; ONLY=${2:-all}; X=${XCX:?set XCX}; T=${TOPIC:?set TOPIC}   # ONLY = all | pv | real | tasks | bag
OUT=$S/../shots
pv(){ (cd $S && GEN=$S/gen.js XCX=$X RIG_SHOTS=$OUT timeout 200 node play.mjs "$@" 2>&1 | grep -E "^(ERR|Error|Terminated)" ); }
real(){ (cd $S && RIG_SHOTS=$OUT timeout 240 node play-driver.mjs '{"path":"pages/index/index","identity":"e2e-openid-9004","agree":true}' "$1" 2>&1 | grep -E "^(ERR|FAIL|waitEval)" ); }
INTRO='{"tap":"开始游戏"},{"wait":2500},{"tap":"轻点跳过"},{"wait":3000}'
DOWN='{"scroll":600},{"wait":1500},{"scroll":600},{"wait":4000}'
PG='getCurrentPages().slice(-1)[0]'
HUD="(()=>{const p=$PG;p.setData({themeBag:Object.assign({},p.data.themeBag,{sessionId:1,hpEnabled:true,hp:3,hpMax:5,names:Object.assign({},p.data.themeBag.names,{hp:'体力'}),inventoryEnabled:true,capacity:6,used:2})});return 1})()"
ITEMS="[{id:1,name:'褪色的车票',initial:'票',image:'/pages/play/images/d_topic2.png',quantity:1,effectText:'可兑换一次提示'},{id:2,name:'铜钥匙',initial:'钥',quantity:2,effectText:'打开老邮局的信箱'},{id:3,name:'旧地图',initial:'图',image:'/pages/play/images/d_topic4.png',quantity:1,effectText:''}]"
BAG0="(()=>{const p=$PG;p.setData({themeBag:Object.assign({},p.data.themeBag,{sessionId:1,capacity:6,used:0,items:[],pendingRows:[],valueRows:[],clues:[]}),themeBackpackOpen:true,themeBackpackTab:'items'});return 1})()"
BAG3="(()=>{const p=$PG;p.setData({themeBag:Object.assign({},p.data.themeBag,{sessionId:1,capacity:6,used:4,items:$ITEMS,pendingRows:[],valueRows:[],clues:[]}),themeBackpackOpen:true,themeBackpackTab:'items'});return 1})()"
SHEET="(()=>{const p=$PG;p.setData({isPreview:false});p.openSheet(p.data.nodes[0]);return 1})()"
QR="(()=>{const p=$PG;p.setData({isPreview:false});p.openScene('qr-ticket',{registrationId:'71',title:'旧街试玩',until:''});return 1})()"
QRREADY="(()=>{const c=$PG.selectComponent('cy-scene-qr-ticket');if(c)c.setData({state:'ready',qr:'/subpackageMember/images/d_qrcode.png'});return !!c})()"

if [ "$ONLY" = tasks ]; then TASKS_ONLY=1; fi
if [ "$ONLY" = bag ]; then BAG_ONLY=1; fi
if [ "$ONLY" != real ] && [ -z "$TASKS_ONLY$BAG_ONLY" ]; then
pv ballShake "" "[$INTRO,$DOWN,{\"shot\":\"a1-v3-10-53-$P\"}]"
pv route "" "[$INTRO,{\"eval\":\"$HUD\"},{\"wait\":1200},{\"shot\":\"a1-v3-13-14-$P\"},{\"tap\":\"N\"},{\"wait\":2500},{\"shot\":\"a1-v3-13-14-map-$P\"},{\"eval\":\"$SHEET\"},{\"wait\":1500},{\"shot\":\"a1-v3-06-$P\"}]"
pv route "" "[$INTRO,{\"eval\":\"$BAG0\"},{\"wait\":1500},{\"shot\":\"a1-v3-16-$P\"},{\"eval\":\"$BAG3\"},{\"wait\":1500},{\"shot\":\"a1-v3-16-items-$P\"}]"
pv route "" "[$INTRO,{\"eval\":\"$QR\"},{\"wait\":2500},{\"eval\":\"$QRREADY\"},{\"wait\":1500},{\"shot\":\"a1-v3-17-$P\"}]"
pv tasks "" "[{\"tryTap\":\"开始游戏\"},{\"wait\":2500},{\"tryTap\":\"轻点跳过\"},{\"wait\":3000},{\"scroll\":900},{\"wait\":2500},{\"shot\":\"a1-v3-15-$P\"},{\"tryTap\":\"延伸任务\"},{\"wait\":1500},{\"shot\":\"a1-v3-15-sheet-$P\"}]"

fi
[ -n "$TASKS_ONLY" ] && { pv tasks "" "[{\"tryTap\":\"开始游戏\"},{\"wait\":2500},{\"tryTap\":\"轻点跳过\"},{\"wait\":3000},{\"scroll\":900},{\"wait\":2500},{\"shot\":\"a1-v3-15-$P\"},{\"tryTap\":\"延伸任务\"},{\"wait\":1500},{\"shot\":\"a1-v3-15-sheet-$P\"}]"; exit 0; }
[ -n "$BAG_ONLY" ] && { pv route "" "[$INTRO,{\"eval\":\"$BAG0\"},{\"wait\":1500},{\"shot\":\"a1-v3-16-$P\"},{\"eval\":\"$BAG3\"},{\"wait\":1500},{\"shot\":\"a1-v3-16-items-$P\"}]"; exit 0; }
[ "$ONLY" = pv ] && exit 0
ENTRY="{\"wait\":1500},{\"open\":\"pages/play/index?topicId=$T\"},{\"wait\":4000}"
LT="(()=>{const c=$PG.selectComponent('#lookTx');return !!c&&c.data.phase==='is-out'})()"
# s0: the look transition after 开始游戏, shot 200 ms into the reveal (the owner's screenshot caught this moment)
real "[{\"wait\":1500},{\"open\":\"pages/play/index?topicId=${TOPIC_S0:?set TOPIC_S0 (a fresh fixture topic per take)}\"},{\"wait\":4000},{\"tryTap\":[\"开启定位\",{\"nth\":1}]},{\"wait\":2000},{\"tap\":\"开始游戏\"},{\"waitEval\":\"$LT\"},{\"wait\":200},{\"shot\":\"s0-fixture-play-entry-$P\"}]"
# Fixture state for the three real runs below: station 1 already arrived (one setup run, rig/walk-setup.json), so 继续探索 opens station 2 (QA + hint ladder).
# a1-v3-21: entry prompt declined (先用扫码) → station card; then the hint ladder on the QA station (c4-ug-05)
real "[$ENTRY,{\"shot\":\"a1-v3-21-location-$P\"},{\"tryTap\":\"先用扫码\"},{\"wait\":1500},{\"tryTap\":\"开始游戏\"},{\"wait\":3000},{\"tryTap\":\"轻点跳过\"},{\"wait\":3000},{\"scroll\":800},{\"wait\":2000},{\"tryTap\":\"继续探索\"},{\"wait\":2500},{\"shot\":\"a1-v3-21-node-$P\"}]"
real "[$ENTRY,{\"tryTap\":[\"开启定位\",{\"nth\":1}]},{\"wait\":2000},{\"tryTap\":\"开始游戏\"},{\"wait\":3000},{\"tryTap\":\"轻点跳过\"},{\"wait\":3000},{\"scroll\":800},{\"wait\":2000},{\"tryTap\":\"继续探索\"},{\"wait\":2500},{\"tap\":\"开始任务\"},{\"wait\":4000},{\"tap\":\"提示\"},{\"wait\":3000},{\"shot\":\"c4-ug-05-$P\"}]"
