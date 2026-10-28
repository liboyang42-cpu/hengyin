// 合成源退役3条旧U4债务：新主线square/list已无longitude/latitude字段；治理square/detail真实读取this.data.replyId并校验原回复目标，均不能继续记零消费。
// d4cc 原主线已退役的两条post-compose U5登记随同采用；合成loadActivityOptions全文1588bytes与d4cc逐字相同(SHA524609f81bb0686cb928acfcccff06e2a4c5888732a91fd24dac6dedb28e8d05)，不删除请求或反馈。
// 2026-10-28 d4cc公共主线合并：仅保留两边原实名记录与退役事实；当前字面键计数/摘要非新源实扫，须由原hosted门禁按合成源码复核。
// 2026-10-28 核销详情已显式四端点，删除其旧U1豁免(10→9)；带队增加一行仅平移里程碑U5旧锚，其理由及43上限不变。
// 2026-10-28 整批公共源合并：原10个调用与public master/治理源锚点上下三行逐字相同，仅迁移行号；理由、数量及冻结上限不变，原排序键算法重算摘要。
// 2026-10-28 合并 master(e52eab7e,#658 之后)进 integrate/devfix-20261027(#641),两侧重锚:既有锚点逐条按两侧父树原行文本(及上下各一行)在合并树定位同一行后换 key,理由一字未改,不新增登记;u1Variable 换 key 7(master 3、本线 4)、u4Dynamic 合并后 60 条(master 侧 34 条、本线侧 31 条换 key)、u5Silent 44 条(master 侧 19 条、本线侧 22 条换 key);删去两侧已移除代码的未命中项:u4Dynamic 3(fabu 旧草稿恢复 patch、模板页两处 _coverFail)、u5Silent 2(post-compose 选活动两读取)、u4Debt 9(本线还清 7 + master 完成页还清 2);棘轮 u1Variable 10 / u3Handler 62 / u4Field 124(master)·119(本线)→117 / u4Dynamic 60·62→60 / u5Silent 43·44→44(=本线既有登记,master 43 + 本线漫游三条 − post-compose 两条),冻结上限不变;五个 keyDigest 按门禁 baselineKeyDigest 在合并树实算。
// 2026-10-28 v8 改后图落代码:signup /api/team/my 同一调用点仅行号 334→398(票夹翻面与开始游戏在上方加代码,行及上下各一行逐字相同,理由不变);u5Silent 新增漫游附近活动兜底两条(42→44);u5Silent digest 按同一算法重算。
// 2026-10-27 最小改动返修:主理人申请页恢复开场形变(P-24 只修时序),U4-DYNAMIC club/apply onInput / toggleBool 两条锚点仅行号位移 221→233 / 232→244(同一行 this.setData({ [f]: ... }),理由不变),u4Dynamic digest 按同一算法重算。
// 2026-10-27 L11 集成(L01–L09 合入 integrate/devfix-20261027):roam/play 等多路同改文件的既有锚点按「行及上下各一行」与登记来源分支逐字比对后换 key,同一调用点的多路重复登记去重(roam 31 条、play 2 条、nearby 1 条等);L05 出账的 post-compose 两条、L05/L07 还清的零消费债务保持出账。条数与各路理由不变:u1Variable 10、u4Field 120、u4Dynamic 62、u5Silent 42;四份 digest 按同一算法重算。
// 2026-10-27 L02 弹层页面化与返回键先关弹层:roam/index.js 顶部加 scene-entry 引用 + openScene 加 page 档分流(+3 行)、play/index.js 同改(+3 行),既有 35 个锚点(roam 31、play 4)仅行号位移,按 difflib 相等块逐条映射换 key(新旧行内容逐字相同);条数与理由不变,u4Dynamic / u5Silent digest 按同一算法重算。
// 2026-10-27 L03 真机走查(G-4/P-23/P-24/M-06):4 条既有锚点仅行号位移——U1-VARIABLE app.js 419→421(request-client 装配补 isPlayerView 两行)、U4-DYNAMIC club/apply 187→221 / 198→232(onInput / toggleBool,同一行 this.setData({ [f]: ... }))、im/list 323→347(同一 this.setData(patch) 行);上下文逐字相同、理由与条数不变,u1Variable / u4Dynamic digest 按同一算法重算。
// 2026-10-27 L06 漫游真机修复:roam/index.js、nearby/index.js、badge-wall/index.js 既有 24 条锚点仅行号位移(difflib 对 origin/master 逐行映射,落在未改动行内,调用点与理由一字未改);u5Silent 新增护照勋章方卡一条(43→44);u1Variable / u4Dynamic / u5Silent digest 按同一算法重算。
// 2026-10-27 L08 真机走查(头像名称同步/我的页/品牌中心):profile/index.js 三条既有锚点仅行号位移(U1-VARIABLE 663→707;U4-DYNAMIC 1152→1276、1991→2120),逐条以行及上下各一行与 origin/master 原位置逐字比对一致;条数、理由与上限不变,u1Variable / u4Dynamic digest 按同一算法重算。
// 2026-10-25 上传候选整合：保留地点线逐项登记，既有调用按逐字相同行迁锚，未扩大地点线门禁上限。
// 2026-10-27 L05 俱乐部与帖文真机修复:square/list 撤掉行内发布框(上方删 ~220 行),u4Dynamic 三条锚点仅行号位移(157→137、197→177、1287→1063,同一 [key] / 翻页追加 setData,上下文逐字相同、理由不变);post-compose 加俱乐部模式在上方插入代码,u5Silent 两条选活动读取被门禁判为静态已处理而出账(43→41);u4Debt / u4Dynamic / u5Silent digest 按同一算法重算。
// 2026-10-28 完成页改版(业主定稿):play/index.js 的 reviewElapsed / reviewDistance 两条 U4 零消费债务随成绩行真渲染出账,u4Field 126→124,u4Debt digest 按同一算法重算。
// 2026-10-28 L9 再并 master(#652):取 master 的首页 U1-VARIABLE 1057→1043 换 key、模板页两条 _coverFail U4-DYNAMIC 退役删除(62→60),本线锚点不变;u1Variable / u4Dynamic digest 按同一算法在合并树上重算。
// 2026-10-28 文档二 L9 集成并 master(#610):play/index.js u4Dynamic 1744→1747、u5Silent 7856→7894,publish/temp/index.js u4Dynamic 1459→1460、1460→1461、1503→1504、2943→2956、3745→3758、3872→3885,仅行号位移(调用行逐字相同),理由与条数不变;u4Dynamic / u5Silent digest 按同一算法重算。
// 2026-10-28 文档二 L9 集成:L5 在 play/index.js 上方净增行,u4Dynamic 1741→1744(章节正文显形 patch)、u5Silent 7851→7856(已完成里程碑读取)仅行号位移,调用行内容与理由一字未改,条数不变;两条 digest 按同一算法重算。
// 2026-10-27 合并三组(0e24786c)后 fabu 页上方净增 3 行:11 条 U4-DYNAMIC 既有锚点逐条以调用行内容回读一致后等量 +3 换 key,理由一字未改,u4Dynamic digest 重算。
// 2026-10-27 W8 收口:既有锚点仅行号位移,逐条按「调用行内容 + 理由」回读后等量换 key(roam 漫游页 U4×4/U5×11 同一位置整体 -10、
//   BL-41 官方活动 -8;play 章节显形 patch 1648→1741、里程碑 7685→7851;fabu 地图重绘 9020→9061;publish/temp 六条 +1~+5;
//   official-mine BL-80 五条 +10 与 U5 一条 148→158;search2 U1 169→193;roam session 186→187),理由一字未改。
//   W8 汇总时 fabu:863 动态 setData 已出账但棘轮仍为 62(key 61 条);同轮 official-mine 新增 _canRecover 动态键
//   (按卡片路径写,WXML「安全恢复原投递」消费)补登记于 164,条数回到 62 = 棘轮,冻结上限未动。三个 digest 按同一算法重算。
// W8-12: participation-detail U1 anchor 226→243; original call context unchanged, no new exemption.
// 2026-10-26 参与详情结果入口接线：既有核销调用 190→226，理由和数量不变，仅重算定位摘要。
// 2026-10-27 陌生人内测走查(地图点位统一 / 开场副标只取首行 / 完成卡只留一条成就气泡):play/index.js u4Dynamic 1621→1624、u5Silent 已完成里程碑读取 7724→7732,仅行号位移,行内容逐字相同、理由不变;u4Dynamic / u5Silent digest 按同一算法重算。
// 2026-10-25 地点卡补照片来源署名一行:nearby 评价列表读取 1004→1005(同一调用点,理由不变),u5Silent digest 按同一算法重算。
// 2026-10-25 地点勋章用「点亮一隅」徽记 + 地点卡公开引述:badge-wall 201→206(同一行 this.setData(patch))、nearby 评价列表读取 1003→1004,上下文逐字相同、理由不变;u4Dynamic / u5Silent digest 按同一算法重算。
// 2026-10-25 官方地点卡片展开详情:nearby/index.js 既有 3 条锚点仅行号位移(213→215、240→242、934→940,上下文逐字相同),u5Silent 新增节点补读与评价列表两条(41→43);u1Variable / u5Silent digest 按同一算法重算。
// 2026-10-24 草稿设置恢复：12 条既有 fabu 动态调用的行及上下各一行逐字相同，仅行号 +12；恢复调用理由补齐五个受类型保护的固定设置，登记数与棘轮仍为62。
// 2026-10-24 预览返回等待原生退场：六条既有动态 setData 调用上下文逐字节相同，仅更新行号及摘要，棘轮仍为 62。
// 2026-10-24 上线合并(#563 + #508 + #554)：fabu 12 条 u4Dynamic 锚点仅行号位移(849→851、1105→1151、2806→2852、3872→3918、4253→4299、5316→5362、5575→5621、6231→6277、6241→6287、6294→6340、6635→6686、8601→8652)、profile 1994→1991，逐条以行及上下各一行与 origin/master 原位置逐字比对一致；u5Silent 新增合作 tab 帖文流与竞猜待答入口两条(37→39)。u4Dynamic/u5Silent digest 按同一算法重算。
// 2026-10-21 移植分支再并入 master 19408d49：相对 master 仅 W8 已批准的 activity/list(u4Dynamic 3→1、u5 两条)与 complaint(u5 一条)登记项，相对移植分支仅 master 行号位移；条数与上限不变，u4Dynamic/u5Silent digest 按同一算法重算。
// 2026-10-21 移植分支(W4+W8)并入 master #293/#402：同 #400，activity/list 与 complaint 取 W8 已批准登记项，u4Field 127→126、u4Dynamic 64→62，三条 digest 按合并树 key 集合实算。
// 2026-10-21 #426：signup 原 /api/team/my 同一请求仅行号263→334；理由、37项数量与上限不变，排序键摘要按原算法重算。新原票查读不静默，失败另有明确定位错误态。
// 2026-10-21 核销 UNKNOWN 恢复：profile 658→663/1088→1095/1145→1152/1987→1994、participation 185→190，逐条回读为原调用点；条数、理由、冻结上限不变，u1Variable/u4Dynamic 摘要按既有排序键算法显式重算。
// 2026-10-21 首页编辑精选来源说明新增方法：4 个既有调用锚点后移 8 行；上下文逐字核对，理由、条数和阈值不变，仅重算对应 key digest。
// 2026-10-20 消息页改收件箱/发件箱(用户要求):u4Dynamic im/list 317→323 仅行号位移(同一 this.setData(patch) 行,上下各一行逐字相同);条数与理由不变,u4Dynamic digest 按同一算法显式重算。
// 2026-10-20 PR396并集：24个既有锚点按两版源码同调用重定位，理由与数量不变；22处上下各一行逐字相同，roam常量3000及analytics更强accepted校验沿master已登记同请求人工复核。证据：本任务私有anchor-receipt.json；不增加ratchet或豁免。
// 同轮城市定位同意接线使 locationInfo 有真实消费，移除第二条旧债务，u4Field 128→127。
// 2026-10-20 小程序修复并集：既有调用点按 #293 与当前源码逐字相同行映射重锚；理由与豁免数量不增加。inlineEdit 已有真实读取，移除旧债务并将 u4Field 129→128。
// 2026-10-19 Claude 小程序线移植到 master(claude/mini-ui-on-master-20261019):45 个既有锚点仅因本线在同文件增删行而位移,逐条以「行及上下各一行」与 master 原位置逐字比对后换 key(roam 2401→2472 为同一位置心跳调用,行尾追加 BL-03 上报状态回调);理由与条数不变。新增 15 项见 u4Dynamic/u5Silent 同日注释。ratchet、keyDigest 与 u5Silent 冻结上限的调整单独提交审批。
// 2026-10-19 移植共享基线 8b7eb814..1e403b13(W2):23 条既有锚点仅行号位移——U4-DYNAMIC square/list 1256→1276、merchantinfo 646→648/2355→2357/2386→2388、im/list 276→277、play 1518→1556、merchant/apply 354→359、roam 1655→1695/3723→3764/4554→4595/4612→4653;U5 roam 1744→1784/2401→2441/2411→2451/4018→4059/4232→4273/4317→4358/4418→4459/4867→4908/5474→5515/5481→5522、play 6691→6788、analytics 201→226。21 条逐条以上下各三行与 origin/master 原位置逐字相同脚本核对;roam 2451(半径改为 ROAM_POI_RADIUS_M 常量,同值 3000)与 analytics 226(success 回调补 accepted 判断)为同一调用点、理由不变,人工核对。条数与理由不变,u4Dynamic / u5Silent digest 按同一算法显式重算。
// 2026-10-15 213社区前端整合：仅4个既有调用锚迁行，U1/U5同一受控请求的理由补精确mine回读；条数及所有棘轮上限不变，排序key摘要显式复算。
// 2026-10-15 PR10 末轮(用户文字实体解码 / 支付回调地址):community-post-api 67→68(u1Variable、u5Silent)、publish/temp 1446→1451 / 1447→1452 / 1490→1495 / 2925→2930 / 3721→3726 / 3848→3853、im/list 274→276、scene-club-edit 161→163(u4Dynamic);逐条连同上下各三行与 cb8950e0 原位置逐字相同，条数与理由不变；三份 digest 待用户审批后按同一算法更新。
// 2026-10-15 PR10 复查修复(登录引导 / 拒绝原因多行框等):merchant/apply 342→354(u4Dynamic)、im/list 267→274(u4Dynamic)、signup 255→263(u5Silent);逐条连同上下各三行与 ec4e565 原位置逐字相同，条数与理由不变，u4Dynamic / u5Silent digest 按同一算法显式重算。
// 2026-10-14 合并 E2：app.js 396→384、merchant/customer 502→504、merchant/apply 340→342、templatedetail 219→204 / 336→321、play 6690→6691；逐条连同上下各一行回读与合并前逐字相同，条数与理由不变，digest 按同一算法显式重算。
// 2026-10-14 合并八路剩余修复：app.js 390→396、square/detail 1150→1163、play 1509→1518 / 6681→6690 逐行回读为同一调用点；templatedetail 219 / 336 取行号与更正后理由。条数与理由不变，u1Variable / u4Dynamic / u5Silent digest 按同一算法显式重算。
// 2026-10-13 f75→f08：58条逐一映射，仅fabu 12/temp 6处既有调用迁行；锚点、理由、owner与上限不变，含两组相邻锚点碰撞，digest按新键重算。
// 2026-10-12 后续请求恢复仅用原checkpoint逐字相同调用重定位，数量理由不变；证据页面与流程-flow-finish-baseline锚点.json。
// 2026-10-12 会话恢复仅迁既有锚；Growth既有请求如今scanU5实际autoSafe还清1条，U5 31→30，证据页面与流程-session-baseline锚点.json。
// 2026-10-12 实际游客/重放源码逐字迁锚；附近team新增1条真实计算失败理由，U5仅30→31。证据见页面与流程-guest-replay-baseline锚点.json。
// 2026-10-12 删除自动假数据注入，首页动态setData真实还清1条(59→58)，其他旧调用逐字迁行，证据见页面与流程-realdata-baseline锚点.json。
// 2026-10-12 profile/square门禁、主题订阅/CRM读取仅迁移保存源码中逐字相同的既有锚点，数量与理由不变；证据见页面与流程-feature-baseline锚点.json。
// 2026-10-12 R5并集后39条既有调用点按保存的pre-R5源码严格逐字迁移，数量不变；证明见docs/design/上线前修复_20261012/页面与流程-R5-baseline锚点.json。
// 2026-10-12：加载失败留白与流程修复，仅逐条原代码行映射既有U1/U4/U5行号；条数和理由不变，SHA重算，证据见页面与流程-baseline锚点.json。
// 2026-10-11 附件账号隔离：既有 consent 调用343→346；调用、理由、11条数量不变，仅重算排序键digest。
// 2026-10-10 项目主办页回包修复：既有调用只移行号，理由、数量与冻结上限保持。
// 2026-10-10 页面回包归属修复：7处既有调用只迁移行号，调用、理由、数量和冻结上限保持；digest按排序键复算。
// 2026-10-05：18处既有U4/U5锚点与76a40c98f0前后各7行完全一致，仅迁移行号；理由、数量和冻结上限不变。
// 2026-10-03：24个旧调用登记重定位；23处与ba87c0b1a0上下7行逐字一致，1处只增加已消费的feedAuthError:false。数量和冻结上限不变。
// 2026-09-30 最终整合：88条既有调用按前后7行唯一相同定位，理由保留；合入3fab仍在源码中的8条性能登记。
// 退役实名patch与3个死字段出账；旧章节opacity/scale/blur登记经人工审查迁到in/dir/d两namespace及数组身份guard，理由如实更新。
// 棘轮按最终实扫收紧：U1 10 / U3 62 / U4字段130 / U4动态59 / U5 28；不新增无关豁免。
// 2026-09-30 story 隔离整合：既有动态调用按前后7行逐字相同等量重定位，数量与理由不变。
// 2026-09-26 模板广场回读取代本地翻转，删除两条动态 setData 债务(53→51)；myproject 同一调用点下移一行。
// 2026-09-26 故事草稿整合：按 8cb83ec 相同行块重定位既有调用，数量与理由不变。
// 2026-09-27 删除主页未消费状态：三处既有调用点仅上移2行，理由及数量不变。
// 统一玩法选择器：三处既有动态写入仅后移 9 行，调用和理由不变。
// 相册模板接线：既有锚点上下各3行逐字核对，仅移动行号，保留理由和数量。
// 最终静态检查将 temp.id / isFreeExploreHost 改实例字段:另9条U4与1条U5仅上移1行,原7行窗口逐字一致。
// 2026-09-25 最终集成:18 条既有锚点与 99c05041c 原位置上下各 3 行逐字一致,仅等量换 key。
// 动态调用/静默理由与数量不变;新增发布定位改为固定字段写入,不扩基线。
// isFreeExploreHost 已用于自由探索招商入口,删除其零消费债务,u4Field 135→134,重算相应 digest。
// 2026-09-24 走查第二轮(CU 110 条,rebase 到 #1165 之后):U1 1 条、U4-DYNAMIC 29 条、U5 11 条纯行号平移,
//   逐条以「行及上下各一行」对比 github/master 原位置内容一致后等量换 key;条数与理由不变,三份 digest 显式重算。
// 2026-09-24 #1165 rebase 到 #1159 之后:myproject U1-VARIABLE 435→461、fabu 六条 U4-DYNAMIC 各 -3(退款开关改只读删了 3 行);
// 2026-09-25 CU-M-05 商家主办单场活动:myproject onLoad 商家态入口 + loadMerchantPublishEntry 共加 30 行,
//   那条上下架 U1-VARIABLE 按门禁实测等量换 key(461→491),条数不变(仍是 1 条)。
//   逐条对比 github/master 对应行内容一字未改，条数不变,u1Variable/u4Dynamic digest 显式重算。
// 2026-09-25 走查接续集成(modal/flow-merchant/visual/copy-merchant 四批 cherry-pick 到 closeout):
//   fabu 12 条 U4-DYNAMIC、templatedetail 2 条 U4-DYNAMIC、customer 1 条 U5 为纯行号位移;
//   逐条以「锚点行 ±3 行」文本对比 cherry-pick 前树(97948c54c)与原位置逐字相同后才等量换 key,
//   理由一字未改,条数 53 / 27 不变,u4Dynamic/u5Silent digest 按本树 key 集合实算。
'use strict';
// 2026-10-22 发布收尾集成 batch1：(a) 按 #467 CY-HANDOFF 删除 3 条过期 u1Variable（profile:1095、official-inbox:109、myproject:571，#449/#467 收紧后已证为有限 URL），棘轮 11→8 只降；
//   (b) #463 fabu 自动云草稿使 12 条 u4Dynamic 纯行号下移（758→849/1010→1105/2698→2806/3758→3872/4139→4253/5202→5316/5461→5575/6117→6231/6127→6241/6180→6294/6521→6635/8419→8601），
//   逐条锚点行 ±3 行与合入前逐字相同后等量换 key，理由一字未改，条数 62 与上限不变；u1Variable/u4Dynamic 摘要按既有排序键算法复算。无新增登记。
// 2026-10-20 审核整合仅迁两处原调用锚：app 384→388、myproject 569→571；受控URL、理由、11条集合与上限不变，显式复算u1Variable摘要。

// B148 举报选项接线仅平移四处既有动态 setData；八行上下文、理由与59条上限不变，key digest 重算。

// 2026-09-24 换玩法/打开旧模板时关掉藏着的限时(rebase 到 #1170 之后):publish/temp/index.js 回填处 +3 行,两条 U4-DYNAMIC 锚点
//   等量换 key(2966→2969 / 3089→3092),逐条回读行内容与理由一字未改,条数不变,digest 按本树 key 集合实算。
// 2026-09-23 检定段开关+下拉 / 删除改 ✕(叠在 #1156 之上):publish/temp/index.js 六条 U4-DYNAMIC 锚点
//   等量换 key(1146→1141 / 1147→1142 / 1202→1197 / 2267→2265 / 2888→2886 / 3007→3005),
//   逐条回读行内容与理由一字未改,条数不变,digest 按本树 key 集合实算。
// 2026-09-21 故事玩法预览与章节动画：按 Git 相同行块重定位既有锚点，债务数量与理由不变。

// 2026-09-22 氛围块(mood):fabu/index.js 与 play/index.js 因新增氛围块的配置入口与
//   正文流传递各净增若干行,28 条既有锚点(u4Debt / u4Dynamic / u5Silent)按 difflib 行号
//   映射等量换 key。逐条回读:拿 git show HEAD 的旧文件与新文件**逐字比对**过,调用点一字
//   未改;三组条数与理由均未动,digest 显式重算。
// 2026-09-22 play 页按预制人生重排(顶栏罗盘/右上角两钮、工具抽屉从底栏转移、起始页下线)
//   + 氛围块:两个页面行号漂移,4 条既有锚点按 difflib 映射等量换 key,
//   逐条拿 git show HEAD 的旧文件逐字比对过调用点未改;digest 显式重算。
// 2026-09-22 思维阁(念头清单入口 + 正文 thought 块 + 玩家侧菜单进度)与 play 页按
//   预制人生重排:两个页面行号漂移,28 条既有锚点按 difflib 映射等量换 key,
//   逐条拿 git show HEAD 的旧文件逐字比对过调用点未改;digest 显式重算。
// 2026-09-22 思维阁 + play 页按预制人生重排(含截图验收后删掉指向空屏的「设置」行):
//   两个页面行号漂移,2 条既有锚点按 difflib 映射等量换 key,逐条拿 git show HEAD 的旧文件
//   逐字比对过调用点未改;digest 显式重算。
// 2026-09-21 初始状态与保存绑定：既有调用点按未改动代码行等量迁移；理由、数量不变，重算 digest。
// UI-GATE-0 存量债务棘轮：不是“候选即合理”的机器快照。扫描器先自动排除 JS 内部消费/可见降级，
// 2026-09-22 阶段 5 条件修正 mods 接进编辑器:publish/temp/index.js 只增不删(+58 行,
//   新增 addModRow / addModPreset / updateModField / pickModOp / removeModRow 五个 handler),
//   六条既有 U4-DYNAMIC 登记项按门禁实测等量换 key —— 1074→1132 / 1075→1133 / 1130→1188 /
//   2193→2251 / 2813→2871 / 2932→2990。逐条比对过行内容**逐字相同**,是纯行号位移,
//   不是新违规,理由一字未改。
// 2026-09-21 账号隔离与上传失败重试修复仅使既有 U4/U5 调用点行号位移；逐项回读后等量换 key，数量与理由不变，digest 显式重算。
// 2026-09-13 F14 音频预听：fabu 的11个动态setData仅行号漂移，逐项对比调用前后7行相同；理由、数量54与棘轮不变，仅更新key/digest。
// 2026-09-18 走查·AI 预检看不见封面：fabu/index.js 在常量区 +2 行、_showPublishCheck +4 行，
//   11 条既有锚点按门禁实测等量换 key（570→572 / 734→736 / 2156→2158 / 3099→3101 / 3470→3472 /
//   4424→4426 / 4672→4674 / 5328→5330 / 5336→5338 / 5389→5391 / 6353→6359）。
//   逐条回读：仍是同一个 setData 调用点，受控字段与理由一字未改，条数不变；digest 显式重算。
  // 2026-09-06 漫游首屏入口卡(HANGOUT_INTRO_CARD 常量 + 字面量跳转,pages/roam/index.js 前段 +8 行):roam 的 U4-DYNAMIC / U5 登记项按门禁实测等量换 key,理由一字未改,digest 显式更新。
// 2026-09-13 F13 结束前排空写入：roam 的4条U4与8条U5调用前后7行逐项相同，仅迁移行号与digest；理由及棘轮数量不变。
// 2026-09-15 CR-927 商家上传节点NPC：merchantinfo.js 新增「节点NPC」入口(4 行数据字段 + 18 行 handler),
//   4 条既有锚点按门禁实测等量换 key —— U4-DYNAMIC 374→378 / 1692→1714 / 1701→1723,U5 2335→2357。
//   逐条回读:调用点、受控字段与理由一字未改,digest 显式重算。
// 这里只登记仍需后续接线或清理的精确债务；未命中会红，数量只能显式调小，新增默认红。
// 2026-09-16 C 组修复(整体检查 C_地图漫游游玩与券成长):roam/index.js 因 C-04(抽屉结束行)、
//   C-05(权限态)、C-06(登录闸)、C-09(marker 数字 id)、C-11(打卡文案分流)、C-14(两处内联态)
//   净增 63 行,play/index.js 因 C-19/C-21/C-22/C-24/C-27 净增 27 行。既有 U4-DYNAMIC 4 条与
//   U5 10 条锚点按门禁实测等量换 key(1551→1592 / 3555→3611 / 4348→4411 / 4406→4469;
//   714→726 / 1640→1681 / 2293→2334 / 2302→2343 / 3828→3884 / 4032→4089 / 4114→4174 /
//   4212→4275 / 4638→4701 / play 5253→5280)。逐条回读:仍是同一个调用点,受控字段与理由一字未改,
//   条数不变;digest 显式重算(u4Dynamic/u5Silent)。
// 2026-09-16 同批第二笔(C-06 登录闸加了「拿不到会话 API 就放行」的 try/catch,门闩路径再 +3 行):
//   roam 的 U4 三条与 U5 五条锚点按门禁实测再等量换 key(3611→3614 / 4411→4414 / 4469→4472;
//   3884→3887 / 4089→4092 / 4174→4177 / 4275→4278 / 4701→4704)。调用点与理由一字未改,digest 重算。
// 2026-09-17 拍板实施(第 5 条错误文案修复):publish/temp/index.js 在 aiNodeError 分支上方加两行注释,
//   三条 U4-DYNAMIC 锚点按门禁实测等量换 key(1883→1884 / 2481→2482 / 2600→2601)。
//   逐条回读:调用点、受控字段与理由一字未改,digest 显式重算。
// 2026-09-17 拍板实施(F21 信息不全):activity/list/index.js 顶部加一行 require、_decorate 末尾加三行
//   缺口字段装饰,U4-DYNAMIC 三条与 U5 两条锚点按门禁实测等量换 key(103→104 / 125→126 / 208→212;
//   105→106 / 129→130)。调用点与理由一字未改,两个 digest 显式重算。
// 2026-09-17 第二轮拍板 17/22(足迹分享直达 + 跨设备续玩):play/index.js 暂停会话双写服务端 + 串行同步/墓碑/恢复作废(+39 行)、首页继续卡两路请求(+18 行)、
//   足迹深链页分享令牌与接收态(+36 行)。U4-DYNAMIC 三条与 U5 一条按门禁实测等量换 key(play 1838→1877 / index 1246→1264 /
//   session 150→186;play U5 5291→5330)。逐条比对调用点前后 7 行完全相同,理由与条数不变,digest 用门禁同一算法重算。
// 2026-09-17 预制人生四玩法 + R14 检定 + 梦块/插入变量:temp/index.js 因五段编辑器 data 与 handlers
//   净增 213/218 行、fabu/index.js 因梦块与插入变量净增 3/123 行、play/index.js 累计净增 10/213 行。
//   u4Dynamic 18 条与 u5Silent 1 条锚点按门禁实测等量换 key(fabu 567→570 / 731→734 / 2033→2156 /
//   2976→3099 / 3347→3470 / 4301→4424 / 4549→4672 / 5205→5328 / 5213→5336 / 5266→5389 / 6230→6353;
//   temp 733→946 / 734→947 / 789→1002 / 1881→2099 / 2481→2699 / 2600→2818;play 1877→1887;
//   U5 play 5319→5532)。逐条用脚本比对调用点前后 7 行**逐字节相同**,受控字段与理由一字未改,
//   条数不变(53 / 26);两个 digest 按门禁同一算法(sha256 of sorted keys joined by \n)显式重算。
// 2026-09-18 photoCheck 接真视觉:temp/index.js 删掉本地判分那套(rule.mode 四选一 + threshold)
//   净减 12 行、play/index.js 净减 1 行。u4Dynamic 6 条与 u5Silent 1 条锚点按门禁实测等量换 key
//   (temp 946→934 / 947→935 / 1002→990 / 2099→2087 / 2699→2687 / 2818→2806;play U5 5532→5531)。
//   前六条与 U5 那条由脚本按 ±7 行上下文**唯一命中**确认;2818 那条脚本报 0 命中是**文件末尾取窗的假阳性**
//   (锚点后只剩 2 行,窗口取不满),已逐行人工核对:该语句与前 8 行逐字节相同,理由「patch 只逐项追加
//   六个固定选择器字段」与现码仍一致。条数不变(53 / 26),两个 digest 按门禁同一算法显式重算。
// 2026-09-18 内嵌/全屏 present:temp/index.js 因呈现二选一的 data 与 handler 净增,
//   play/index.js 因内嵌 kit 渲染净增。u4Dynamic 6 条 + u5Silent 1 条锚点等量换 key
//   (temp 934→939 / 935→940 / 990→995 / 2087→2126 / 2687→2729 / 2806→2848;play U5 5531→5559)。
//   六条由脚本按上下文唯一命中;temp:2687 那条脚本报 0 命中,因为同一个 setData 调用里**插入了两行**
//   新字段(presentInlineLocked / presentLockReason)把取窗顶开了 —— 已逐行人工核对:
//   锚点语句 `...couponPatch` 与其后 7 行逐字节相同,且原有的 gameSection / timerAvailable
//   **仍在同一个 setData 里没被删**(全文件出现次数 5→10 / 4→4),受控字段与理由未变。
//   条数不变(53 / 26),两个 digest 按门禁同一算法重算。
// 2026-09-20 R3 推理类三玩法:temp/index.js 新增编辑器字段与 handler 后，六条既有
//   U4-DYNAMIC 锚点按门禁实测等量换 key(939→1028 / 940→1029 / 995→1084 /
//   2038→2147 / 2652→2766 / 2771→2885)。逐条回读仍是同一调用点，受控字段与理由不变；
//   条数仍 54，digest 按当前 key 集合重算。
// 2026-09-18 FIX-P3(阶段3 修复):roam/index.js 因 3-06 return、3-17/3-18 handler、3-24 城市贴纸 place 净增 2-3 行,
//   play/index.js 因 3-18/3-24 宿主 handler +11 行,post-compose 因 3-19 复位 +2 行。roam U4-DYNAMIC 4 条、U5 9 条,
//   play U5 1 条、post-compose U5 2 条按门禁实测等量换 key(逐条比对调用点前后 7 行完全相同);理由与条数不变,digest 用门禁同一算法重算。
module.exports = {
  // 2026-10-27 真机走查 L04(首页 P-02/P-08 删「为什么看到」与继续旅程卡,模板页 P-11 加游戏 tab 过滤):
  //   6 条既有登记仅行号位移,等量换 key —— 首页 U1-VARIABLE 1057→1043、U4-DYNAMIC 1216→1150 / 1325→1259 / 1432→1366;
  //   模板页 U4-DYNAMIC 268→283 / 280→295。逐条回读调用点与理由一字未改,条数不变,u1Variable / u4Dynamic 两个 keyDigest 按门禁同一算法重算。
  // 2026-09-22 合 master(现场感/玩法批)后:publish/fabu 与 search2 两文件同时被两边改动,
  //   合并树的真实行号既不是我方的也不是 master 的。12 条 U4-DYNAMIC 按**门禁实测行号**换 key,
  //   并逐对回读原文确认字节相同(全部命中同一调用点),受控字段与理由一字未改,digest 随之重算。
  // 2026-09-21 F-26~F-56 那批改动使六条既有锚点行号漂移,按门禁仪式等量换 key:
  //   u5Silent play/index.js:5660→5668(my-completed)
  //   与 u4Dynamic play/index.js:1914→1916(F-37 放行注释使 play 页整体下移 2 行)、merchantinfo.js:2375→2400(info-to-user);
  //   u1Variable cy/profile:509→510 / 796→799;u4Dynamic cy/profile:850→853、merchantinfo:398→402 / 1734→1759 / 1743→1768。
  //   六条逐行回读均为**字节相同**的同一调用点,受控字段与理由一字未改,两个 digest 显式重算。
  //   同批冒出的 managedChapterProject「零消费」未入账 —— 它在 merchantinfo.js:310 真被读,
  //   只是写成 that.data 令门禁正则看不见,已就地改回 this.data,债务与棘轮均不增长。
  // 2026-09-19 并 github/master(合并树 rebase):play/index.js 改回 master 超集版本后
  //   u4Dynamic play:1771→1890、u5Silent play:5083→5585 与既有 roam/fabu/temp 等 20 余条
  //   锚点按门禁实测**等量换 key**,逐条回读仍是同一调用点,受控字段与理由一字未改,
  //   五个 keyDigest 随之显式重算。U1-VARIABLE 三条(scene-route-content 218→220 / 477→487,
  //   myproject 423→429)同批换 key,条数 10 不变。
  //   U4-DEBT 棘轮 149→151(显式调大,+2):本支批3C 删 AI 链让 themeName/nodeName 失去
  //   本页唯一 this.data 读取方转存量登记 —— 删字段=动 fabu→temp 跨页契约,留待重构收口;
  //   club/edit#routeReady 与 team#goLogin 两笔合并残渣已当场修复(补回 wx:if 闸 / 删孤儿),未入账。
  // 2026-09-06 三条向导加开场屏/动效/形变(rebase 到 #1001 之后):club/apply 与
  //   merchant/apply 行号漂移,三条既有 U4-DYNAMIC 锚点 203/213→191/202、284→316 等量换 key。
  //   逐行回读:仍是同样的 `[f]: e.detail.value` / `[f]: this.data[f] ? 0 : 1`,
  //   受控字段与理由一字未改,digest 显式更新。
  // 2026-09-05 自由探索点卡 hero 展开层落地(play/index.js 新增 _openHero/closeHero/heroEnter),
  //   既有 U5 锚点 4303→4368(loadMilestone 的 my-completed 静默项);调用点与理由一字未改,
  //   按 UI-GATE-0 实测等量换 key 并更新 digest。
  // 2026-08-31 开场 GIF 改系统 emoji 后 roam/index.js 顶部少 8 行：U4-DYNAMIC 四条与 U5 七条
  //   按门禁实测再等量换 key（1099→1091 等），调用点与理由未改，digest 显式更新。
  // 2026-08-31 漫游开场从雷达换成 HTML 弧线切场：roam/index.js 顶部增加 emoji 分区
  //   辅助函数，四条既有 U4-DYNAMIC 与七条 U5 锚点按 UI-GATE-0 实测行号等量换 key；
  //   逐行回读确认调用点、字段集合、静默降级理由与数量均未改变，digest 随之显式更新。
  // 2026-08-30 客户抽屉恢复 host 唯一「查看台账」动作后，merchantinfo.js 三处既有
  //   动态 setData 锚点 315/1485/1494→316/1491/1500；调用点、受控字段与理由未变，
  //   按 UI-GATE-0 实测等量换 key 并更新 digest。
  // 2026-08-30 Figma 客户筛选接线使 customer/index.js 既有 U5 锚点 870→872；
  //   调用点与静默降级理由未变，按门禁实测等量换 key 并更新 digest。
  // 2026-08-30 客户分页状态移出渲染 data 后，既有 U5 锚点 881→883；
  //   调用点与静默降级理由未变，按门禁实测等量换 key 并更新 digest。
  // 2026-08-26 触达历史改静默刷新:customer/index.js:870 新增一条 u5Silent 登记(12→13),棘轮显式调大并更新 digest。
  // 2026-08-26 刷新失败横幅退役:删除 staleError/loadError 等死字段使多页行号整体上移,
  //   u1Variable(2 条)与 u4Dynamic(6 条)登记项等量换 key,调用点与理由一字未改,digest 显式更新。
  // 2026-08-27 门店位改 lettermark:cy/profile 新增头像位 observer(+13 行),U1-VARIABLE 两条与
  //   U4-DYNAMIC 一条登记项等量换 key,调用点与理由一字未改,digest 显式更新。
  // 2026-08-29 已批准删改项收口:删除 celebration 入口、报名 A 选择块与漫游工具底栏后,
  //   play/roam/template 既有 U4-DYNAMIC/U5 锚点按门禁实测行号等量换 key,理由一字未改。
  // 2026-08-29 漫游浮层收尾:删除隐藏 gpsMode/stampNew 写入后,roam 既有 U4-DYNAMIC/U5
  //   锚点再次按 ci/xcx-check.sh 实测行号等量换 key,理由一字未改。
  // 2026-08-30 playkit 领取后遮罩状态接线使 play 既有 U5 锚点 4294→4296；
  //   调用点与静默降级理由未变，按门禁实测等量换 key 并更新 digest。
  // 2026-08-30 模板半屏预览退役后，两条封面失败动态写入锚点 277/289→275/287；
  //   逐条回读白名单、稳定身份与目标字段后，等量换 key 并更新 digest。
  // 2026-08-30 首页推荐主题轮播接线使 index 三处既有受控动态调用锚点下移；
  //   调用点、字段集合与理由未变，按 UI-GATE-0 实测等量换 key 并更新 digest。
  // 2026-08-30 提现安全预检集中到 withdrawal-preflight：两条既有变量调用更新行号；
  //   预检工具使用四个可枚举请求且不静默错误，原两页静默登记已收回，U1/U5 债务不增长。
  // 2026-08-30 提现错误契约收口后，scene-route-content 受控 request.url 499→504；
  //   调用点、有限映射与理由不变，按门禁实测等量换 key 并重算 digest。
// 2026-09-20 工作区收编批(实名三入口接线)落 ship/land-all-0920:simple 页随申请页改版收口整体瘦身,
//   u3Debt 18 条零绑定与 u4Debt 14 条零消费债务随死代码整删还清(80→62 / 149→135,逐条 grep 确认
//   方法/字段在全页无定义或无读取方),u4Dynamic simple:233 同笔出账;
//   u1Variable 2 条、u4Dynamic 23 条、u5Silent 1 条锚点按门禁实测等量换 key(scene-route-content 220→239/487→554,
//   club/apply 183→222/194→233,merchant/apply 319→339,fabu 11 条,temp 6 条,template 279→277/291→289,
//   play 1890→1889;291→289 一条脚本报 ±3 窗假阳性 —— #1120 摘除搜索入口改动了窗口内一行注释,已逐行人工核对
//   锚点语句逐字节相同),受控字段与理由一字未改;新增 fabu:2013/5685 两条实名接线调用点进账(53→54 显式调大,
//   只写固定键,理由见登记项)。五个 keyDigest 按门禁同一算法(sha256 of sorted keys joined by \n)显式重算。
  "generatedFromHead": "28f1bb6264480d88677c5db55f29d68962aaa734",
  "ratchet": {
    // 2026-08-25 并入最新 master(#814/#820/#823/#824/#825/#826 之后):棘轮显式调小。
    //   u4Field 191→174:816 让 18 条零消费字段要么被真消费(如 square/list#hasMore 现在
    //     由 this.data.hasMore 读)、要么整个删掉(如 club/create#stepTitle)。逐条核过源码。
    //   u4Dynamic 65→54:11 处动态 setData 被 816 改成静态字段或整段删除
    //     (scene-sound-haptics 整个文件重写成 9 行、0 处 setData)。
    //   u3Handler 100→99:scene-how-to-play-detail#onBack 的零绑定债务已还。
    //   ⚠️ 只准调小。这几个数字是欠债余额,不是配额。
    // 2026-09-09 一枚圆钮:roam#onCenterHoldStart / onCenterHoldEnd 两笔零绑定债务已还 ——
    //   长按 3 秒结束的逻辑早就写好了,这次真接到主键的 touchstart/touchend 上,99→97。
    "u1Variable": 9,
    // 2026-09-06 合入 github/master:D6 商家承接段装回章节弹窗、音频块回归故事流,
    //   master 里 6 条「零绑定债务」(onChapterRecruitToggleChange / onChapterMerchantCategoryChange /
    //   onChapterMaxMerchantInput / onChapterTermsModeChange / onChapterPerkMinValueInput / onPreviewAudio)
    //   在本分支已被 WXML 真绑定,债务已还 ⇒ 99→93,显式调小。
    // 2026-09-07 彩蛋从发布页整段撤除:onEggTextInput 这条零绑定债务随代码一起没了,93→92。
    // 2026-09-11 并 github/master:两边各自还过的零绑定债务合起来 —— 本分支的 99→97
    //   与 master 的 99→93→92 不是同一批,并完门禁实测 90,显式调小。
  // (本支) /* 2026-09-16 AI 助写按原型撤掉入口、**逻辑保留**(功能补血用):
  // (本支) onNodeAiGenerate / onNodeAiCancel 两个 handler 零绑定(90 → 92),
  // (本支) aiNodeError 一个字段零消费(161 → 162)。
  // (本支) ⚠️ 这是显式加大棘轮,不是修复 —— 新入口做出来之后必须调回去。 */
  // (本支) // 2026-09-18 批3C 兑现上面那句「调回去」:AI 助写链整删(两 handler、aiNode* 字段、
  // (本支) //   配套契约测试 publish-temp-ai.test.js 一起退役),u3Handler 92→90 取门禁实测。
  // (本支) //   同删让 themeName/nodeName 失去本页唯一的 this.data 读取方(原本只被 AI 请求体读),
  // (本支) //   转为存量零消费债务登记(162-1+2=163,显式调大),删字段=动 fabu→temp 跨页契约,留待重构收口。
  // (本支) "u3Handler": 62,
    // 2026-09-16 截图冒烟修复(本批):merchantinfo 缺参态按「与其它详情页一致」补了
    //   <cy-nav-bar bind:back="goBack">,该页 goBack 的零绑定债务(81 号)真的被消费 ⇒ 81→80。
    "u3Handler": 62,
    // 2026-09-06 孤儿页清理:club/dissolution-blockers(focusType/focusId)与 merchant/discover(loaded)
    //   三页级零消费字段随页面整删,u4Field 170→167,digest 取门禁实测。
    // 2026-09-06 原生 toast 收口:simple#toast / templateadd#id / infomationdetail#id 三条零消费债务已被真消费,167→164。
    // 2026-09-08 三个编辑弹窗(名称/副标题/简介)整块撤除 —— 全仓没有任何
    // bindtap="showEditModal",是一段谁也点不开的死 UI。editModalField 的零消费债务随之没了,164→163。
  // (本支) // 2026-09-18 批3C:AI 助写链整删 —— aiNodeError 登记随代码退役(162→161),但同删让
  // (本支) //   themeName/nodeName 失去本页唯一 this.data 读取方,转存量登记(+2)=163,显式审查调大。
  // (本支) "u4Field": 114,
    // 2026-09-17 HO-26:简易发布页去掉「仅主理人可发起」死闸后,新预检真读 this.data.clubId,
    //   simple/index.js#clubId 这条零消费债务已还,153→152,digest 取门禁实测。
    // 2026-09-17 E 类小修批:3 条零消费债务还清(B-08 baoming#ticketList 真消费、B-06 partner 两字段随页退役),
    //   u4Field 153→150,只准降的棘轮显式同步调小。
    // 2026-09-17 发布版本 release-0917 再合 fix-xcx:两侧各自还债(HO-26 −1、E 类小修 −3)叠加 153→149,只降不升。
    // 2026-09-19 批复1-a=2:pricing/partner 页按用户裁决复活(B-06 曾整页退役),其两条存量零消费
    //   字段 toType/toId 先随页回账(149→151),随即在同批复落地时还清——index.js 三处读改回显式
    //   this.data.toType/toId,门禁实测不再命中。两条登记出账,棘轮回 149,digest 复算。
    "u4Field": 121,
      // 2026-08-26 圈层主题接回主题货架:新增 circleTheme import,两条 _coverFail 动态 setData
      //   等量下移(269→270 / 281→282,行号取门禁实测)。调用点与强化理由不变,显式改 digest。
    // 2026-08-27 合并 #883:activity/list 三条 U4-DYNAMIC 登记行号 +2(129→131/151→153/231→233,
      //   #883 在同文件上方新增 rangeMin 计算与注释)。调用点与理由一字未改,等量换 key,显式改 digest(取门禁实测)。
    // 2026-09-22 play 页删地图/底部读数卡:地点卡层整层退场,其 poiCard.rows[i].visitorAvatars
    //   逐行回填那条动态 setData 随之删除(pages/play/index.js:1906 登记项出账),54 → 53,
    //   棘轮显式调小;同页 U5 一条(loadMilestone 静默)只随行号位移 5614 → 5424,条数不变。
    //   两条 digest 按门禁同一算法(sha256 of sorted keys joined by \n)显式重算。
    //   (同页 U5 那条随后又因删天色系统、自审修到达失败可见性两次位移 5424 → 5390,digest 随之重算。)
    // 2026-10-19 Claude 小程序线移植到 master(用户批准冻结数值更新):本线 6 处动态 setData 逐项登记(BL-80 通知复盘 5 处、BL-70 头像堆 1 处),58 → 64。
      // 2026-10-28 模板页货架移进分包组件(业主定稿原型 v21):旧页两处 _coverFail 动态 setData(index.js:283/295)随列表退役删除,62 → 60;keyDigest 按同一算法重算。
      "u4Dynamic": 65,
    // 2026-09-10 附近正在漫游的人:**条数 13 → 15**,棘轮显式调大两格。
    //   新增的两条都在 pages/roam/index.js,是同一件事的两半 —— 位置心跳的上行与下行:
    //     · _reportPresence(30 s 一次上报自己):掉一次就是这一格没报上去,下一次覆盖它。
    //       弹错等于每走两步弹一个框,而且用户对此无事可做。真失效的表现是「自己从别人
    //       地图上消失」(服务端 5 分钟收不到就摘掉),不是这里报一个错。
    //     · _fetchRunners(45 s 一次拉别人):拉不到保留上一批不清空(调用点有注释),
    //       地图上没人本来就是常态;这一层报错会把「附近本来就没人」说成「出问题了」。
    //   两条都不承载用户要做的动作,也都没有「重试」这个动作可给 —— 下一次心跳就是重试。
    // 2026-09-11 并 github/master:本分支两条(漫游位置心跳)+ master 两条(activity/list
    //   删面板后转逐项登记),15 → 17。两批都在上面各自写了理由。
    // 2026-09-11 17 → 20:理由同 ui-integrity.js 那条棘轮的说明。
    // 2026-09-15 地图组队 P 方案:组局页打卡点两条静默随功能退场、主题点位层补登一条,25 → 24。
    // 2026-09-15 合拢收尾:漫游页 f-hangout 一档(120 s 拉附近有人在组局)随搭子局下架整卡退场,
    //   该条静默登记删除,24 → 23;同页其余 9 条 U5 与 4 条 U4-DYNAMIC 仅按门禁实测整体位移
    //   (调用点与理由一字未改),两条 digest 显式重算。
    // 2026-09-17 第二轮拍板 17 足迹分享快照:utils/roam-share-snapshot.js 统一请求口带 silentError(三路都有专用可见反馈,见登记理由),23 → 24(冻结上限 25 不变)。
    // 2026-09-17 同批:接收方匿名 GET 改为单独字面量调用(GET 权限矩阵只认字面量 sendRequest),同文件再 +1,24 → 25(冻结上限 25 不变)。
    // 2026-09-17 release-0917 第三阶段 C:总控裁定营销同意入口附属读取静默(唯一允许的 U5 放宽),25 → 26。
    // 2026-09-19 定价页接合作方入口(批复1-a=2):loadLineupNames 补名读取新增一条 u5Silent 登记,26 → 27。
    // 2026-10-12主控明确批准本人投诉进展新增读取，精确登记故障/401/403边界；不是既有锚迁行。
    // 2026-10-19 Claude 小程序线移植到 master(用户批准冻结数值更新):本线 9 处 silentError 逐项登记(举报/纠错半屏、安全感受、通知复盘、漫游头像堆/官方活动、券统计),28 → 37。
    // 2026-10-24 上线合并(#563 bug 修复 + #508 业主 UI + #554 文案):业主 UI 带入两处静默读取逐项登记(合作 tab 帖文流失败落 feedState 错误卡并给重试;消息页竞猜待答入口读失败照样显示入口),37 → 39。
    // 2026-10-25 官方地点卡片展开详情:节点补读与评价列表两条静默背景读取逐项登记,41 → 43。
    // 2026-10-27 L05 P-20:post-compose 两条选活动读取(registration/list、activity/list)门禁现判为同请求内静态证明已处理(失败汇总进 activityError 由选活动层呈现),逐项登记随之出账,43 → 41。
    // 2026-10-27 L06 漫游真机修复 P-29:护照勋章方卡读最新点亮勋章图一条静默读取逐项登记,43 → 44。
    // 2026-10-27 L11 集成:L05 出账 2 条 + L06 登记 1 条,43 → 42。
    // 2026-10-28 p74:漫游附近活动两级兜底读取逐项登记 2 条,42 → 44(未超冻结上限 44)。
    "u5Silent": 44,
    "keyDigest": {
      // 2026-09-24 卡包底图:play/index.js data 里加 topicCover 一行,u5Silent 那条已完成里程碑读取 5444→5445,
      //   新旧行内容一致、理由一字未改,u5Silent digest 按同算法显式重算(旧值复算吻合)。
      // 2026-09-23 ComputerUse 走查修复批:条数不变(u1Variable/u4Dynamic/u5Silent),13 条只是行号下移
      //   (profile 510→518/799→808/853→862、official-inbox 99→103、myproject 429→435、temp 2265→2267/2886→2888/3005→3007、
      //   merchantinfo 1766→1773/1775→1782/2407→2414、customer 1232→1237、pricing 156→159)。
      //   逐条比对 github/master 旧行与新行内容完全一致,理由一字未改,三条 digest 显式重算。
      // 2026-09-22 商家走查 M-09/M-12:条数不变(u4Dynamic 53、u5Silent 27),只是行号整体下移 ——
      //   merchantinfo.js 加 M-12 降级分支与注释 7 行(402→409、1759→1766、1768→1775、2400→2407),
      //   customer/index.js 广播选人/重新核对逻辑插入 54 行(1178→1232)。逐条比对新旧行内容完全一致,理由一字未改,两条 digest 显式重算。
      // 2026-08-17 批5 票源归因:u1Variable 条数不变(13),只是 app.js 的 consent adapter
      //   调用点行号从 266 移到 284(onLaunch 里加了一行 captureTicketSource + 新增 onShow/
      //   captureTicketSource 两个方法)。棘轮对「等量换 key」也要求显式改 digest ——
      //   这一条就是那次显式审查:该条债务的性质与理由一字未变,仍是同一个 consent wrapper。
      // 2026-08-18 并入 master #737:u1Variable 条数不变(13),只是 subpackageA/pages/myproject/index.js
      //   的两处 url 三元赋值行号从 255/277 移到 281/317(#737 在 toggleOnline 里补了
      //   expectedUserStatus 与 release/complete 兜底、在 deleteItem 上方插了代码)。
      //   逐条看过:两处仍是同一个「bizType 二选一,两个字面量都在同方法内」的形状,
      //   理由一字未改;U1 的静态扫描照旧覆盖那四个 /api 字面量。
      // 2026-08-19 UI 缺陷收口:u1Variable 条数不变(13),只是 subpackageMember/components/cy/profile/index.js 的
      //   那条运行时变量请求路径行号从 1058 移到 1062(本轮在 profilePatch 里补了 follow/fans 的
      //   「缺字段显示 —」兜底与四行注释,整段下移)。逐条看过:债务性质与理由一字未改。
      // 2026-08-19 第二轮 UI 收口:u1Variable 条数仍不变(13),同一文件的两条行号再 +5 ——
      //   subpackageMember/components/cy/profile/index.js 1062→1067、616→621。本轮把 data 初值里的
      //   followNum/fansNum 从 null 改成「—」并写了 5 行说明(A42 实拍「关注 null / 粉丝 null」,
      //   上一轮只补了成功回调的兜底、没补初值,所以第一屏照旧打印 null)。
      //   逐条看过:两条债务的性质与理由一字未改,只是被上面插入的注释整段下移。
      // 2026-08-19 提审前删小红书 banner:pages/index/index.js 三条登记行号 +4
      //   (943→947 / 639→643 / 1070→1074),因为 HERO_PLACEHOLDER_PICS 上方加了 5 行说明、
      //   同时删掉一行图片路径。逐条看过:性质与理由一字未改。
      // 2026-08-20 商家端UI整改(PR#753):u1Variable 条数不变(13),subpackageMember/components/cy/profile/index.js
      //   两条行号 621→625、1067→1071(本轮 goUserInfo 改成商家分流并加 1 行注释,上方整段下移)。
      //   逐条看过:仍是同一个 req wrapper 二选一值 / verification-scan 枚举核销 API 的老债,
      //   性质与理由一字未改。等量换 key,显式改 digest。
      // 2026-08-21 主题 Beta + 三环境隔离 + 首页竞态/同步 abort + 全量审计分支：
      //   u1Variable 最终为 12 条；商家扫码动态 URL 登记已移除，app.js consent adapter
      //   位于 303，首页受控 retry wrapper 最终位于 956。逐条理由均未变，显式更新 digest。
      // 2026-08-22 并入 #782：profile 两条存量变量请求锨点 +2，理由不变。
      // 2026-08-23 合入 #787：主题监听、首页动效与漫游双 require 仅移动既有锚点；
      //   门禁逐项回读后 12 条理由不变，按最终源码行号显式更新 digest。
      // 2026-08-24 首页三角色动态 banner：pages/index 删掉 hero 占位块(-105/+7)，既有登记项整体上移，调用点与理由不变（key 等量替换，digest 随之显式更新）。
      // 2026-08-25 自审后去掉 onReady 冗余重渲染:1 行代码换成 2 行注释,pages/index 三个登记点整体下移一行,调用点与理由不变(key 等量替换,digest 显式更新)。
      // 2026-08-24 交互组件收口:cy-dropdown / cy-date-field 等新增代码使各页行号整体下移,既有登记项调用点与理由不变（key 等量替换，digest 随之显式更新）。
      // 2026-08-25 自审修复:同意注释由 1 行改成 4 行如实描述,scene-route-content 该调用点下移 3 行(386→389),调用点与理由不变。
      // 2026-08-25 816 并入:key 集合按上面的逐条核对结果变化,digest 显式更新。
      // 2026-08-27 X01 生产写闸接线:app.js 注入 production-write-gate(+3 行),
      //   consent adapter 调用点 313→316。调用点与理由一字未改,key 等量替换,digest 显式更新。
      // 2026-09-12 门口码:app.js 捕获门口 scene、首页 consumeDoorScene 行号下移,
      //   consent/getListData 调用点与理由一字未改,等量换 key。
      // 2026-09-12 绑定手机 P0:首页 index.js 顶部加 boundPhoneFromResponse,
      //   retry wrapper 1065→1086。调用点仍是 getListData(req.url),理由一字未改。
      // 2026-09-12 审核收口:删 talent/search,myproject 上下架调用点随编辑/取消入口下移到 371。
  // (本支) // 2026-09-18 死事件接线批(fix/audit-wiring-202609):scene-route-content 加 onNestedTransferOwner
  // (本支) //   与 json 开关后,两条变量路径登记点 217→219 / 505→515;myproject 活动卡「待审/未通过→编辑器」
  // (本支) //   分支插 6 行,上下架变量路径 388→394。调用点与理由一字未改,等量换 key,digest 显式更新。
  // (本支) "u1Variable": "5c64534f654f08234d2c2fe34cadd30d9d6a745af3fee2eb6700fb470fbdb9fa",
      // 2026-09-15 弹窗合同第 3 轮:search2/result 多路搜索请求加 autoErrorToast:false(+1 行),113 → 114,理由一字未改。
      // 2026-09-15 删除运行时生产写闸(用户拍板):app.js 去掉 require 与两处 getRequestClient/
      //   getUploadClient 注入(-3 行),consent adapter 调用点 337→334。调用点与理由一字未改,
      //   key 等量替换,digest 按合并树门禁实测重算。
      // 2026-09-16 补合第二批 合 withdraw-cs-popup(cb9b15929):scene-route-content 提现场景
      //   停用建单链路(删掉 withdrawForm 校验与 submitBankWithdrawal 分支),两条既有 U1-VARIABLE
      //   登记点按合并树 lint:ui 实测等量换 key(217→218 / 505→477)。调用点与理由一字未改,digest 按实测重算。
      // 2026-09-16 补合第二批 合 reviewc-dialog(1b04281c3):search2/result 首屏 auto-back 只在首屏生效
      //   (+4 行),一条 U1-VARIABLE 登记点 114→118。调用点与理由一字未改,digest 按实测重算。
      // 2026-09-16 B 组审计修复(B-02):scene-member-participation-detail 顶部新增两个 require
      //   (+2 行),既有 U1-VARIABLE 登记点 154→156。调用点、有限路由与理由一字未改,digest 按
      //   门禁实测重算(条数不变 10)。
      // 2026-09-16 整体检查 A 组修复:首页新增邀请参数兼容/登录后重试(+18 行),
      //   受控 retry wrapper 调用点 1086→1104。调用点仍是 getListData(req.url)、理由一字未改,
      //   key 等量替换,digest 按 lint:ui 实测重算。
      // 2026-09-16 候选池页退役:subpackageA/pages/myproject/index.js 删掉 goCandidates 导航
      //   (−4 行),既有 U1-VARIABLE 登记点 388→389。调用点、有限路由与理由一字未改,
      //   条数不变 10,digest 按门禁实测重算。
      // 2026-09-16 截图界面问题修复(fix-ui-shots-0916):subpackageMember/components/cy/profile/index.js 顶部 +1 行
      //   require(isEndedProject) 与我的项目预览过滤/状态章 +10 行,两条 U1-VARIABLE 登记点 467→478 / 751→762。
      //   调用点与理由一字未改,条数不变 10,digest 按门禁实测重算。
      // 2026-09-16 截图冒烟修复(本批):等量换 key 与还债各一处 ——
      //   · scene-member-participation-detail 新增 recordId 属性观察器(+18 行),那条 U1-VARIABLE
      //     登记点 156→174;调用点、有限路由与理由一字未改。
      //   · merchantinfo 缺参态补 cy-nav-bar bind:back="goBack",u3Debt 81→80(见棘轮注释)。
      //   · searchmap/merchantinfo/play 的既有 U4-DYNAMIC·U5 登记点随本次编辑整体位移
      //     (906→925 / 378→390 / 1714→1726 / 1723→1735 / 2357→2369 / 5280→5291),
      //     逐条回读仍是同一批调用点、理由一字未改。
      //   · tixian 退役银行卡表单时顺手删掉了零消费字段 userInfo,故 u4Debt key 集合不变。
      // 2026-09-16 合拢第五轮 A 组/候选池/界面修复/截图冒烟并入:四侧锚点同时生效(首页 1104、
      //   myproject 389、profile 478/762、scene-member-participation-detail 174),key 集合为并集
      //   (条数仍 10),digest 按合并树门禁实测重算。
      // 2026-09-17 E 类小修批:profile 顶部 import 增补 statusLabelOf、normalizeProjectPreview 加注释,
      //   首页 data 增 4 行行内重试标记。三条既有 U1-VARIABLE 登记点按门禁实测等量换 key
      //   (478→482 / 762→766 / 1104→1108)。调用点与理由一字未改,digest 重算。
      // 2026-09-18 C8-05 我的项目加「取消活动/主题前退款人数预览」两个方法(均为字面量 URL),
      //   上下架那条既有 U1-VARIABLE 登记点按门禁实测等量换 key(myproject 389→423)。
      //   逐条回读:仍是 bizType 在两个字面量间二选一,理由一字未改,条数 10 不变,digest 重算。
      // 2026-09-18 阶段4 修复:profile 新增 shapeFeaturedCard/goFeatured 与主推卡渲染(+23 行),
      //   两条 U1-VARIABLE 登记点 482→505 / 766→791(含旧商家主页兼容壳去门禁的 −1 行,净 +23)。
      //   调用点与理由一字未改,digest 重算。
      // 2026-09-25 CU-M-05 商家主办单场活动:myproject onLoad 商家态入口 + loadMerchantPublishEntry
      //   共加 30 行,那条上下架 U1-VARIABLE 登记点按门禁实测等量换 key(461→491)。
      //   逐条回读:仍是 bizType 在 activity/update_publish_status 与 topic/update_user_status
      //   两个字面量间二选一,理由一字未改,条数 10 不变,digest 按门禁同一算法复算。
      // 2026-10-04 撤下我的项目身份/状态筛选：登记点 492→408，
      // 受控路由、理由及 10 条登记保持，digest 仅随行号更新。
      // 2026-10-11 登录隔离及正常失败状态兼容：App consent wrapper 329→343，同一调用及理由，数量仍11。
      // 2026-10-14 W5尾项:profile 544→559/870→885、参与详情 176→177 仅行号下移，同一调用及理由，数量仍11。
      "u1Variable": "5355079effbb04369592177b1817e01345e1812d7671c95c9d13c3fabe88771f",
      // 2026-08-25 816 并入:key 集合按上面的逐条核对结果变化,digest 显式更新。
      "u3Debt": // 2026-09-09 一枚圆钮:roam#onCenterHoldStart / onCenterHoldEnd 两笔零绑定债务已还
      //   —— 长按 3 秒结束的逻辑接到了主键的 touchstart/touchend 上。99→97,digest 显式更新。
            // 2026-09-15 C1 简易发布预设单一真源:内置预设(PRESETS/TEMPLATES)与站点手动编辑链整体删除,
      //   9 条零绑定 handler(预设/站点/地图)一并消失;棘轮 90→81,digest 取门禁实测。
"27c9924fc0b0ee61e2a20f5c176adf2d4e3e19c6b4ecfcd71e7aee7d9ab376ea",
      // 2026-08-23 高级玩法编辑器接线后 errors/totalNodes 已被真实消费，债务 194→192。
      // 2026-08-23 全页面返回审计：pricing/partner 的 topicId 现用于根栈返回，真实消费后债务 192→191。
      // 2026-08-25 816 并入:key 集合按上面的逐条核对结果变化,digest 显式更新。
  // (本支) "u4Debt": "58d96682b3c4b8573643eaa5be0e71fd73ccb3526880da62e8602fff8dbe596a",
            // 2026-09-15 C1:simple 的地图与数据条字段随 redrawMap 一起删除(markers / polyline /
      //   includePoints / center / stationCount / totalKm / totalMin / gameCount 8 条零消费
      //   债务已还);棘轮 161→153,digest 取门禁实测。
// 2026-09-17 E 类小修批/B-06:三条零消费债务清零 —— baoming#ticketList 被 B-08 换票种真消费,
      //   pricing/partner 两个页级零消费字段(toType/toId)随整页退役。棘轮 153→150 显式同步调小,digest 重算。
      // 2026-09-17 发布版本 release-0917 再合 fix-xcx:u4Debt 两侧删除项叠加,digest 按合并树 key 集合实算。
      // 2026-09-19 并 master 收口:temp 页 themeName/nodeName 随 AI 链删除后确认无任何读取方(含路由参数侧 fabu→temp,
      // 参数留存 URL 但页面不再落 data),两条存量债直接出账,149 键集实算复挖。
      // 2026-09-19 批复1-a=2 还清:partner#toType/toId 两条随页复活短暂回账(151 键)后当场还债出账,
      //   key 集回到退役前那份 149,digest 按当前 key 集合实算。
"u4Debt": "d8ea150c9aa89628073507c832b720c6a235cef84a4307982b6461a06f0a7000",
      // 2026-08-17 漫游 S1 施工:条数不变(65),只是 pages/roam/index.js 的两处
      //   `...this._calcArch()` 动态 setData 行号从 3263/3305 移到 3261/3303
      //   (删掉 _apiCheckin 与 /api/play/arrive 那段后整段上移)。
      // 2026-08-18 并入 master #737:u4Dynamic 条数不变(65),只是 pages/topic/merchantinfo/merchantinfo.js
      //   的三处动态 setData 行号从 302/1250/1259 移到 301/1255/1264(#737 改了该页的承接态渲染)。
      //   逐条看过:三处仍是「setData 参数不是对象字面量」这同一条性质,理由一字未改。
      // 2026-08-19 UI 缺陷收口:u4Dynamic 条数不变(65),四处行号随本轮插入的行上移/下移:
      //   subpackageMember/components/cy/profile/index.js 661→665(补 follow/fans 兜底);
      //   pages/merchant/customer/index.js 126→127(空态文案改用本次响应的分段计数);
      //   pages/square/list/index.js 121/177/216→123/179/218(data 里加 listLoading 骨架开关)。
      //   逐条看过:四处仍是同一批「动态 setData 未登记」的老债,性质与理由一字未改。
      // 2026-08-19 第二轮 UI 收口:u4Dynamic 条数仍不变(65),subpackageMember/components/cy/profile/index.js
      //   那条 `that.setData(profilePatch)` 行号 665→670(本轮在 data 初值里把 followNum/fansNum
      //   从 null 改成「—」并写了 5 行说明)。逐条看过:仍是同一条「setData 参数不是对象字面量」的
      //   老债,性质与理由一字未改。
      // 2026-08-19 第三轮 UI 收口:u4Dynamic 条数仍不变(65),pages/publish/simple/index.js
      //   两条行号 279→285、317→323(本轮在 generateAiPlan 里补了「配额已用完就不发请求」的
      //   前置闸与 6 行说明 —— 页面顶部写着「还可生成 0 次」,输入框和发送钮却照常可用)。
      //   逐条看过:两条仍是同一批「setData 参数不是对象字面量」的老债,性质与理由一字未改。
      // 2026-08-19 提审前删小红书 banner:pages/index/index.js 三条登记行号 +4
      //   (943→947 / 639→643 / 1070→1074),因为 HERO_PLACEHOLDER_PICS 上方加了 5 行说明、
      //   同时删掉一行图片路径。逐条看过:性质与理由一字未改。
      // 2026-08-20 商家端UI整改(PR#753):u4Dynamic 条数不变(65),三条行号漂移 ——
      //   subpackageMember/components/cy/profile/index.js 670→674(goUserInfo 商家分流 +4 行);
      //   pages/template/index.js 169→171、181→183(data 加 avatar 字段与 1 行注释)。
      //   逐条看过:仍是同一批「setData 参数不是对象字面量 / _coverFail 动态 key」的老债,
      //   性质与理由一字未改。等量换 key,显式改 digest。
      // 2026-08-20 主题模板货架入口(去弹窗直落版):u4Dynamic 条数不变(65),两条行号漂移 ——
      //   pages/template/index.js 171→176、183→188(data 加 ttList/ttUsing 两字段与 2 行注释,
      //   onShow 加 getTopicTemplates())。逐条看过:仍是 _coverFail 动态 key 老债,
      //   性质与理由一字未改。等量换 key,显式改 digest。
      // 2026-08-20 专业编辑器去弹窗批次(PR#760):u4Dynamic 条数不变(65),13 条行号漂移 ——
      //   pages/publish/fabu/index.js 十条(618→642 / 1696→1751 / 2606→2661 / 2945→3000 /
      //   3814→3869 / 4053→4108 / 4412→4467 / 4420→4475 / 4465→4520 / 5547→5608,
      //   本轮插入 _createChapterDirect(+24 行)、onNativeBackGuard(+27 行)与配置模板直进注释);
      //   pages/publish/temp/index.js 三条(1086→1105 / 1532→1551 / 1657→1676,
      //   插入 onNativeBackGuard +19 行)。逐条看过:仍是同一批「setData 参数不是对象
      //   字面量 / 动态 key」的老债,性质与理由一字未改。等量换 key,显式改 digest。
      // 2026-08-20 章节氛围预设:u4Dynamic 条数仍为65,编辑器新增受控预设字段与选择器后,
      //   同页11条既有动态 setData 整体下移。逐条回读仍是原调用点、理由未变,只更新行号与 digest。
      // 同批次补齐传统新增章节入口与保存后重置的 DEFAULT，后八条再下移 1/3 行；
      // 数量仍为65，逐条回读理由未变。
      // 2026-08-21 主题级组队配置(PR#761):u4Dynamic 条数不变(65),专业编辑器新增
      //   组队字段回填与三个交互方法,使 pages/publish/fabu/index.js 的 11 个既有
      //   登记点漂移(485→488 / 642→645 / 1751→1756 / 2661→2681 / 3000→3020 /
      //   3869→3889 / 4108→4128 / 4467→4487 / 4475→4495 / 4520→4540 / 5608→5630)。
      //   逐条核对仍为原动态 setData 调用,性质与理由未改;等量迁移 key。
      // 2026-08-21 rebase 到含章节氛围预设的 master:两批位移叠加后,11 个调用点为
      //   491 / 649 / 1761 / 2689 / 3035 / 3904 / 4143 / 4502 / 4510 / 4555 / 5645。
      //   数量、调用点与逐项理由均不变,只更新最终行号与 digest。
      // 2026-08-21 rebase 到含《生活不掉线》货架入口的 master:u4Dynamic 数量不变(65),
      //   仅 pages/template/index.js 两条 _coverFail 动态 setData 因货架入口代码上移而位移
      //   (171→176 / 183→188)。已逐条回读:调用点性质与理由一字未改,只更新行号与 digest。
      // 2026-08-22 模板页顶栏胶囊避让:新增 8 行运行时安全区计算,两条 _coverFail
      //   动态 setData 等量位移(176→184 / 188→196),调用点与理由均未改变。
      // 2026-08-21 主题 Beta 试玩区:u4Dynamic 条数不变(65),pages/index/index.js 一条行号
      //   1074→1075(同上,_buildRecoHero 补 betaFlag 一行导致下移)。逐条回读仍是原调用点
      //   (setData 参数不是对象字面量),性质与理由未改。详情页新增的 setData({'info.betaFlag':0})
      //   是对象字面量,不进动态登记。等量换 key,显式改 digest。
      // 2026-08-21 创作引导卡(四步清单):u4Dynamic 条数不变(65),pages/publish/fabu/index.js
      //   十一条行号漂移(488→500 / 646→658 / 1756→1850 / 2669→2763 / 3015→3109 /
      //   3884→3978 / 4123→4217 / 4482→4585 / 4490→4593 / 4535→4638 / 5623→5726,
      //   本轮插入 require+GUIDE_STEP_DEFS(+12 行)、onTapGuideStep 等引导卡处理器(+82 行)、
      //   refreshPrimaryActionState 里 guideCard 派生(+9 行))。逐条回读仍是原调用点
      //   (patch/summaryPatch/Object.assign 动态 setData),性质与理由一字未改。
      //   新增引导卡代码自身的 setData 全是对象字面量,不新增动态登记。等量换 key,显式改 digest。
      // 2026-08-22 漫游 bgTracker require 归位模块顶层 + 三渲染路径统一逐点半径:
      //   u4Dynamic 条数不变(65),pages/roam/index.js 四条因 require 上移与逐点 r 注释而漂移
      //   (985→993 / 2513→2516 / 3269→3272 / 3311→3314)。逐条回读仍是原动态 setData 调用点,
      //   性质与理由一字未改。行号取门禁实测,非手算。等量换 key,显式改 digest。
      // 2026-08-22 rebase 到 #764：装机计划编辑器使 pages/publish/temp/index.js 八条
      //   存量登记位移到 281/282/304/312/576/1160/1615/1740；逐条回读理由不变。
      // 2026-08-22 全量审计分支 rebase 到最新主干：65 条逐项理由不变；
      //   主干漫游定位、逐点半径、本分支既有位移及登录态改动同时生效。
      //   逐条回读仍是原动态 setData 调用，只更新合并后最终行号与 digest。
      // 2026-08-22 全量审计分支 rebase 到最新主干并完成动态验收门禁：
      //   65 条逐项理由不变；主干漫游定位与逐点半径、roamSessionReady、
      //   漫游结算定位、首页 epoch/abort 同时生效。逐条回读后只更新最终行号与 digest。
      // 2026-08-21 再 rebase 创作引导卡：专业编辑器十一处最终为 503 / 661 / 1855 /
      //   2783 / 3129 / 3998 / 4237 / 4605 / 4613 / 4658 / 5748；逐条仍是原动态
      //   setData 调用，数量与理由不变，只更新最终行号锚点与 digest。
      // 2026-08-22 空响应保护：searchmap/detail 的存量动态 setData 分别位移到
      //   789 / 358；调用与理由不变，只更新最终行号锚点与 digest。
      // 2026-08-22 并入 #779/#782：profile、publish/temp、roam 共12条存量锨点纯位移；
      //   门禁实测数量仍为 65，逐条理由不变。
      // 2026-08-23 合入 #787：65 条旧动态 setData 数量与理由不变；
      //   首页、漫游、模板页和模板详情页锚点按门禁实测更新。
      // 2026-08-23 #780 叠加圈层页：模板与商家供给表单的 5 个既有/新增动态 setData
      // 已逐项核对 WXML 消费并登记；数量从 60 回到冻结上限 65，显式更新 key digest。
      // 2026-08-23 高级玩法叠加 #780：65 个既有动态调用逐项映射到合并后源码行号，理由不变。
      // 2026-08-23 再合入交互模板图标映射：两条 _coverFail 调用点随静态映射位移。
      // 2026-08-23 编辑器返回与减动效修复：temp 新增 7 行偏好同步、mytemplate 新增
      //   8 行根栈返回兜底；10 条存量动态 setData 只发生等量行号位移，理由不变。
      // 2026-08-23 成功离场与预览返回拆分：temp 新增 5 行 exitPage，8 条存量
      //   动态 setData 锚点同步 +5；逐条调用与登记理由不变。
      // 2026-08-24 玩家级漫游记忆并入 master：pages/roam 新增记忆模块 require 与分页同步、
      //   subpackageRoam/session 新增一个 require；5 条既有动态 setData 仅行号漂移，理由逐字不变。
      // 2026-08-24 分支剧情路线 M0-M5 并入 master：publish/fabu、publish/temp、play 靠前处新增代码，既有登记项仅行号漂移，理由不变（key 等量替换，digest 随之显式更新）。
      // 2026-08-24 广场 feed 与详情改版：square/detail、square/list 新增分享卡与 remix 代码，roam 随 master 位移；既有登记项仅行号漂移，理由不变（key 等量替换，digest 随之显式更新）。
      // 2026-08-24 Figma 玩法 UI 落地：subpackageRoam/session 与 play 新增回放与叙事代码，既有登记项仅行号漂移，理由不变（key 等量替换，digest 随之显式更新）。
      // 2026-08-24 首页三角色动态 banner：pages/index 删掉 hero 占位块(-105/+7)，既有登记项整体上移，调用点与理由不变（key 等量替换，digest 随之显式更新）。
      // 2026-08-25 自审后去掉 onReady 冗余重渲染:1 行代码换成 2 行注释,pages/index 三个登记点整体下移一行,调用点与理由不变(key 等量替换,digest 显式更新)。
      // 2026-08-24 交互组件收口:cy-dropdown / cy-date-field 等新增代码使各页行号整体下移,既有登记项调用点与理由不变（key 等量替换，digest 随之显式更新）。
      // 2026-08-24 门店 AI 形象并入:pages/roam 等新增代码使既有登记项行号漂移,调用点与理由不变（key 等量替换，digest 随之显式更新）。
      // 2026-08-24 模板货架封面换轨:pages/template 新增 key art 代码,既有登记项仅行号漂移,调用点与理由不变（key 等量替换，digest 随之显式更新）。
      // 2026-08-25 816 并入:key 集合按上面的逐条核对结果变化,digest 显式更新。
      // 2026-08-24 17 页接上 cy-privacy-gate 导致下方 setData 行号下移（key 等量替换，digest 随之显式更新）。
      // 2026-08-25 4 条 setData 行号漂移(merchantinfo x3 / coupon-qr),已逐条核对源码行
      // 文本与 github/master 逐字一致,key 等量替换,digest 随之显式更新。
      // 2026-08-26 游戏化地图头像 rebase 到管理员治理之后：roam/index.js 新增头像取值与
      // Canvas 绘制导致 4 条既有动态 setData 行号漂移（1027/2641/3397/3439 →
      // 1036/2719/3475/3517），调用点、理由与数量均不变，等量替换后显式更新 digest。
      // 2026-08-26 合入管理员治理：scene-club-edit 的动态字段调用点 129→131，
      // 理由与数量不变，按棘轮要求显式更新 key digest。
      // 2026-09-02 P 系列配置屏对稿：scene-club-edit 顶部插入 syncDerived + 定位 T1 选择器
      //   一组方法，onInput 那条动态 setData 的行号 130→147。调用点、理由与数量均不变
      //   （新加的 applyPicker 刻意写成两个字面量分支，不引入第二条动态 setData）。
      // 2026-08-26 日期区间组件接线:u4Dynamic 条数不变(54),只是 pages/activity/list/index.js
      //   三处动态 setData 的行号从 127/149/229 移到 129/151/231 —— 把「活动开始/活动结束」
      //   两个独立 cy-date-field 换成一次选完的 cy-date-range-sheet 时,在上方插入了
      //   pubOpenRange/pubCloseRange/pubOnRange 三个方法。
      //   逐条看过:三处仍是同一个「setData 参数不是对象字面量」的形状,豁免理由一字未改。
            // 2026-08-26 刷新失败横幅退役:删除 staleError/loadError 等死字段使多页行号上移,
      //   u4Dynamic 6 条登记项等量换 key,调用点与理由一字未改,与上面的漂移合并后重算 digest。
      // 2026-08-27 X10 漫游 onHide 时钟/GPS 一致性:onHide 加了 10 行说明与一个 !this._realOn 判据,
      //   pages/roam/index.js 其后的登记点整体下移 10 行。调用点与理由一字未改,key 等量替换,digest 显式更新。
      // 2026-08-27 X05 发布页拆分:buildRouteMap(176→15 行)与 _buildValidationBag(99→20 行)
      //   抽成 utils/publish/route-map-view.js 与 publish-validation-bag.js,页面净减 239 行。
      //   fabu 的 11 条动态 setData 登记逐条按**内容**核对过 old→new 行号(10 条逐字一致,
      //   第 11 条是 buildRouteMap 末尾那行由 setData(patch) 改写为 setData(view.patch),同一调用点)。
      //   等量换 key,digest 显式更新。
      // 2026-08-27 日期区间接线补三根线(allow-same-day/min/months):pages/activity/list/index.js
      //   顶部加 require(calendar) + data 加 rangeMin,三处既有动态 setData 行号
      //   129/151/231 → 131/153/233(行号取门禁实测)。调用点与理由一字未改,等量换 key,显式更新 digest。
      // 2026-08-27 合入 master(#882-#885)时本行冲突:两侧各自改过 digest,不能二选一。
      //   逐条对过三方 key 集合:合并结果 = 本分支的 fabu(11)/roam(4)漂移 + master 的
      //   activity/list 三行(129/151/231 → 131/153/233),两两不相交,54 条不增不减。
      //   合并后跑门禁,U4 实测「动态 setData 54/54 已登记」—— 行号对合并后的树是准的。
      //   digest 按合并后的 key 集合重算,不是取任一侧的值。
      // 2026-08-27 v5.1 玩法配置补齐:u4Dynamic 条数不变(54),8 条登记项等量换 key ——
      //   pages/publish/temp/index.js 新增七段玩法的重复项编辑器(+83 行)、
      //   pages/publish/simple/index.js 接住模板名初始想法(+4 行),既有登记点整体下移:
      //   temp 523→606 / 524→607 / 579→662 / 1484→1567 / 2042→2126 / 2161→2245,
      //   simple 291→295 / 329→333。逐条回读:调用点形状与理由一字未改,
      //   新增的 setData 全是字面量字段路径(advanced.blindTaste.options 这类),不进动态登记。
      //   rebase 到 #859 之后与 master 侧「刷新失败横幅退役」的漂移合并,digest 取门禁实测。
      // 2026-08-27 二轮(一卡两钮):u4Dynamic 条数仍不变(54),pages/template/index.js
      //   的两条 _coverFail 动态 setData 各下移一行 ——
      //   本轮只在 data 里加回了 ttUsingId 一行。调用点与理由一字未改,digest 随之更新(取门禁实测)。
      // 2026-08-27 采用链秘密字段回填:pages/publish/temp/index.js 在 prepareFormData 附近
      //   插入 _advancedValidateOpts(+7 行),下游两条既有登记点下移(2126→2133 / 2245→2252)。
      //   调用点形状与理由一字未改,等量换 key,digest 显式更新(取门禁实测)。
      // 2026-08-27 二次合入 master(#872 v5.1 采用链)时,条目表本身也冲突了 ——
      //   两侧改的是同一批文件的行号,不是各改各的,靠算术推不可靠。做法是先取本分支一侧
      //   (它的理由文本是超集:fabu 地图重绘那条带着 X05 抽出纯函数的说明,master 侧没有),
      //   再跑门禁,由它报出合并树上的实测行号来校准。门禁只点了 8 条:
      //     simple 291→295 / 329→333,temp 523→606 / 524→607 / 579→662 /
      //     1484→1567 / 2042→2133 / 2161→2252 —— 全是本分支没碰过的文件,
      //     master 的 +83/+4 行是新事实,照收。
      //   fabu 与 roam 一条没点,说明本分支那 15 条行号在合并树上本来就是对的。
      //   校准后 U4 实测「动态 setData 54/54 已登记」。
      //   三方 key 集合复核:54/54/54,凭空新增 0 —— 每条要么来自本分支(我改过的文件)、
      //   要么来自 master(我没碰的文件),没有第三种来源。digest 按合并结果重算。
      // 2026-08-28 rebase 合流:行号取门禁实测、条数不变、理由一字未改。
      // 2026-08-28 三次合入 master(#911 发布/主题表单状态、#913 社群草稿与投票):条目表再次冲突。
      //   仍不挑边:先取本分支一侧(理由文本是超集),再跑门禁由它报出合并树上的实测行号来校准。
      //   门禁点了 7 条,逐条按它报的改,没做算术外推 —— 位移量本来就不一致(+1/+5/+12):
      //     fabu 5114→5115 / 5122→5123 / 5167→5168 / 6117→6129,
      //     temp 1567→1572 / 2133→2138 / 2252→2257。
      //   三方 key 集合复核 54/54/54,来源逐条可追:
      //     36 条两侧一致;11 条取自本分支(master 侧行号已陈旧);
      //     3 条 temp/* 取自 master —— 本分支对 temp/index.js 零改动,master 的行号才是准的;
      //     4 条 fabu/* 两边都不是 —— fabu/index.js **两侧都改了**
      //     (本分支 +33/-270 是 X05 抽出纯函数,master #911 +21/-9),
      //     合并后的行号必然是第三个值,任何一侧都不可能知道,只能由门禁在合并树上实测。
      //   凭空新增 0:每条要么来自本分支、要么来自 master、要么是上述两侧共同改动的实测结果。
      //   digest 从合并后的 key 集合重算(a5ad1390…),不取任何一侧的旧值。
      // 2026-08-29 漫游浮层收尾:删除 gpsMode/stampNew 隐藏状态后,roam 四条存量登记
      //   按门禁实测 1045→1041 / 2728→2722 / 3479→3470 / 3521→3512 等量换 key。
      // 2026-08-30 商家入驻四步结构补齐:页面头部增加 11 行，既有 onInput 动态字段写入
      //   由 271→282；调用点、允许字段来源与理由不变，等量换 key 后显式更新 digest。
      // 2026-09-03 新建帖文改成广场页上的弹窗:pages/square/list 的 data 里加了 2 行
      //   (composeShow / composePrefill),两条既有动态 setData 锚点 139→141、201→203。
      //   条数不变(54),调用点与理由一字未改,等量换 key 后显式更新 digest。
      // 2026-09-03 rebase 到最新 master:master 侧把 pages/square/list 的两条锚点挪到 139/141,
      //   本分支侧把 scene-club-edit 的动态字段调用点从 130 挪到 157(P 系列配置屏在它上方
      //   插了 syncDerived 与定位 T1 选择器)。两边各改各的条目,digest 两边都不是并集后的真值 ——
      //   这里的值是按合并后的 key 集合当场重算的,不是从任一侧抄的。条数不变(54)。
      // 2026-09-03 入职流 H1-H4 重做:apply 页那两条计算键 setData 还是同样的两处
      //   ([f]: e.detail.value 与 [f]: this.data[f] ? 0 : 1),只是行号 142/152 → 202/212 ——
      //   等量换 key,条数仍 54,不是放宽。理由同时写细了:键取自 wxml data-field 的七个枚举。
      //   同批把 refreshStep / refreshDerived 两处 Object.assign 摊开改成写明字段,
      //   那两处本来就没登记过 —— 能写静态的就别来登记册占位。
      // 2026-09-10 等量换 key:scene-club-edit 解散阻断项按稿 P6 改成四段渲染,顶部多了 3 行,
      //   那条动态 setData 登记行号 158 → 161。调用点与理由一字未改,条数不变(54),digest 显式更新。
      // 2026-09-11 并入 master:两边各自改过这个 digest(本分支是上面那次行号位移,master 是它自己那批),
      //   所以不是二选一 —— 值按合并后的真实文件重算,下面这个是重算出来的。
  // (本支) // 2026-09-18 死事件接线批:roam onLoad 接 scene 参数 +3 行(1370/3320/4086/4136 → +3),
  // (本支) //   play onUnload 补 _cancelRunHold +3 行(1768→1771)。调用点与理由一字未改,等量换 key,digest 显式更新。
      // 2026-09-17 E 类小修批:三条动态 setData 登记点按门禁实测等量换 key
      //   (profile 817→821 / index 787→791 / 1246→1250)。调用点与理由一字未改,digest 重算。
      // 2026-09-24 #1137 合 github/master(#1159/#1164/#1165/#1169 等):u4Dynamic 条数不变,21 条只是行号下移(fabu 13、temp 6、profile/search2 各 1)。
      //   逐条按「登记理由 ↔ 新行代码」配对(不按行号顺序:fabu 摘要补丁 5578→5584、发布按钮补丁 5570→5592 两条先后与旧记录相反),理由一字未改,digest 显式重算。
      "u4Dynamic": // 2026-09-09 一枚圆钮:play/roam 两页在中键上方各插了长按处理与注释,下方既有登记点整体下移。
      //   2026-09-09 点位语言 + 顶部输入地址:roam/index.js 顶部加 GAME_STAR / SEARCH_ROW_H
      //   两个常量、_genIcons 里加图钉与金星绘制、_syncMarkers 前加 _searchPlaces 与
      //   onSearchPick,四条登记点 1104/2808/3556/3606 → 1127/2883/3631/3681。
      //   2026-09-09 商家卡头像堆:**条数 54 → 55**,棘轮显式调大一格。
      //   新增的是 play/index.js:1070 —— 按行下标回填 poiCard.rows[i].visitorAvatars/.visitorTotal。
      //   这不是放宽:字段名的两个后缀都是本方法里的字面量,下标取自本页 rows 的遍历序号,
      //   接口回包只提供值、不提供键名,所以它写得出哪些键是静态可判的。
      //   之所以还是走登记而不是改成静态 setData:要改的是「第 i 张卡」的两个字段,
      //   整表重写会让横滑卡在回包到达时整条重渲染、滚动位置被顶回第一张。
      //   其余调用点与理由一字未改,等量换 key,digest 显式更新。
      //   2026-09-10 读数真值挪出 data:roam/index.js 把 data.stats 改成实例字段 this._stats
      //   (它一行都不进 wxml,留在 data 会被 U4 死数据字段判成 A2),同批 data 与 onLoad
      //   各加了几行注释。四条登记点 1132/2926/3674/3724 → 1140/2934/3682/3732。
      //   条数仍 55,调用点与理由一字未改,等量换 key,digest 显式更新。
      //   2026-09-10 附近正在漫游的人:roam/index.js 顶部加 normalizeRunners import 与三个常量、
      //   data 加 runnerView、_syncMarkers 里加他人图层、页里加 _reportPresence/_fetchRunners/
      //   _genRunnerIcons/openRunner/runnerSayHi/runnerOpenProfile。四条登记点
      //   1140/2934/3682/3732 → 1151/3094/3848/3898。条数仍 55,等量换 key,digest 显式更新。
      //   2026-09-10 漫游四模式脱离 DS 壳:roam/index.js 加 sceneIn 开关与 noop,
      //   openScene/closeScene 各多几行,四条登记点整体 +8(1151/3094/3848/3898 →
      //   1159/3102/3856/3906)。条数仍 55,等量换 key,digest 显式更新。
      //   2026-09-10 第二批(漫游 13 个半屏一并换成原型壳):roam/index.js 加
      //   onProtoSheetClose,四条登记点再 +8。条数仍 55,等量换 key,digest 显式更新。
      //   2026-09-10 旅程手记照原型重做成半屏:play/index.js 删掉滚动沉降与测量那一整段,
      //   1070 → 1069。条数仍 55,等量换 key,digest 显式更新。
      //   2026-09-10 自查补清:手记那批留下的死字段(justWrote/opacity/blur/scale…)与
      //   SINK 色阶一并清掉,play/index.js 再少几行,1069 → 1068。等量换 key。
      //   2026-09-10 漫游补工具抽屉:roam/index.js data 里加 more、方法区加一串抽屉入口,
      //   四条 roam 登记点 +2。条数仍 55,等量换 key,digest 显式更新。
      //   同批还加了 clearAsk 与清空确认的三个方法,四条 roam 登记点再 +3(按门禁实测)。
      //   2026-09-10 自由探索加地图模式:play/index.js 加 freeMap/freeExpireText 与两个 handler,
      //   那条登记点 1068 → 1072。条数仍 55,等量换 key。
      //   2026-09-11 游玩页脱离 DS 壳:play/index.js 删掉 poiCard 上一行注释缩进的空位,
      //   那条登记点 1072 → 1074;2026-09-11 并 master 后 → 1729;2026-09-12 故事流退出补丁再 +7 → 1736(按门禁实测)。条数仍 55,等量换 key,digest 显式更新。
      //   2026-09-11 并 #1043:merchant/apply 开场屏把 onInput 动态 setData 284→316,
      //   条数仍 55。同批再并 #1061 漫游换面,roam 登记点取 master 行号。
      //   digest 按合并后 key 集合重算,不是取任一侧。
      // 2026-09-12 门口码与绑定手机 P0 叠在首页:动态 setData 753→769 / 1207→1228。
      //   调用点与理由一字未改,条数仍 55。
      // 2026-09-12 #1060 空音频占位 try 包进页链路:fabu 10 条登记点按门禁实测换行号,
      //   调用点与理由一字未改,条数仍 55。digest 按合并后 key 集合重算。
  // (本支) // 2026-09-16 竞猜揭晓时间:publish/temp/index.wxml 里插了「第几天揭晓 / 揭晓时间」两格,
  // (本支) //   index.js 里 onAdvancedPicker 多了一行 predictDayLabel 同步 —— 该页六条既有登记点
  // (本支) //   整体下移 9 行(729/730/785/1852/2447/2566 → 738/739/794/1861/2456/2575)。
  // (本支) //   **条数不变(54)**,调用点、字段集合与理由一字未改,等量换 key 后按实测重算 digest。
  // (本支) // 2026-09-16 二轮(九宫格预览回落):publish/temp/index.js 的 previewGameFromSheet
  // (本支) //   多了三行注释与一行 buildSampleKit 回落 —— 该页三条登记点 1861/2456/2575 → 1864/2459/2578。
  // (本支) //   条数不变(54),调用点与理由未改,等量换 key 后按实测重算 digest。
  // (本支) // 2026-09-16 三轮(编辑页按原型重排):publish/temp 删掉 AI 助写卡与它那 ~130 行孤儿逻辑、
  // (本支) //   删玩法难度、封面提到最上、玩法规则改步骤列表 —— 该页六条登记点整体上移
  // (本支) //   (738/739/794/1864/2459/2578 → 726/727/782/1760/2357/2476;themeName / nodeName 两个
  // (本支) //   只被 AI 请求体读的字段也一并清掉,零消费债务回到 161)。
  // (本支) //   条数不变(54),调用点与理由未改,等量换 key 后按实测重算 digest。
  // (本支) "c1a5dd34ab0b0fecaf817f7726a823154a9ed136039714f86deaa008f67926d2",
      // 2026-09-13 R9-42/43/44 漫游历史存读/轨迹/分享修复:pages/roam/index.js 顶部
      //   新增两条 require、onProtoSheetClose 的历史分享分支,并删掉被新实现覆盖的旧
      //   onSceneSessionShare 桩;4 条 U4-DYNAMIC 锚点 1581/3586/4379/4429 →
      //   1586/3591/4384/4444。逐行回读:仍是同一批动态 setData,理由一字未改,
      //   条数仍 54,digest 按门禁实测重算。
      // 2026-09-14 CI 修复(干净树实测):temp 1852/2447/2566→1879/2476/2595、square/list 150/209→151/210、roam 1594/3599/4392/4450→+1。
      //   逐条比对 0a74099f4 与现码调用点上下 7 行逐字一致,仅行号漂移;理由与条数 54 不变,digest 按实测重算。
            // 2026-09-15 C1:simple 的预设应用动态 setData 随 applyPreset 删除,棘轮 54→53;
      //   loadMyClubs 的 that.setData(patch) 随删码上移 299→233,调用点与理由一字未改,digest 取门禁实测。
      // 2026-09-15 拍板 27:同上,u4Dynamic 里 fabu 的 11 条登记项随主题级 BGM 删除整体上移,
      //   逐条门禁实测回读(见上方 u4Dynamic 区块说明),条数仍 54,digest 显式更新。
      // 2026-09-15 集成总线合并 C1(p3-cleanup)× 拍板 27(ruling-cleanup):两边 key 集合取并(fabu 取拍板 27 的上移行号,
      //   simple 取 C1 删预设后的 233 且去掉已删的 337),条数 53,digest 按合并后 key 集合实算。
      //   2026-09-15 弹窗合同:club/apply 删掉 blocked 态的同义「无法申请」弹窗(-6 行),两条 U4-DYNAMIC 190/201 → 184/195,onInput/toggleBool 与理由一字未改,digest 显式更新。
      //   2026-09-15 弹窗合同第 3 轮:club/apply 删掉提交前「无法申请」单钮弹窗改 toast 并去掉孤儿 modal require,184/195 → 183/194;
      //   merchant/apply 首载请求加 autoErrorToast:false 与 everSettled 标记(+3 行),316 → 319。调用点与理由一字未改,digest 显式更新。
      // 2026-09-15 合作池收编:im/list 删「合作池」cell 与 goCoopPool 上移 4 行,
      //   1 条 U4-DYNAMIC 锚点 233 → 229。逐行回读:仍是同一个动态 setData,理由一字未改,条数不变,digest 按门禁实测重算。
      // 2026-09-15 R9-21 暂停会话恢复:play/index.js 新增暂停落盘/恢复逻辑,play 唯一那条登记点
      //   1768→1828;调用点上下 15 行逐字一致,仅行号漂移;理由与条数 54 不变,digest 按门禁实测重算。
      //   2026-09-15 R1(审查C P1)通关会话收尾:play/index.js 加 _endRunSession 并把 openFinish
      //   接上统一收尾(净 +8 行),同一条动态 setData 登记点 1828→1836;调用点与理由一字未改,
      //   条数仍 54,digest 按门禁实测重算。
      // 2026-09-16 H050/H051 帖文操作接线:square/list 的 showAction/closePostActions 在数据与
      //   方法区各 +2 行,两条登记点 151/210 → 153/212(逐行回读仍是同一个动态 setData,理由未改)。
      //   条数仍 53,digest 按门禁实测重算。
      // 2026-09-15 公开承接池开关恢复(裁决 12B):fabu 8 条登记点再 +7(上送体注释 -2 抵消后按门禁实测:
      //   2976→2983 / 3342→3349 / 4296→4303 / 4544→4551 / 5200→5207 / 5208→5215 / 5261→5268 / 6227→6232),
      //   逐条比对调用点上下 7 行逐字一致;理由与条数 54 不变,digest 按实测重算。
      // 2026-09-15 补充裁决(俱乐部自办团不进公开承接池):fabu 上送体上方 +2 行注释,
      //   6232→6234 一条锚点位移(其余 8 条在该点上方不受影响);调用点与理由一字未改,digest 按实测重算。
            // 2026-09-15 集成总线第二段 合 publish-rules(裁决 12B):fabu 8 条 u4Dynamic 锚点因上方新增
      //   onClubPoolChange 处理器整体 +7(2968→2975 / 3334→3341 / 4288→4295 / 4536→4543 /
      //   5192→5199 / 5200→5207 / 5253→5260 / 6217→6224),按合并树 lint:ui 实测回读,非两侧自报值。
      //   调用点与理由一字未改,条数仍 53,digest 按合并后 key 集合实算。
            // 2026-09-15 集成总线第二段 合 map-team:U4-DYNAMIC key 集合本身未变(仍 53),
      //   但 order-detail 组件新增建队卡后需按合并树重算 digest。
      // 2026-09-16 补合 coupon-redeem-fix(券出示码改服务端 HMAC 动态码):coupon-qr 同一条动态 setData 97→224 等量换 key,理由一字未改,digest 按合并后 key 集合实算。
      // 2026-09-16 补合第二批 合 batch-xcx-small(dbafa945e) 与 orphan-field 两条线合流:
      //   batch-xcx:fabu/temp/templatedetail 三个文件新增销毁预听与 publisher 派生代码,
      //   16 条既有 U4-DYNAMIC 登记点按合并树 lint:ui 实测等量换 key(2975→2976 / 3341→3347 /
      //   4295→4301 / 4543→4549 / 5199→5205 / 5207→5213 / 5260→5266 / 6224→6230;
      //   temp 729→733 / 730→734 / 785→789 / 1879→1883 / 2476→2481 / 2595→2600;
      //   templatedetail 175→197 / 250→278)。
      //   orphan-field:play/index.js data 区加 nightHint 字段(+2 行),play 唯一那条 U4-DYNAMIC
      //   锚点 1836→1838;调用点(poiCard.rows[i] 头像堆)、字段集合与理由一字未改。
      //   两侧调用点与理由均一字未改,条数仍 53,digest 按合并后 key 集合实算。
      // 2026-09-16 H050/H051 帖文操作接线:square/list 两条登记点 151/210 → 153/212
      //   (见上方区块说明),条数仍 53,digest 按合拢后门禁实测重算。
      //   同日合后收口:currentActionIndex 改实例属性(死数据字段门禁)后 data 区 +1 行,
      //   两条登记点 153/212 → 154/213,调用点与理由一字未改,条数仍 53,digest 再按门禁实测重算。
      // 2026-09-16 合拢第三轮 B/C 合流:u4Dynamic 两侧并集(C 组 roam/play 锚点 + 收口 square 锚点),
      //   调用点与理由一字未改,条数仍 53,digest 按合并树门禁实测重算。
      // 2026-09-16 整体检查 A 组修复:首页邀请逻辑与 searchmap 日期/价格改客户端过滤,
      //   四条 U4-DYNAMIC 登记点等量换 key(index 769→787 / 1228→1246,
      //   searchmap 906→916);同日 A-02 收口把「今天/明天」也写入真实日期边界
      //   (searchFilter 净 -3 行),searchmap 再 916→913。调用点、字段集合与理由一字未改,
      //   digest 按 lint:ui 实测重算。
      // 2026-09-16 截图界面问题修复(fix-ui-shots-0916):同上整段下移,profile 的 U4-DYNAMIC 登记点
      //   806→817;profilePatch 固定键与理由一字未改,条数不变 53,digest 按门禁实测重算。
      // 2026-09-16 截图冒烟修复:searchmap/merchantinfo/play 的既有 U4-DYNAMIC 登记点随本次编辑
      //   整体位移(searchmap 906→925 / merchantinfo 378→390 / 1714→1726 / 1723→1735 / play 5280→5291),
      //   逐条回读仍是同一批调用点、字段集合与理由一字未改。
      // 2026-09-16 合拢第五轮 A 组/界面修复/截图冒烟并入:C 组锚点、A 组锚点、profile 锚点与
      //   searchmap 冒烟修复锚点并存,条数仍 53,digest 按合并树门禁实测重算。
      // 2026-09-16 发布版本 release-0916 合资金线+非资金线:coupon-qr 224(资金)与 fabu/temp/searchmap/square/merchantinfo 等锚点(非资金)并存,
      //   条数仍 53,调用点与理由一字未改,digest 按合并树 key 集合实算。
      // 2026-09-17 券拍板项实施:coupon-qr 上方加了一行「有效期只到日」注释,同一条动态 setData
      //   224→225;调用点、受控字段(patch)与理由一字未改,条数仍 53,digest 按门禁实测重算。
      // 2026-09-17 漫游拍板实施(C-12/C-16):pages/roam/index.js 新增距离闸/浮层清场(净 +47 行),
      //   4 条 U4-DYNAMIC 既有锚点按门禁实测等量换 key(1592→1613 / 3614→3632 / 4414→4461 / 4472→4519);
      //   逐条回读:仍是同一批「setData 参数不是对象字面量」,字段集合与理由一字未改,条数仍 53,digest 按实算重算。
      // 2026-09-17 发布版本 release-0917 合券+漫游:coupon-qr 225(券)与 roam 1613/3632/4460/4518(漫游)两侧 key 并存,
      //   条数仍 53,调用点与理由一字未改,digest 按合并树 key 集合用门禁同算法 sha256(sorted keys) 实算。
      // 2026-09-17 拍照人工审核下线(拍板第16条):publish/temp/index.js 删掉「需人工审核照片」开关
      //   与 onPhotoReviewChange handler,三条既有 U4-DYNAMIC 登记点按门禁实测等量换 key
      //   (1883→1880 / 2481→2480 / 2600→2599)。调用点、受控字段与理由一字未改,digest 显式重算。
      // 2026-09-17 发布版本 release-0917 再合游玩:publish/temp 1880/2480/2599(游玩)与上方券+漫游 key 并存,
      //   条数仍 53,调用点与理由一字未改,digest 按合并树 key 集合实算。
      // 2026-09-17 拍板实施(第 5 条错误文案修复):publish/temp 三条锚点 +1 行(见文件头),
      //   条数仍 53,调用点与理由一字未改,digest 按门禁实测重算。
      // 2026-09-17 发布版本 release-0917 再合杂项:publish/temp 三锚点两侧叠加(游玩 −3/−1/−1、杂项 +1)1883/2481/2600→1881/2481/2600(lint:ui 实测),
      //   条数仍 53,调用点与理由一字未改,digest 按合并树 key 集合实算。
      // 2026-09-17 发布版本 release-0917 再合 fix-xcx:profile 817→821(fix-xcx 实测),activity/list 取合并树;条数仍 53,digest 实算。
      // 2026-09-17 发布版本 release-0917 再合 fix-play:play 1838→1877(fix-play +39)、index 1250→1268(fix-xcx +4 与 fix-play +18 叠加)、
      //   session 150→186(lint:ui 实测);条数仍 53,理由一字未改,digest 按合并树 key 集合实算。
      // 2026-09-17 拍板 #15 首页搜索入口:index 两条 u4Dynamic 锚点按门禁实测等量换 key
      //   (1246→1252;另一条 787 未动),条数仍 53,调用点与理由一字未改,digest 按 lint:ui 实测重算。
      // 2026-09-17 发布版本 release-0917 再合 fix-player:首页搜索入口 +6 行,index 1268→1274(lint:ui 实测),其余取合并树;条数仍 53,digest 实算。
      // 2026-09-18 阶段4 修复:profile 新增 shapeFeaturedCard/goFeatured 与主推卡渲染(+23 行),
      //   该条动态 setData 登记点 821→846;条数仍 53,调用点与理由一字未改,digest 按 lint:ui 实测重算。
      // 2026-09-18 rebase 到 github/master(#1100 内嵌/全屏 present)后按合并树重算:两侧 key 位移叠加,
      //   lint:ui 实测条数仍 53、53/53 全部命中、未命中 0、理由一字未改,digest 取合并树实算。
      // 2026-09-18 rebase #1072 到 master(3cce66ab0):publish-center-motion 在 pages/template/index.js
      //   顶部 require、data 的 reducedMotion 与 onShow 各加一行(共 +3 行),两条既有 U4-DYNAMIC 锚点
      //   按当前树实测等量换 key(276→279 / 288→291),其余 51 条取 master 侧。调用点、受控字段与理由
      //   一字未改,条数仍 53;值不取任一侧,按合并后 key 集合用门禁同算法实算。
      // 2026-09-18 合 github/master(含 #1100/阶段4/#1104 等)后 merge 重建:master 侧 53 条 key 与
      //   #1072 的 template 两条位移叠加成立,条数仍 53、理由一字未改,digest 按合并树 key 集合实算。
      // 2026-09-19 并 github/master(#1093 票种 5 列):fabu/index.js 在上送体补时间归一(+4 行),
      //   唯一漂移锚点 6373→6377 等量换 key,条数仍 53、理由一字未改,digest 按合并树实算。
      // 2026-09-19 收口批落 master 后 merchantinfo 三条 U4 登记点 +8 漂移(390/1726/1735→398/1734/1743),逐条回读调用点与理由一字未改,条数不变,digest 按合并树实测重算。
      // (本支) 2026-09-19 摘除首页搜索入口:index.js 锚点 1274→1268 等量换 key(见下方 u4Dynamic 区块),条数仍 53,digest 按本树 key 集合实算。
      // 2026-09-20 合并《预制人生》与商家工作台收口后，按最终 key 集合重算。
      // 2026-09-22 现场感三件套 S1 取景轮廓:temp 页在 photoCheck 段上新增「取景轮廓」上传位与
      //   浓淡字段(+12 行),该页六条既有 U4-DYNAMIC 登记点整体等量下移
      //   (1028/1029/1084/2147/2767/2886 → 1040/1041/1096/2159/2779/2898)。逐条回读上下 7 行:
      //   仍是同一批动态 setData(listName 两条、_setFormState、预览 patch、couponPatch、模板回填 patch),
      //   受控字段与理由一字未改,条数仍 54,digest 按本树 key 集合实算。
      // 2026-09-22 现场感三件套 S1 收口 review:pages/publish/temp/index.js 的 uploadPhotoFrame
      //   从 8 行长到 15 行(改带 skipCrop,绕开会铺白底的裁剪台,保住透明底轮廓),
      //   该文件 826 行之后的 6 条 U4-DYNAMIC 登记点整体 +7(1040→1047 / 1041→1048 / 1096→1103 /
      //   2159→2166 / 2779→2786 / 2898→2905)。逐条回读:调用点、受控字段与理由一字未改,
      //   条数仍 54,等量换 key,digest 按本树 key 集合实算。
    // 2026-09-22 play 页删地图/底部读数卡:地点卡层整层退场,其 poiCard.rows[i].visitorAvatars
    //   逐行回填那条动态 setData 随之删除(登记项出账),54 → 53,棘轮显式调小;
    //   与 master 的 S1 等量换 key 合流后,digest 按本树 key 集合实算。
// 2026-09-22 现场感 S2 罗盘:publish/temp 新增 onCompassCapture(+27 行),本页六个动态 setData
      //   锚点整体 +27(1047/1048/1103/2166/2786/2905→1074/1075/1130/2193/2813/2932)。
      //   逐条回读:调用点、受控字段与理由一字未改,条数仍 54,digest 按本树 key 集合实算。
      // 2026-09-22 编辑页单选统一下拉:temp 页加 pickedItem 小函数(+4)与 presentOptions / diceCountOptions /
      //   medalStyleOptions 三张选项表(+5),各处理器改按下标取 key;六条 U4-DYNAMIC 登记点纯行号位移
      //   (1132→1146 / 1133→1147 / 1188→1202 / 2251→2267 / 2871→2888 / 2990→3007)。
      //   逐条回读调用点与理由未变,条数不变,digest 按本树 key 集合实算。
// 2026-09-24 玩法配置接通核查(C-01/C-03/C-04/C-05,C-02 卡已撤)重做于最新 master:条数不变,6 条仅行号下移;逐条比对新旧行内容一致,理由一字未改,digest 显式重算。
      // 2026-09-25 B 扫码显形 OVERLAY + 显形档真 AR(含 3D 模型上传)rebase 到 984a64096:temp 页应答形态「显形」、
      //   scanArModes、pickScanArMode、uploadScanModel / clearScanModel 等插入,六条 U4-DYNAMIC 登记点纯行号位移
      //   (1162→1181 / 1163→1182 / 1218→1237 / 2288→2312 / 2942→2966 / 3065→3089)。
      //   逐条回读调用点与理由未变,条数不变,digest 按本树 key 集合实算。
// 2026-09-25 CU-M-183/185/186(主办方抽屉台账入口作用域 + 章节控制两行的禁用条件)在 merchantinfo.js
      //   上方加了注释与两个小方法,两条 U4-DYNAMIC 登记点纯行号位移(1807→1809 / 1837→1839)。
      //   逐条回读:仍是 buildPlayerSheet 返回值那两处动态 setData,受控字段与理由一字未改,条数 53 不变。
// 2026-10-07 旧模板采用回读修复：六处既有动态字段仅移行，理由及59条棘轮不变。
// 2026-10-07：采用请求补 JSON 头与旧后端拒绝分支，两个锚点仅后移5行；上下各7行逐字核对，理由与59项总数不变。
// 2026-10-14 W5尾项:profile 926→941/1706→1721、templatedetail 218→219/331→336 仅行号下移，同一调用及理由，数量仍58。
// 2026-10-17 合并 master 进漫游上线分支:roam/index.js 14 条、nearby/index.js 2 条既有调用仅行号位移(按 master 源码逐行回读定位);漫游两栏改版删掉 /api/registration/list 静默读取(749),U5 29→28。digest 按同一算法显式重算。
// 2026-10-19 Claude 小程序线移植到 master(用户批准冻结数值更新):45 个既有锚点仅行号位移换 key + 本线 6 处新增登记,digest 按门禁同一算法(sha256 of sorted keys joined by \n)重算。
// 2026-10-20 合并 master(#327 等)进 #293:两侧重锚逐条按合并后源码同一行文本定位一致(fabu 12 条、merchantinfo 2 条、scene-club-edit 1 条),square/list 196/1259 随 #325 点赞修复后移一行;digest 按门禁同一算法重算。
// 2026-10-27 devfix L07(21 处动态 setData 仅行号位移):逐条以 master 原行与新行逐字比对一致,理由一字未改,digest 按门禁同算法重算。
"b7817af1dcbe17a2b895e1ea54768e0a680188a80fc84911de383c635b7a60c5",
// 2026-10-28 模板页货架改版(#652)合 master(#610):本线删 template/index 两条 + master play 1621→1624 重锚，合并树 60 条，digest 按同一算法重算。
"159188a8c5ac9afe13b2a4d5b1c602e983ca7dabd900e0c52181cd1d23b8f786",
// 2026-10-20 R5 尾段(#327,owner 批准 12 条重锚):fabu 729→731、898→900、2524→2530、3570→3588、3951→3969、4957→4990、5215→5248、5866→5899、5876→5909、5929→5962、6270→6303、7936→8060,逐行比对 master 原行一字未改,理由与条数 58 不变;digest 按同一算法显式重算。
      // 2026-08-20 开卡包四拍动效:u5Silent 条数不变(12),只是 play/index.js 的调用点
      //   行号从 2360 移到 2573(见下方 u5Silent 区块的逐条说明)。等量换 key,显式改 digest。
      // 2026-08-16 uifix-play 集成:u5Silent 条数不变(12),只是 play/index.js 的
      //   里程碑 silentError 调用点行号从 2319 移到 2360(a35ba59a1 在其上方插了代码)。
      //   棘轮对「等量换 key」也要求显式改 digest —— 这一条就是那次显式审查。
      // 2026-08-17 漫游 S1 施工:条数仍为 12。行号 2954/3045/3145/3388 → 2956/3038/3143/3386;
      //   其中 3038(原 3045)那条的**理由也改了** —— 它从「best-effort 只返回布尔」变成
      //   「免费层唯一的打卡写入，失败经 _sendCheckin 转成页内 visit.checkinErr + 可重试」,
      //   这不是等量替换而是语义升级,逐项审查过了。
      // 2026-08-20 删「无使用的组件」:在上面那次位移之上,又删掉 cy-start-cta 的 4 行孤儿注释
      //   ⇒ 同一调用点 2573 → 2568。两次位移叠加,不是二选一。理由一字未改,digest 随之显式更新。
      // 2026-08-20 核销环改等分间断条:play/index.js 多了 ringSegs 的构造,
      //   同一调用点 2657 → 2671。只是位移,理由一字未改,digest 随之显式更新。
      // 2026-08-20 Questo 低成本项:完赛回看与本人照片投影插在同文件上方,
      //   同一调用点 2671 → 2680。只是位移,理由一字未改,digest 随之显式更新。
      // 2026-08-20 审查修复:开玩前 checklist 改为每次行程必现，删掉已读缓存的 13 行旧逻辑；
      //   同一调用点 2680 → 2667。只是位移,理由一字未改,digest 随之显式更新。
      // 2026-08-20 谜题提示/个人最佳与完赛回看合并:同一里程碑 best-effort 调用点
      //   加入 reveal 按钮颜色后最终落在 2793；失败仍保持 milestone=null 且不渲染,理由不变。
      // 2026-08-21 开卡包拖拽撕开(③′)并入:play/index.js 加了拖拽三态常量、data 字段与
      //   onPackTouch* 四个方法,同一 silentError 调用点 2793 → 2899。只是位移,理由一字未改,
      //   digest 随之显式更新。
      // 2026-08-22 漫游 bgTracker require 归位 + 逐点半径三路径统一:u5Silent 条数不变(12),
      //   pages/roam/index.js 七条同因漂移(428→436 / 1074→1082 / 2769→2772 / 2964→2967 /
      //   3046→3049 / 3151→3154 / 3394→3397)。理由一字未改,行号取门禁实测,digest 显式更新。
      // 2026-08-22 rebase 到 #764：装机计划偏好链使 play/index.js 既有里程碑增强
      //   静默调用位移到 3085；请求、降级与登记理由均未改变。
      //   随后删除未绑定的 navTo 孤儿方法并叠加主干入口位移；调用、失败降级与
      //   保留理由均未改变，仅显式更新最终行号锚点与 digest。
      // 2026-08-21 删除未绑定的 navTo 孤儿方法后，同一调用点 2793 → 2782；
      //   调用、失败降级与保留理由均未改变，仅显式更新行号锚点与 digest。
      // 2026-08-21 全量动作审计增加 play 重试类型 1 行、roam 会话就绪态 3 行；12 个
      //   silentError 调用点逐条复核仍为原调用与原降级理由，只更新行号锚点与 digest。
      // 2026-08-21 同一 _stopReal 增量使后续 5 个既有 silentError 调用点整体下移 4 行；
      //   静默降级契约与逐项理由未改变，只显式更新行号锚点与 digest。
      // 2026-08-22 并入 #779/#782：play 一条、roam 七条存量锨点纯位移，理由不变。
      // 2026-08-23 合入 #787：12 条静默调用的页内反馈/安全降级理由不变；
      //   漫游七条锚点按最终源码显式更新。
      // 2026-08-23 #780 在里程碑请求前增加圈层跳转，既有可选增强锚点 +8；语义不变。
      // 2026-08-23 高级玩法叠加 #780：里程碑可选增强调用移动到 3193，静默降级语义不变。
      // 2026-08-23 模板引导新增 7 行减动效同步；同一推荐卡预取调用仅行号位移。
      // 2026-08-24 玩家级漫游记忆并入 master：pages/roam 新增记忆模块与分页同步代码，
      //   7 条既有 silentError 调用仅行号漂移，逐条仍由原页内反馈或安全降级承接，理由不变。
      // 2026-08-24 分支剧情路线 M0-M5 并入 master：publish/fabu、publish/temp、play 靠前处新增代码，既有登记项仅行号漂移，理由不变（key 等量替换，digest 随之显式更新）。
      // 2026-08-24 三身份游戏模组并入 master：play 在 loadMilestone 之前新增模组代码，同一 /api/play/my-completed 调用点仅行号漂移（key 等量替换，digest 随之显式更新）。
      // 2026-08-24 广场 feed 与详情改版：square/detail、square/list 新增分享卡与 remix 代码，roam 随 master 位移；既有登记项仅行号漂移，理由不变（key 等量替换，digest 随之显式更新）。
      // 2026-08-24 Figma 玩法 UI 落地：subpackageRoam/session 与 play 新增回放与叙事代码，既有登记项仅行号漂移，理由不变（key 等量替换，digest 随之显式更新）。
      // 2026-08-24 门店 AI 形象并入:pages/roam 等新增代码使既有登记项行号漂移,调用点与理由不变（key 等量替换，digest 随之显式更新）。
      // 2026-08-25 816 并入:key 集合按上面的逐条核对结果变化,digest 显式更新。
      // 2026-08-25 Figma 完赛 Canvas 浅色收口：play 增加 13 行只读色板常量，
      //   叠加 #829 后同一 /api/play/my-completed 可选增强调用从 4114 位移到 4127，理由不变。
      // 2026-08-27 X10 漫游 onHide 时钟/GPS 一致性:onHide 加了 10 行说明与一个 !this._realOn 判据,
      //   pages/roam/index.js 其后的登记点整体下移 10 行。调用点与理由一字未改,key 等量替换,digest 显式更新。
      // 2026-08-27 三次合入 master(#886/#895/#899~#908)时本行冲突:两侧各自改过 digest。
      //   三方 key 集合 13/13/13,凭空新增 0 —— merged 取自 master 的是
      //   play/index.js:4322(本分支没碰 play),取自本分支的是 roam 的 6 条(X10 的 +10 位移)。
      //   合并后跑门禁,U5 实测「需逐项理由 13,逐项理由 13」、U4「动态 setData 54/54」,
      //   两张条目表对合并树都是准的。digest 按合并结果重算,不取任一侧的值。
      // 2026-08-29 漫游浮层收尾:删除 gpsMode/stampNew 隐藏状态后,roam 七条存量静默
      //   调用按门禁实测 446→442 / 1134→1130 / 2984→2978 / 3179→3173 /
      //   3261→3255 / 3361→3352 / 3605→3596 等量换 key。
      // 2026-09-09 途中彩蛋埋点名下线:u5Silent 条数不变(15),只是 utils/analytics.js 那条
      //   静默登记的行号 172→171(白名单里删掉了 egg_shown 一行,整段上移一行,行号取门禁实测)。
      //   逐条看过:调用点与理由一字未改,仍是同一句「埋点投递是纯副作用,失败不打扰玩家」。
      // 2026-09-11 修 #1055 门禁红:u5Silent 条数不变(15),只是 pages/play/index.js 的
      //   loadMilestone silentError 登记行号 4945→4950(#1055 在同文件上方 onUnload 里补了
      //   _chapAudio 停播销毁与两个 timer 清理,共 +5 行,整段下移)。逐条看过:调用点仍是
      //   /api/play/my-completed 的可选增强,静默理由一字未改,等量换 key,digest 取门禁实测。
      // 2026-09-18 死事件接线批:roam onLoad 接 scene 参数 +3 行(八条登记点整体 +3),
      //   play onUnload 补 _cancelRunHold +3 行(5075→5083)。调用点与静默理由一字未改,等量换 key,digest 显式更新。
      "u5Silent": // 2026-09-09 一枚圆钮:play/roam 两页在中键上方各插了长按处理与注释,下方既有登记点整体下移。
      //   2026-09-09 工具抽屉:play/index.js 删掉与 onSheetGoTap 同义的 onGoTap、新增
      //   onLocationRowTap,净减一行;自由探索不编号与顶部输入地址两批又把它推下去,
      //   play 唯一那条登记点最终 4327→4345。
      //   2026-09-09 点位语言 + 顶部输入地址 + 投一张换一张:roam/index.js 同一批插入,
      //   七条登记点 497/1193/3064/3261/3343/3438/3713 → 511/1216/3139/3336/3418/3513/3788;
      //   play 那条 4327 → 4378。条数仍 13,只是行号跟着上方新增的代码整体下移。
      //   调用点与理由一字未改,等量换 key,digest 显式更新。
      //   2026-09-10 读数真值挪出 data(同上一条同一批):roam/index.js 七条登记点
      //   515/1221/3182/3379/3461/3556/3831 → 517/1229/3190/3387/3469/3564/3839。
      //   条数仍 13,调用点与理由一字未改,等量换 key,digest 显式更新。
      //   2026-09-10 附近正在漫游的人:**条数 13 → 15**(理由写在上面 u5Silent 棘轮那条),
      //   原有七条 roam 登记点 517/1229/3190/3387/3469/3564/3839 →
      //   528/1240/3356/3553/3635/3730/4005。新增两条是 1842/1851。
      //   2026-09-10 漫游四模式脱离 DS 壳(同上一批同一次插入):九条 roam 登记点整体 +8。
      //   条数仍 15,等量换 key,digest 显式更新。
      //   2026-09-10 第二批(同上一次同一处插入):九条 roam 登记点再 +8。条数仍 15。
      //   2026-09-10 同上:play 那条登记点 4378 → 4328(上面删掉约 50 行)。条数仍 15。
      //   2026-09-10 自查补清(同上一处):play 那条登记点 4328 → 4316。条数仍 15。
      //   2026-09-10 同上:九条 roam 登记点 +2。条数仍 15。
      //   同上:九条 roam 登记点再 +3(按门禁实测)。条数仍 15。
      //   2026-09-10 同上:play 那条登记点 4316 → 4328。条数仍 15。
      //   2026-09-11 游玩页脱离 DS 壳 + 抽屉照原型重排:play/index.js 前面净增约 17 行,
      //   那条登记点 4328 → 4345。条数仍 15,等量换 key,digest 显式更新。
      // 2026-09-12 故事流退出补丁:play/index.js heroEnter 提前 closeChapStory、dust rAF 加 show 守卫、_clearTransientUiTimers 多清三个 timer(+7 行),silentError 登记 5032→5039。调用点与理由一字未改,条数仍 23,等量换 key,digest 显式更新。
  // (本支) // 2026-09-16 计步换屏:pages/play/index.js 的 _submitPlayKitAction 岔口从「只认 refresh」
  // (本支) //   改成按 kit.type 认 walk,多了四行注释 —— loadMilestone 那条 u5Silent 登记点
  // (本支) //   5071 → 5075。**条数不变(25)**,调用点与静默理由一字未改,等量换 key 后重算 digest。
  // (本支) "9d8542e2ce14ddcbdd5974742db27257febe102003f624e9374c294d089411ba"
      // 2026-09-13 R9-42/43/44 漫游历史存读/轨迹/分享修复:pages/roam/index.js 顶部两条
      //   require、onProtoSheetClose 分支与旧 onSceneSessionShare 桩删除,使 10 条 U5
      //   登记点按门禁实测整体位移(602→601、751→756、1670→1675、2323→2328、2332→2337、
      //   3859→3864、4063→4068、4145→4150、4243→4248、4536→4626)。逐行回读:仍是同一批
      //   silentError 调用点,静默理由一字未改,条数仍 25,digest 按门禁实测重算。
      // 2026-09-14 CI 修复(干净树实测):roam 602/757/1683/2336/2345/3872/4076/4158/4256→+1、merchant/customer 1013→1014、
      //   play 5071→5111、post-compose 383/396→384/397 上下 7 行逐字一致仅漂移;roam 4671→4683 是同一 /api/creativesquare/action
      //   发帖调用被 7c269a1ca 在上方插入幂等意图键下推,原登记理由是占位错文,按现码失败承接重写。条数 25 不变,digest 重算。
      // 2026-09-15 历史履约访问集成(7ecb44427):play/index.js 历史手记/只读全文在 loadMilestone 之前落位(+31 行),
      //   该 silentError 登记点 5111→5138 之后 historyNodes 内部状态化(死数据门禁,再 +3 行)5138→5141,
      //   上下 7 行逐字一致;调用点与静默理由一字未改,条数仍 25,digest 重算。
      // 2026-09-15 R9-21 暂停会话恢复:play 的 /api/play/my-completed 登记点 5111→5180;
      //   调用点上下 15 行逐字一致,仅行号漂移;静默理由与条数 25 不变,digest 按门禁实测重算。
      // 2026-09-15 集成总线:历史访问(5111→5141)与 R9-21(5111→5180)两边行号叠加,合并树门禁实测 5210;理由与条数 25 不变,digest 按合并后 key 集合实算。
      // 2026-09-15 集成总线合并 R1(审查C P1)通关会话收尾:_endRunSession 在该登记点之上净 +8 行,
      //   集成树 5210→5218(r921 单分支上是 5180→5188,两边行号叠加);调用点上下 7 行逐字一致,
      //   静默理由与条数 25 一字未改,digest 按合并树门禁实测重算。
      //   2026-09-15 地图组队 P 方案:subpackageRoam/nearby 删掉打卡点两条静默(392/427),新增主题点位层一条(137),25 → 24,digest 取门禁实测。
      //   2026-09-15 合拢收尾:同页(roam)删掉 f-hangout 一条(603)并整卡退场,其余 9 条按门禁实测位移
      //   (758→714、1684→1640、2337→2293、2346→2302、3873→3828、4077→4032、4159→4114、4257→4212、4683→4638),
      //   调用点与静默理由一字未改,24 → 23,digest 取门禁实测。
      //   2026-09-15 同日再一次:nearby 页删掉稿上那个没有筛选维度定义的「筛选」入口时,
      //   在主题点位层调用点上方补了两行说明注释,该条 U5 行号 137→139(理由与条数不变),digest 取门禁实测。
            // 2026-09-15 集成总线第二段 合 map-team:roam/nearby 侧取 map-team 的登记(它改了这两个源文件,
      //   删 roam:603 与 nearby:392/427、新增 nearby:139、其余 9 条位移),play/index.js 取集成侧 5218
      //   (r921+R1 的位移只在集成树上)。合并树 lint:ui 实测 U5 23/23 全部命中,digest 按合并后 key 集合实算。
            // 2026-09-15 集成总线第二段 合 photo-review(拍照人工审核通过才发奖):play/index.js 在该登记点
      //   上方净 +22 行,U5 锚点 5218→5240。两侧自报值都不对(集成侧 5218 / 分支侧 5163,
      //   photo-review 的基线早于 map-team),取合并树 lint:ui 实测;理由与条数 23 一字未改,digest 实算。
      // 2026-09-15 F15 联系号码接口:customer/index.js 在 701 行附近新增换取号码的成功/失败半屏处理(+50 行),
      //   既有 U5 锚点 1014→1064 等量换 key;新增的换取调用点由失败半屏承接被门禁自动认定,不进册子。条数 23 不变,digest 按合并后 key 集合实算。
      // 2026-09-16 补合第二批 合 reviewc-dialog 与 orphan-field 两条线合流:
      //   reviewc-dialog:merchant/customer 触达历史改只认首屏失败(+5 行),一条 U5 锚点 1014→1019。
      //   orphan-field:play/index.js 在 data 区 +2 行、onComplete 回执 +2 行、新增 _flashNightHint
      //   方法 +9 行,U5 锚点 5240→5253。调用点与静默理由一字未改,条数仍 23,digest 按合并后 key 集合实算。
      // 2026-09-16 H050/H051 帖文操作接线:components/cy/post-compose 加 editPost 编辑态(props/
      //   applyEdit/show 观察器/onPublish 分支,净 +29 行),两条 silentError 登记点 384/397 → 413/426。
      //   逐行回读:仍是选活动「我参加的/我主办的」两路读取,静默理由一字未改,条数仍 23,digest 按合拢后门禁实测重算。
      // 2026-09-16 候选池页退役:merchantinfo 删掉 goCandidates(−6 行),一条 U5 登记点 2357→2355。
      //   调用点、静默理由一字未改,条数仍 23,digest 按门禁实测重算。
      // 2026-09-16 截图冒烟修复:merchantinfo 缺参态新增 WXML 分支与 goBack 根栈安全,+14 行,
      //   同一台账读取调用点再整体位移(2357→2369)。调用点与静默理由一字未改,digest 按门禁实测重算。
      // 2026-09-16 合拢第五轮 候选池/冒烟修复并入:两笔改动同文件叠加,登记点按合并树实测取唯一真值。
      // 2026-09-16 发布版本 release-0916 合资金线+非资金线:customer 触达历史锚点两侧叠加(F15 +50、reviewc-dialog +5)1064/1019→1069(lint:ui 实测),
      //   roam/merchantinfo 取非资金线锚点;条数仍 23,理由一字未改,digest 按合并树 key 集合实算。
      // 2026-09-17 券拍板项实施:play/index.js 券票面去掉「满X可用」并接共享日期格式化,+3 行,
      //   里程碑 silentError 登记点 5291→5294;调用点与静默理由一字未改,条数仍 23,digest 按门禁实测重算。
      // 2026-09-17 漫游拍板实施(C-12/C-13/C-16/C-26):pages/roam/index.js 新增 CHECKIN_NEAR_M +
      //   _checkinGap/_mkShopCard 距离态、startVisit 距离闸、失败收卡改 toast、_resetRoamRecoverySession
      //   清浮层、_sendCheckin 世代守卫(净 +47 行),pages/play/index.js 新增 goGetPass 与 needPass
      //   空态按钮(净 +8 行)。roam 9 条与 play 1 条 U5 锚点按门禁实测等量换 key
      //   (726→733 / 1681→1702 / 2334→2355 / 2343→2364 / 3887→3924 / 4092→4139 / 4177→4224 /
      //   4278→4325 / 4704→4751;play 5291→5299)。其中 4224(商家打卡提交)的静默理由**确实改了**:
      //   失败不再写 visit.checkinErr/挂 retryCheckin,只 toast 一句并收卡(拍板「打卡不存在失败态」),
      //   已按新实现重写理由;其余 9 条调用点与理由一字未改。条数仍 23,digest 按实算重算。
      // 2026-09-17 发布版本 release-0917 合券+漫游:play/index.js 里程碑登记点两侧叠加(券 +3、漫游 +8)5291→5302(lint:ui 实测),
      //   roam 锚点取漫游侧;条数仍 23,理由一字未改,digest 按合并树 key 集合实算。
      // 2026-09-17 拍照人工审核下线(拍板第16条):play/index.js 删掉待审回执分支与审核态渲染,
      //   里程碑 silentError 调用点 5291→5269。调用点与静默理由一字未改,digest 显式重算。
      // 2026-09-17 申请留言接线(拍板第19条):nearby/index.js 顶部加 modal require(+1 行),
      //   主题点位层 silentError 调用点 139→140。调用点与静默理由一字未改,digest 显式重算。
      // 2026-09-17 发布版本 release-0917 再合游玩:play/index.js 里程碑登记点三侧叠加(券 +3、漫游 +8、游玩 −22)5291→5280(lint:ui 实测),
      //   nearby 139→140 取游玩侧,roam 锚点取漫游侧;条数仍 23,理由一字未改,digest 按合并树 key 集合实算。
      // 2026-09-17 定向广播接线:customer 触达历史锚点 1069→1179(上方新增广播预览/发送两段),
      //   条数仍 23,调用点与理由一字未改,digest 按新 key 集合实算。
      // 2026-09-17 广播实现收尾:同文件删掉只写不读的 cast.preview 死状态(净 -1 行),
      //   锚点按门禁实测 1179→1178;条数与理由不变,digest 按新 key 集合重算。
      // 2026-09-17 发布版本 release-0917 再合广播:customer 触达历史锚点取广播侧 1178,roam/play/nearby 取合并树;
      //   条数仍 23,理由一字未改,digest 按合并树 key 集合实算。
      // 2026-09-17 发布版本 release-0917 再合杂项:activity/list 105/129→106/130 取杂项侧,roam/play/nearby/customer 取合并树(lint:ui 实测);
      //   条数仍 23,理由一字未改,digest 按合并树 key 集合实算。
      // 2026-09-17 发布版本 release-0917 再合 fix-play:新增 roam-share-snapshot.js:24/:127 两条(棘轮 23→25,到冻结上限,未放宽),
      //   play 里程碑 5280→5319(lint:ui 实测);其余锚点取合并树;digest 按合并树 key 集合实算。
      // 2026-09-17 release-0917 第三阶段 C:新增 utils/marketing-consent-entry.js:60(总控裁定),digest 按 key 集合实算。
      // 2026-09-18 主包瘦身:单 owner 分包专用文件下沉(134 文件移至各自分包),受影响的 U5 登记项
      //   路径位移(pages/square/components/cy/post-compose/index.js:415/:428),理由一字未改,digest 按合并树新 key 集合实算。
      // 2026-09-18 合并 github/master(#1088/#1092/#1101):play 里程碑锚点 5542→5570、roam 分享 4750→4753。
      //   条数与理由均未改,digest 取合并树 lint:ui 实测 key 集合按同一算法实算。
      // 2026-09-19 同批:merchantinfo U5 那条 2367→2375,理由一字未改,digest 重算。
      // 2026-09-20 UI-17 收口:signup 去掉死字段并让背景跟随当前票,静默登记点 208→210;
      //   调用与理由不变,digest 按当前 key 集合实算。
      // 2026-09-20 合并《预制人生》与 UI-17 后，按最终 key 集合重算。
      // 2026-09-21 路线快照信任边界收口删去客户端量化代码，两处调用仅上移，理由与数量不变。
      // 2026-09-21 端到端收口合入上传重试与账号隔离，既有调用仅位移，理由与数量不变。
      // 2026-09-25 CU-M-183/185/186 同批:merchantinfo.js 那条 info-to-user silentError 调用点 2530→2532,
      //   逐条回读仍是「只为读章节 recruitEnabled、失败不弹全局 toast」这一处,静默理由一字未改,条数 27 不变。
// 2026-10-17 合并 master 进漫游上线分支:roam/index.js 14 条、nearby/index.js 2 条既有调用仅行号位移(按 master 源码逐行回读定位);漫游两栏改版删掉 /api/registration/list 静默读取(749),U5 29→28。digest 按同一算法显式重算。
// 2026-10-19 Claude 小程序线移植到 master(用户批准冻结数值更新):锚点位移 + 举报半屏/AI 对话组件下沉会员分包换路径 + 本线 9 处新增登记,digest 按同一算法重算。
"482a475d59f0ffed6a1406214827632a42f1725cc2a867857c0e0bcf6324eae8"

    }
  },
  "cleanupPlan": {},
  "u1Variable": {
    "chengyinhub-xcx/subpackageRoam/nearby/index.js:959": "公开照片侧车只由 poiGalleryPath 的平台相对图片键构造，拒绝外站、路径穿越和非图片后缀；poiGallery 再校验地点ID、封面与同目录高清图片，账号/卡片代际隔离迟到回包；owner=上海据点，复核=2026-10-24",
    "chengyinhub-xcx/subpackageMember/components/cy/scene-roam-poi-detail/index.js:572": "公开图片许可仅取自白名单平台相对封面键对应的 .gallery.json，不接受外站与路径穿越，回包核对地点ID并限制文本长度，账号/页面代际隔离迟到回包；owner=上海据点，复核=2026-10-24",
    "chengyinhub-xcx/utils/community-post-api.js:84": "canonical 社区受控请求对象：BASE+path 只接固定发布/编辑阶段 helper 与举报闭包的枚举路由，举报回读仅显式切至本人 /api/community-trust/reports/mine；完整目标匹配 Controller，owner/current 检查及 pipeline success/fail 可见反馈保留，取消不报成功；owner=小程序，复核=213",
  // (本支) "chengyinhub-xcx/app.js:337": "consent adapter 的 options 只由 utils/compliance/consent-client.js 构造，该模块把唯一 endpoint 固定为 /api/compliance/consents；owner=小程序，复核=2026-09-15",
  // (本支) "chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:467": "_submitVerification 的 options 只来自本文件三处调用：verification-scan 的有限核销路由，以及 chapter/station 两个显式 /api 字面量；非法扫码在调用前返回，不存在外部 URL 直传；owner=小程序，复核=2026-09-15",
  // (本支) "chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:751": "url 只由 isSelf 在 /api/user/info 与 /api/user/public-info 两个同方法字面量之间二选一，req 不接收其它来源；owner=小程序，复核=2026-09-15",
  // (本支) "chengyinhub-xcx/components/cy/scene-route-content/index.js:219": "config 只由本文件 sceneRequest(sceneId) 的有限映射生成，所有请求 URL 均为映射内 /api 字面量，未知 sceneId 返回 null、缺必需 id 也在请求前终止；owner=小程序，复核=2026-09-15",
  // (本支) "chengyinhub-xcx/components/cy/scene-route-content/index.js:515": "request 只取自本方法内 requests[sceneId] 的七个非提现固定写接口对象，未知 sceneId 显示提交错误并返回；提现分支在此前已转交 withdrawal-preflight，不会走到本调用；owner=小程序，复核=2026-09-15",
  // (本支) "chengyinhub-xcx/pages/activity/official-inbox/index.js:109": "url 由 organizerInvite 与受控 action 组合成两组官方邀约路由，前两行字面量及动态后缀均由 U1 扫描；owner=小程序，复核=2026-09-15",
  // (本支) "chengyinhub-xcx/pages/index/index.js:1086": "retry 只回放 _listReq 中由 getListData 首次字面量调用冻结的 topic/list 或 activity/list，外部输入不能写该缓存；owner=小程序，复核=2026-09-15",
  // (本支) "chengyinhub-xcx/pages/search2/result/index.js:113": "request 数组只由 utils/discover-search.buildRequests 构造，其按搜索类型枚举后端字面量且当前 U1 全仓扫描覆盖；owner=小程序，复核=2026-09-15",
  // (本支) "chengyinhub-xcx/subpackageA/pages/myproject/index.js:394": "上下架 URL 只由 bizType 在 activity/update_publish_status 与 topic/update_user_status 两个字面量间选择，未知 bizType 仍落固定 topic 路由；owner=小程序，复核=2026-09-15",
  // (本支) "chengyinhub-xcx/subpackageMember/components/scene-member-participation-detail/index.js:154": "会员快捷核销只消费 verification-scan.js 的有限核销路由，非法扫码在 sendRequest 前显示 toast 并返回；owner=小程序，复核=2026-09-15"
    "chengyinhub-xcx/app.js:424": "consent adapter 的 options 只由 utils/compliance/consent-client.js 构造，该模块把唯一 endpoint 固定为 /api/compliance/consents；owner=小程序，复核=2026-09-15",
    "chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:720": "_submitVerification 的 options 仅来自本页1678扫码、1741选章、1772选站三处：verification-scan.js只产出groupcode/redeem、scan_dynamic_code、coupon/verification、scan_qr_code四个固定/api路由，另两处为scan_qr_code_chapter/station字面量；非法扫码先返回，Object.assign仅复制原options并替换反馈回调，不改url/data/method；seat分支保留原actor/login revision/页面代次与UNKNOWN只读原receipt，不存在外部URL直传；owner=小程序，复核=2026-10-24",
    "chengyinhub-xcx/components/cy/scene-route-content/index.js:238": "config 只由本文件 sceneRequest(sceneId) 的有限映射生成，所有请求 URL 均为映射内 /api 字面量，未知 sceneId 返回 null、缺必需 id 也在请求前终止；owner=小程序，复核=2026-09-15",
    "chengyinhub-xcx/components/cy/scene-route-content/index.js:551": "request 只取自本方法内 requests[sceneId] 的七个非提现固定写接口对象，未知 sceneId 显示提交错误并返回；提现分支在此前已转交 withdrawal-preflight，不会走到本调用；owner=小程序，复核=2026-09-15",
    "chengyinhub-xcx/pages/index/index.js:1043": "retry 只回放 _listReq 中由 getListData 首次字面量调用冻结的 topic/list 或 activity/list，外部输入不能写该缓存；owner=小程序，复核=2026-09-15",
    "chengyinhub-xcx/pages/search2/result/index.js:193": "request 数组只由 utils/discover-search.buildRequests 构造，其按搜索类型枚举后端字面量且当前 U1 全仓扫描覆盖；owner=小程序，复核=2026-09-15",
  },
  "u3Debt": {
    "chengyinhub-xcx/subpackageMember/components/cy/profile/index.js#onThemeTap": "存量零绑定债务：onThemeTap 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/components/cy/profile/index.js#onAiStart": "存量零绑定债务：onAiStart 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/components/cy/profile/index.js#onCompare": "存量零绑定债务：onCompare 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/components/cy/profile/index.js#goMyJoin": "存量零绑定债务：goMyJoin 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/components/cy/qr-voucher/index.js#onMask": "存量零绑定债务：onMask 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-detail/index.js#goBack": "存量零绑定债务：goBack 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/components/cy/scene-route-content/index.js#onFilterTap": "存量零绑定债务：onFilterTap 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/addressinfo/addressinfo.js#onDefaultChange": "存量零绑定债务：onDefaultChange 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/coop/withdraw/index.js#goBack": "存量零绑定债务：goBack 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/gerenziliao/gerenziliao.js#onCaseIntroductonChange": "存量零绑定债务：onCaseIntroductonChange 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/merchant/decor/index.js#goBack": "存量零绑定债务：goBack 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/merchant/index/index.js#goSearch": "存量零绑定债务：goSearch 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/merchant/index/index.js#goCoupon": "存量零绑定债务：goCoupon 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/merchant/index/index.js#goPreviewProfile": "存量零绑定债务：goPreviewProfile 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/merchant/index/index.js#goCoopProfile": "存量零绑定债务：goCoopProfile 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/privacy/index.js#onTocTap": "存量零绑定债务：onTocTap 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onTapQuickAdd": "存量零绑定债务：onTapQuickAdd 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onNodeStoryChange": "待删除或重新接线：现码只剩方法定义，唯一其它命中是说明注释，WXML 无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onNodeHookInput": "存量零绑定债务：onNodeHookInput 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onNodeCardHookInput": "存量零绑定债务：onNodeCardHookInput 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onNodeFragmentInput": "存量零绑定债务：onNodeFragmentInput 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onChapterDescChange": "存量零绑定债务：onChapterDescChange 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onRegionChange": "存量零绑定债务：onRegionChange 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onChapterRecruitToggle": "存量零绑定债务：onChapterRecruitToggle 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onChapterMerchantCategory": "存量零绑定债务：onChapterMerchantCategory 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onChapterTermsMode": "存量零绑定债务：onChapterTermsMode 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onChapterPerkMinValue": "存量零绑定债务：onChapterPerkMinValue 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onChapterMaxMerchant": "存量零绑定债务：onChapterMaxMerchant 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onChapterDescriptionInputChange": "存量零绑定债务：onChapterDescriptionInputChange 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onChapterRequiredChange": "存量零绑定债务：onChapterRequiredChange 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onChapterValidationMethodToggle": "存量零绑定债务：onChapterValidationMethodToggle 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onChapterMaxNodeXpInput": "存量零绑定债务：onChapterMaxNodeXpInput 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onInputChange": "存量零绑定债务：onInputChange 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onTicketChange": "存量零绑定债务：onTicketChange 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onTicketModeChange": "存量零绑定债务：onTicketModeChange 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onTicketNumChange": "存量零绑定债务：onTicketNumChange 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onEditTicketModeChange": "存量零绑定债务：onEditTicketModeChange 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#onTicketPriceChange": "存量零绑定债务：onTicketPriceChange 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#handleSearch": "存量零绑定债务：handleSearch 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/roam/index.js#onPlayHudToggle": "存量零绑定债务：onPlayHudToggle 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/roam/index.js#onShareSwipe": "存量零绑定债务：onShareSwipe 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/roam/index.js#goSearchMap": "存量零绑定债务：goSearchMap 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/roam/index.js#onRoamDrawerTap": "存量零绑定债务：onRoamDrawerTap 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/shezhi/shezhi.js#goPinpai": "存量零绑定债务：goPinpai 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/shezhi/shezhi.js#goRuzhu": "存量零绑定债务：goRuzhu 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/shezhi/shezhi.js#goEdit": "存量零绑定债务：goEdit 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/detail/index.js#onImageLoad": "存量零绑定债务：onImageLoad 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/detail/index.js#onCommentBlur": "存量零绑定债务：onCommentBlur 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#onImageLoad": "存量零绑定债务：onImageLoad 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#onDrawerTouchStart": "存量零绑定债务：onDrawerTouchStart 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#onDrawerTouchMove": "存量零绑定债务：onDrawerTouchMove 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#onDrawerTouchEnd": "存量零绑定债务：onDrawerTouchEnd 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#goSearchMap": "存量零绑定债务：goSearchMap 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#onAddRoute": "存量零绑定债务：onAddRoute 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#onPublishSuccess": "存量零绑定债务：onPublishSuccess 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/talent/list/index.js#onHeroTap": "存量零绑定债务：onHeroTap 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/talent/list/index.js#onTopicTap": "存量零绑定债务：onTopicTap 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/talent/list/index.js#goMyClubConsole": "存量零绑定债务：goMyClubConsole 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/merchantapply/index.js#onAddressChange": "存量零绑定债务：onAddressChange 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageB/pages/im/chat/index.js#onBack": "存量零绑定债务：onBack 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/components/scene-member-participation-detail/index.js#goBack": "存量零绑定债务：goBack 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/mytemplate/mytemplate.js#onFilterCategory": "存量零绑定债务：onFilterCategory 在同页 WXML 与 JS 调用图均无入口；owner=小程序，到期=2026-09-30"
  },
  "u4Debt": {
    "chengyinhub-xcx/pages/publish/fabu/index.js#totalStats": "存量零消费债务：totalStats 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/components/cy/profile/index.js#exploreValue": "存量零消费债务：exploreValue 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/components/cy/profile/index.js#identityCard": "存量零消费债务：identityCard 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-history/index.js#nodata": "存量零消费债务：nodata 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-history/index.js#isPaying": "存量零消费债务：isPaying 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/components/cy/scene-member-order-history/index.js#verifyingPayment": "存量零消费债务：verifyingPayment 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/components/cy/scene-merchant-profit/index.js#myIncomeTotal": "存量零消费债务：myIncomeTotal 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/components/cy/scene-merchant-profit/index.js#pendingCount": "存量零消费债务：pendingCount 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/components/cy/scene-route-content/index.js#activeFilter": "存量零消费债务：activeFilter 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/components/cy/scene-sheet/index.js#navTop": "存量零消费债务：navTop 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/components/cy/scene-sheet/index.js#navRight": "存量零消费债务：navRight 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/components/cy/slide-confirm/index.js#pct": "存量零消费债务：pct 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/activity/baoming/baoming.js#ticketId": "存量零消费债务：ticketId 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/activity/baoming/baoming.js#userInfo": "存量零消费债务：userInfo 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/activity/baoming/baoming.js#editPhone": "存量零消费债务：editPhone 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/activity/baoming/baoming.js#editRealName": "存量零消费债务：editRealName 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/activity/baoming/baoming.js#waitlistOfferToken": "服务端签发的短期候补令牌不展示给用户，只与 offerId 成对用于页内 JS 的可支付闸、quote 与 create payload，并在换票/退出候补时清空；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/activity/baoming/baoming.js#verifyingPayment": "存量零消费债务：verifyingPayment 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/addressinfo/addressinfo.js#isEdit": "存量零消费债务：isEdit 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/club/apply/index.js#backLabel": "存量零消费债务：backLabel 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/club/detail/index.js#sceneStack": "存量零消费债务：sceneStack 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/club/detail/index.js#tabInitialized": "存量零消费债务：tabInitialized 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/gerenziliao/gerenziliao.js#selectedCategoryNames": "存量零消费债务：selectedCategoryNames 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/gerenziliao/gerenziliao.js#formData": "存量零消费债务：formData 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/gerenziliao/gerenziliao.js#selectedCategoryNamesStr": "存量零消费债务：selectedCategoryNamesStr 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/index/index.js#isAppReady": "存量零消费债务：isAppReady 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/index/index.js#bottomTopicPageNo": "存量零消费债务：bottomTopicPageNo 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/index/index.js#bottomActivityPageNo": "存量零消费债务：bottomActivityPageNo 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/index/index.js#isLoading": "存量零消费债务：isLoading 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/merchant/apply/index.js#locationLat": "存量零消费债务：locationLat 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/merchant/apply/index.js#locationLng": "存量零消费债务：locationLng 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/merchant/decor/index.js#loadErrTitle": "存量零消费债务：loadErrTitle 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/merchant/index/index.js#revBars": "存量零消费债务：revBars 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/merchant/marketing/index.js#merchantId": "存量零消费债务：merchantId 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/merchant/profile/index.js#topicId": "存量零消费债务：topicId 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/merchant/profile/index.js#topicName": "存量零消费债务：topicName 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/mylike/mylike.js#pageConfig": "存量零消费债务：pageConfig 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/play/index.js#selfPlay": "存量零消费债务：selfPlay 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/play/index.js#leadUnlockChecking": "存量零消费债务：leadUnlockChecking 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/play/index.js#stack": "存量零消费债务：stack 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/play/index.js#wrongCount": "存量零消费债务：wrongCount 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/play/index.js#journalDot": "存量零消费债务：journalDot 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/play/index.js#finishBadge": "存量零消费债务：finishBadge 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/play/index.js#reviewEmpty": "存量零消费债务：reviewEmpty 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/privacy/index.js#scrollInto": "存量零消费债务：scrollInto 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/activity/index.js#categoryList": "存量零消费债务：categoryList 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/activity/index.js#popChapterNodes": "存量零消费债务：popChapterNodes 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#editorState": "存量零消费债务：editorState 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#minuteIndex": "存量零消费债务：minuteIndex 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#canUndo": "存量零消费债务：canUndo 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#horizontalImage": "存量零消费债务：horizontalImage 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#showCategoryModal": "存量零消费债务：showCategoryModal 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#totalDuration": "存量零消费债务：totalDuration 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#searchKeyword": "存量零消费债务：searchKeyword 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#chapterStats": "存量零消费债务：chapterStats 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js#completeness": "存量零消费债务：completeness 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/temp/index.js#selectedCategoryNames": "存量零消费债务：selectedCategoryNames 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/temp/index.js#selectedCategoryNamesStr": "存量零消费债务：selectedCategoryNamesStr 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/temp/index.js#userData": "存量零消费债务：userData 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/temp/index.js#correctAnswerOptions": "存量零消费债务：correctAnswerOptions 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/publish/temp/index.js#correctAnswerIndex": "存量零消费债务：correctAnswerIndex 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/roam/index.js#isDevtools": "存量零消费债务：isDevtools 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/roam/index.js#poiEmpty": "存量零消费债务：poiEmpty 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/roam/index.js#heading": "存量零消费债务：heading 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/roam/index.js#locErr": "存量零消费债务：locErr 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/roam/index.js#playHud": "存量零消费债务：playHud 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/search2/result/index.js#categoryId": "存量零消费债务：categoryId 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/shezhi/shezhi.js#userInfo": "存量零消费债务：userInfo 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#isSticky": "存量零消费债务：isSticky 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#drawerSnapping": "存量零消费债务：drawerSnapping 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#drawerCollapsed": "存量零消费债务：drawerCollapsed 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#tabBarH": "存量零消费债务：tabBarH 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#drawerDockBarH": "存量零消费债务：drawerDockBarH 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#drawerDockH": "存量零消费债务：drawerDockH 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#mapLatitude": "存量零消费债务：mapLatitude 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#mapLongitude": "存量零消费债务：mapLongitude 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js#mapScale": "存量零消费债务：mapScale 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/talent/list/index.js#myClubCount": "存量零消费债务：myClubCount 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/talent/list/index.js#myClubId": "存量零消费债务：myClubId 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/template/index.js#capsuleBottom": "存量零消费债务：capsuleBottom 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/template/index.js#pubLoaded": "存量零消费债务：pubLoaded 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/templatedetail/templatedetail.js#difficultyLetter": "存量零消费债务：difficultyLetter 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/templatedetail/templatedetail.js#difficultyLabel": "存量零消费债务：difficultyLabel 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/templatedetail/templatedetail.js#selectedOption": "存量零消费债务：selectedOption 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/components/project-host/index.js#anyDrawerOpen": "存量零消费债务：anyDrawerOpen 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/components/project-join/index.js#anyDrawerOpen": "存量零消费债务：anyDrawerOpen 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/index/index.js#totalStickyHeight": "存量零消费债务：totalStickyHeight 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/index/index.js#topBarHeight": "存量零消费债务：topBarHeight 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/index/index.js#tabSectionTop": "存量零消费债务：tabSectionTop 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/index/index.js#tabHeight": "存量零消费债务：tabHeight 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/merchantapply/index.js#nodeId": "存量零消费债务：nodeId 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/merchantapply/index.js#templateId": "存量零消费债务：templateId 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/merchantapply/index.js#addressName": "存量零消费债务：addressName 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/merchantapply/index.js#longitude": "存量零消费债务：longitude 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/merchantapply/index.js#latitude": "存量零消费债务：latitude 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/merchantinfo/merchantinfo.js#chapterRecruitmentLoading": "存量零消费债务：chapterRecruitmentLoading 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/merchantinfo/merchantinfo.js#coopState": "存量零消费债务：coopState 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/merchantinfo/merchantinfo.js#templateText": "存量零消费债务：templateText 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/merchantinfo/merchantinfo.js#perkText": "存量零消费债务：perkText 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/merchantinfo/merchantinfo.js#sharingText": "存量零消费债务：sharingText 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/pages/topic/merchantinfo/merchantinfo.js#factTailText": "存量零消费债务：factTailText 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageA/components/scene-how-to-play-detail/index.js#id": "存量零消费债务：id 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageA/pages/assetcenter/earnings/index.js#sceneStack": "存量零消费债务：sceneStack 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageA/pages/infomation/infomation.js#sceneStack": "存量零消费债务：sceneStack 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageB/pages/im/chat/index.js#statusBarHeight": "存量零消费债务：statusBarHeight 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageB/pages/im/chat/index.js#navBarHeight": "存量零消费债务：navBarHeight 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageB/pages/im/chat/index.js#myAvatar": "存量零消费债务：myAvatar 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageB/pages/im/list/index.js#capsuleRightGap": "存量零消费债务：capsuleRightGap 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/mycanyu/mycanyu.js#sceneStack": "存量零消费债务：sceneStack 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/mytemplate/mytemplate.js#filterCategoryId": "存量零消费债务：filterCategoryId 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/mytemplate/mytemplate.js#total": "存量零消费债务：total 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/signup/index.js#hasMore": "存量零消费债务：hasMore 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageMember/signup/index.js#nodata": "存量零消费债务：nodata 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30",
    "chengyinhub-xcx/subpackageRoam/citynode-code/index.js#poiId": "存量零消费债务：poiId 在同页 WXML 与 this.data 读取中均未命中；owner=小程序，到期=2026-09-30"
  },
  // 2026-08-25 模板页删「圈层自由探索」「热门节点榜」两节:条数不变(54),
  //   pages/template/index.js 两条 _coverFail 动态 setData 因上方 getCircleThemes/
  //   goCircleThemeConfig 整体删除而上移(276→222 / 288→234;自审又删掉零消费的 home.total,各再上移一行)。逐条回读:调用点与理由
  //   一字未改,等量换 key,显式改 digest。
  // 2026-08-28 发布流 UI 补摘要与行内错误：fabu/temp 既有 7 条动态 setData 仅因上方插入
  //   展示派生与清除方法而位移；逐条由 UI-GATE-0 实测回读，理由与棘轮数量不变。
  // 2026-09-15 拍板 27:主题级 BGM 残留删除(fabu/index.js 净减 10 行),fabu 的 11 条
  //   动态 setData 登记项仅行号位移(571→567 / 735→731 / 2039→2033 / 2976→2968 /
  //   3342→3334 / 4296→4288 / 4544→4536 / 5200→5192 / 5208→5200 / 5261→5253 / 6227→6217)。
  //   逐条按门禁实测回读:调用点与理由一字未改,条数仍 54。
  // 2026-09-15 公开承接池开关恢复(裁决 12B):pages/publish/fabu/index.js 8 条既有动态 setData
  //   仅因上方插入 onClubPoolChange 处理器(+7 行)与上送体注释改写而位移;
  //   逐条回读调用点与理由一字未改,等量换 key,digest 显式更新。
  // 2026-09-15 补充裁决(俱乐部自办团不进公开承接池):pages/publish/fabu/index.js 上送体上方 +2 行注释,
  //   6232→6234 一条 u4Dynamic 锚点等量换 key,理由一字未改。
  // 2026-09-17 拍板 #15:首页 hero 之后插入只读搜索入口(wxml +10 行、wxss +22 行、js +6 行),
  //   pages/index/index.js 的 2 条 u4Dynamic 锚点按门禁实测等量换 key(1246→1252;787 未动)。
  //   逐条回读:仍是同一个动态 setData 调用点,受控字段与理由一字未改,digest 显式重算。
  // 2026-10-11 模板生命周期修复：既有两处登记等量换行号 217→218 / 304→331；身份清空复用静态 applyDetailPayload，不新增动态豁免。
  "u4Dynamic": {
    "chengyinhub-xcx/pages/square/components/route-map/index.js:45": "loadRoute入场清图仅调用utils/topic-route-preview.build([])，固定routeReady/routeIsRoad/routeSourceText/mapLatitude/mapLongitude/routeMarkers/routePolyline/routeIncludePoints；七个字段由WXML/JS消费，routeIsRoad是同源道路判定的固定元数据（不声称其单独可见），不展开服务端键；session revision+read epoch阻断迟到。owner=小程序；复核=2026-10-24",
    "chengyinhub-xcx/pages/square/components/route-map/index.js:56": "同一loadRoute业务拒绝清图，routePreview.build([])只返回固定路线视图字段并触发empty；共享topic-route-preview实现与WXML逐字段核对，不从响应提供键。owner=小程序；复核=2026-10-24",
    "chengyinhub-xcx/pages/square/components/route-map/index.js:62": "同一loadRoute网络失败清图，current()先校验session revision/read epoch，固定投影routePreview.build([])与empty事件给宿主可见空态；不回写其他页面。owner=小程序；复核=2026-10-24",
    "chengyinhub-xcx/pages/square/components/route-map/index.js:71": "buildRouteMap的局部view只由utils/topic-route-preview.build(chaptersList)产生；固定路线视图八字段，routeReady决定ready/empty，节点只供值、不能供setData键；地图与提示消费其余七字段，routeIsRoad仅同源固定判定元数据，不声称直接渲染。owner=小程序；复核=2026-10-24",
    "chengyinhub-xcx/pages/topic/index/index.js:2041": "buildRouteMap只提交utils/topic-route-preview.build(chaptersList)固定八个路线视图字段；每章道路独立校验，缺损退节点虚线，不展开章节任意键；路线地图/提示与JS选择消费其余七字段，routeIsRoad仅同源固定判定元数据，不声称直接渲染。owner=小程序；复核=2026-10-24",

    // 2026-10-19 Claude 小程序线移植到 master:本线新增 6 处动态 setData 逐项登记(棘轮/digest 待审批后同步)。
    "chengyinhub-xcx/pages/activity/official-mine/index.js:155": "BL-80 通知复盘按卡片位置拼 broadcasts[i]._statsLoading/_statsText 路径写入「正在读取复盘」;字段在本页 wxml 该卡触达行消费;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/pages/activity/official-mine/index.js:163": "BL-80 通知复盘读取成功:按同一卡片路径写 _statsLoading=false 与复盘文案(读不到写后端原因+点一下重试);owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/pages/activity/official-mine/index.js:165": "BL-80 通知复盘读取成功分支的 _statsText 动态键:复盘文案或带重试提示,在该卡触达行消费;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/pages/activity/official-mine/index.js:171": "BL-80 通知复盘读取网络失败:按卡片路径写 _statsLoading=false;sameItem 校验防止写到换行后的另一张卡;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/pages/activity/official-mine/index.js:172": "BL-80 通知复盘读取网络失败的 _statsText 动态键:写「网络连接失败，点一下重试」,在该卡触达行消费;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/pages/roam/index.js:566": "BL-70 横滑卡头像堆按命中行拼 shopStrip.rows[i].visitors 路径批量回写;只在横滑卡 poi__who 头像堆消费,epoch 丢弃迟到回包;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:2138": "本人帖文翻页patch只用list[oldLen+i]固定列表名与响应遍历下标，其余feedErr/feedAuthError/hasMore/nodata为字面字段；服务端仅供值不供键；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/activity/official-mine/index.js:164": "BL-80 通知复盘读取成功分支的 _canRecover 动态键:按同一卡片路径写是否可安全恢复原投递(仅 code 200 且 canRecoverInapp 为真),sameItem 校验防止写到换行后的另一张卡,在该卡「安全恢复原投递」按钮消费;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/pages/index/index.js:1259": "底部主题翻页只追加bottomTopicList[oldLen+i]，其余页码/状态/hasMore固定字面字段；下标来自当前列表长度和响应遍历序号；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/index/index.js:1366": "底部活动翻页只追加bottomActivityList[oldLen+i]，其余页码/状态/hasMore固定字面字段；下标来自当前列表长度和响应遍历序号；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/merchant/customer/index.js:504": "客户翻页只追加rows[oldLen+i]，其余页面状态与分群摘要为同方法固定字面字段；hasMore仍用rowCount/total主线规则；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/searchmap/index.js:316": "地图活动翻页只追加list[oldLen+i]，无坐标计数/页码/时间与加载状态均为固定字面字段；只处理当前query epoch响应；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/searchmap/index.js:570": "新增活动marker只拼activityMarkers[at]路径，at从已建点数量顺序递增；marker全局activityId来自列表offset+i，不接收服务端键名；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/square/detail/index.js:1262": "评论翻页只追加list[oldLen+i]，其余commentState/listErr/nodata/hasMore固定字面字段；响应遍历序号只形成数组下标；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js:1063": "广场翻页只追加list[oldLen+i]，其余listErr/loading/nodata/hasMore固定字面字段；下标由当前长度和响应序号形成；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/play/index.js:1760": "章节正文显形局部patch只写chapterParas[i]或chapStory.lines[i]的in/dir/d三个固定字段；i来自当前冻结数组遍历，值为本地布尔/方向枚举/延迟数值，异步回包前后校验章节模式及数组身份；WXML真消费两namespace；替代旧opacity/scale/blur渲染登记，经人工审查迁移；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/club/apply/index.js:233": "onInput 的键来自 wxml data-field，取值是同页写死的七个枚举（identity/leaderName/phone/coFounders/avgEventSize/maxEventSize/hasGuideCert），全部在同一份 wxml 表单里被渲染消费，不是外部输入直传；owner=小程序，复核=2026-10-31",
    "chengyinhub-xcx/pages/club/apply/index.js:244": "toggleBool 的键取自同一份 data-field 枚举，只在 0/1 之间翻转能力自评项；owner=小程序，复核=2026-10-31",
    "chengyinhub-xcx/components/cy/nav-bar/index.js:39": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/components/cy/nav-bar/index.js:51": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:806": "profilePatch 在本方法内以 userInfo/balanceText/cityName/categoryList/casePicsList/joinDate/friendNum/followNum/fansNum 固定键创建，仅按本人/他人条件追加 growthLevel 与 primaryCta，不展开服务端对象键；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/activity/list/index.js:103": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/activity/list/index.js:125": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/activity/list/index.js:208": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/play/index.js:1950": "商家卡头像堆按行下标回填：字段名由本方法用 poiCard.rows[i].visitorAvatars / .visitorTotal 两个字面后缀拼出，下标取自本页 rows 的遍历序号，接口回包只提供值不提供键名；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/index/index.js:769": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/index/index.js:1228": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/merchant/apply/index.js:324": "动态 setData 需精确登记：[f]: e.detail.value；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/fabu/index.js:687": "草稿恢复 patch 只含固定 formData/pendingMaterials，并按本地信封是否存在追加固定的分类与起止时间字段；信封值不会成为 setData 字段名；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/fabu/index.js:907": "_storyCommand 固定写 formData.chapters 与 pendingMaterials，uiPatch 的全部调用点都在本文件且传对象字面量/空值，只补 storyEditor、节点抽屉与撤销态等固定键；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/fabu/index.js:2322": "资格同步 patch 固定含 merchantPoolEditable，仅在确证无资格时追加 formData.openMerchantPool=false，不接收动态字段名；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/fabu/index.js:3291": "我的俱乐部响应只写固定 myClubs，并按锁定/唯一俱乐部追加固定 clubIndex 与 formData.clubId；服务端对象不会展开为页面键；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/fabu/index.js:3659": "节点定位 patch 固定写 selectedNodeLid，坐标有效时只追加 mapCenter 与 mapScale，字段集合由本方法写死；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/fabu/index.js:4579": "当前用户回填 patch 固定写 draftMemberId；仅在未恢复本地草稿时追加 formData.collaboratorList/Ids，避免覆盖恢复内容，不展开接口字段；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/fabu/index.js:4835": "票种同步 patch 固定写 editingTicket.syncWithTheme，开启同步时只追加 startTime/endTime 两个固定子字段；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/fabu/index.js:5499": "summaryPatch 只由本方法逐项追加 chapterStates/chapterActionLabels/creationAnchor/starterAction/pendingMaterialStates/topicDetailSummary 六个固定派生字段，用于避免无变化 setData，不接收外部键；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/fabu/index.js:5491": "patch 最多只含经本页校验袋计算出的 canPublish 一个固定字段，用于值变化时才写入；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/fabu/index.js:5552": "校验定位 patch 只可能含 secCollapsed.ticket、showTicketEditor、editingTicket 三个固定键；错误 key 仅决定是否进入 ticket 分支和定位下标，不参与字段名拼接；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/fabu/index.js:7004": "地图重绘 patch 固定含 mapMarkers/mapPolyline/mapInclude/hasRoute，未选节点时只追加 mapCenter；节点数据只作为值参与计算。X05 后 patch 由 utils/publish/route-map-view.js 的纯函数算好交回，键集合由该模块单测钉住（publish-route-map-view.test.js）；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/simple/index.js:299": "俱乐部列表响应只写固定 myClubs，且仅在唯一俱乐部时追加固定 clubIndex/clubId；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/simple/index.js:337": "预设应用 patch 固定含 presetIndex/stations/focusIndex/routeTag，满足标题替换条件时仅追加 title/titleManual；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/temp/index.js:810": "listName 的当前调用点只枚举 difficultyOptions 固定列表名（R5已移除人数/时长选择器），接口结果先验证为记录数组后才写标签数组，不允许请求数据决定字段名；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/temp/index.js:811": "listName + Data 与上一条共享同一有限调用集，当前只落 difficultyOptionsData 固定字段（R5已移除人数/时长选择器）；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/temp/index.js:865": "_setFormState 是本页私有写入入口，全部调用点传本文件构造的字段 patch（高级玩法动态路径也来自 WXML 固定 section/field），函数只克隆该 patch 并追加固定 submitError，不展开后端对象键；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/temp/index.js:1833": "玩家预览 patch 先固定写 pvStep/pvStepKey/pvStepLabel 与四个临时态，仅按 story/challenge 枚举追加 pvBeats 或任务展示字段；步骤 key 不参与字段名拼接；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/temp/index.js:2500": "couponPatch 在同方法内从空对象开始，只可能追加 selectedCouponId 与 selectedCouponName 两个固定奖励展示字段，再展开进固定回填对象；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/publish/temp/index.js:2633": "模板回填 patch 只逐项追加 durationIndex/Text、difficultyIndex/Label、playersIndex/Text 六个固定选择器字段，且仅在字典中找到匹配值时写入；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/subpackageMember/components/cy/profile/index.js:1291": "profilePatch 在本方法内以 userInfo/balanceText/cityName/categoryList/casePicsList/joinDate/friendNum/followNum/fansNum 固定键创建，仅按本人/他人条件追加 growthLevel 与 primaryCta，不展开服务端对象键；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/activity/list/index.js:339": "applyFilter 只派生 events/summary/emptyTitle/emptySub/emptyKind/curError/loading/errorMsg；extra 的两个调用点仅传 tab 或 keyword 固定键，不展开接口对象键，公开与本人数据源各自保留原状态；owner=小程序，复核=2026-10-16",
    // 2026-09-20 合并后按最终源码重钉。
    // (本支) 2026-09-19 用户改判摘除首页搜索入口(goSearch 及其注释 -6 行),锚点 1274→1268 等量换 key,调用点与理由一字未改,digest 显式重算。
    "chengyinhub-xcx/pages/index/index.js:1150": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/merchant/apply/index.js:359": "动态 setData 需精确登记：[f]: e.detail.value；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js:1151": "_storyCommand 固定写 formData.chapters 与 pendingMaterials，uiPatch 的全部调用点都在本文件且传对象字面量/空值，只补 storyEditor、节点抽屉与撤销态等固定键；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js:2857": "资格同步 patch 固定含 merchantPoolEditable，仅在确证无资格时追加 formData.openMerchantPool=false，不接收动态字段名；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js:3938": "我的俱乐部响应只写固定 myClubs，并按锁定/唯一俱乐部追加固定 clubIndex 与 formData.clubId；服务端对象不会展开为页面键；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js:4319": "节点定位 patch 固定写 selectedNodeLid，坐标有效时只追加 mapCenter 与 mapScale，字段集合由本方法写死；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js:5405": "当前用户回填 patch 固定写 draftMemberId；仅在未恢复本地草稿时追加 formData.collaboratorList/Ids，避免覆盖恢复内容，不展开接口字段；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js:5664": "票种同步 patch 固定写 editingTicket.syncWithTheme，开启同步时只追加 startTime/endTime 两个固定子字段；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js:6370": "summaryPatch 只由本方法逐项追加 chapterStates/chapterActionLabels/creationAnchor/starterAction/pendingMaterialStates/topicDetailSummary 六个固定派生字段，用于避免无变化 setData，不接收外部键；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js:6380": "patch 最多只含经本页校验袋计算出的 canPublish 一个固定字段，用于值变化时才写入；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js:6433": "校验定位 patch 只可能含 secCollapsed.ticket、showTicketEditor、editingTicket 三个固定键；错误 key 仅决定是否进入 ticket 分支和定位下标，不参与字段名拼接；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js:9054": "地图重绘 patch 固定含 mapMarkers/mapPolyline/mapInclude/hasRoute，未选节点时只追加 mapCenter；节点数据只作为值参与计算。X05 后 patch 由 utils/publish/route-map-view.js 的纯函数算好交回，键集合由该模块单测钉住（publish-route-map-view.test.js）；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/temp/index.js:1476": "listName 的当前调用点只枚举 difficultyOptions 固定列表名（R5已移除人数/时长选择器），接口结果先验证为记录数组后才写标签数组，不允许请求数据决定字段名；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/temp/index.js:1477": "listName + Data 与上一条共享同一有限调用集，当前只落 difficultyOptionsData 固定字段（R5已移除人数/时长选择器）；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/temp/index.js:1520": "_setFormState 是本页私有写入入口，全部调用点传本文件构造的字段 patch（高级玩法动态路径也来自 WXML 固定 section/field），函数只克隆该 patch 并追加固定 submitError，不展开后端对象键；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/temp/index.js:2974": "玩家预览 patch 先固定写 pvStep/pvStepKey/pvStepLabel 与四个临时态，仅按 story/challenge 枚举追加 pvBeats 或任务展示字段；步骤 key 不参与字段名拼接；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/temp/index.js:3811": "couponPatch 在同方法内从空对象开始，只可能追加 selectedCouponId 与 selectedCouponName 两个固定奖励展示字段，再展开进固定回填对象；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/temp/index.js:3938": "模板回填 patch 只逐项追加 durationIndex/Text、difficultyIndex/Label、playersIndex/Text 六个固定选择器字段，且仅在字典中找到匹配值时写入；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/search2/index.js:459": "动态 setData 需精确登记：[field]: selected；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/searchmap/index.js:962": "动态 setData 需精确登记：[field]: selected；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js:137": "动态 setData 需精确登记：[key]: item；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/square/list/index.js:177": "动态 setData 需精确登记：[key]: currentItem；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/topic/merchantinfo/merchantinfo.js:651": "可承接章节失败态只在 categoryMissing 布尔判定下二选一；两个对象分支的键均在本方法写死，state/error/allChaptersList 被 WXML 消费，loading 是已精确登记的页内控制字段；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/subpackageB/pages/im/list/index.js:372": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/subpackageMember/coupon-qr/index.js:227": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/subpackageP3/pages/badge-wall/index/index.js:205": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/subpackageP3/pages/stamp-camera/index/index.js:74": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/templatedetail/templatedetail.js:176": "onLoad 的 data 由本方法构造：先写路由参数派生的固定键，dev 环境命中 mock 时交给 patchNormalized；patchNormalized 以 info/gallery/coverUrl/capRows/detailStats/materialChips/sceneChips/isTopicTemplate/difficultyLetter/difficultyLabel 十项逐个赋值，服务端对象只作为值参与归一化，不展开为 setData 字段名；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/templatedetail/templatedetail.js:251": "onLoad 的 data 由本方法构造：先写路由参数派生的固定键，dev 环境命中 mock 时交给 patchNormalized；patchNormalized 以 info/gallery/coverUrl/capRows/detailStats/materialChips/sceneChips/isTopicTemplate/difficultyLetter/difficultyLabel 十项逐个赋值，服务端对象只作为值参与归一化，不展开为 setData 字段名；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:1376": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:3326": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:4092": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:4142": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/subpackageRoam/session/index.js:145": "动态 setData 需精确登记：...this._route(s)；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/template/index.js:276": "列表路径先经 COVER_LISTS 白名单限制为 topList/tailList，再校验整数下标、边界与稳定 id 一致，最终只写目标条目的 _coverFail；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/template/index.js:288": "target 只允许 banner，再校验事件与当前头牌稳定 id 一致，最终只写 banner._coverFail；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/topic/merchantinfo/merchantinfo.js:1736": "可承接章节失败态只在 categoryMissing 布尔判定下二选一；两个对象分支的键均在本方法写死，state/error/allChaptersList 被 WXML 消费，loading 是已精确登记的页内控制字段；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/topic/merchantinfo/merchantinfo.js:1745": "可承接章节失败态只在 categoryMissing 布尔判定下二选一；两个对象分支的键均在本方法写死，state/error/allChaptersList 被 WXML 消费，loading 是已精确登记的页内控制字段；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/pages/roam/index.js:1842": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/pages/roam/index.js:3971": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/pages/roam/index.js:4812": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/pages/roam/index.js:4870": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/pages/templatedetail/templatedetail.js:204": "onLoad 的 data 由本方法构造：先写路由参数派生的固定键，dev 环境命中 mock 时交给 patchNormalized；patchNormalized 以 info/gallery/coverUrl/capRows/detailStats/materialChips/sceneChips/isTopicTemplate/difficultyLetter/difficultyLabel 十项逐个赋值，服务端对象只作为值参与归一化，不展开为 setData 字段名；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/pages/templatedetail/templatedetail.js:321": "checkTextOverflow 的 patch 在 query.exec 回调内从空对象开始，只在 #tpl-desc / #tpl-rule 实测高度超过 110 时分别写入固定键 descNeedMore / ruleNeedMore（两键均为 true，被 WXML 的「展开」入口消费），且仅在请求令牌与详情上下文仍为当前时写入；键名不来自服务端或路由参数；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/subpackageRoam/session/index.js:187": "动态 setData 需精确登记：...this._route(s)；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/pages/topic/merchantinfo/merchantinfo.js:2360": "客户名单成功回读只调用本页 buildPlayerSheet；返回对象仅含 playerSheet 的十个固定子路径，服务端数据只作值。主办/承办组件消费 groups/filter/summary/shopDay/计数/contactHint/emptyText；raw 供同页重筛，rows 是归一名单值，字段名不取服务端输入；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/pages/topic/merchantinfo/merchantinfo.js:2391": "客户筛选只调用本页 buildPlayerSheet 重建同一组十个固定 playerSheet 子路径；筛选 key 仅作值与状态比较，不拼字段名。主办/承办组件消费筛选、分组、汇总与空态，raw 提供重筛源，rows 是归一名单值；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/components/cy/scene-club-edit/index.js:173": "动态 setData 需精确登记：[f]: e.detail.value；owner=小程序，复核=2026-09-30",
    "chengyinhub-xcx/pages/publish/fabu/index.js:6775": "onIdentityFieldInput 的键来自同页 wxml data-field，枚举只有 identityRealName/identityIdCard 两个（grep 全页仅此两处绑定），另追加固定 identityError；owner=小程序，复核=2026-09-30",
  },
  // 2026-09-07 自由探索游玩页:u5Silent 条数不变(13),只是 pages/play/index.js 那条
  //   「已完成里程碑可选增强」的调用点行号从 4368 移到 4889 —— 本轮在它上面加了卡片详情
  //   投影(_heroView)、导航行、店铺分身对话、章节故事流几组方法,整段下移。逐行回读确认:调用点仍是
  //   同一个 my-completed 请求,静默降级理由一字未改,digest 随等量换 key 显式更新。
  // 2026-09-08 复审整改(复制/重答操作行、等待名条第二拍、语音改走统一上传通道)又在其上方
  //   加了几个方法,同一个调用点 4889 → 4954。同样逐行回读:仍是 my-completed 那一处,理由未改。
  // 2026-09-08 合 master(原生 toast/modal 收口等 58 个提交)后再次位移:master 侧记的是 4300、
  //   本分支侧记的是 4954,**两侧都不是合并树的真值** —— 按本文件 2026-08-27 那条留痕的规矩,
  //   对合并结果重新实测得 4959,digest 同样按合并结果重算,不取任一侧的值。
  // 2026-09-08 视觉整改(重答失败保住原文)又在其上方加了几行,同一调用点 4959 → 4968。
  //   仍是 my-completed 那一处,静默降级理由一字未改。
  // 2026-09-08 二次合 master(#1047/#1048)后再次实测:play/index.js 的 my-completed 调用点
  //   落在 4945(master 侧记 4277、本分支侧记 4968,两侧对合并树都不对)。理由一字未改,digest 重算。
  "u5Silent": {
    // 2026-10-28 p74 业主「附近活动总是有的」:漫游起始页附近为空时的两级兜底读取逐项登记。
    "chengyinhub-xcx/pages/roam/index.js:5953": "漫游起始页「附近活动」3 公里为空时的 20 公里兜底读取(同 /api/roam/entry/nearby、同权限档):主读取已成功落定(空也是真结果),这一条只往底部卡片带补同城活动,读不到就接着读其他城市公开主题;不改地图点位、不承载写入;只写回同一次进页序号与同一身份;owner=漫游,复核=2026-10-28",
    "chengyinhub-xcx/pages/roam/index.js:5962": "漫游起始页「附近活动」同城也为空时读其他城市公开主题(/api/topic/list 公开列表):只填底部卡片带,读不到时卡片带保持主读取给出的空态卡;不改地图点位、不承载写入;只写回同一次进页序号与同一身份;owner=漫游,复核=2026-10-28",
    // 2026-10-25 官方地点卡片:展开详情两条背景读取逐项登记。
    "chengyinhub-xcx/subpackageRoam/nearby/index.js:1003": "官方地点卡展开时补读节点(相册 / 玩法 / 完成状态 / 评分):卡片已先用地图点位数据渲染名称、地址、介绍和封面,读不到只是少相册与玩法块,开始挑战前挑战页会重新读同一节点并在失败时给错误页和重试;只写回仍开着的同一张卡;owner=上海据点,复核=2026-10-25",
    "chengyinhub-xcx/subpackageRoam/nearby/index.js:1024": "官方地点卡评价列表背景读取:失败时不显示评价区空态文案(reviewsLoaded=false),卡片其余内容与按钮照常,重新点开卡片会重读;不承载提交;只写回仍开着的同一张卡;owner=上海据点,复核=2026-10-25",
    "chengyinhub-xcx/subpackageRoam/nearby/index.js:959": "可选公开相册补充读取，缺失或失败保留已显示的封面与正常导航，不将缺失侧车伪报为地点不可用；不承载提交或奖励，重新点开可重读；owner=上海据点，复核=2026-10-24",
    "chengyinhub-xcx/subpackageMember/components/cy/scene-roam-poi-detail/index.js:573": "可选照片署名与许可补充读取，缺失时保留后端相册、玩法和导航，不阻断任务；没有写入或奖励成功提示；owner=上海据点，复核=2026-10-24",
    // 2026-10-19 Claude 小程序线移植到 master:本线新增 9 处 silentError 逐项登记(超冻结上限 30,棘轮/上限/digest 待审批后同步)。
    "chengyinhub-xcx/subpackageMember/components/cy/report-sheet/index.js:51": "BL-32/BL-218 举报半屏读原因:失败落 stage=loadfail 结果半屏(failText 带重试),seq 丢弃关掉重开后的旧回包;字段不以 error 命名,静态扫描证明不了;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/subpackageMember/components/cy/report-sheet/index.js:96": "BL-32 举报提交:失败落 fail/unknown 结果半屏(含后端原因,同 requestId 可重试不重复受理),关自动 toast 免双弹;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/pages/activity/official-mine/index.js:158": "BL-80 通知复盘读取:失败写进该卡 _statsText 触达行并可再点重试(动态键,静态扫描不能证明);owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/pages/play/merchant/components/safety-rating-sheet/index.js:115": "BL-89 安全感受评价先按 public-home 换取商家 ID:失败经 done(null,msg) 落 submitError 半屏内 cy-inline-error,保留输入可重试;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/pages/roam/index.js:558": "BL-70 漫游商家横滑卡打卡头像堆(/api/roam/shop/visitors):附加信息,失败或迟到回包不动卡片,不挡「去这儿」;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/pages/roam/index.js:809": "BL-41 护照「官方活动」大卡读可参与的漫游官方活动:读不到时卡片保持原文案与原行为,不弹 toast;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/pages/roam/index.js:780": "2026-10-27 P-29 护照「勋章墙」方卡读最新点亮勋章图(/api/medal/wall):只为方卡换一张图,读不到保持业主空态插画,token 丢弃迟到回包,页面无可给的重试动作,不弹 toast;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/subpackageMember/components/cy/merchant-correction-sheet/index.js:46": "BL-67/BL-219 商家纠错半屏读纠错类型:失败落 stage=loadfail 结果态带重试,seq 丢弃关掉重开后的旧回包;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/subpackageMember/components/cy/merchant-correction-sheet/index.js:87": "BL-67 商家纠错提交:失败落 fail/unknown 结果态(含后端原因,同键可重试),关自动 toast 免双弹;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/subpackageMember/coupon/coupon.js:192": "BL-77 券统计卡是辅助只读信息:读取失败即不展示、不挡券列表,不弹 toast;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/subpackageRoam/nearby/index.js:255": "附近队伍地图层既有silent读取改为带参failed及计算两图层OR，静态扫描不能直接证明：真实401/403显示登录或权限并清队伍，普通失败按loadError留白且refresh/retryNearby可回读；owner/seq阻止迟到写回，57项Node含真实异常/按钮契约通过；owner=小程序，复核=2026-10-12",
    "chengyinhub-xcx/subpackageMember/complaint/index.js:232": "本人投诉进展辅助读取，普通500/网络失败保留同主旧列表或首载留白；401/code2 显式显示登录入口，403/400/422 写 historyAccessError，账号与可见代际变化先清旧历史，写操作错误不静默；服务端本人范围不传 memberId；owner=页面流程，复核=2026-10-16",
    "chengyinhub-xcx/pages/activity/list/index.js:201": "公开活动读取，失败写实例 _loadError/_loadErrorMsg 并由 applyFilter 派生 curError/errorMsg；普通500/网络失败沿既有 load-failure 留白或保留旧公开列表，公开数据可匿名读，不借私有登录态掩盖失败；owner=小程序，复核=2026-10-16",
    "chengyinhub-xcx/pages/activity/list/index.js:247": "本人参与活动读取，owner/token/seq/可见代际隔离旧回包；401/code2 显示登录单飞恢复，403 独立无权限提示，普通500/网络失败保留同主旧列表或首载 load-failure 留白，经 applyFilter 消费实例 _mineError/_mineErrorMsg；owner=小程序，复核=2026-10-16",
    "chengyinhub-xcx/utils/community-post-api.js:78": "canonical 社区受控请求对象：BASE+path 只接固定发布/编辑阶段 helper 与举报闭包的枚举路由，举报回读仅显式切至本人 /api/community-trust/reports/mine；完整目标匹配 Controller，owner/current 检查及 pipeline success/fail 可见反馈保留，取消不报成功；owner=小程序，复核=213",
    "chengyinhub-xcx/pages/roam/index.js:5792": "漫游入口地图活动层随地图中心后台刷新:失败汇总到 entryLoadError 并提供重新加载,关闭通用 toast 避免拖图时与页内错误态重复报错;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/pages/roam/index.js:5799": "漫游入口地图队伍层随地图中心后台刷新:失败汇总到 entryLoadError 并提供重新加载,关闭通用 toast 避免拖图时与页内错误态重复报错;owner=小程序,复核=2026-12-31",
  // (本支) "chengyinhub-xcx/pages/activity/list/index.js:105": "公共活动列表读取:失败落 loadError/errorMsg,由页面 cy-error 呈现并给重试,关掉自动 toast 是避免双弹;owner=小程序,复核=2026-12-31",
  // (本支) "chengyinhub-xcx/pages/activity/list/index.js:129": "「我发布的」列表读取:失败落 mineError/errorMsg,同页错误态承接,同上;owner=小程序,复核=2026-12-31",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:2068": "上报自己位置的心跳,30 s 一次:掉一次就是这一格没报上去,下一次覆盖它;弹错等于每走两步弹一个框,而且用户对此无事可做 —— 真失效(服务端 5 分钟收不到)的表现是自己从别人地图上消失,不是这里报错;owner=小程序,复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:2077": "拉附近还在走的人,45 s 一次:拉不到就保留上一批不清空(见调用点注释),地图上没人是常态不是错误;这一层报错会把「附近本来就没人」说成「出问题了」;owner=小程序,复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/merchant/customer/index.js:1013": "触达历史是页面区块的 stale 刷新:2026-08-26 起有旧记录时刷新失败静默降级,旧列表留在屏上、重试靠下拉,首屏无数据时仍由整页 cy-error 兜底;owner=小程序,复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/topic/merchantinfo/merchantinfo.js:2389": "/api/topic/info-to-user 在这里只用来给「更多 → 开放报名」那一行读章节的 recruitEnabled:读不到就是那一行不带「开/关」标签,抽屉本身照常能用。为这个弹全局 toast 会让主办方以为整个抽屉出错了;owner=小程序,复核=2026-09-30",
    "chengyinhub-xcx/utils/marketing-consent-entry.js:60": "支付成功面板/订单详情上取商家营销同意状态(GET /api/merchant/crm/marketing-consents)是非必要附属请求:失败只隐藏可选的同意勾选行(fail-closed),主流程(面板自愈跳转、订单详情)不受影响;支付刚成功就弹通道默认「网络错误」会让玩家以为没付成功(2026-09-17 总控裁定,release-0917 第三阶段 C);保存同意(POST)仍不静默;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/utils/roam-share-snapshot.js:137": "接收方读取朋友分享的足迹快照(匿名 GET,单独字面量供 GET 权限矩阵核对):服务端明确没有落整页「这条足迹已不再公开」空态,断网/网关落整页错误态带重试;通道自动 toast 会与整页状态重复;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/utils/roam-share-snapshot.js:22": "足迹分享快照三路请求(发布/作废/接收方读取)都有专用可见反馈:发布失败在分享面板写明「足迹链接没生成」并给重试,作废失败由清空确认后的精确 toast 说明「链接暂未失效」,接收方读取失败按 gone/error 落整页空态或重试;通道自动 toast 会把同一件事报两遍;owner=小程序,复核=2026-12-31",
      "chengyinhub-xcx/pages/roam/index.js:2600": "上报自己位置的心跳,30 s 一次:掉一次就是这一格没报上去,下一次覆盖它;弹错等于每走两步弹一个框,而且用户对此无事可做 —— 真失效(服务端 5 分钟收不到)的表现是自己从别人地图上消失,不是这里报错;owner=小程序,复核=2026-09-30",
      "chengyinhub-xcx/pages/roam/index.js:2629": "拉附近还在走的人,45 s 一次:拉不到就保留上一批不清空(见调用点注释),地图上没人是常态不是错误;这一层报错会把「附近本来就没人」说成「出问题了」;owner=小程序,复核=2026-09-30",
    // 2026-09-17 定向广播接线:pages/merchant/customer/index.js 在原触达历史登记点上方
    //   新增广播预览/发送两段(服务端预览失败、发送失败都落半屏内 cast.error 行,含可重试说明,
    //   属「同请求内静态证明显式处理」,不需要逐项登记),既有那条静默登记点按门禁实测
    //   1069→1179 等量换 key。调用点与理由一字未改,digest 按新 key 集合实算。
    // 2026-09-17 广播实现收尾:同文件删掉只写不读的 cast.preview 死状态(净 -1 行),
    //   锚点按门禁实测 1179→1178;调用点上下逐字一致,理由一字未改,条数仍 23。
    "chengyinhub-xcx/pages/topic/merchantapply/index.js:227": "/api/merchant/info 只用来给报名表单补「你的门店」那张卡(稿 133:310):三行都是给主办方看的补充信息,不是提交字段,拉不到就整块不渲染,报名照常填照常交。全局 toast 在这里会被当成「我表单填错了」;owner=小程序,复核=2026-09-30",
    "chengyinhub-xcx/subpackageMember/signup/index.js:398": "/api/team/my 只用来给票下面那一行「我的队伍 ›」找落点:拉不到就整行不出,票夹本身照常可用。弹全局 toast 会让人以为票没加载出来;owner=小程序,复核=2026-09-30",
    "chengyinhub-xcx/components/cy/scene-member-invite-history/index.js:63": "邀请历史使用本地 request wrapper 将网络/异常状态归一成空响应，列表据此展示无记录而不弹全局错误；owner=小程序，复核=2026-09-15",
    "chengyinhub-xcx/utils/analytics.js:233": "埋点投递是纯副作用且不应打扰玩家，fail 只回报 onDone(false)；必须显式登记，禁止因非空回调自动安全；owner=小程序，复核=2026-09-15",
    "chengyinhub-xcx/utils/ui-state-request.js:10": "共用请求封装,静默由调用方接管:各页在 fail 分支写页内错误态与重试,禁止 request-client 再弹一次；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/pages/roam/index.js:1931": "官方活动到达验证提交:失败由 .then 内 _setEventFeedback 写活动叠层页内错误态(后端 msg 或「本次验证没有送达，请保持当前位置后重试」),关自动 toast 避免双弹；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/pages/roam/index.js:4266": "据点发现确认:失败按 net/unknown/server 分类返回,调用方写 paceCard.checkinErr 页内错误并按是否可重试给重试钮(结果未知不给重试防重复提交),关自动 toast 避免双弹；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/pages/roam/index.js:4480": "发现奖励卡上附带的附近探店日候选:失败或无候选返回 null,只是奖励卡少一条可选推荐,不影响主流程;弹错会打断奖励反馈；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/pages/roam/index.js:4565": "商家打卡提交:失败按 net/unknown/server 分类返回,_sendCheckin 只给一句 toast 并把打卡卡收起(2026-09-16 拍板「漫游打卡不存在失败态」:visit.checkinErr 与 retryCheckin 已删,距离不够在 startVisit 就被拦下),关自动 toast 避免双弹;owner=小程序,复核=2026-09-30",
      "chengyinhub-xcx/pages/roam/index.js:4676": "三店连亮勋章配置读取:失败沿用兜底阈值与文案(SHOP_BADGE_FALLBACK_THRESHOLD),fail-open 不比改动前差;无用户可做的动作；owner=小程序，复核=2026-09-30",
      "chengyinhub-xcx/pages/roam/index.js:5136": "漫游分享发到广场的发帖请求:失败由同一 Promise 链接管——业务失败 fail(msg) 出 toast,网络/HTTP 异常与 catch 均提示「到广场核对，暂不要重复发布」并置 squarePostUnknown;关自动 toast 是避免双弹;owner=小程序,复核=2026-09-30",
      "chengyinhub-xcx/subpackageRoam/nearby/index.js:282": "附近主题地图层只读：当前epoch/账号有效后401与403显示登录或权限提示并清图层；普通失败进入loadError读取留白，refresh/retryNearby可回读；异步icon生成受owner/seq约束，关闭通用toast避免拖图重复反馈；owner=小程序，复核=2026-10-12",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:747": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:1462": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:3587": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:3791": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:3873": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:3981": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:598": "拉附近有人在组局(原型 f-hangout),120 s 一次的背景轮询:拉不到就保持上一张不闪,地图上没有局本来就是常态;这一层报错会把「附近本来就没人组局」说成「出问题了」,而且页面里没有可给的重试动作;owner=小程序,复核=2026-09-30",
  // (本支) "chengyinhub-xcx/subpackageRoam/nearby/index.js:398": "拉附近别人打过卡的地方(原型 h-place),跟着地图中心走的背景读取:读不到就这一次列表为空,用户换个地方拖图就会再拉一次;弹错等于每拖一下图弹一个框;owner=小程序,复核=2026-09-30",
  // (本支) "chengyinhub-xcx/subpackageRoam/nearby/index.js:433": "拉「谁在这儿打过卡」的头像与人数:半屏的名字与地址已经先渲染出来了,这一条只是往上补人;补不上就只显示名字,不该为它弹错;owner=小程序,复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/roam/index.js:4246": "动态 setData 需精确登记：setData 参数不是对象字面量；owner=小程序，复核=2026-09-30",
  // (本支) "chengyinhub-xcx/pages/play/index.js:5695": "已完成里程碑为可选增强，请求失败保持 milestone=null 且轴不渲染；owner=小程序，复核=2026-09-15",
  // (本支) "chengyinhub-xcx/components/cy/post-compose/index.js:383": "广场选活动报名列表读取:失败落组件 activityError,由选活动层呈现并给重试,关掉自动 toast 避免双弹;owner=小程序,复核=2026-12-31",
  // (本支) "chengyinhub-xcx/components/cy/post-compose/index.js:396": "广场选活动「我主办的」列表读取:失败汇总进同一 activityError,同上;owner=小程序,复核=2026-12-31"
      // 2026-09-18 合并 github/master(#1088):play/index.js 上方净增 28 行,
      //   loadMilestone 的 /api/play/my-completed 这条 silentError 调用点 5542→5570(门禁实测)。
      //   主包瘦身后 post-compose 已下沉到 pages/square/components/cy/。
      // 2026-09-20 合并后按最终源码重钉，调用与静默降级理由未改。
      "chengyinhub-xcx/pages/play/index.js:7944": "已完成里程碑为可选增强，请求失败保持 milestone=null 且轴不渲染；owner=小程序，复核=2026-09-15",
    "chengyinhub-xcx/pages/topic/pricing/index.js:184": "合作阵容行补名是 best-effort 装饰读取:拉不到名字就保留「合作俱乐部/承接商家」类型兜底,条款摘要照常、点开合作方页自有错误态;为补名弹全局 toast 会让主办方以为整页定价坏了;owner=小程序,复核=2026-12-31",
    "chengyinhub-xcx/pages/merchant/relation/index.js:264": "合作 tab 帖文流读取(2026-10-22 业主:合作 tab 看帖文):失败经同函数内具名 fail() 落 feedState=error/stale-error 与 feedError,页面 cy-error 呈现并给重试,关通道 toast 避免双重反馈;owner=小程序,复核=2026-11-30",
    "chengyinhub-xcx/utils/predict-entry.js:15": "消息页「竞猜待答」入口显隐读取(2026-10-22 业主:无待答不出入口):读失败照样显示入口(宁可多一行,不让 48 小时期限提醒消失),不是用户动作,无需弹错;owner=小程序,复核=2026-11-30",
  }
};
