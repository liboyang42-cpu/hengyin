# 场景表:每条 = 页面 + 状态 + 步骤,前后两边逐字相同。@@ 会被换成「场景名-前/后-」。
LOOKS="STARDUST:stardust PAPER:paper SURVIVAL:survival CYBER:cyber"
P='getCurrentPages().slice(-1)[0]'
INTRO='{"tap":"开始游戏"},{"wait":1200}'
SHEET="(()=>{const p=$P;p.setData({isPreview:false});p.openSheet(p.data.nodes[0]);return 1})()"
GATE2="(()=>{const p=$P;p.setData({chapter:Object.assign({},p.data.chapter,{id:2,name:'老书店',description:'店门虚掩着,柜台后面没有人。'})});p._seenChapterGates=new Set();p.showChapterGate();return 1})()"
FIN="(()=>{const p=$P;p.setData({showFinish:true,finale:{show:true,faces:[{cover:'',style:'engrave',label:'02',photos:[]},{cover:'',style:'riso',label:'01',photos:[]},{cover:'',style:'gold',label:'通关 · 老城三封信',photos:[]}],info:{kicker:'全部完成',title:'老城三封信',desc:'',stats:[{v:'7',k:'坐标'},{v:'58:00',k:'用时'},{v:'420',k:'探索值'}]}}});return 1})()"
NEAR='{"lat":31.2440,"lng":121.4850}'
if [ -z "$SKIPLOOKS" ]; then for lk in $LOOKS; do L=${lk%%:*}; l=${lk##*:}
  # S1 地点卡:定位在站点附近(距离 / 步行分钟有数),开卡后 140ms 抓一张(入场中),再等落定抓一张
  scn "s1-$l" $L route 1 "$NEAR" "[$INTRO,{\"wait\":5000},{\"eval\":\"$SHEET\"},{\"shot\":\"@@mid\"},{\"wait\":1600},{\"shot\":\"@@main\"}]"
  # S2 章节开场:光晕 / 星尘在画布后面;1.8s 与 2.6s 各一张
  scn "s2-$l" $L route 1 "{}" "[$INTRO,{\"wait\":1800},{\"shot\":\"@@main\"},{\"wait\":900},{\"shot\":\"@@late\"}]"
  # S3 通关卡:卡出来之后(光芒 / 光晕 / 星尘)与按钮亮起之后
  scn "s3-$l" $L route 1 "{}" "[$INTRO,{\"wait\":4500},{\"eval\":\"$FIN\"},{\"wait\":7000},{\"shot\":\"@@main\"},{\"wait\":2500},{\"shot\":\"@@late\"}]"
  # S4 自由探索卡包:待机(流光 / 悬浮)两帧
  scn "s4-$l" $L route 2 "{}" "[{\"wait\":3500},{\"shot\":\"@@main\"},{\"wait\":1400},{\"shot\":\"@@mid\"}]"
done; fi
# S9 ⋯ 工具菜单:点开后 120ms 抓一张(缩放展开 + 行错开中),落定再抓一张
scn "s9-menu" STARDUST route 1 "{}" "[$INTRO,{\"wait\":4500},{\"eval\":\"($P.toggleSessionTools(),1)\"},{\"wait\":120},{\"shot\":\"@@mid\"},{\"wait\":1400},{\"shot\":\"@@main\"}]"
# S5 主题详情:首屏 + 滚 300px(头图视差 0.5×,导航折叠不变)
scn "s5-topic" none - 1 '{"path":"pages/topic/index/index?id=990014"}' '[{"wait":3000},{"shot":"@@main"},{"scroll":1000},{"wait":900},{"shot":"@@scroll"}]'
# S6 票夹:注入两张可用票(同一段注入前后各跑一次),票根缺口 + 顶边高光
WAL="(()=>{const p=$P;p._topic={state:'ready',list:[{id:101,registrationStatus:2,paymentStatus:2,cmsTopic:{name:'老城三封信',productType:1,imgUrl:'/pages/play/images/d_topic1.png'}},{id:102,registrationStatus:2,paymentStatus:2,cmsTopic:{name:'外滩夜行',productType:1,imgUrl:'/pages/play/images/d_topic2.png'}}]};p._activity={state:'ready',list:[]};p.rebuildTicketList();return p.data.ticketList.length})()"
scn "s6-wallet" none - 1 '{"path":"subpackageMember/signup/index","identity":"e2e-openid-9009"}' "[{\"wait\":3000},{\"eval\":\"$WAL\"},{\"wait\":3500},{\"shot\":\"@@main\"}]"
# S7 结算:注入一张自玩票(同一段注入前后各跑一次),总计金额 + 支付按钮
BM="(()=>{const p=$P;p.setData({pageState:'ready',activityInfo:{name:'老城三封信',isSignUp:0},selectedTicket:{id:1,name:'自玩门票',price:88,unitPrice:88},ticketList:[{id:1,name:'自玩门票',price:88}],totalAmount:88,originalAmount:88,canPay:true,participantState:'ready',selectedAddress:{name:'玩家小城',phone:'138****0000'}});return 1})()"
scn "s7-checkout" none - 1 '{"path":"pages/activity/baoming/baoming?activityId=13"}' "[{\"wait\":2500},{\"eval\":\"$BM\"},{\"wait\":800},{\"scroll\":500},{\"wait\":600},{\"shot\":\"@@main\"}]"
# S8 首页
scn "s8-home" none - 1 '{"path":"pages/index/index"}' '[{"wait":2500},{"shot":"@@main"},{"scroll":500},{"wait":700},{"shot":"@@scroll"}]'
# S10 商家工作台(浅色)
scn "s10-merchant" none - 1 '{"path":"pages/merchant/index/index","identity":"e2e-openid-9002"}' '[{"wait":3500},{"shot":"@@main"},{"scroll":500},{"wait":700},{"shot":"@@scroll"}]'
