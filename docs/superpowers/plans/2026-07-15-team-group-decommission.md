# 成团下线 · 轻量版(PR A)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 拆掉「成团自动散团引擎」(到期扫 `min_people`、自动置未成团、定时任务批量退款),保留轻量展示(「已报名 N 人」)+ 主理人手动取消;顺带把被成团门槛卡死的「出发前提醒」从成团态上摘下来。

**Architecture:** 以删除为主。成团引擎在生产**从未执行过一次**(见下「生产实测」),因此拆除**不改变任何线上行为**——这不是灰度下线,是移除死代码。字段/列一律**保留不物理删**(回滚/审计)。轻量版所需的两件东西均已存在:「已报名 N 人」由 `/api/club/topics` 的 `signupCount` 提供、主理人手动取消由 `cancelPaidRegistrationByOwner` 提供(它本就不读 `teamStatus`,不受本 PR 影响)。

**Tech Stack:** Java 8(`target=1.8`)· Spring · MyBatis · JUnit 5 + Mockito · Maven · 微信原生小程序(wxml/wxss/js)· Questify DS v2 组件(`components/cy/*`)

## Global Constraints

- **Java 8 API only** —— `target=1.8` 且无 `--release`,`List.of`/`Map.of`/`Optional.isEmpty`/`String.isBlank` 会本地过、CI 挂。
- **UI 走 Questify DS v2**(`components/cy/*`),逻辑对标 Eventbrite/Klook/Meetup/GetYourGuide,**不照抄标杆界面**。
- **不物理删列/字段/表** —— `min_people`/`team_status`/`team_size`/`signup_deadline`/`refund_rule` 全部保留,代码停读即可。
- **不启用任何 sys_job** —— 见 Task 6。
- **停在 commit** —— 不 push、不开 PR,等用户明说。
- ⚠️ **合并前置(全局,非本 PR 引入)**:生产 `chengyin.pii.key` 必须已注入 + `piiBackfillTask` 已回填,否则 master 的 fail-closed `PiiCryptoTypeHandler` 一部署即报名瘫痪。见 `~/Documents/城瘾/小程序/P0_部署雷_39加密生产没生效_2026-07-15.md`。

## 生产实测(2026-07-15 只读查 <生产库主机>/chengyinhubdb,MySQL 8.0.45)

| 查询 | 结果 | 含义 |
|---|---|---|
| `min_people > 0` 的票种数 | **0** | 没有任何票种设过成团门槛 |
| `team_status` 分布 | 全部 =0(72/72) | 没有一个成团过或散团过 |
| 真在途(募集中+有门槛+有已付款) | **0** | 无人在等成团,拆机制零风险 |
| `sys_job` autoRefundTask(id=100) | 已注册,**status=1 暂停** | 到期散团从没跑过 |
| `sys_job` beforeStartNotifyTask(id=102) | 已注册,**status=1 暂停** | 出发前提醒从没跑过 |
| `parent_order_no` 非空 | **0** | 前端从没发过多票单 |
| `cms_registration` | 35 已付款 / 6 待支付 / 1 已取消 | 都是单票 |

**⇒ 成团引擎从未生效**:`evaluateTeamStatus:114` 与 `processOneTicket:82` 的守卫都是 `minPeople > 0` 才继续,而全库 `min_people=0` ⇒ 两个方法每次直接 return。`min_people` 之所以全 0,是因为它派生自发布器里一个叫「建议组队」的**非必填**输入框(`fabu/index.js` `Number(et.teamSize) || 0`),没人填过。

**⇒ 出发前提醒双层死锁**:`selectTeamFormedStartingSoon`(`OmsTicketMapper.xml:189-191`)要求 `team_status = 1`,而无票种为 1 ⇒ 恒空;且 job 102 暂停。**35 个已付款报名从没收到过出发前提醒。**

## 决策依据(用户已拍板)

| 项 | 决定 |
|---|---|
| 成团 | **轻量版**:去引擎,留「已报名 N 人」展示 + 主理人手动取消 |
| `pages/club/enroll` | 改**纯报名名册**(去成团态/进度条/筛选) |
| 出发前提醒 | 本 PR 只**摘除 `team_status` 依赖**,**不启用 job 102**(启用=新行为进生产,独立决策) |
| 字段 | 保留不物理删 |
| P0-A | **不在本 PR**,由 PR B 整单退解决 |

---

### Task 1: 后端 —— 拆成团引擎

**Files:**
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/ITeamGroupService.java`
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TeamGroupServiceImpl.java`
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java:916`
- Delete: `chengyinhub-system/src/main/java/com/chengyinhub/business/task/AutoRefundTask.java`
- Modify: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java`

**Interfaces:**
- Produces: `ITeamGroupService` 收敛为三个方法 —— `cancelPaidRegistration(Long,Long)`、`cancelPaidRegistrationByOwner(Long,Long)`、`notifyBeforeStartBatch()`。Task 2/3 依赖此收敛后的接口。

**保留清单(别误删):**
- `refundOne`(私有)—— `cancelPaidRegistration` 仍在用
- `markRefunded` / `returnPoints` / `restoreInventory` —— `refundOne` 在用
- `cancelPaidRegistrationByOwner` —— **主理人手动取消 = 轻量版的一半**,且本就不读 `teamStatus`
- `notifyBeforeStartBatch` —— Task 3 处理

- [ ] **Step 1: 删掉引擎方法**

`TeamGroupServiceImpl` 删除:`listExpiredUngroupedTicketIds`(:67)、`processOneTicket`(:80)、`evaluateTeamStatus`(:112)、`setTeamStatus`(:242)、`forceEvaluateTopicByOwner`(:343)、`forceProcessTopicByOwner`(:352)。
`ITeamGroupService` 同步删除对应声明,并把残留的成团注释(`:14-15`、`:20`、`:25`)改掉。

⚠️ `cancelPaidRegistration` 内有一处 `ticket.getTeamStatus() != 1` 的守卫(约 `:152`,Read 后以实际为准)—— **一并删除**,成团态不复存在,它恒真。删完确认 `OmsTicket` 变量是否还有别的用途,没有则连同查询一起清掉(避免 orphan)。

- [ ] **Step 2: 支付回调去掉成团评估**

`CmsRegistrationServiceImpl:916` 删除这一行:
```java
try { teamGroupService.evaluateTeamStatus(registration.getTicketId()); } catch (Exception ignore) {}
```
删后检查 `teamGroupService` 字段是否变成未使用 —— 是则连 `@Autowired` 一并删(**只清理本次改动造成的孤儿**)。

- [ ] **Step 3: 删定时任务类**

```bash
rm chengyinhub-system/src/main/java/com/chengyinhub/business/task/AutoRefundTask.java
```

- [ ] **Step 4: 删内测 debug 入口**

`ApiRegistrationController` 删除 `/team/debug/evaluate`(约 `:495`)与 `/team/debug/process`(约 `:515`)两个方法。它们只被 `pages/club/enroll` 的内测工具调用,Task 4 会一并去掉前端。

- [ ] **Step 5: 编译 + 全量回归**

```bash
cd /Users/arlan/Downloads/chengyin-worktrees/s0715-1414
mvn -q -pl chengyinhub-admin -am test 2>&1 | tail -c 4000
```
Expected: BUILD SUCCESS,0 failures。

- [ ] **Step 6: 引用清零核对**

```bash
grep -rn "evaluateTeamStatus\|processOneTicket\|listExpiredUngroupedTicketIds\|autoRefundTask\|AutoRefundTask\|forceEvaluateTopicByOwner\|forceProcessTopicByOwner" \
  --include="*.java" --include="*.xml" chengyinhub-system/src chengyinhub-admin/src
```
Expected: **零命中**(测试目录也不该有)。

- [ ] **Step 7: Commit**

```bash
git add -A chengyinhub-system/src/main/java/com/chengyinhub/business/ \
           chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java
git commit -m "$(cat <<'EOF'
refactor(team): 拆除成团自动散团引擎(后端)

删除 evaluateTeamStatus/processOneTicket/listExpiredUngroupedTicketIds/setTeamStatus、
两个 owner 强制触发 debug 入口、AutoRefundTask 定时任务类;支付回调去掉成团评估调用;
cancelPaidRegistration 去掉 ticket.teamStatus!=1 守卫。

生产实测该引擎从未执行过一次:全库 min_people=0(派生自发布器非必填的「建议组队」,
没人填过),而 evaluateTeamStatus/processOneTicket 的守卫均为 minPeople>0 才继续 ⇒ 恒 no-op;
autoRefundTask(sys_job 100)注册即暂停,从没跑过;72 个票种 team_status 全停在 0。
⇒ 拆除不改变任何线上行为,是移除死代码而非灰度下线。

保留:cancelPaidRegistrationByOwner(主理人手动取消,轻量版的一半,本就不读 teamStatus)、
refundOne 及其私有助手(用户自助退款仍在用)。
字段 min_people/team_status/team_size/signup_deadline/refund_rule 一律保留不物理删。


EOF
)"
```

---

### Task 2: 后端 —— 停掉 minPeople 的写入源

**Files:**
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java:1569-1576`

**Interfaces:**
- Consumes: Task 1(引擎已删)
- Produces: 发布路径不再给票种兜底写 `min_people`/`signup_deadline`。

- [ ] **Step 1: 删除发布时的成团字段兜底**

`CmsTopicServiceImpl:1569-1576` 有一段把 `teamSize` 兜底成 `minPeople`、把 `saleEndTime` 兜底成 `signupDeadline` 的逻辑。Read 后删除。⚠️ 行号以 Read 结果为准,不要照抄本计划的行号。

- [ ] **Step 2: 编译 + 全量回归**

```bash
mvn -q -pl chengyinhub-admin -am test 2>&1 | tail -c 3000
```
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java
git commit -m "refactor(team): 发布路径不再兜底写 min_people/signup_deadline

成团引擎已拆(见上一 commit),这两个字段不再被任何代码读取,停止写入。
列保留不物理删。

"
```

---

### Task 3: 后端 —— 把「出发前提醒」从成团态上摘下来

**Files:**
- Modify: `chengyinhub-system/src/main/resources/mapper/business/OmsTicketMapper.xml:182-191`
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/OmsTicketMapper.java:37-40`

**Interfaces:**
- Consumes: Task 1
- Produces: `selectTeamFormedStartingSoon` 不再依赖 `team_status`,改按「mode=1 且 start_time 在窗口内」选票种。方法名保留(改名会牵动 `notifyBeforeStartBatch`,收益不抵风险),但注释须改。

**⚠️ 这是本 PR 唯一的行为变更点**,其余全是死代码移除。它把一个恒空的查询变成会返回数据的查询 —— 但因 **job 102 仍暂停**,生产行为仍不变。

- [ ] **Step 1: 删掉恒空的成团扫描查询**

`OmsTicketMapper.xml:182-187` 的 `selectUngroupedExpiredTickets` 整段删除(唯一调用方 `listExpiredUngroupedTicketIds` 已随 Task 1 删)。
`OmsTicketMapper.java:37` 的声明 + 注释一并删。

- [ ] **Step 2: 摘除 `selectTeamFormedStartingSoon` 的成团条件**

`OmsTicketMapper.xml:189-191`,把:
```xml
        where mode = 1 and team_status = 1 and del_flag = 0
          and start_time is not null and start_time &gt;= #{from} and start_time &lt;= #{to}
```
改为:
```xml
        <!-- 成团下线:原条件含 team_status = 1(已成团),而成团引擎从未生效、全库 team_status 恒为 0
             ⇒ 本查询恒空、出发前提醒从没发出过一条。摘除成团依赖后,所有经典定向票的已付款成员均可收到提醒。
             注:sys_job beforeStartNotifyTask(id=102)仍为暂停态,启用是独立决策,不随本 PR 生效。 -->
        where mode = 1 and del_flag = 0
          and start_time is not null and start_time &gt;= #{from} and start_time &lt;= #{to}
```
`OmsTicketMapper.java:40` 的注释「出发前提醒:已成团(team_status=1)且…」改为不提成团。

- [ ] **Step 3: 编译 + 回归 + 引用清零**

```bash
mvn -q -pl chengyinhub-admin -am test 2>&1 | tail -c 3000
grep -rn "selectUngroupedExpiredTickets" --include="*.java" --include="*.xml" chengyinhub-system/src chengyinhub-admin/src
```
Expected: BUILD SUCCESS;grep 零命中。

- [ ] **Step 4: Commit**

```bash
git add chengyinhub-system/src/main/resources/mapper/business/OmsTicketMapper.xml \
        chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/OmsTicketMapper.java
git commit -m "$(cat <<'EOF'
fix(notify): 出发前提醒摘除成团态依赖 — 原查询恒空,提醒从没发出过

selectTeamFormedStartingSoon 要求 team_status=1,而成团引擎从未生效、全库 team_status 恒为 0
⇒ 查询恒空 ⇒ 35 个已付款报名从没收到过出发前提醒。成团下线后该条件失去意义,摘除。
同时删除 selectUngroupedExpiredTickets(唯一调用方已随引擎移除)。

⚠️ sys_job beforeStartNotifyTask(id=102)仍为暂停态,本 PR 不启用 —— 启用一个从未在生产
跑过的订阅消息发送任务属新行为,应独立决策+独立验证,不搭成团下线的车。


EOF
)"
```

---

### Task 4: 小程序 —— `pages/club/enroll` 改纯报名名册(轻量版)

**Files:**
- Modify: `chengyinhub-xcx/pages/club/enroll/index.wxml`
- Modify: `chengyinhub-xcx/pages/club/enroll/index.js`
- Modify: `chengyinhub-xcx/pages/club/enroll/index.wxss`(清理孤儿样式)

**Interfaces:**
- Consumes: Task 1(debug 接口已删)
- Produces: 名册页 —— 保留「已报名 N 人」+ 报名者名单 + 主理人退款按钮;去掉成团态筛选/徽章/进度条/内测工具。

**轻量版的边界(用户已定):** 留「已报名 N 人」**不带分母**(不显示「/ 成团需 M 人」),因为 `minPeople` 已停用。

- [ ] **Step 1: 改 `index.js`**

删除:`teamStatusText`(:8)、`filter`/`filters` data(:24-25)、`onFilter`(:112)、`applyFilter`(:116)、`debugEvaluate`/`debugProcess`/`_debugTeam`(:218-245)、`isDevEnv`(:213)与 `_dev` data(:20)。
`loadTeams` 的 map(:94-103)去掉 `teamStatus` 与 `statusText`,去掉 `_show`(筛选没了,全显示)。
`loadTeamDetail` 的 map(:142-168)去掉 `threshold`/`pct`/`formed`/`teamSize`,保留 `name`/`mode`/`totalInventory`/`signups`/`regs`。
文件头注释(:1-4)改掉「成团管理看板 / 成团进度」。
`refundReg` 的注释(:182)去掉「成团前」。

- [ ] **Step 2: 改 `index.wxml`**

- `:2` `title="报名 / 成团管理"` → `title="报名名册"`
- `:11-15` 成团状态筛选整段删除
- `:21-22` 空态 sub「…查看报名与成团进度」→ 去掉成团措辞
- `:26` `wx:if="{{team._show}}"` 删除(无筛选)
- `:36` 注释「各票种成团进度 + 报名者名单」→ 改
- `:42-45` `ticket-badge` 整段删除(成团徽章)
- `:46-49` 成团进度条整段删除
- `:51` `已报名 {{tk.signups}} 人<text wx:if="{{tk.teamSize > 0}}"> / 成团需 {{tk.teamSize}} 人</text>` → 去掉分母,保留「已报名 N 人 · 上限 K」
- `:66-73` 内测工具整段删除
- `:78-80` 底部规则「满成团人数自动成团、未成团自动全额退款」→ 改为只讲担保托管 + 主理人可清退

⚠️ 行号以 Read 结果为准 —— 本页刚被 #51 改过(收编 `cy-nav-bar`/`cy-empty`/`cy-avatar`),删除会连锁改变后续行号,**每删一处重新 Read**。

- [ ] **Step 3: 清理孤儿样式**

删除 `index.wxss` 中因上述删除而无引用的 class:`ticket-badge`(含 `.formed`)、`bar`、`bar-fill`。
**只删本次改动造成的孤儿**,别顺手清理别的。

```bash
cd /Users/arlan/Downloads/chengyin-worktrees/s0715-1414/chengyinhub-xcx
for c in ticket-badge bar-fill; do echo "--- $c ---"; grep -rn "$c" pages/club/enroll/ | head -3; done
```

- [ ] **Step 4: 小程序静态检查**

```bash
cd /Users/arlan/Downloads/chengyin-worktrees/s0715-1414
bash ci/xcx-static-check.sh 2>&1 | tail -c 2000 || echo "(脚本名以 ci/ 下实际为准)"
```
Expected: 通过。⚠️ 先 `ls ci/` 确认脚本名。

- [ ] **Step 5: Commit**

```bash
git add chengyinhub-xcx/pages/club/enroll/
git commit -m "$(cat <<'EOF'
refactor(xcx): club/enroll 成团看板降级为纯报名名册(轻量版)

去掉成团态筛选/徽章/进度条/「成团需 M 人」分母/内测强制触发工具(后端 debug 接口已删);
保留「已报名 N 人」展示 + 报名者名单 + 主理人清退退款 —— 即轻量版的两件东西。


EOF
)"
```

---

### Task 5: 小程序 —— 发布器去成团派生

**Files:**
- Modify: `chengyinhub-xcx/pages/publish/fabu/index.js`(`saveTicket` 的派生段)
- Modify: `chengyinhub-xcx/pages/publish/fabu/step3.wxml`(「集合与成团」分区、「建议组队」输入框)
- Modify: `chengyinhub-xcx/pages/publish/simple/index.js`(payload 派生段)
- Modify: `chengyinhub-xcx/pages/publish/simple/index.wxml`(「成团名额」输入框、预览条)
- Modify: `chengyinhub-xcx/pages/member/orderinfo/orderinfo.js` + `orderinfo.wxml`(退款文案)

**Interfaces:**
- Consumes: Task 2(后端不再兜底)
- Produces: 新建票种不再携带 `minPeople`/`signupDeadline`/`refundRule`。

- [ ] **Step 1: `fabu` 去派生**

`fabu/index.js` 的 `saveTicket`(约 :2510-2527)删除三行:
```js
minPeople: Number(et.teamSize) || 0,
signupDeadline: /^\d{4}-\d{2}-\d{2}$/.test(et.saleEndTime) ? (et.saleEndTime + ' 23:59:59') : null,
refundRule: '未成团自动全额退款至平台余额',
```
⚠️ `teamSize` 本身**先保留**(它是「建议组队」的展示值,且 `enroll` 的旧数据兼容读过它)—— 但既然 UI 输入框要删,`teamSize` 就成了恒 0 的死字段。**本 PR 的处置:输入框删、`teamSize: Number(et.teamSize) || 0` 保留**(恒 0 写入,列保留),避免连锁改动 `editingTicket` 的 6 处初始化。

`fabu/step3.wxml` 删除「建议组队」输入框(约 :257-258);分区标题「集合与成团」(约 :239)改为「集合」。

- [ ] **Step 2: `simple` 去派生**

`simple/index.js`(约 :386-395)删除 `minPeople`/`signupDeadline`/`refundRule` 三行。
`simple/index.wxml` 删除「成团名额」输入框(约 :105-106);预览条(约 :262)`成团 {{teamSize || '—'}} 人 · 报名截止 …` 去掉「成团 N 人」段。
⚠️ 「报名截止」(`saleEnd`)**保留** —— 它是售票截止,不是成团截止,与成团无关。

- [ ] **Step 3: 退款文案去成团**

`orderinfo.wxml`(约 :67)「成团前可取消报名…成团后或已核销不可退」→ 改为不提成团(如「未核销可申请退款,已核销不可退」)。
`orderinfo.js`(约 :96)弹窗文案同改。

⚠️ **顺带发现但不在本 PR 修**:`orderinfo.js:96` 弹窗说「原路退回微信」而 `:110` 成功提示说「已退款至余额」,四处口径三种说法。这是既有不一致,**只改成团措辞,别顺手统一口径**(那是 PR C/D 的事)。

- [ ] **Step 4: 全站成团措辞清零核对**

```bash
cd /Users/arlan/Downloads/chengyin-worktrees/s0715-1414/chengyinhub-xcx
grep -rn "成团\|募集中\|已成团\|散团\|minPeople\|signupDeadline\|refundRule" \
  pages/ components/ utils/ --include="*.js" --include="*.wxml" | grep -v "^scripts/" | head -20
```
Expected: 只剩 `utils/subscribe.js` 的 `teamFormed` 模板键(订阅消息模板 ID,后端已不再发,**保留不动**,删它要动 `application.yml` 的模板配置,属另一个 PR)与 `pages/topic/pricing`(同名不同义,`teamSize` 用于摊销算底价,与票种成团无关)。**其余应清零。**

- [ ] **Step 5: 静态检查 + Commit**

```bash
cd /Users/arlan/Downloads/chengyin-worktrees/s0715-1414
ls ci/ | head; bash ci/<小程序静态检查脚本> 2>&1 | tail -c 1500
git add chengyinhub-xcx/pages/publish/ chengyinhub-xcx/pages/member/orderinfo/
git commit -m "$(cat <<'EOF'
refactor(xcx): 发布器去成团派生 + 退款文案去成团措辞

fabu/simple 的 saveTicket/payload 不再派生 minPeople/signupDeadline/refundRule;
删除「建议组队」「成团名额」输入框(minPeople 全库为 0 正因这两个非必填框没人填);
「集合与成团」分区改「集合」;orderinfo 退款文案去掉「成团前/成团后」。

保留:售票结束时间(saleEndTime,与成团无关)、teamSize 字段本身(恒 0 写入,列保留)、
utils/subscribe.js 的 teamFormed 模板键(删它要动 application.yml 模板配置,另开 PR)。


EOF
)"
```

---

### Task 6: 注销散团定时任务(sys_job 100)

**Files:**
- Create: `chengyinhub-admin/sql/migration_team_decommission_job.sql`

**⚠️ 这是本 PR 唯一的③类改动(要在库上跑的 SQL)——合并前必须先手动落库。**

**目录+前缀铁律**:必须是 `chengyinhub-admin/sql/` **第一层** + `migration_` 前缀,否则 `release-manifest.sh:49` 扫不到、CI 的 `migration_layout` 门禁会拦。

- [ ] **Step 1: 写注销 SQL**

```sql
-- 成团下线:注销散团定时任务(autoRefundTask)
-- 背景:该任务 sys_job id=100,注册即暂停(status='1'),生产从未执行过一次;
--       其 bean AutoRefundTask 已随成团引擎一并删除,留着注册行会变成「悬空」
--       (库里引用了不存在的 bean),audit_sys_job.py 会报。
-- 幂等:按 invoke_target 删,重复执行无副作用。
DELETE FROM sys_job WHERE invoke_target = 'autoRefundTask.autoRefundUnformed()';
```

⚠️ **不要动 job 102(beforeStartNotifyTask)** —— 它的 bean 仍在(Task 3 只改了查询),保持暂停态。

- [ ] **Step 2: 只读预检**

```bash
export CHENGYIN_DB_PASSWORD=...
python3 ci/run_migration.py \
  --exec "SELECT job_id, job_name, invoke_target, status FROM sys_job WHERE invoke_target LIKE '%autoRefundTask%'"
```
Expected: 一行,job_id=100,status=1。

- [ ] **Step 3: 向用户确认后执行**

**不要自动跑。**把「要在生产库删这一行 sys_job、风险评估、可逆性(可用注册 SQL 重新 insert 恢复)」讲清楚,得到用户同意再:
```bash
python3 ci/run_migration.py \
  --sql chengyinhub-admin/sql/migration_team_decommission_job.sql
```

- [ ] **Step 4: 对账复核**

```bash
python3 ci/audit_sys_job.py --ref worktree
```
Expected: 无「悬空」落差(autoRefundTask 的 bean 和注册行都没了)。

- [ ] **Step 5: Commit**

```bash
git add chengyinhub-admin/sql/migration_team_decommission_job.sql
git commit -m "chore(sql): 注销散团定时任务 sys_job(autoRefundTask)

bean AutoRefundTask 已随成团引擎删除,留着注册行会变成悬空引用(audit_sys_job.py 会报)。
该任务注册即暂停、生产从未执行过。可逆:用原 migration_c3_team_job.sql 重新 insert 即恢复。

③类改动:合并前须先手动落库。

"
```

---

## 完成后的验证闸

- [ ] `mvn -q -pl chengyinhub-admin -am test` 全绿,**把尾部输出贴给用户**(不许只说「通过了」)
- [ ] 小程序静态检查通过
- [ ] `grep` 成团措辞/符号清零(Task 1 Step 6、Task 5 Step 4),例外项已在计划中列明
- [ ] `/code-review` 走一遍本 PR diff(**禁肉眼扫**)
- [ ] `audit_sys_job.py --ref worktree` 无悬空落差
- [ ] **小程序 UI 改动要过截图闸**:`enroll` 名册页 + 发布器票种表单,走 `wechat-miniprogram-verify`(automator 自证,**不肉眼截图**),给用户看图点头后才算完
- [ ] **停在 commit** —— 不 push、不开 PR

## PR 描述里必须标的影响分类

- **①后端运行时**:`chengyinhub-system/**`、`ApiRegistrationController` → Flow 真部署
- **②小程序发布**:`chengyinhub-xcx/**` → ECS no-op,**另走微信上传**
- **③数据库**:`migration_team_decommission_job.sql` → **合并前先落库**
- ⚠️ **全局合并前置**:生产 `chengyin.pii.key` 未注入前,**任何 PR 都不能合**(见 Global Constraints)

## 不在本 PR 范围

- **启用 job 102(出发前提醒)** —— 新行为进生产,独立决策 + 独立验证。**本 PR 让它逻辑上可用,但不打开开关。**
- **`utils/subscribe.js` 的 `teamFormed` 模板键** —— 删它要动 `application.yml:216` 的模板配置
- **物理删列** —— 保留 `min_people`/`team_status`/`team_size`/`signup_deadline`/`refund_rule`
- **退款文案口径统一**(「原路退回微信」vs「已退款至余额」四处三说)—— 既有不一致,PR C/D 处理
- **`CmsTopic.teamStatus` 非持久字段 + `ApiClubController` 富化** —— enroll 不再展示后成为死字段;**本 PR 保留**(动它要碰 `/api/club/topics` 契约,收益不抵风险),记入后续清理
