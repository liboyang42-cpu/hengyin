# 小程序玩家组队功能 · 实现方案调研(2026-08-20)

> 结论先行:**组队 = 「组局模型」(队伍挂在活动场次上),不是拼团模型**。建两张新表(`play_team` + `play_team_member`),状态机与并发抄 CRMEB/music-city 的验证过的做法,邀请链路走带参转发 + scene 短码,队伍聊天复用现有 IM 群聊(`TYPE_GROUP=4` 已预留)。**绝不复活已下线的成团自动散团引擎**(2026-07-15 裁决)。

---

## 0. 既有裁决约束(vault)

1. **成团引擎已主动下线**(`成团下线_多票整单退_设计_2026-07-15.md`):`oms_ticket` 的 `min_people/team_status/team_size` 列保留但停用,`AutoRefundTask` 已删、sys_job 注册行已被 `migration_team_decommission_job.sql` 删除。判定:自动散团+批量退款引擎是自造复杂度,5 个行业标杆无一存在。→ **本次组队不得重新引入「不成团自动退款引擎」;队伍失败与否由人(队长/主理人)决定,退款走既有订单级整单退链路。**
2. **组队是正牌需求**(探店日总纲 20260813 §2.2):城市定向 SKU 定义为「2–4 人一队、固定时间共同开场、团队成绩排名」。组队是这个 SKU 的前置能力。

## 1. 仓库现有基座(Explore 实勘,路径相对仓库根)

| 基座 | 现状 | 对组队的可用性 |
|---|---|---|
| `coop_invite`(邀约) | 纯 B 端握手(商家×俱乐部×发布者),带条款/保证金/分账,`ApiCoopController` | **不复用**数据模型(受邀方是 merchant/club 实体);但它的 status 枚举(0待确认…5已过期)和 `expireStaleInvites` 定时过期模式可照抄 |
| 三层活动模型 | `cms_topic` → `cms_activity`(场次)→ `oms_ticket`(票)→ `cms_registration`(一人一单) | 队伍应挂在 **`cms_activity`**(或 topic,视玩法)上;报名保持一人一单不动 |
| 带队场次运行时 | `club_lead_state` + `club_lead_checkin`(`ApiClubLeadController`,轮询 3~5s,封顶 20 人) | 「一场多人集合→行进→结算」的运行时先例;但成员集合是「本场已支付者」派生,非显式队伍 |
| 玩家↔玩家关系 | 只有单向关注 `ums_member_follow` + 拉黑 `im_block` + 俱乐部成员 `club_member`;**无好友、无玩家队伍表** | 队伍表需新建;拉黑校验直接复用 `im_block` |
| IM | 四表齐全,`ImConversation.TYPE_GROUP=4` 已定义,`getOrCreateClubGroup(biz_key="club_"+clubId)` 已有群聊实现 | **直接复用**:队伍群 `biz_key="team_"+teamId`,新增一个 getOrCreate 分支即可 |
| 分享链路 | 32 页有 `onShareAppMessage`,但 path 只带内容 id;唯一带邀请语义的是拉新 `?inviter=`;`PlayerWxacodeServiceImpl` 落地页写死 `pages/index/index`,scene 带 HMAC 签名 | 带参转发直接可用;小程序码需给 wxacode 服务加一个 team scene 类型 |
| 报名/支付 | `/api/registration/quote → create`,`parent_order_no` 是「一人买多张」不是「多人一队」 | 组队与买票解耦:**入队不动支付链路**,各自买各自的票 |

## 2. 外部调研要点(一手来源)

### 微信官方能力
- **带参转发**:`onShareAppMessage` 返回 `path` 携带 teamId,是主邀请链路(官方分享文档)。
- **群能力**:`wx.getShareInfo` 自基础库 2.17.3 起被 **`wx.getGroupEnterInfo`** 替代(解密得 `opengid/group_openid/chat_type`);openGId 只能做「同群可见」的展示增强,**不能当组队主键**。新代码禁用 getShareInfo。
- **小程序码**:`wxacode.getUnlimited` scene 上限 **32 可见字符**、不支持 `%`/中文,page 必须是已发布页 → teamId 用短码,格式如 `t:<id>`。

### GitHub 开源实现(读过源码)
- **CRMEB-Java 拼团**(`eb_store_pink`):不建团表,**单表自引用**(`kId=0`=团长,团员挂团长 id),status `1进行中/2成/3败`;定时 `detectionStatus()` 扫过期团;**支付回调二次校验**——回调时团满不退款而是自动转为新开一团。
- **lilishop 拼团**:团寄生在订单 `parentOrderSn` 上 + 延时消息触发到期检查。证明「团可以不建表」,但城瘾队伍有社交属性(队名/审核/聊天),不学这条。
- **music-city(排至城,RuoYi 系,与城瘾同基座)**:**最贴近城瘾的参考**。`pzc_activity_group`(队伍挂 activityId,含 title/约定时间地点/AA 或请客/可见性)+ `pzc_activity_group_apply`(申请表,applyStatus `0申请中→1通过待确认→2进行中→3结束`,带申请留言、到达确认、保证金)。**陌生人加入走申请-审核,两表分离。**
- **Colyseus room server**:`maxClients` 满员自动 lock、退员解锁;**seat reservation**(加入先占位,TTL 超时释放)专治「加入→支付」窗口期的名额竞态。

### 竞品形态
- **Meetup**:满员自动 waitlist,退出自动递补;**收费活动不开 waitlist**(候补阶段不收钱,递补成功再拉支付)。
- **小红书找搭子**:申请制加群;类目+城市是陌生人组队一级筛选;队伍卡片可以当 feed 发。
- **拼团 vs 组局**:拼团挂 SKU、凑单拿低价、人满即止;组局挂「时间+地点+人」、有局长/审核/群聊。**探店日组队是组局模型。**未来若做「组满 N 人打折」,是组局之上叠价格规则,两层别混。

## 3. 推荐方案

### 3.1 数据模型(两张新表,③迁移)

```sql
-- play_team:玩家队伍(挂在场次/主题上)
CREATE TABLE play_team (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner_type TINYINT NOT NULL COMMENT '1=topic 2=activity(对齐 oms_ticket.owner_type)',
  owner_id BIGINT NOT NULL,
  leader_member_id BIGINT NOT NULL,
  title VARCHAR(60) DEFAULT NULL COMMENT '队名/一句话招募语,走UGC机审',
  join_mode TINYINT NOT NULL DEFAULT 1 COMMENT '1=邀请制(仅链接/码可进) 2=公开申请制',
  max_members INT NOT NULL DEFAULT 4,
  joined_count INT NOT NULL DEFAULT 1 COMMENT '原子扣位用',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0招募中 1已锁定(满员) 2进行中 3已结束 4已解散',
  expire_time DATETIME DEFAULT NULL COMMENT '默认=场次开始时间',
  create_time DATETIME, update_time DATETIME,
  KEY idx_team_owner (owner_type, owner_id, status),
  KEY idx_team_expire (status, expire_time)
);

-- play_team_member:成员(含申请态)
CREATE TABLE play_team_member (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  team_id BIGINT NOT NULL,
  member_id BIGINT NOT NULL,
  role TINYINT NOT NULL DEFAULT 0 COMMENT '0成员 1队长',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '0待审核 1已加入 2已退出 3已移出 4已拒绝',
  apply_message VARCHAR(200) DEFAULT NULL,
  create_time DATETIME, update_time DATETIME,
  UNIQUE KEY uk_team_member (team_id, member_id),
  KEY idx_member_teams (member_id, status)
);
```

设计取舍:
- **不学 CRMEB 单表自引用**(它没有社交属性),学 music-city 两表,但把它的独立申请表合并成 member 表的 `status=0 待审核` 一个状态——少一张表,申请/成员本就是同一实体的两个阶段。
- **不加 min_people、不加自动退款**:成团判定 = 队长口头决定 / 场次照常进行,遵守 7-15 裁决。队伍与订单零外键耦合——退队不退票、退票不自动退队(首版;提示文案引导用户自己去退)。
- `joined_count` + 条件更新做原子扣位:`UPDATE play_team SET joined_count=joined_count+1, status=IF(joined_count+1>=max_members,1,status) WHERE id=? AND joined_count<max_members AND status=0`,affected=0 即满员,不先查后写。`uk_team_member` 防重复加入。退出/移出反向 -1 并把 status 1→0 解锁(Colyseus lock/unlock 模式)。
- 免费场景不需要 seat reservation(占位 TTL);**若未来入队绑定买票**再加占位,首版 YAGNI。

### 3.2 接口(一个新 Controller:`ApiPlayTeamController`,前缀 `/api/team`)

`create`(建队,报名过的人才能建)/ `info`(队伍详情,未登录可看)/ `join`(邀请链接直进 or 提交申请,按 join_mode 分流)/ `handle`(队长审核 0→1/4)/ `quit` / `kick` / `disband` / `my`(我的队伍)。队长退出 = 顺位移交最早加入者,队伍只剩 1 人时退出 = 解散。拉黑校验复用 `im_block`(被拉黑者不可申请)。

### 3.3 邀请链路(小程序端)

1. 队伍详情页 `onShareAppMessage` → `path=/pages/team/join/index?teamId=xx&inviter=<memberId>`(主链路,零后端成本)。
2. 线下海报/群外场景:`PlayerWxacodeServiceImpl` 加 team 类型 scene(`t:<teamId>`,32 字符内),落地页解析后跳 team/join。
3. 群战报(可选增强,不进首版):`showShareMenu({withShareTicket:true})` + `wx.getGroupEnterInfo` 拿 opengid,做「本群 N 人已入队」;**禁用 wx.getShareInfo**。

### 3.4 队伍聊天/通知

复用 IM:`ImServiceImpl` 加 `getOrCreateTeamGroup(teamId)`,`biz_key="team_"+teamId`,type=4(常量已存在,零 schema 改动);入队即入群、退队即退群、解散即 close(照抄 `getOrCreateClubGroup/leaveClubGroup/closeClubGroup` 三件套)。通知(审核通过/被移出/队伍解散)走既有 `notify/NotifyChannel` 站内信,不新增订阅消息模板(首版)。

### 3.5 超时清理

一个 sys_job:扫 `status IN (0,1) AND expire_time<=now` → 置 `3已结束`(场次开始即冻结),按 team 粒度幂等。**没有任何退款动作**——这正是它和被砍掉的 AutoRefundTask 的本质区别。

### 3.6 分期

- **P1(最小闭环)**:两表迁移③ + `/api/team` 七接口① + 建队/入队/队伍详情三页② + IM 群聊分支① + 超时 job(含 sys_job 注册③)。邀请制 only(join_mode=1),无公开广场。
- **P2**:公开申请制 + 活动详情页「队伍广场」入口 + scene 小程序码。
- **P3(城市定向 SKU 落地时)**:队伍接入 `club_lead_state` 式运行时(共同开场/团队进度/团队成绩),满员锁队后生成团队成绩单。

### 3.7 部署影响分类(按全局 §5)

①后端(Controller/Service/domain/mapper)→ 真部署;②小程序三页 + 分享 → 微信上传;③两表 DDL + sys_job 注册 SQL(`migration_*` 放 `chengyinhub-admin/sql/` 第一层)→ **合并前先走 chengyin-db-migrate 落库**;job 注册后跑 `audit_sys_job.py` 对账。

---

## 来源清单

- 微信分享/群能力/小程序码:developers.weixin.qq.com 官方文档(share、wx.getGroupEnterInfo、wx.getShareInfo 替代声明、getUnlimitedQRCode)
- CRMEB-Java:github.com/crmeb/crmeb_java(`StorePink.java`、`StorePinkServiceImpl.detectionStatus`、`OrderPayServiceImpl` L601-617)
- lilishop:github.com/lilishop/lilishop(`Pintuan.java`、`PromotionTimeTriggerExecutor.java`)
- yshop:github.com/guchengwuyue/yshopmall(`PinkEnum.java`)
- music-city:github.com/fl1906/music-city(`PzcActivityGroup.java`、`PzcActivityGroupApply.java`)
- Colyseus:docs.colyseus.io/room(生命周期、lock、seat reservation)
- Meetup waitlist:help.meetup.com(360003883411)
- 小红书找搭子:m.pedaily.cn/news/521273 等媒体报道(产品观察)
- 仓库现状:本仓 Explore 实勘(coop_invite / cms_activity / oms_ticket / club_lead_state / im_* / ums_member_follow / PlayerWxacodeServiceImpl 等,路径见 §1)
- 既有裁决:vault `成团下线_多票整单退_设计_2026-07-15.md`、`探店日方案_三玩法三身份商业模式总纲_20260813.md` §2.2
