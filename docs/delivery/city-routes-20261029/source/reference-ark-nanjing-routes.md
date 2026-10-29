# 城瘾测试线路：《海上方舟》《南京路上的四座高峰》

生成时间：2026-10-24（UTC+8）。仓库：liboyang42-cpu/chengyin（HEAD，只读）。所有 advanced_config_json 都已用仓库里 `AdvancedGameConfigValidator.java` 的原文编译运行校验，25/25 通过（2026-10-24 17:xx 重跑）。

## (a) 产品功能清单（以代码为准）

**数据结构**：主题 `cms_topic` → 章节 `cms_topic_chapter`（required 必修/选修，sort_id）→ 节点 `cms_topic_node`（name、address、longitude/latitude **GCJ-02**、template_id、checkin_code、business_time "HH:mm-HH:mm"、sort_id、xp_value、hook_text、card_hook_long、fragment_text、unlock_after_node_id 前置解锁）→ 玩法模板 `cms_member_template`（validation_method + 题目列 + 体验层字段 + advanced_config_json）。
来源：`chengyinhub-system/.../domain/CmsTopicNode.java`、`CmsMemberTemplate.java`、`sql/migration_seed_official_solo_story_topics_20260823.sql`（种子里写的是“GCJ-02 双机实走”“50m GPS 围栏”）。

**完成方式 validation_method**（`chengyinhub-common/.../constant/ValidationMethod.java`）：
| 值 | 名称 | 配置参数 |
|---|---|---|
| 0/空 | 到点即完成 | 无 |
| 1 | 文字验证 | question_name、question_answer；hint1/hint2/answer_reveal |
| 2 | 拍照打卡 | photo_require_desc、photo_review |
| 3 | 选项验证 | question_a–d、correct_answer(A–D)；hint1/hint2/answer_reveal |
| 4 | 扫码打卡 | 节点 checkin_code（自动生成 UUID，要打印成码） |
| 5 | GPS到达 | 节点坐标（50m 围栏） |
| 6 | 偏好题组 | preference_json（结构没查到，见下） |
| 7 | 传感器挑战（仅 App） | sensor_type、sensor_config |
| 8 | 估数题 | advanced.estimate{title,min,max,answer,tolerance,unit,reveal} |
| 9 | 价格连击/猜图 | advanced.pricePair{title,items[3–8]{id,name,imageUrl,correct},maxTries,xp} |
| 10 | 找东西 | advanced.hiddenObject{title,imageUrl,hotspots[3–5]{id,label,x,y,r 0.03–0.15},maxTries} |

**提示与计分**（`PuzzlePlayServiceImpl.java`、`util/PuzzleScorePolicy.java`、`migration_hint_unlock_points_20260721.sql`）：只有 vm=1/3 走 hint1→hint2→answer_reveal 的阶梯。不看提示 100 分，看过1级 70 分，看过2级 40 分，直接看答案 0 分。体验层字段（`migration_level_editor_fields.sql`）：story_text、story_img、audio_url、audio_duration、photo_require_desc；另有 xp_value、medal_name、finish_medal_name。

**高级玩法 advanced_config_json，schemaVersion=1**（`service/support/AdvancedGameConfigValidator.java`，每个段都要写 `enabled:true`）：
- 机制叠加段：timer{durationSeconds 10s–24h, timeoutResult FAILED/EXPIRED}；leaderboard{metric ELAPSED_TIME/SCORE/COMPLETED_UNITS, scope ACTIVITY/TOPIC, limit 1–100}；multiplayer{mode SEQUENTIAL/ROLE_BASED, min/maxPlayers 2–20, roles, turnOrder…}（只支持活动场次+队伍）；timeWindow{openFrom,openTo HH:mm}；requires/effects（clue./relation./counter. 变量，tag.、HAS_TAG、NODE_COMPLETED、ITEM_HELD）；check{checkId,tier easy/medium/hard…}；variants（自适应放宽）；present inline/fullscreen；mistakeTier。
- 剧情选择类：branch{startStepId, steps[{id,title,body,terminal,outcomeCode(终点必填),options[{id,label,nextStepId,score,effects}]}]}；random 盲盒{items[{id,label,weight,content}],drawCount}；profile 建档{questions[{key,label,kind text/pick,options}]}；roleViews 双人视角 A/B。
- 答题类：qa{mode TYPE/PICK/SHOT, title, answerText 或 options[{id,label,correct,fb}], multi, maxTries}；sort{prompt,items 2–8,answerOrder}；match{left/right 2–6,pairs}；classify{bins 2–4,items 2–10,answer{item:bin}}；compare{left/right{label,items[{id,time,text}]},answer[]}；typeIn{target 1–40字,seconds 3–120,tries}；estimate；pricePair；hiddenObject；predict 竞猜{question,options 2–4,closeAtHour,revealDays}。
- 拍照/记录类：photoCheck{title,requirement≤60字,minConfidence,maxTries 1–10,fallback retake/pass,mode CARD 拍物成卡,cardTitle,cardStyle foil/plain,frameUrl}；album{images 1–6}；note 留言{title,prompt,maxLength,presets,showPrevious}；diyName；dailySign 城市签；slowTask 跨日{waitDays 1–7,unlockText}；musicCorner{title,durationSeconds}。
- 扫码/AR：scan{kind TEXT/VOICE/IMAGE/OVERLAY, reply/audioUrl/imageUrl/overlayUrl, arMode NONE/PLANE/MARKER, markerUrl, modelUrl .glb}。
- 设备/体感类：steps 计步{goal 100–100000}、compass{bearing 0–359,tolerance 5–90,holdSeconds 1–10}、countdown{seconds,doneText}、stopwatch{targetSeconds,toleranceMs 50–5000,tries}、coinFlip、diceRoll{d6 faces×6 / d20 dc}、reaction、ballShake、quietHold、shout。
- 商家消费类：blindTaste 盲品、silentOrder 沉默点单、pricePair。
- 已知缺陷（`chengyinhub-xcx/docs/audit-e2e-202609/04-playkit-event-contract.md` F-PK-1 P0）：reaction/ballShake/quietHold 前端分发器没接 `bind:start`，提交时必报“还没开始”。

**组队**：play_team + 邀请码，多人玩法只支持活动场次（`migration_20260820_play_team_core.sql`、`migration_20260826_team_race_multiplayer.sql`、named_mechanic 迁移注释）。
**导入格式**：仓库里没有面向主题/线路的 JSON 导入或后台批量导入。唯一的导入器是商户线索导入（MerchantLeadImportParser）。官方内容全部走 SQL 种子迁移（member_id=100000），所以本次按该 SQL 格式生成，另外附一份 JSON 源。

## 叙事层（v3 · 档案体，2026-10-24）
- 体裁：受网络“异常档案”类文学启发的原创档案体（编号/类别/特殊收容措施/描述/访谈记录/观测报告/[数据删除]/█████/权限等级）。未复制任何既有作品文字、编号、实体或其等级名称（未用 Safe/Euclid/Keter），不需要 CC BY-SA 署名；若后续直接改编某篇具体作品，则须按 CC BY-SA 3.0 署名并同协议发布。
- 虚构机构：城市异常档案局（CUA），档案员K（线路1）、档案员M（线路2）、访谈受访者——全部【游戏设定】。
- 线路1 档案 CUA-HK-39「方舟」（庇护型/蔓生级）：调查员权限 L1→L7；7 份残档（登记表/值班日志/餐券/勘测记录/访谈记录/观测表/结案报告），关键词 签证/S/汤/边界/对角/名字/选择；结案：收留者“不是一艘船，是人”，档案改判“非异常·人类行为”，名单增长机制=持续的史料考证。
- 线路2 档案 CUA-NJ-17「高峰」（竞争型/游移级）：四座百货为实体 α先施/β永安/γ新新/δ大新；权限 L1→L10；残档00—10；关键词 资本/起点/前四大/不二价/天桥/第一声/扶梯/原点/放映/归档；结案于起源点张园，改判“非异常·城市记忆”。
- 大光明：由“插曲”改为正式残档08（影像证物），给关键词「放映」，承接国际饭店“同一设计师的两座楼”的悬念。
- 字段映射：story_text=档案正文（含【史实】段与█████待解密处）；feedback_text/fragment_text=“解密完成”原文+权限提升+关键词+下一站坐标线索；hint1/hint2=档案员提示；card=解密目标+交通说明。

## 解密附录（真实史实与来源）
档案中所有“异常”“实体”“船形区域”“名单自行增长”等均为游戏设定，不是历史。真实史实只限各站【史实】段落，逐站来源见下方各节点“来源”行；名单增长的真实原因是持续的史料考证与增补（2014年13732人、2020年18578人、2025年19672人）。

## 线路1《海上方舟》——档案 CUA-HK-39「方舟」：一份仍在变长的名单
城市异常档案局 · 外勤档案（游戏设定）
档案编号：CUA-HK-39「方舟」
档案类别：庇护型 / 蔓生级
你的身份：外勤调查员，初始权限 L1
特殊收容措施：调查员须沿苏州河由西向东逐站进入目标区域，每站解密一份残档后方可获得下一站坐标。区域内须保持肃静；不得进入居民楼；不得对名单墙计数以外的任何人进行拍摄。
描述：一片以提篮桥为中心的街区。档案局记录显示，与该区域相关的一份名单自建档以来持续增长。本档案的任务是查明：是什么在“收留”这些名字。
【游戏设定】“城市异常档案局”、档案编号、权限等级、调查员身份、受访者与档案员均为虚构；文中“异常”只是叙事装置。所有标【史实】的内容均为真实历史并附来源（见每站来源与《解密附录》）。
路线：和平饭店北楼→河滨大楼→霍山路JDC旧址→霍山公园→舟山路59号→摩西会堂（上海犹太难民纪念馆）→白马咖啡馆，约4.9公里。纪念馆周一闭馆，16:00停止入场。测试版：EXPERIMENTAL，坐标待双机实走校准。

- 步行总长：约 4.9 km；建议时长 3 小时 30 分；完成勋章：方舟见证人
- 坐标：GCJ-02（入库值）/ WGS84；`est` = 人工推估，误差可能 30–60 m，必须现场校准

### 主线（required=1，逐站解锁）
#### 启航 · 和平饭店北楼
- 地址：上海市黄浦区南京东路20号（原沙逊大厦，外部公共区域）
- 坐标 GCJ-02：`121.489565, 31.23911`｜WGS84：`121.48508, 31.24108`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，267字）：▌CUA-HK-39「方舟」｜残档 01/7｜入境登记表 · 权限 L1
调查员须知：本档案的第一份文件是一张空白登记表。档案局无法解释它为何每次被打开时都是空白的。请由你本人填写。
——
【史实】1938—1940年任中国驻维也纳总领事的何凤山，不顾上级反对持续签发“上海签证”，存世的一张序号已到3639。身后这座绿色金字塔顶的大楼是沙逊大厦（今和平饭店北楼），1926年动工、1929年落成，由公和洋行设计。
——
登记表：
出发城市：█████
姓名：█████
抵达地点：外滩
备注：持有者“已被收留”。收留方：[数据删除]
- 玩法：validation_method=5（GPS到达） + 高级玩法 `profile`；模板标题 `CY-T1-01 身份登记：沙逊大厦`
  - advanced_config_json：

```json
{"schemaVersion": 1, "profile": {"enabled": true, "title": "1939 抵沪登记", "lead": "为这趟旅程建立一个身份，后续结局文字会引用它。", "questions": [{"key": "origin", "label": "你从哪座城市出发？", "kind": "pick", "options": [{"key": "vienna", "label": "维也纳", "effects": [{"op": "ADD_TAG", "value": "tag.from_vienna"}]}, {"key": "berlin", "label": "柏林", "effects": [{"op": "ADD_TAG", "value": "tag.from_berlin"}]}, {"key": "prague", "label": "布拉格", "effects": [{"op": "ADD_TAG", "value": "tag.from_prague"}]}]}, {"key": "alias", "label": "给1939年的自己起个名字", "kind": "text", "maxLength": 8}]}}
```
- 碎片（fragment_text）：▌解密完成｜权限 L1 → L2
登记表备注栏恢复为：“持有者已被收留。收留方：一张签证。”
🔑 关键词1/7「签证」
📎 登记表背面渗出一行铅笔字：“北苏州路400号。那栋楼在空中写着它主人的名字。”
- 来源：https://cn.chinadaily.com.cn/a/202109/11/WS613bc655a3101e7ce9763033.html ｜ https://www.thepaper.cn/newsDetail_forward_25871923 ｜ https://www.shobserver.cn/wx/detail.do?id=2732
#### 接待站 · 河滨大楼
- 地址：上海市虹口区北苏州路400号（南侧沿河人行道观察，不进入居民楼）｜距上一站 ≈1.2 km
- 坐标 GCJ-02：`121.483975, 31.243629`｜WGS84：`121.47947, 31.24558`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，320字）：▌CUA-HK-39「方舟」｜残档 02/7｜接待站值班日志（节选）· 权限 L2
【史实】河滨大楼1931年动工、1935年竣工，由公和洋行为沙逊设计，曾被称为“远东第一公寓”。1938年10月19日，上海犹太社团在嘉道理洋行开会，沙逊的代表透露：一个月前，沙逊已无偿提供这栋楼作为欧洲难民的临时接待站；1939年1月，约240名难民暂住于此。据亲历者郑侠飞回忆，1937年10月31日清晨撤出四行仓库的谢晋元，也曾在这栋楼三楼短暂休息。
——
值班日志（游戏设定）：
“第14日。新到者问我这栋楼为什么弯弯曲曲。我答：它从天上看，是一个字母 █。新到者不再提问。”
档案员K批注：该字母与本楼出资人的姓氏首字母一致。是否构成异常，待定。
- 玩法：validation_method=1（文字验证）；模板标题 `CY-T1-02 字母谜：河滨大楼`
  - 题目参数：question_name=`从空中俯瞰，河滨大楼的平面像哪个英文字母？（填1个大写字母）`；question_answer=`S`
  - hint1：档案员K：“沿河走几步，看楼身在哪里拐弯。”｜hint2：档案员K：“出资人：Victor S█████。”｜answer_reveal：S。大楼采用连续S形平面，一方面受地块限制，一方面被认为暗合沙逊（Sassoon）的首字母。
- 碎片（fragment_text）：▌解密完成｜权限 L2 → L3
原文：“它从天上看，是一个字母 S。”
档案员K结论：非异常。地块限制＋出资人姓氏，见解密附录。
🔑 关键词2/7「S」
📎 日志最后一页：“接待站只能住几个月。下一批人，往东，霍山路119号。”
- 来源：https://www.shobserver.cn/wx/detail.do?id=2732 ｜ https://news.qq.com/rain/a/20230403A0144A00 ｜ https://www.shhk.gov.cn/xwzx/002006/20201211/9004e45b-9b60-45f3-b425-f262aa11424f.html ｜ https://www.shhk.gov.cn/xwzx/002003/20220210/7f394202-834e-480b-be0f-943e088a4c56.html
#### 救济 · JDC旧址
- 地址：上海市虹口区霍山路119—121号（外部观察）｜距上一站 ≈3.3 km（或地铁12号线到提篮桥）
- 坐标 GCJ-02：`121.51081, 31.253673`｜WGS84：`121.5064, 31.2557`（est）｜营业时间字段：`00:00-23:59`
- 故事（story_text，259字）：▌CUA-HK-39「方舟」｜残档 03/7｜救济餐券（背面）· 权限 L3
【史实】美犹联合分配委员会（JDC）1938年在上海设立分支，办公于霍山路119—121号，援助资金多用于难民宿舍、救济食堂和医院。1943年2月18日，日本占领当局发布布告，要求1937年后抵沪的无国籍难民三个月内迁入“限定居住区”。
——
餐券背面印着（游戏设定）：
“本券由 J.D.C. 发放。本机构成立于 ████ 年，████。凭券领汤一份。”
档案员K批注：持券者在区域内的存活率显著高于预期。原因：[数据删除——见解密附录]
- 玩法：validation_method=3（选项验证）；模板标题 `CY-T1-03 选择题：美犹联合分配委员会`
  - 题目参数：question_name=`美犹联合分配委员会（JDC）最初成立于？`；question_a=`1914年·纽约`；question_b=`1938年·上海`；question_c=`1943年·上海`；question_d=`1945年·维也纳`；correct_answer=`A`
  - hint1：档案员K：“上海分支不是总部，总部早了二十多年。”｜hint2：档案员K：“第一次世界大战爆发那年，大西洋另一边。”｜answer_reveal：A。JDC于1914年在美国纽约成立，1938年设立驻沪分支，办公于霍山路119—121号。
- 碎片（fragment_text）：▌解密完成｜权限 L3 → L4
原文：“本机构成立于1914年，纽约。凭券领汤一份。”
存活率原因解密：宿舍、食堂、医院。
🔑 关键词3/7「汤」
📎 餐券边缘印着一张极小的地图：“边界见对面公园石碑。越界后果：████。”
- 来源：https://www.shhkjrm.com/fw/zgjs.htm ｜ https://news.qq.com/rain/a/20240121A070A100
#### 边界 · 霍山公园纪念碑
- 地址：上海市虹口区霍山路118号霍山公园内｜距上一站 ≈80 m（隔街相对）
- 坐标 GCJ-02：`121.511109, 31.253432`｜WGS84：`121.5067, 31.25546`（osm）｜营业时间字段：`06:00-18:00`
- 故事（story_text，279字）：▌CUA-HK-39「方舟」｜残档 04/7｜边界勘测记录 · 权限 L4
【史实】这座公园建于1917年，最初叫斯塔德利公园，后改称舟山公园，1944年6月改名霍山公园。限定居住区里，难民很难自由走出边界，这块绿地成了孩子玩耍、大人聊天和举办小型音乐会的地方。1994年“犹太人重聚上海”活动期间，这里竖起“二战期间犹太难民居住区”纪念碑，用中文、英文、希伯来文写下区域的大致范围。
——
勘测记录（游戏设定）：
“区域边界：西 █████ ／ 东 █████ ／ 南 █████ ／ 北 █████。
形状：近似一条船。
本区域被内部人员称为‘方舟’。”
- 玩法：validation_method=5（GPS到达） + 高级玩法 `match`；模板标题 `CY-T1-04 连线题：限定居住区四至`
  - advanced_config_json：

```json
{"schemaVersion": 1, "match": {"enabled": true, "prompt": "根据纪念碑碑文，把限定居住区的四个方向与边界道路连起来。", "left": [{"id": "w", "label": "西"}, {"id": "e", "label": "东"}, {"id": "s", "label": "南"}, {"id": "n", "label": "北"}], "right": [{"id": "gongping", "label": "公平路"}, {"id": "tongbei", "label": "通北路"}, {"id": "huimin", "label": "惠民路"}, {"id": "zhoujiazui", "label": "周家嘴路"}], "pairs": [["w", "gongping"], ["e", "tongbei"], ["s", "huimin"], ["n", "zhoujiazui"]], "xp": 20}}
```
- 碎片（fragment_text）：▌解密完成｜权限 L4 → L5
原文：西起公平路，东到通北路，南起惠民路，北至周家嘴路。
档案员K：“船的形状只是我们的说法。石碑上没有写船。”
🔑 关键词4/7「边界」
📎 勘测员在边界内标了一个点：“舟山路59号。一间不足10平方米的舱室。”
- 来源：https://www.shhkjrm.com/fw/zgjs.htm ｜ https://news.qq.com/rain/a/20240121A070A100 ｜ https://www.bs178.com/s-1-4d2501bffe9e6c9f/
#### 亭子间 · 舟山路59号
- 地址：上海市虹口区舟山路59号（居民住宅，仅街面观察）｜距上一站 ≈150 m
- 坐标 GCJ-02：`121.510013, 31.253976`｜WGS84：`121.5056, 31.256`（est）｜营业时间字段：`09:00-18:00`
- 故事（story_text，314字）：▌CUA-HK-39「方舟」｜残档 05/7｜访谈记录 HK-39-05 · 权限 L5
受访者：前居民（游戏设定，虚构）
访谈者：外勤调查员（你）
——
调查员：你们一家住哪一间？
受访者：二楼。不足10平方米。
调查员：怎么睡？
受访者：█████。
［记录中断约11秒］
——
【史实】1939年，13岁的迈克·布卢门撒尔随父母从德国逃到上海，一家住在舟山路59号二楼一间不足10平方米的小屋，据讲解员介绍，一家人要“对角”才能平躺睡下。他在上海生活约8年，1947年赴美求学，后获普林斯顿大学经济学博士学位，1977—1979年出任美国第64任财政部长。
⚠️ 这里仍是居民住宅：只在街面拍门牌或铭牌，不入内、不拍居民。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `photoCheck`；模板标题 `CY-T1-05 拍物成卡：布卢门撒尔旧居`
  - advanced_config_json：

```json
{"schemaVersion": 1, "photoCheck": {"enabled": true, "title": "拍下旧居的门牌", "shotNote": "只拍门牌或铭牌，不拍居民和室内。", "requirement": "画面中能看到舟山路59号门牌或“布卢门撒尔旧居”铭牌", "minConfidence": 60, "maxTries": 3, "fallback": "pass", "mode": "CARD", "cardTitle": "舟山路59号·布卢门撒尔旧居", "cardStyle": "plain", "xp": 20}}
```
- 碎片（fragment_text）：▌解密完成｜权限 L5 → L6
原文：“受访者：对角。”
🔑 关键词5/7「对角」
📎 访谈结束前，受访者问：“名单上……会有我们吗？”档案员K：“去长阳路口。那份名单，至今还在变长。”
- 来源：https://xmwb.xinmin.cn/html/2019-09/22/content_14_2.htm ｜ https://xmwb.xinmin.cn/lab/html/2015-02/08/content_4_2.htm ｜ https://www.shhk.gov.cn/xwzx/002008/002008040/20240522/d66658b8-3c34-43f1-bf14-5bd915fb01f1.html
#### 名单墙 · 摩西会堂
- 地址：上海市虹口区长阳路62号上海犹太难民纪念馆｜距上一站 ≈150 m
- 坐标 GCJ-02：`121.509236, 31.254128`｜WGS84：`121.50482, 31.25615`（osm）｜营业时间字段：`09:00-16:00`
- 故事（story_text，262字）：▌CUA-HK-39「方舟」｜残档 06/7｜名单增长观测表 · 权限 L6
【史实】摩西会堂1927年由俄罗斯犹太人集资修建，二战时是难民聚会和宗教仪式场所；2007年以它为核心建立上海犹太难民纪念馆。广场名单墙以原难民索尼娅·穆尔伯格提供的约1.4万人名单为基础，2014年落成、2020年扩建，2025年10月21日又揭幕新增部分。
——
观测表（游戏设定）：
观测1：█████ 人
观测2：█████ 人
观测3：█████ 人
档案员K批注：名单在无人刻写的年份里也被判定为“未完成”。增长机制：[需权限 L7]
- 玩法：validation_method=5（GPS到达） + 高级玩法 `sort/timeWindow`；模板标题 `CY-T1-06 排序题：名单墙上的名字`
  - advanced_config_json：

```json
{"schemaVersion": 1, "sort": {"enabled": true, "prompt": "把名单墙三个阶段的姓名数量按时间先后排序（最早在上）。", "items": [{"id": "n18578", "label": "18578个名字"}, {"id": "n19672", "label": "19672个名字"}, {"id": "n13732", "label": "13732个名字"}], "answerOrder": ["n13732", "n18578", "n19672"], "maxAttempts": 3, "xp": 20}, "timeWindow": {"enabled": true, "openFrom": "09:00", "openTo": "16:00", "eyebrow": "开放时段", "title": "纪念馆16:00停止入场"}}
```
- 碎片（fragment_text）：▌解密完成｜权限 L6 → L7（最高）
原文：2014年13732人；2020年18578人；2025年19672人。
🔑 关键词6/7「名字」
📎 L7 权限已开放最后一份文件。地点：长阳路67号，白马咖啡馆。档案员K留言：“带上全部六个关键词。结案需要你签字。”
- 来源：https://www.shhkjrm.com/zl/mdq.htm ｜ https://www.shhkjrm.com/info/1221/9742.htm ｜ https://www.shhkjrm.com/fw/zgjs.htm ｜ https://www.shhkjrm.com/fw/cgzn.htm
#### 终章 · 白马咖啡馆
- 地址：上海市虹口区长阳路67号（纪念馆对面）｜距上一站 ≈50 m（隔街相对）
- 坐标 GCJ-02：`121.509116, 31.254579`｜WGS84：`121.5047, 31.2566`（est）｜营业时间字段：`09:00-17:00`
- 故事（story_text，273字）：▌CUA-HK-39「方舟」｜残档 07/7｜结案报告（草稿）· 权限 L7
【史实】1939年，从维也纳逃到上海的鲁道夫·莫斯伯格夫妇在今长阳路、临潼路口开了白马咖啡馆，提篮桥因此有了“小维也纳”之称。2009年原址小楼因道路拓宽被拆除，部分梁柱构件被保存；2015年按原样复建于长阳路67号，内部陈设参考了店主后人罗恩·克林格从悉尼带来的老照片。战后，莫斯伯格一家去了澳大利亚。
——
结案报告：
“异常来源：一个‘收留’名字的区域。
收集证据：签证／S／汤／边界／对角／名字。
收留者身份：█████。”
档案员K：“最后一栏，由你来填。”
- 玩法：validation_method=5（GPS到达） + 高级玩法 `branch/leaderboard`；模板标题 `CY-T1-07 分支结局：白马咖啡馆`
  - advanced_config_json：

```json
{"schemaVersion": 1, "branch": {"enabled": true, "startStepId": "decision", "steps": [{"id": "decision", "title": "结案报告 · 最终分类", "body": "1945年8月，限定居住区解除。受访者家庭（游戏设定）需要一条去向，档案也需要一个分类。你签署：", "terminal": false, "options": [{"id": "stay", "label": "分类A：留在上海，继续登记新来的人", "nextStepId": "e_stay", "score": 20}, {"id": "usa", "label": "分类B：远行，像布卢门撒尔一样去美国读书", "nextStepId": "e_usa", "score": 15}, {"id": "aus", "label": "分类C：迁徙，像莫斯伯格一家一样去澳大利亚", "nextStepId": "e_aus", "score": 15}]}, {"id": "e_stay", "title": "分类A · 登记员", "body": "档案状态：开放。你把名字抄进册子。【史实】真实的名单墙如今立在长阳路62号纪念馆，仍在增补。", "terminal": true, "outcomeCode": "stay", "outcomeLabel": "登记员", "options": []}, {"id": "e_usa", "title": "分类B · 远行者", "body": "档案状态：外延。【史实】真实的布卢门撒尔1969年起多次回到舟山路，2015年在名单墙上找到了自己和家人的名字。", "terminal": true, "outcomeCode": "usa", "outcomeLabel": "远行者", "options": []}, {"id": "e_aus", "title": "分类C · 白马守护者", "body": "档案状态：复建。【史实】真实的白马咖啡馆，参考店主后人从悉尼带来的老照片，按原样复建。", "terminal": true, "outcomeCode": "aus", "outcomeLabel": "白马守护者", "options": []}]}, "leaderboard": {"enabled": true, "metric": "SCORE", "scope": "TOPIC", "limit": 50}}
```
- 碎片（fragment_text）：▌档案 CUA-HK-39 已结案
收留者身份解密为：“不是一艘船，是人——签证官、楼主、救济员、邻居，和不停寻找名字的后来者。”
🔑 关键词7/7「选择」
档案重新分类：庇护型 / 蔓生级 → 非异常 · 人类行为。
名单增长机制：持续的史料考证。档案保持开放。
- 来源：https://www.shhkjrm.com/fw/zgjs.htm ｜ http://www.xinhuanet.com/politics/2015-05/07/c_1115207009.htm ｜ https://www.thepaper.cn/newsDetail_forward_1366940 ｜ https://www.shhk.gov.cn/xwzx/002008/002008040/20221011/81d88b46-b543-49b6-81dc-f5b37dc5a9c3.html ｜ http://xmwb.xinmin.cn/resfile/2015-08-27/A08/A08.pdf

### 支线 · 玩法彩蛋（required=0）
#### 支线 · 给下船的人留一句话
- 地址：上海市虹口区北苏州路400号外
- 坐标 GCJ-02：`121.483975, 31.243629`｜WGS84：`121.47947, 31.24558`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，68字）：▌CUA-HK-39「方舟」｜残档 B1/7｜附录 · 匿名留言簿
档案员K：“留言簿上的字迹来自不同年代。添一句给下一位到达者的话。”
- 玩法：validation_method=5（GPS到达） + 高级玩法 `note`；模板标题 `CY-T1-B1 留言：苏州河边`
  - advanced_config_json：

```json
{"schemaVersion": 1, "note": {"enabled": true, "title": "写给1939年下船的人", "prompt": "如果你能对刚到河滨大楼的难民说一句话，你会说什么？", "maxLength": 40, "presets": ["欢迎来到上海", "这里会有人帮你", "先吃口热的"], "showPrevious": 3}}
```
#### 支线 · 走向提篮桥
- 地址：上海市虹口区北苏州路400号外（出发点）
- 坐标 GCJ-02：`121.483975, 31.243629`｜WGS84：`121.47947, 31.24558`（osm）｜营业时间字段：`06:00-20:00`
- 故事（story_text，74字）：▌CUA-HK-39「方舟」｜残档 B2/7｜附录 · 步行测距
接待站至提篮桥约3公里。请记录步数。档案局需要知道，一个人要走多少步才算“到达”。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `steps`；模板标题 `CY-T1-B2 计步：从接待站到弄堂`
  - advanced_config_json：

```json
{"schemaVersion": 1, "steps": {"enabled": true, "goal": 3000, "eyebrow": "河滨大楼 → 提篮桥", "xp": 20}}
```
#### 支线 · 三种文字
- 地址：上海市虹口区霍山路118号霍山公园内
- 坐标 GCJ-02：`121.511109, 31.253432`｜WGS84：`121.5067, 31.25546`（osm）｜营业时间字段：`06:00-18:00`
- 故事（story_text，70字）：▌CUA-HK-39「方舟」｜残档 B3/7｜附录 · 碑文影像
纪念碑用中文、英文、希伯来文三种文字记录了这片区域。请拍摄碑文，存入档案。
- 玩法：validation_method=2（拍照打卡）；模板标题 `CY-T1-B3 拍照打卡：纪念碑`
  - photo_require_desc：拍下霍山公园纪念碑碑文，画面中能看到中、英、希伯来三种文字；不拍其他游客。
- 来源：https://www.shhkjrm.com/fw/zgjs.htm
#### 支线 · 名单墙前的一分钟
- 地址：上海市虹口区长阳路62号上海犹太难民纪念馆
- 坐标 GCJ-02：`121.509236, 31.254128`｜WGS84：`121.50482, 31.25615`（osm）｜营业时间字段：`09:00-16:00`
- 故事（story_text，66字）：▌CUA-HK-39「方舟」｜残档 B4/7｜附录 · 静默协议
名单墙上有近两万个名字。执行静默协议：不拍照、不说话，阅读60秒。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `countdown`；模板标题 `CY-T1-B4 倒计时：静默一分钟`
  - advanced_config_json：

```json
{"schemaVersion": 1, "countdown": {"enabled": true, "kicker": "名单墙", "seconds": 60, "doneText": "谢谢你记住他们。"}}
```
- 来源：https://www.shhkjrm.com/zl/mdq.htm
#### 支线 · 生命签证
- 地址：上海市虹口区长阳路62号上海犹太难民纪念馆
- 坐标 GCJ-02：`121.509236, 31.254128`｜WGS84：`121.50482, 31.25615`（osm）｜营业时间字段：`09:00-16:00`
- 故事（story_text，75字）：▌CUA-HK-39「方舟」｜残档 B5/7｜附录 · 签证签发人
残档01中被涂黑的签发人姓名，可在纪念馆展厅中找到。他被称为“生命签证”的签发者。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `qa`；模板标题 `CY-T1-B5 问答：何凤山`
  - advanced_config_json：

```json
{"schemaVersion": 1, "qa": {"enabled": true, "mode": "PICK", "title": "1938—1940年，何凤山担任中国驻哪座城市的总领事？", "lead": "在展厅中找答案", "options": [{"id": "a", "label": "维也纳", "correct": true, "fb": "对。他在维也纳签发了大量去上海的签证。"}, {"id": "b", "label": "柏林", "fb": "不对，再看看展板。"}, {"id": "c", "label": "布拉格", "fb": "不对，再看看展板。"}, {"id": "d", "label": "罗马", "fb": "不对，再看看展板。"}], "maxTries": 3, "xp": 20}}
```
- 来源：https://cn.chinadaily.com.cn/a/202109/11/WS613bc655a3101e7ce9763033.html ｜ https://paper.people.com.cn/rmzk/html/2016-06/07/content_1685785.htm
#### 支线 · 扫码领一杯“维也纳”
- 地址：上海市虹口区长阳路67号白马咖啡馆外
- 坐标 GCJ-02：`121.509116, 31.254579`｜WGS84：`121.5047, 31.2566`（est）｜营业时间字段：`09:00-17:00`
- 故事（story_text，41字）：测试扫码打卡链路：请扫描本节点的打卡二维码（由测试员在出发前打印，不张贴在店内）。
- 玩法：validation_method=4（扫码打卡）；模板标题 `CY-T1-B6 扫码打卡：白马咖啡馆`
#### 支线 · 给你的咖啡起个名字
- 地址：上海市虹口区长阳路67号白马咖啡馆
- 坐标 GCJ-02：`121.509116, 31.254579`｜WGS84：`121.5047, 31.2566`（est）｜营业时间字段：`09:00-17:00`
- 故事（story_text，72字）：▌CUA-HK-39「方舟」｜残档 B7/7｜附录 · 物品登记
当年难民在这里重温维也纳的咖啡馆生活。为本次结案时桌上的这杯咖啡登记一个名称。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `diyName`；模板标题 `CY-T1-B7 命名：一杯咖啡`
  - advanced_config_json：

```json
{"schemaVersion": 1, "diyName": {"enabled": true, "title": "给这杯咖啡起个名字", "maxLength": 12, "suggestions": ["小维也纳", "1939的拿铁", "方舟一号"]}}
```

## 线路2《南京路上的四座高峰》——档案 CUA-NJ-17「高峰」：四座会互相长高的大楼
城市异常档案局 · 外勤档案（游戏设定）
档案编号：CUA-NJ-17「高峰」
档案类别：竞争型 / 游移级
你的身份：外勤调查员，初始权限 L1
特殊收容措施：调查员须自外滩向西逐站推进，每站核对一页残档。四个目标实体（编号 NJ-17-α 至 NJ-17-δ）禁止被单独观察——请务必站在其中一座的门口观察另一座。
描述：南京路上四座百货大楼。档案记录称它们“像彼此的影子一样相继出现，每一座都要比上一座更高、更新、更会招徕人群”。档案末页指向一处已不存在的地点，标注为“起源点”。
【游戏设定】“城市异常档案局”、档案编号、权限等级、调查员身份、受访者与档案员均为虚构；文中“异常”只是叙事装置。所有标【史实】的内容均为真实历史并附来源（见每站来源与《解密附录》）。
路线：汇丰银行大楼→和平饭店→福利→先施→永安→新新→大新→国际饭店→大光明→上海市历史博物馆→张园，约4.6公里。商场可只逛不买。历史博物馆周一闭馆，16:00停止入馆。测试版：EXPERIMENTAL，坐标待双机实走校准。

- 步行总长：约 4.6 km；建议时长 3 小时 30 分；完成勋章：南京路掌柜
- 坐标：GCJ-02（入库值）/ WGS84；`est` = 人工推估，误差可能 30–60 m，必须现场校准

### 主线（required=1，逐站解锁）
#### 序章 · 汇丰银行大楼（外滩12号）
- 地址：上海市黄浦区中山东一路10—12号（外部公共区域）
- 坐标 GCJ-02：`121.490681, 31.236026`｜WGS84：`121.4862, 31.238`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，305字）：▌CUA-NJ-17「高峰」｜残档 00/11｜资金来源核查 · 权限 L1
调查员须知：NJ-17 系列实体需要大量“养料”。档案局认为，第一批养料来自外滩。
——
【史实】外滩12号原汇丰银行大楼，1921年5月开工、1923年6月竣工，由公和洋行建筑师威尔逊设计，是外滩占地最多、立面最宽、体量最大的建筑，门厅穹顶有200多平方米的马赛克壁画。1956—1995年曾是上海市政府办公地；1998年6月23日起由浦发银行启用，修缮时重新发现了1955年被石灰覆盖的穹顶壁画。它与四大公司同属上海第一批优秀历史建筑。
——
核查记录：四条描述中有一条被篡改。篡改者在记录中留下了一个设计师的名字：█████。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `qa`；模板标题 `CY-T2-S0 多选题：外滩12号`
  - advanced_config_json：

```json
{"schemaVersion": 1, "qa": {"enabled": true, "mode": "PICK", "multi": true, "title": "关于外滩12号，下列哪些说法正确？（多选）", "lead": "可在门外观察，也可查看门口铭牌", "options": [{"id": "a", "label": "由公和洋行设计", "correct": true, "fb": "对，设计师为威尔逊。"}, {"id": "b", "label": "1923年竣工", "correct": true, "fb": "对，1923年6月竣工。"}, {"id": "c", "label": "是外滩体量最大的建筑", "correct": true, "fb": "对。"}, {"id": "d", "label": "由邬达克设计", "fb": "不对，邬达克设计的是国际饭店和大光明。"}], "maxTries": 3, "xp": 20}}
```
- 碎片（fragment_text）：▌解密完成｜权限 L1 → L2
被篡改的一条：“由邬达克设计”——不实。
档案员M（游戏设定）：“记住这个名字。篡改者往往会在后面的档案里再次出现。”
🔑 关键词1/10「资本」
📎 下一站坐标：南京路东端，沙逊大厦脚下。
- 来源：https://www.thepaper.cn/newsDetail_forward_25710672 ｜ https://news.qq.com/rain/a/20250611A023LU00 ｜ https://news.spdb.com.cn/about_spd/xwdt_1632/202309/t20230912_1118244.shtml ｜ https://fgj.sh.gov.cn/yxlsjz/20200331/fe7b4d6f35774b708a2c5122849065bd.html
#### 过站 · 和平饭店北楼
- 地址：上海市黄浦区南京东路20号和平饭店北楼外｜距上一站 ≈400 m（沿外滩人行道；OSRM 绕行值 829 m）
- 坐标 GCJ-02：`121.489565, 31.23911`｜WGS84：`121.48508, 31.24108`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，144字）：▌CUA-NJ-17「高峰」｜残档 01/11｜观测起点 · 权限 L2
【史实】和平饭店北楼（原沙逊大厦）脚下，就是南京路的东端。19世纪下半叶，最早的百货生意集中在离外滩不远的南京路、四川路口一带。
——
档案员M：“NJ-17 系列之前，这条街上有更早的一批‘前体’。去四川路口。”
- 玩法：validation_method=0（到点即完成）；模板标题 `CY-T2-00 到点即完成：南京路东端`
- 碎片（fragment_text）：▌到达确认｜权限 L2 → L3
🔑 关键词2/10「起点」
📎 前体编号：NJ-17-前01 至 前04。位置：南京东路四川中路口。
- 来源：https://finance.sina.cn/2020-09-13/detail-iivhuipp4126875.d.html
#### 前传 · 福利公司旧址
- 地址：上海市黄浦区南京东路、四川中路路口（今南京东路99号、114号一带）｜距上一站 ≈210 m
- 坐标 GCJ-02：`121.487722, 31.238486`｜WGS84：`121.48323, 31.24045`（osm-intersection）｜营业时间字段：`00:00-23:59`
- 故事（story_text，288字）：▌CUA-NJ-17「高峰」｜残档 02/11｜前体谱系 · 权限 L3
【史实】1848年英国商人霍尔在福州路开了福利洋行，后迁至南京路、四川路东南转角（今南京东路99号）；1855年霍尔茨入股，更名“Hall & Holtz”，中文名“福利”。它起初卖面包、洋酒，1892年改组为有限公司并经营环球百货，被称为上海第一家百货公司；1914年迁至路口西北转角（今南京东路114号）。福利、汇司、泰兴、惠罗合称“前四大”，全是外资。
——
谱系记录：前体四个，全部外资。此后，同一条街的西段出现了四个新实体，“像在模仿，又像在挑战”。其中出现于1920年以前的是：█████。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `compare`；模板标题 `CY-T2-01 时间轴对照：前四大与四大`
  - advanced_config_json：

```json
{"schemaVersion": 1, "compare": {"enabled": true, "prompt": "标出右侧所有在1920年以前开业的华资百货。", "left": {"label": "前四大·外资", "items": [{"id": "l1848", "time": "1848", "text": "霍尔在福州路创办福利洋行"}, {"id": "l1892", "time": "1892", "text": "福利改组为有限公司，经营环球百货"}, {"id": "l1904", "time": "1904", "text": "惠罗公司在上海设立分公司"}]}, "right": {"label": "四大·华资", "items": [{"id": "r1917", "time": "1917", "text": "先施公司开业"}, {"id": "r1918", "time": "1918", "text": "永安公司开业"}, {"id": "r1926", "time": "1926", "text": "新新公司开业"}, {"id": "r1936", "time": "1936", "text": "大新公司开业"}]}, "answer": ["r1917", "r1918"], "maxAttempts": 3, "xp": 20}}
```
- 碎片（fragment_text）：▌解密完成｜权限 L3 → L4
🔑 关键词3/10「前四大」
📎 两个新实体已定位：NJ-17-α（先施）与 NJ-17-β（永安），浙江路口，隔街对峙。档案员M：“它们至今仍互相看着。”
- 来源：https://finance.sina.cn/2020-09-13/detail-iivhuipp4126875.d.html
#### 第一峰 · 先施公司
- 地址：上海市黄浦区南京东路690号（浙江中路口，今上海时装商店）｜距上一站 ≈1.1 km
- 坐标 GCJ-02：`121.477516, 31.235823`｜WGS84：`121.47299, 31.23776`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，257字）：▌CUA-NJ-17「高峰」｜残档 03/11｜实体 NJ-17-α 观测报告 · 权限 L4
【史实】1917年10月20日，先施公司开业，是上海第一家由华人经营的大型环球百货公司。东南角带大钟的塔楼当年叫“摩星塔”；六七楼有先施乐园，京剧、杂技、魔术、电影都有，屋顶还有花园；楼里有东亚酒家和东亚旅馆。它首创商品明码标价、开具发票，还大胆雇用女营业员。今天，这栋楼是上海时装商店。
——
观测报告：α 体内部设有“乐园”。乐园的原型来源不明，标注为[见残档10]。
α 的铭文：“始创█████，统办环球货。”
- 玩法：validation_method=5（GPS到达） + 高级玩法 `qa`；模板标题 `CY-T2-02 填空：先施的口号`
  - hint1：档案员M：“明码标价——价格只有一个。”｜hint2：档案员M：“不讲价。三个字，第一个字是‘不’。”｜answer_reveal：（见玩法配置）
  - advanced_config_json：

```json
{"schemaVersion": 1, "mistakeTier": "easy", "qa": {"enabled": true, "mode": "TYPE", "title": "先施的口号：“始创＿＿＿，统办环球货”，请填入三个字。", "lead": "提示：和明码标价有关", "answerText": "不二价", "maxTries": 5, "xp": 20}}
```
- 碎片（fragment_text）：▌解密完成｜权限 L4 → L5
原文：“始创不二价，统办环球货。”
🔑 关键词4/10「不二价」
📎 警告：α 被观测时，对面的 β 会做出反应。请立即过街。
- 来源：https://mzj.sh.gov.cn/lnb-wsws/20230904/dd7b73152aa04fc2930214b602980188.html ｜ https://www.thepaper.cn/newsDetail_forward_8520355
#### 第二峰 · 永安公司
- 地址：上海市黄浦区南京东路635号（永安百货）｜距上一站 ≈100 m（过街；OSRM 272 m）
- 坐标 GCJ-02：`121.478303, 31.23487`｜WGS84：`121.47378, 31.23681`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，244字）：▌CUA-NJ-17「高峰」｜残档 04/11｜实体 NJ-17-β 观测报告 · 权限 L5
【史实】1918年9月5日，郭氏兄弟创办的永安公司在南京路635号开业，老楼六层，附设旅馆、酒楼、舞厅、游乐场和屋顶花园，与先施隔街对垒。后来永安在旁边建起更高的永安新厦，第七层是著名的“七重天”；永安在新旧楼之间架起两座封闭式天桥，让顾客往来不受日晒雨淋，今天依然可见。
——
观测报告：β 在α出现约一年后出现，随后“长出了第二具躯体”，并以 █████ 连接两者。请拍摄该结构作为证据。
- 玩法：validation_method=2（拍照打卡）；模板标题 `CY-T2-03 拍照打卡：永安天桥`
  - photo_require_desc：拍到连接永安老楼与新永安大楼的封闭式跨街天桥（不拍行人正脸）。
- 碎片（fragment_text）：▌解密完成｜权限 L5 → L6
原文：“并以两座封闭式天桥连接两者。”
🔑 关键词5/10「天桥」
📎 下一份残档是一段截获的广播记录，来源：NJ-17-γ，五楼。
- 来源：https://www.thepaper.cn/newsDetail_forward_3738563 ｜ https://www.thepaper.cn/newsDetail_forward_22798040
#### 第三峰 · 新新公司
- 地址：上海市黄浦区南京东路720号（今上海市第一食品商店）｜距上一站 ≈240 m
- 坐标 GCJ-02：`121.476689, 31.235605`｜WGS84：`121.47216, 31.23754`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，260字）：▌CUA-NJ-17「高峰」｜残档 05/11｜广播截获记录 · 权限 L6
【史实】1926年1月23日，刘锡基、李敏周等人创办的新新公司在南京路720号开业，名字取自“苟日新，日日新，又日新”，主打推销国货。1949年5月25日凌晨，新新公司的地下党组织控制了大楼五楼的凯旋电台，向全市播出“我们上海人民解放了！”，随后连续广播三天，被称为“上海解放的第一声”。今天这里是上海市第一食品商店。
——
截获记录：信号源：γ 五楼。持续：三日。原文：“██████████！”
档案员M：“信号正在衰减。20秒内转写。”
- 玩法：validation_method=5（GPS到达） + 高级玩法 `typeIn`；模板标题 `CY-T2-04 限时打字：解放的第一声`
  - advanced_config_json：

```json
{"schemaVersion": 1, "typeIn": {"enabled": true, "title": "打出那句广播", "target": "我们上海人民解放了", "seconds": 20, "tries": 3, "xp": 20}}
```
- 碎片（fragment_text）：▌解密完成｜权限 L6 → L7
原文：“我们上海人民解放了！”
档案员M：“这一条不归异常档案管。归入城市正史。”
🔑 关键词6/10「第一声」
📎 最后一个实体 NJ-17-δ：西藏中路口。报告称它“会让地面自己移动”。
- 来源：https://mzj.sh.gov.cn/lnb-wsws/20231204/c6c7b33e371847178599e3b641a9a440.html ｜ https://xmwb.xinmin.cn/html/2019-05/26/content_20_2.htm ｜ https://www.thepaper.cn/newsDetail_forward_11845487 ｜ https://shhongse.fudan.edu.cn/info/1019/1467.htm
#### 第四峰 · 大新公司
- 地址：上海市黄浦区南京东路830号（西藏中路口，今第一百货商业中心）｜距上一站 ≈210 m
- 坐标 GCJ-02：`121.474805, 31.23512`｜WGS84：`121.47027, 31.23705`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，269字）：▌CUA-NJ-17「高峰」｜残档 06/11｜实体 NJ-17-δ 观测报告 · 权限 L7
【史实】1936年1月10日，大新公司在南京路830号开业，后来居上成为“四大公司”之冠。它设地下商场，一楼装了载客自动扶梯，每梯每小时可运客四千人，被称为远东第一家安装自动扶梯的百货商场。开业当天《申报》头版整版广告写着“设备电动扶梯无劳跨步”。据报道，为控制人流，大新临时发售乘扶梯的门票，凭票购物还能抵扣。1953年，上海市第一百货商店迁入。
——
观测报告：δ 是四个实体中最晚、也最强的一个。进入其移动地面需支付通行费：█████。
- 玩法：validation_method=8（估数题） + 高级玩法 `estimate`；模板标题 `CY-T2-05 估数：坐一次扶梯多少钱`
  - advanced_config_json：

```json
{"schemaVersion": 1, "estimate": {"enabled": true, "title": "1936年，乘一次大新公司自动扶梯的门票是几角钱？", "unit": "角", "min": 0, "max": 20, "answer": 4, "tolerance": 1, "reveal": "四角（四毛）。票款可在商场购物时抵扣。", "xp": 20}}
```
- 碎片（fragment_text）：▌估算已记录｜权限 L7 → L8
🔑 关键词7/10「扶梯」
📎 四个实体已全部观测。但残档00里那个“篡改者”的名字，在西藏路对面出现了两次。档案员M：“去测量他。”
- 来源：https://www.thepaper.cn/newsDetail_forward_1712076 ｜ http://xmwb.xinmin.cn/html/2016-04/07/content_26_5.htm ｜ https://thepaper.cn/newsDetail_forward_23581041
#### 原点 · 国际饭店
- 地址：上海市黄浦区南京西路170号｜距上一站 ≈530 m
- 坐标 GCJ-02：`121.471535, 31.233678`｜WGS84：`121.46699, 31.2356`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，229字）：▌CUA-NJ-17「高峰」｜残档 07/11｜测量基准点记录 · 权限 L8
【史实】国际饭店1934年建成，由匈牙利籍建筑师邬达克设计，地上高83.8米，曾是“远东第一高楼”。1950年11月，上海市地政局以楼顶中心旗杆为原点，建立上海城市平面坐标系；大堂旗杆正下方设有“上海城市坐标原点（副点）”标志。2021年启用“上海2000坐标系”，原点仍保留在国际饭店。
——
基准点记录：全城的每一个坐标都从这里量起，包括本档案中的每一站。方向：█████。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `compass`；模板标题 `CY-T2-06 罗盘：城市坐标原点`
  - advanced_config_json：

```json
{"schemaVersion": 1, "compass": {"enabled": true, "kicker": "城市坐标原点", "bearing": 0, "tolerance": 20, "holdSeconds": 3, "hint": "慢慢转动手机，让指针对准正北。"}}
```
- 碎片（fragment_text）：▌校准完成｜权限 L8 → L9
原文：“方向：正北。”
档案员M：“篡改者不是坏人。他只是在这条街上留下了两座楼——一座最高，一座最摩登。”
🔑 关键词8/10「原点」
📎 第二座就在隔壁。档案中附有一张它的旧照，三处被红笔圈出。
- 来源：https://www.gzw.sh.gov.cn/shgzw_zxzx_gqdt/20231208/b8e95f2a8268440ea78cb67b0f8fd668.html ｜ https://ghzyj.sh.gov.cn/hyxw/20201231/da24f7f6f2fe4da29bcb3e34e998220a.html ｜ http://www.cnr.cn/shanghai/tt/20210216/t20210216_525414865.shtml
#### 光影 · 大光明电影院
- 地址：上海市黄浦区南京西路216号｜距上一站 ≈100 m
- 坐标 GCJ-02：`121.471076, 31.232929`｜WGS84：`121.46653, 31.23485`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，195字）：▌CUA-NJ-17「高峰」｜残档 08/11｜影像证物 · 权限 L9
【史实】1928年，原卡尔登舞厅被改建为大光明影戏院；1931年停业后易主，1932年拆除旧院，请邬达克重新设计，1933年6月14日以“大光明大戏院”之名开业，被誉为“远东第一影院”。
——
证物说明：照片中三处细节被涂成 █████。请对照实物逐一找出。档案员M：“这是整份档案里唯一一张‘会动的照片’的外壳。”
- 玩法：validation_method=10（找东西） + 高级玩法 `hiddenObject`；模板标题 `CY-T2-07 找东西：大光明立面`
  - advanced_config_json：

```json
{"schemaVersion": 1, "hiddenObject": {"enabled": true, "title": "在大光明立面上找3处细节", "hint": "先找高处，再找入口。", "imageUrl": "/profile/chengyin-test/grand-cinema-facade.webp", "maxTries": 5, "hotspots": [{"id": "tower", "label": "半透明灯柱/塔楼", "x": 0.78, "y": 0.22, "r": 0.08}, {"id": "sign", "label": "“大光明”招牌", "x": 0.45, "y": 0.4, "r": 0.08}, {"id": "door", "label": "主入口", "x": 0.4, "y": 0.78, "r": 0.08}], "xp": 20}}
```
- 碎片（fragment_text）：▌解密完成｜权限 L9 → L10
🔑 关键词9/10「放映」
📎 整份档案需要一次最终核对，地点：南京西路325号，钟楼。核对超时，档案将被重新封存。
- 来源：https://www.thepaper.cn/newsDetail_forward_4411237 ｜ https://www.thepaper.cn/newsDetail_forward_16783397
#### 博物馆 · 上海市历史博物馆
- 地址：上海市黄浦区南京西路325号（原跑马总会大楼）｜距上一站 ≈270 m
- 坐标 GCJ-02：`121.471166, 31.230697`｜WGS84：`121.46662, 31.23262`（osm）｜营业时间字段：`09:00-16:00`
- 故事（story_text，216字）：▌CUA-NJ-17「高峰」｜残档 09/11｜限时归档程序 · 权限 L10
【史实】这里原是上海跑马总会，1933年动工、1934年建成，钟楼高约53米。2018年3月26日，修缮后的大楼作为上海市历史博物馆（上海革命历史博物馆）开放，周二至周日9:00—17:00，16:00停止入馆，免费、免预约。
——
归档程序：八条证据，四个实体（α 先施／β 永安／γ 新新／δ 大新）。限时300秒。错误归档的证据将被[数据删除]。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `classify/timer/leaderboard`；模板标题 `CY-T2-08 分类挑战：四座高峰`
  - advanced_config_json：

```json
{"schemaVersion": 1, "classify": {"enabled": true, "prompt": "把每条线索放回它所属的百货公司。", "bins": [{"id": "sincere", "label": "先施"}, {"id": "wingon", "label": "永安"}, {"id": "sunsun", "label": "新新"}, {"id": "dasun", "label": "大新"}], "items": [{"id": "i1", "label": "1917年开业"}, {"id": "i2", "label": "摩星塔"}, {"id": "i3", "label": "1918年开业"}, {"id": "i4", "label": "七重天与天桥"}, {"id": "i5", "label": "1926年开业"}, {"id": "i6", "label": "凯旋电台"}, {"id": "i7", "label": "1936年开业"}, {"id": "i8", "label": "自动扶梯"}], "answer": {"i1": "sincere", "i2": "sincere", "i3": "wingon", "i4": "wingon", "i5": "sunsun", "i6": "sunsun", "i7": "dasun", "i8": "dasun"}, "xp": 30}, "timer": {"enabled": true, "durationSeconds": 300, "timeoutResult": "EXPIRED"}, "leaderboard": {"enabled": true, "metric": "ELAPSED_TIME", "scope": "TOPIC", "limit": 50}}
```
- 碎片（fragment_text）：▌归档完成｜用时已上榜
🔑 关键词10/10「归档」
⚠️ 系统提示：α 与 β 的“乐园”条目指向同一处原型，该原型在现存城市中不可见。档案员M：“残档10。起源点。它在西边，一座已经散场的花园。”
- 来源：https://www.thepaper.cn/newsDetail_forward_2042037 ｜ http://sh.chinadaily.com.cn/2018-03/26/content_35918460.htm ｜ https://www.meet-in-shanghai.net/cn/revolutionary-memorial-site/shanghai-history-museum-shanghai-museum-of-revolutionary-history-176145/ ｜ https://whlyj.sh.gov.cn/wbzx/20260522/c7cb299f767747ba863244b53fcf7c41.html
#### 尾声 · 张园
- 地址：上海市静安区威海路590弄张园（石门一路、威海路、茂名北路、吴江路之间，开放街区）｜距上一站 ≈1.4 km
- 坐标 GCJ-02：`121.460626, 31.228472`｜WGS84：`121.45605, 31.23037`（osm-landuse）｜营业时间字段：`09:00-21:00`
- 故事（story_text，289字）：▌CUA-NJ-17「高峰」｜残档 10/11｜起源点调查 · 权限 L10
【史实】1882年，无锡富商张叔和购地建园，取名“张氏味莼园”，1885年4月17日对外开放，成为当时上海最大的公共活动场所，被誉为“海上第一名园”。1886年10月6日，园中试燃电灯数十盏，游人“咸以为奇观”；1892年建成的安垲第楼可容千人，是当时上海最高建筑。张园后来被分块出售，建起一片石库门里弄；2018年启动保护性征收，2022年底西区重新开放。
——
起源点记录：电灯、千人大楼、游乐——四个实体所具备的特征，最早都出现在这里。起源点名称：█████。请拍摄一处石库门细节作为结案证物。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `qa`；模板标题 `CY-T2-09 拍照问答：海上第一名园`
  - advanced_config_json：

```json
{"schemaVersion": 1, "qa": {"enabled": true, "mode": "SHOT", "title": "拍一张张园里你认为最“摩登”的石库门细节，并说说它让你想起哪家百货公司。", "lead": "不拍居民正脸与室内", "shotNote": "拍门头、山墙、门牌或弄堂口即可", "maxTries": 3, "xp": 20}}
```
- 碎片（fragment_text）：▌档案 CUA-NJ-17 已结案
起源点名称解密：张园。
结案结论：四个实体之间不存在异常竞争——存在的是商业竞争；“乐园”的原型来自一座19世纪的花园。
档案重新分类：竞争型 / 游移级 → 非异常 · 城市记忆。
十个关键词：资本／起点／前四大／不二价／天桥／第一声／扶梯／原点／放映／归档。
- 来源：https://www.jingan.gov.cn/rmtzx/003008/003008006/20210706/29c2369d-d62b-43ef-857e-d05210c24177.html ｜ https://www.jingan.gov.cn/rmtzx/003008/003008006/20240129/a324b454-0bec-4020-85ac-32a1e27500a4.html ｜ https://www.jingan.gov.cn/rmtzx/003008/003008006/20230729/c228430c-a0f9-47f1-8f7a-5bb73f78a330.html ｜ https://www.jingan.gov.cn/rmtzx/003008/003008006/20240612/086ad7b8-b667-48b3-b259-114056e6994b.html

### 支线 · 玩法彩蛋（required=0）
#### 支线 · 先施乐园节目单
- 地址：上海市黄浦区南京东路690号外
- 坐标 GCJ-02：`121.477516, 31.235823`｜WGS84：`121.47299, 31.23776`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，65字）：▌CUA-NJ-17「高峰」｜附录 · α 乐园节目单｜先施乐园设有京剧、地方戏、杂技、魔术、电影等专场。随机抽取一张节目单存档。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `random`；模板标题 `CY-T2-B1 盲盒：今晚看什么`
  - advanced_config_json：

```json
{"schemaVersion": 1, "random": {"enabled": true, "deckName": "先施乐园节目单", "drawCount": 1, "items": [{"id": "opera", "label": "京剧专场", "weight": 1, "content": "今晚先施乐园京剧专场。"}, {"id": "acro", "label": "杂技专场", "weight": 1, "content": "今晚杂技专场。"}, {"id": "magic", "label": "魔术专场", "weight": 1, "content": "今晚魔术专场。"}, {"id": "film", "label": "电影专场", "weight": 1, "content": "今晚电影专场。"}]}}
```
- 来源：https://www.thepaper.cn/newsDetail_forward_8520355
#### 支线 · 老楼还是新楼
- 地址：上海市黄浦区南京东路635号外
- 坐标 GCJ-02：`121.478303, 31.23487`｜WGS84：`121.47378, 31.23681`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，52字）：▌CUA-NJ-17「高峰」｜附录 · β 双体观测顺序｜永安有一老一新两栋楼。抛硬币决定先观测哪一具。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `coinFlip`；模板标题 `CY-T2-B2 抛硬币：永安两栋楼`
  - advanced_config_json：

```json
{"schemaVersion": 1, "coinFlip": {"enabled": true, "kicker": "永安两栋楼", "heads": {"label": "老楼", "action": "站到635号老楼门口，找一处你认为最老的建筑细节"}, "tails": {"label": "新楼", "action": "抬头找到新永安大楼顶部的七重天"}}}
```
- 来源：https://www.thepaper.cn/newsDetail_forward_3738563
#### 支线 · 南京路城市签
- 地址：上海市黄浦区南京东路720号外
- 坐标 GCJ-02：`121.476689, 31.235605`｜WGS84：`121.47216, 31.23754`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，65字）：▌CUA-NJ-17「高峰」｜附录 · γ 命名来源｜新新公司的名字来自“苟日新，日日新，又日新”。抽取一支城市签，记录当日读数。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `dailySign`；模板标题 `CY-T2-B3 城市签：新新`
  - advanced_config_json：

```json
{"schemaVersion": 1, "dailySign": {"enabled": true, "poems": [["苟日新", "日日新", "又日新"], ["始创不二价", "统办环球货"], ["无劳跨步", "四时如春"]], "signer": "南京路", "sealText": "新新"}}
```
- 来源：https://mzj.sh.gov.cn/lnb-wsws/20231204/c6c7b33e371847178599e3b641a9a440.html ｜ https://www.thepaper.cn/newsDetail_forward_1712076
#### 支线 · 扶梯十秒
- 地址：上海市黄浦区南京东路830号外
- 坐标 GCJ-02：`121.474805, 31.23512`｜WGS84：`121.47027, 31.23705`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，58字）：▌CUA-NJ-17「高峰」｜附录 · δ 移动地面计时｜大新的扶梯每小时能运四千人。不看屏幕，凭感觉停在10秒整。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `stopwatch`；模板标题 `CY-T2-B4 精准停表：十秒`
  - advanced_config_json：

```json
{"schemaVersion": 1, "stopwatch": {"enabled": true, "kicker": "扶梯十秒", "targetSeconds": 10, "toleranceMs": 500, "tries": 3}}
```
- 来源：https://www.thepaper.cn/newsDetail_forward_1712076
#### 支线 · 从原点出发
- 地址：上海市黄浦区南京西路170号外
- 坐标 GCJ-02：`121.471535, 31.233678`｜WGS84：`121.46699, 31.2356`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，51字）：▌CUA-NJ-17「高峰」｜附录 · 原点随机观测｜全上海的坐标从这里量起。掷骰子领取一项观测任务。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `diceRoll`；模板标题 `CY-T2-B5 掷骰子：原点任务`
  - advanced_config_json：

```json
{"schemaVersion": 1, "diceRoll": {"enabled": true, "kicker": "原点任务", "mode": "d6", "diceCount": 1, "faces": ["向北看：找一处1930年代的建筑", "向南看：数一数人民公园的入口", "向东看：找到第一百货的招牌", "向西看：找到大光明的灯柱", "抬头：估计国际饭店有多少层", "低头：找一块写着路名的铭牌"]}}
```
#### 支线 · 明天来看一场电影
- 地址：上海市黄浦区南京西路216号
- 坐标 GCJ-02：`121.471076, 31.232929`｜WGS84：`121.46653, 31.23485`（osm）｜营业时间字段：`00:00-23:59`
- 故事（story_text，54字）：▌CUA-NJ-17「高峰」｜附录 · 延时观测｜今日仅观测大光明外观。24小时后返回，观测门厅或一场放映。
- 玩法：validation_method=5（GPS到达） + 高级玩法 `slowTask`；模板标题 `CY-T2-B6 跨日任务：大光明`
  - advanced_config_json：

```json
{"schemaVersion": 1, "slowTask": {"enabled": true, "title": "明天再来一次大光明", "startLabel": "今天先看外观", "waitHint": "一天后回来解锁下一步", "unlockLabel": "我回来了", "unlockText": "欢迎回来。走进门厅，看看邬达克设计的楼梯与灯光；看电影是可选项。", "waitDays": 1}}
```
- 来源：https://www.thepaper.cn/newsDetail_forward_16783397

## (c) 功能覆盖表

| 功能 | 测试位置 |
|---|---|
| vm0 到点即完成 | T2 和平饭店过站 |
| vm1 文字验证 + hint1/hint2/answer_reveal + 解谜分 100/70/40/0 | T1 河滨大楼 |
| vm2 拍照打卡 | T2 永安；T1 支线 霍山公园 |
| vm3 选项验证 + 提示阶梯 | T1 JDC |
| vm4 扫码打卡（checkin_code） | T1 支线 白马 |
| vm5 GPS 到达（50m） | 两条线大部分节点 |
| vm8 估数 | T2 大新 |
| vm10 找东西 | T2 大光明（⚠需上传图片并重标热区） |
| profile 建档 + effects ADD_TAG | T1 和平饭店 |
| match / sort / timeWindow | T1 霍山公园 / 纪念馆 |
| photoCheck CARD 拍物成卡 | T1 舟山路59号 |
| branch(outcomeCode) + leaderboard SCORE | T1 白马 |
| note / steps / countdown / qa PICK / diyName | T1 支线 |
| qa PICK 多选(multi) / compare / qa TYPE + mistakeTier / typeIn / compass / qa SHOT 拍照问答 | T2 汇丰 / 福利 / 先施 / 新新 / 国际饭店 / 张园 |
| classify + timer 300s + leaderboard ELAPSED_TIME | T2 历史博物馆 |
| random / coinFlip / dailySign / stopwatch / diceRoll / slowTask | T2 支线 |
| 章节 required=1/0、unlock_after_node_id 线性解锁、business_time、fragment_text、finish_medal_name、xp_value | 两条线 |

**没覆盖的功能和原因**
- vm6 偏好题组：preference_json 的结构这次没在代码里查到，不敢瞎填。
- vm7 传感器挑战：只有 App 支持（`migration_app_sensor_template_fields_20260821.sql`），小程序测不了。
- vm9 pricePair、blindTaste、silentOrder：要商家商品或真实消费，没法零配置。
- reaction、ballShake、quietHold：有 F-PK-1 P0 缺陷，必然失败。可以单独做回归节点验证修复。
- shout：要求在公共街区、纪念空间大声喊，不合适。
- multiplayer、roleViews、组队：只支持活动场次 + play_team，自玩主题跑不了。
- scan(TEXT/VOICE/IMAGE/OVERLAY/AR)、musicCorner、album、audio_url、story_img：都要先上传音频、图片、识别图或 .glb 素材，还要查清扫码内容的匹配规则。素材到位后可以直接加进支线。
- predict：要运营到揭晓时间录入结果。
- check、variants、requires：要先在主题里注册状态键或旅程规则（发布期对账），字段位置这次没查清。effects 只在 profile 里用了 tag，发布时如果报“键未注册”，就先删掉 effects。

## (e) 网上查不到、需要现场核实的点
1. 坐标：标 `est` 的点（JDC 霍山路119–121号、舟山路59号、白马咖啡馆长阳路67号）是按 OSM 街道推估的，其余点也要按种子要求做 GCJ-02 双机实走。另外，步行距离里有几段是 OSRM 路网缺边算出来的（霍山公园→舟山路59号显示 666 m，实际约 150 m），已人工修正，需要实走确认。
2. 霍山公园纪念碑原文到底是“东止”还是“东至”：权威媒体两种都有引用，题目已经改成方向连线，避开了这个字。
3. 先施“摩星塔”是否仍在、塔上的钟是否还在；永安两座天桥的确切位置（跨的是哪条街或弄），以及能不能清楚拍到。
4. 白马咖啡馆的营业时间（官网没有写，现在先填了 09:00–17:00）；舟山路59号铭牌的具体样式和位置。
5. 和平饭店北楼外有没有合适的安全驻足点（南京东路口人流很大）。国际饭店大堂的“原点副点”标志非住客能不能进去看。
6. 大光明立面的找东西图片：要现场拍摄或使用有授权的图片，再重标 hotspots 的 x/y。
7. 历史博物馆、犹太难民纪念馆在测试当天有没有临时闭馆。两馆都是周一闭馆、16:00 停止入场（官网 2026 年信息）。
8. （线路2加长新增）汇丰→和平饭店段 OSRM 给出 829 m 绕行，按外滩西侧人行道估约 400 m，需实走；外滩12号门前驻足点与浦发营业时间（非必须入内）。
9. 张园坐标取 OSM 地块中心（landuse），需改到实际开放入口（威海路590弄或吴江路/茂名北路口）；张园开放时段（填 09:00–21:00 为估值）与西区/东区开放范围。
