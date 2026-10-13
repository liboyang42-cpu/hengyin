# 勋章引擎地基 + 成就页四层分区 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把游戏侧硬编码勋章改造成"数据驱动 + 规则求值器"引擎,并让成就页按身份卡/成长/纪念/荣誉四层分区展示（含锁定态全集）。

**Architecture:** 复用现有 `growth_badge` 表作为统一定义表 `badge_def`（加四层字段），把 `PlayerProgressionServiceImpl` 里的 `BADGE_META` 硬编码与 if-else 阈值搬到数据行 + 纯函数 `RuleEvaluator`。现有 6 个勋章**继续写 `player_badge`**（不做破坏性合表）。新增 `BadgeReadModelService` + `/api/badge/wall-v2` 把 `player_badge` + `member_badge` 按 `badge_def` union 成四层读模型；前端成就页消费它。

**Tech Stack:** Java 8 + Spring Boot 2.5.15 + MyBatis（XML mapper）；Jackson `ObjectMapper` 解析 `unlock_rule_json`；JUnit 5 + Mockito 3.9（无 Spring 容器，`ReflectionTestUtils` 注入）；微信小程序原生（`app.sendRequest`）。

## Global Constraints

- 后端服务代码放 `chengyinhub-system/src/main/java/com/chengyinhub/business/...`；**单测放 `chengyinhub-admin/src/test/java/com/chengyinhub/...`**（system 模块无 test 目录）。
- 单测框架 JUnit 5（`org.junit.jupiter.api`）+ Mockito 3.9，**不启 Spring 容器**，用 `mock(...)` + `ReflectionTestUtils.setField(service, "字段名", mock)`；字段名必须与 `@Autowired` 字段名一致。
- 跑单测：`mvn -pl chengyinhub-admin -am test`；单类 `mvn -pl chengyinhub-admin -am test -Dtest=类名`。
- 幂等范式沿用现成：player_badge 用 `playerBadgeMapper.selectOne(memberId, code, sourceId)` 命中即跳过 + insert try/catch 兜唯一键；member_badge 用 `countByMemberAndCode>0`。
- **生产库迁移不本机执行**：Task 1 只产出 `chengyinhub-admin/sql/migration_*.sql`，由用户走 `chengyin-db-migrate` 在服务器侧执行；ALTER 加列（INSTANT 不锁表）+ INSERT 种子行。加列必须先于引用新列的 jar 上线。
- `badge_def` 种子行**沿用现有 badge_code**（FIRST_STEP/STREAK_3/STREAK_7/TOPIC_CLEAR/MILE_10/MILE_50），不改历史 player_badge 的 code；新勋章用新 code。
- 前端请求：`app.sendRequest({url,method:'POST',data:{},...})`，判成功 `res.code == '200'`（字符串）。
- 不删除 V1 现有 `player_badge/member_badge` 历史数据；不做十连弹窗。

**四层字段与聚合语义（贯穿全篇，取自 spec §5.1 / §8.1）：**
- `asset_type`：`IDENTITY`(身份卡) / `GROWTH`(成长) / `COLLECTION`(纪念) / `HONOR`(荣誉)。
- `unlock_rule_json`：`{ "trigger_event","aggregate"(exists|count|sum|distinct),"aggregate_field","threshold","scope","dedupe_key","window","exclude" }`。
- P0 引擎只需 `aggregate` + `threshold` 参与求值（`observedValue >= threshold`）；`scope/window/distinct 逐事件计数` 留给后续"平台事件"计划，本计划不实现事件派发。

---

## File Structure

**后端（chengyinhub-system 主，chengyinhub-admin 放 controller+test）**
- `business/domain/GrowthBadge.java` — 修改：加 4 层字段 getter/setter。
- `business/mapper/GrowthBadgeMapper.java` + `resources/mapper/business/GrowthBadgeMapper.xml` — 修改：加 `selectAllActive()`、`selectByCode` 已有。
- `business/badge/BadgeRule.java` — 新建：`unlock_rule_json` 解析后的 POJO。
- `business/badge/RuleEvaluator.java` — 新建：纯函数，parse + 阈值判定。
- `business/service/IBadgeReadModelService.java` + `impl/BadgeReadModelServiceImpl.java` — 新建：四层读模型装配。
- `business/domain/vo/BadgeWallV2VO.java` + `BadgeWallItemVO.java` — 新建：wall-v2 返回结构。
- `business/service/impl/PlayerProgressionServiceImpl.java` — 修改：去 `BADGE_META`+if-else，改读 `badge_def` + `RuleEvaluator`。
- `chengyinhub-admin/.../web/controller/api/ApiBadgeController.java` — 新建：`/api/badge/wall-v2`。
- `chengyinhub-admin/src/test/.../business/badge/RuleEvaluatorTest.java` — 新建。
- `chengyinhub-admin/src/test/.../business/service/PlayerProgressionServiceImplTest.java` — 新建。
- `chengyinhub-admin/src/test/.../business/service/BadgeReadModelServiceImplTest.java` — 新建。
- `chengyinhub-admin/sql/migration_p4_badge_def_engine.sql` — 新建（迁移 + 种子）。

**前端（chengyinhub-xcx）**
- `subpackageP3/pages/achievements/index/index.js` + `.wxml` + `.wxss` — 修改：加四层分区，消费 `/api/badge/wall-v2`。

---

## Task 1: 迁移 SQL —— `growth_badge` 扩展为 `badge_def` + 种子行

**Files:**
- Create: `chengyinhub-admin/sql/migration_p4_badge_def_engine.sql`

**Interfaces:**
- Produces: `growth_badge` 新列 `asset_type / category / trigger_event / unlock_rule_json / rule_version / rarity / badge_family / name_en / statement`；种子行覆盖现有 6 个 code + L1/L2 新 code（锁定展示用）。

- [ ] **Step 1: 写迁移文件**

```sql
-- migration_p4_badge_def_engine.sql
-- 走 chengyin-db-migrate:只读预检 `--describe growth_badge` 确认列不存在后执行。
-- ADD COLUMN nullable,MySQL8 INSTANT 不锁表,可逆。必须先于引用新列的 jar 上线。

ALTER TABLE growth_badge
  ADD COLUMN asset_type       VARCHAR(16)  NULL COMMENT 'IDENTITY/GROWTH/COLLECTION/HONOR',
  ADD COLUMN category         VARCHAR(16)  NULL COMMENT 'EXPLORE/CREATE/ORGANIZE/CONNECT/CO_CREATE',
  ADD COLUMN trigger_event    VARCHAR(64)  NULL COMMENT '订阅的领域事件',
  ADD COLUMN unlock_rule_json JSON         NULL COMMENT '规则表达(aggregate/threshold/...)',
  ADD COLUMN rule_version     INT          NULL DEFAULT 1,
  ADD COLUMN rarity           VARCHAR(16)  NULL COMMENT 'L3/L4可用;L1/L2为空',
  ADD COLUMN badge_family     VARCHAR(64)  NULL COMMENT '梯度族(如MILE),同族不同阈值',
  ADD COLUMN name_en          VARCHAR(64)  NULL,
  ADD COLUMN statement        VARCHAR(255) NULL COMMENT '身份宣言/卡面主文案';

-- 种子:现有 6 个游戏勋章(沿用旧 code),补四层定义。status '0'=启用(沿用现表约定)。
INSERT INTO growth_badge (badge_code, badge_name, icon_url, asset_type, category, trigger_event, unlock_rule_json, rule_version, status, sort_no, create_time)
VALUES
 ('FIRST_STEP','初次启程','','IDENTITY','EXPLORE','node_done','{"aggregate":"exists","threshold":1}',1,'0',1,now()),
 ('TOPIC_CLEAR','通关纪念','','IDENTITY','EXPLORE','topic_complete','{"aggregate":"exists","threshold":1}',1,'0',2,now()),
 ('MILE_10','城市漫游·10km','','GROWTH','EXPLORE','node_done','{"aggregate":"sum","aggregate_field":"mileage","threshold":10}',1,'0',3,now()),
 ('MILE_50','城市行者·50km','','GROWTH','EXPLORE','node_done','{"aggregate":"sum","aggregate_field":"mileage","threshold":50}',1,'0',4,now()),
 ('STREAK_3','连续三日','','GROWTH','EXPLORE','node_done','{"aggregate":"distinct","aggregate_field":"active_day","threshold":3,"window":{"type":"natural_day_streak"}}',1,'0',5,now()),
 ('STREAK_7','一周不辍','','GROWTH','EXPLORE','node_done','{"aggregate":"distinct","aggregate_field":"active_day","threshold":7,"window":{"type":"natural_day_streak"}}',1,'0',6,now())
ON DUPLICATE KEY UPDATE
 asset_type=VALUES(asset_type), category=VALUES(category), trigger_event=VALUES(trigger_event),
 unlock_rule_json=VALUES(unlock_rule_json), rule_version=VALUES(rule_version);

-- 种子:L1/L2 新勋章定义(P0 暂不可获得,四层页锁定展示;授予在后续计划接入)。
INSERT INTO growth_badge (badge_code, badge_name, name_en, statement, icon_url, asset_type, category, trigger_event, unlock_rule_json, rule_version, status, sort_no, create_time)
VALUES
 ('ID_ROAM_FIRST','初次漫游','ROAM','第一次漫游这座城。','','IDENTITY','EXPLORE','roam_session_finished','{"aggregate":"exists","threshold":1}',1,'0',7,now()),
 ('GW_ROUTE_3','城市脚步·三','','','','GROWTH','EXPLORE','topic_complete','{"aggregate":"distinct","aggregate_field":"topicId","threshold":3}',1,'0',8,now()),
 ('GW_ROUTE_10','城市脚步·十','','','','GROWTH','EXPLORE','topic_complete','{"aggregate":"distinct","aggregate_field":"topicId","threshold":10}',1,'0',9,now())
ON DUPLICATE KEY UPDATE asset_type=VALUES(asset_type);
```

- [ ] **Step 2: 语法自检**

Run: `grep -c "INSERT INTO growth_badge" chengyinhub-admin/sql/migration_p4_badge_def_engine.sql`
Expected: `2`

- [ ] **Step 3: Commit**

```bash
git add chengyinhub-admin/sql/migration_p4_badge_def_engine.sql
git commit -m "feat(badge): migration - extend growth_badge to badge_def + seed defs"
```

---

## Task 2: `GrowthBadge` 域对象 + Mapper 扩展

**Files:**
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/GrowthBadge.java`
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/GrowthBadgeMapper.java`
- Modify: `chengyinhub-system/src/main/resources/mapper/business/GrowthBadgeMapper.xml`

**Interfaces:**
- Produces: `GrowthBadge.getAssetType()/getCategory()/getTriggerEvent()/getUnlockRuleJson()/getStatement()/getNameEn()`；`GrowthBadgeMapper.selectAllActive()` 返回所有 `status='0'` 的定义。

- [ ] **Step 1: 给 `GrowthBadge` 加字段**

在 `GrowthBadge.java` 现有字段后加（配套 getter/setter）：

```java
    private String assetType;
    private String category;
    private String triggerEvent;
    private String unlockRuleJson;
    private Integer ruleVersion;
    private String rarity;
    private String badgeFamily;
    private String nameEn;
    private String statement;

    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTriggerEvent() { return triggerEvent; }
    public void setTriggerEvent(String triggerEvent) { this.triggerEvent = triggerEvent; }
    public String getUnlockRuleJson() { return unlockRuleJson; }
    public void setUnlockRuleJson(String unlockRuleJson) { this.unlockRuleJson = unlockRuleJson; }
    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getRarity() { return rarity; }
    public void setRarity(String rarity) { this.rarity = rarity; }
    public String getBadgeFamily() { return badgeFamily; }
    public void setBadgeFamily(String badgeFamily) { this.badgeFamily = badgeFamily; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }
    public String getStatement() { return statement; }
    public void setStatement(String statement) { this.statement = statement; }
```

- [ ] **Step 2: 给 Mapper 接口加 `selectAllActive`**

在 `GrowthBadgeMapper.java` 接口加：

```java
    List<GrowthBadge> selectAllActive();
```

- [ ] **Step 3: XML 加 resultMap 列 + 查询**

在 `GrowthBadgeMapper.xml` 的 resultMap 内补列映射，并加查询（列名与 Task 1 一致）：

```xml
    <result property="assetType"     column="asset_type"/>
    <result property="category"      column="category"/>
    <result property="triggerEvent"  column="trigger_event"/>
    <result property="unlockRuleJson" column="unlock_rule_json"/>
    <result property="ruleVersion"   column="rule_version"/>
    <result property="rarity"        column="rarity"/>
    <result property="badgeFamily"   column="badge_family"/>
    <result property="nameEn"        column="name_en"/>
    <result property="statement"     column="statement"/>
```

```xml
    <select id="selectAllActive" resultMap="GrowthBadgeResult">
        select id, badge_code, badge_name, badge_desc, icon_url, unlock_rule, status, sort_no, club_id,
               asset_type, category, trigger_event, unlock_rule_json, rule_version, rarity, badge_family, name_en, statement
        from growth_badge
        where status = '0'
        order by sort_no
    </select>
```

> 注：resultMap id 以文件实际为准（Explore 报告示例为 `GrowthBadgeResult`；实现时先看 XML 顶部 `<resultMap id=...>`）。

- [ ] **Step 4: 编译**

Run: `mvn -pl chengyinhub-system -am -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add chengyinhub-system/src/main/java/com/chengyinhub/business/domain/GrowthBadge.java \
        chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/GrowthBadgeMapper.java \
        chengyinhub-system/src/main/resources/mapper/business/GrowthBadgeMapper.xml
git commit -m "feat(badge): extend GrowthBadge domain + mapper with badge_def fields"
```

---

## Task 3: `RuleEvaluator` 纯函数引擎 + 单测（核心）

**Files:**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/badge/BadgeRule.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/badge/RuleEvaluator.java`
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/business/badge/RuleEvaluatorTest.java`

**Interfaces:**
- Produces:
  - `BadgeRule { String aggregate; String aggregateField; long threshold; }`（getter）。
  - `RuleEvaluator.parse(String json) -> BadgeRule`（json 为空/非法返回 `aggregate=null, threshold=Long.MAX_VALUE` 的安全默认，永不授予）。
  - `RuleEvaluator.isThresholdMet(BadgeRule rule, long observedValue) -> boolean`（`observedValue >= rule.threshold`，rule 为 null 返回 false）。

- [ ] **Step 1: 写失败测试**

`RuleEvaluatorTest.java`：

```java
package com.chengyinhub.business.badge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuleEvaluatorTest {

    private final RuleEvaluator ev = new RuleEvaluator();

    @Test
    void parse_extractsAggregateAndThreshold() {
        BadgeRule r = ev.parse("{\"aggregate\":\"sum\",\"aggregate_field\":\"mileage\",\"threshold\":50}");
        assertEquals("sum", r.getAggregate());
        assertEquals("mileage", r.getAggregateField());
        assertEquals(50L, r.getThreshold());
    }

    @Test
    void isThresholdMet_true_whenValueReachesThreshold() {
        BadgeRule r = ev.parse("{\"aggregate\":\"exists\",\"threshold\":1}");
        assertTrue(ev.isThresholdMet(r, 1));
        assertTrue(ev.isThresholdMet(r, 5));
    }

    @Test
    void isThresholdMet_false_whenBelow() {
        BadgeRule r = ev.parse("{\"aggregate\":\"distinct\",\"aggregate_field\":\"topicId\",\"threshold\":10}");
        assertFalse(ev.isThresholdMet(r, 9));
    }

    @Test
    void parse_nullOrBlank_returnsSafeNeverAward() {
        assertFalse(ev.isThresholdMet(ev.parse(null), 999999));
        assertFalse(ev.isThresholdMet(ev.parse(""), 999999));
        assertFalse(ev.isThresholdMet(ev.parse("not-json"), 999999));
    }

    @Test
    void isThresholdMet_nullRule_returnsFalse() {
        assertFalse(ev.isThresholdMet(null, 100));
    }
}
```

- [ ] **Step 2: 跑测确认失败**

Run: `mvn -pl chengyinhub-admin -am -q test -Dtest=RuleEvaluatorTest`
Expected: FAIL（`cannot find symbol RuleEvaluator/BadgeRule`）。

- [ ] **Step 3: 写 `BadgeRule`**

```java
package com.chengyinhub.business.badge;

/** unlock_rule_json 解析结果。P0 只用 aggregate + threshold。 */
public class BadgeRule {
    private String aggregate;
    private String aggregateField;
    private long threshold;

    public String getAggregate() { return aggregate; }
    public void setAggregate(String aggregate) { this.aggregate = aggregate; }
    public String getAggregateField() { return aggregateField; }
    public void setAggregateField(String aggregateField) { this.aggregateField = aggregateField; }
    public long getThreshold() { return threshold; }
    public void setThreshold(long threshold) { this.threshold = threshold; }
}
```

- [ ] **Step 4: 写 `RuleEvaluator`**

```java
package com.chengyinhub.business.badge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 纯函数规则求值器:解析 unlock_rule_json + 阈值判定。无副作用,可单测。 */
public class RuleEvaluator {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 解析失败/空 → 安全默认(threshold=MAX,永不授予)。 */
    public BadgeRule parse(String json) {
        BadgeRule r = new BadgeRule();
        r.setThreshold(Long.MAX_VALUE);
        if (json == null || json.trim().isEmpty()) return r;
        try {
            JsonNode n = OM.readTree(json);
            if (n.hasNonNull("aggregate")) r.setAggregate(n.get("aggregate").asText());
            if (n.hasNonNull("aggregate_field")) r.setAggregateField(n.get("aggregate_field").asText());
            if (n.hasNonNull("threshold")) r.setThreshold(n.get("threshold").asLong(Long.MAX_VALUE));
        } catch (Exception ignore) {
            // 非法 json → 保持安全默认
        }
        return r;
    }

    /** observedValue 由调用方按 aggregate 预聚合后传入;此处只做阈值比较。 */
    public boolean isThresholdMet(BadgeRule rule, long observedValue) {
        if (rule == null) return false;
        return observedValue >= rule.getThreshold();
    }
}
```

- [ ] **Step 5: 跑测确认通过**

Run: `mvn -pl chengyinhub-admin -am -q test -Dtest=RuleEvaluatorTest`
Expected: PASS（5 tests）。

- [ ] **Step 6: Commit**

```bash
git add chengyinhub-system/src/main/java/com/chengyinhub/business/badge/ \
        chengyinhub-admin/src/test/java/com/chengyinhub/business/badge/RuleEvaluatorTest.java
git commit -m "feat(badge): add RuleEvaluator pure-function engine + tests"
```

---

## Task 4: 重构 `PlayerProgressionServiceImpl` —— 去硬编码,改走 badge_def + RuleEvaluator

**Files:**
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PlayerProgressionServiceImpl.java`
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/PlayerProgressionServiceImplTest.java`

**Interfaces:**
- Consumes: `GrowthBadgeMapper.selectByCode(code)`（取名称/图标/rule）、`RuleEvaluator`。
- Produces: `onNodeDone/onTopicComplete` 行为不变（仍写 player_badge、仍幂等），但名称/图标来自 `badge_def`，阈值来自 `unlock_rule_json`。

- [ ] **Step 1: 写失败测试（行为等价 + 数据驱动）**

`PlayerProgressionServiceImplTest.java`：

```java
package com.chengyinhub.business.service;

import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.PlayerGrowth;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.PlayerGrowthMapper;
import com.chengyinhub.business.service.impl.PlayerProgressionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerProgressionServiceImplTest {

    private PlayerGrowthMapper growthMapper;
    private PlayerBadgeMapper badgeMapper;
    private GrowthBadgeMapper defMapper;
    private PlayerProgressionServiceImpl svc;

    private GrowthBadge def(String code, String name, String ruleJson) {
        GrowthBadge g = new GrowthBadge();
        g.setBadgeCode(code); g.setBadgeName(name); g.setIconUrl("icon/" + code);
        g.setUnlockRuleJson(ruleJson);
        return g;
    }

    @BeforeEach
    void setUp() {
        growthMapper = mock(PlayerGrowthMapper.class);
        badgeMapper = mock(PlayerBadgeMapper.class);
        defMapper = mock(GrowthBadgeMapper.class);
        svc = new PlayerProgressionServiceImpl();
        ReflectionTestUtils.setField(svc, "playerGrowthMapper", growthMapper);
        ReflectionTestUtils.setField(svc, "playerBadgeMapper", badgeMapper);
        ReflectionTestUtils.setField(svc, "growthBadgeMapper", defMapper);
        ReflectionTestUtils.setField(svc, "ruleEvaluator", new RuleEvaluator());
        when(defMapper.selectByCode("FIRST_STEP"))
            .thenReturn(def("FIRST_STEP", "初次启程", "{\"aggregate\":\"exists\",\"threshold\":1}"));
    }

    @Test
    void firstNodeDone_awardsFirstStep_withNameFromDef() {
        when(growthMapper.selectByMember(1L)).thenReturn(null); // 新用户,checkins=1
        List<PlayerBadge> newly = svc.onNodeDone(1L);
        assertTrue(newly.stream().anyMatch(b -> "FIRST_STEP".equals(b.getBadgeCode())));
        assertEquals("初次启程", newly.stream()
            .filter(b -> "FIRST_STEP".equals(b.getBadgeCode())).findFirst().get().getBadgeName());
        verify(badgeMapper).insertPlayerBadge(argThat(b -> "FIRST_STEP".equals(b.getBadgeCode())));
    }

    @Test
    void secondNodeDone_doesNotReAwardFirstStep() {
        PlayerGrowth g = new PlayerGrowth();
        g.setMemberId(1L); g.setTotalCheckins(5);
        when(growthMapper.selectByMember(1L)).thenReturn(g);
        when(badgeMapper.selectOne(eq(1L), eq("FIRST_STEP"), anyLong())).thenReturn(new PlayerBadge());
        List<PlayerBadge> newly = svc.onNodeDone(1L);
        assertTrue(newly.stream().noneMatch(b -> "FIRST_STEP".equals(b.getBadgeCode())));
    }
}
```

- [ ] **Step 2: 跑测确认失败**

Run: `mvn -pl chengyinhub-admin -am -q test -Dtest=PlayerProgressionServiceImplTest`
Expected: FAIL（`growthBadgeMapper`/`ruleEvaluator` 字段不存在，或名称不匹配）。

- [ ] **Step 3: 改 `PlayerProgressionServiceImpl`**

删掉 `BADGE_META` 静态块；注入 `GrowthBadgeMapper` 与 `RuleEvaluator`；`addIfNew` 名称/图标改从 `badge_def` 取；阈值判定改用 `RuleEvaluator`。关键改动：

```java
    @Autowired
    private com.chengyinhub.business.mapper.GrowthBadgeMapper growthBadgeMapper;

    @Autowired
    private com.chengyinhub.business.badge.RuleEvaluator ruleEvaluator;
```

`addIfNew` 内取名称/图标：

```java
    private void addIfNew(List<PlayerBadge> newly, Long memberId, String code, int sourceType, Long sourceId) {
        if (playerBadgeMapper.selectOne(memberId, code, sourceId) != null) return;
        GrowthBadge d = growthBadgeMapper.selectByCode(code);
        String name = d != null ? d.getBadgeName() : code;
        String icon = d != null ? d.getIconUrl() : "";
        PlayerBadge b = new PlayerBadge();
        b.setMemberId(memberId);
        b.setBadgeCode(code);
        b.setBadgeName(name);
        b.setIconUrl(icon);
        b.setSourceType(sourceType);
        b.setSourceId(sourceId == null ? 0L : sourceId);
        b.setObtainTime(new Date());
        b.setDelFlag(0);
        b.setCreateTime(new Date());
        try { playerBadgeMapper.insertPlayerBadge(b); newly.add(b); }
        catch (Exception e) { /* 唯一键并发兜底 */ }
    }
```

阈值判定改数据驱动（示例，里程档）：

```java
        // 旧: if (total.compareTo(new BigDecimal("50")) >= 0) addIfNew(..., "MILE_50", ...);
        // 新: 用 badge_def 的 unlock_rule_json 阈值
        awardByThreshold(newly, memberId, "MILE_50", 3, 0L, total.longValue());
        awardByThreshold(newly, memberId, "MILE_10", 3, 0L, total.longValue());
```

新增 helper：

```java
    /** 数据驱动阈值发章:读 def 的 unlock_rule_json,observedValue >= threshold 才发。 */
    private void awardByThreshold(List<PlayerBadge> newly, Long memberId, String code,
                                  int sourceType, Long sourceId, long observedValue) {
        GrowthBadge d = growthBadgeMapper.selectByCode(code);
        if (d == null) return;
        com.chengyinhub.business.badge.BadgeRule rule = ruleEvaluator.parse(d.getUnlockRuleJson());
        if (ruleEvaluator.isThresholdMet(rule, observedValue)) {
            addIfNew(newly, memberId, code, sourceType, sourceId);
        }
    }
```

同理 STREAK：`awardByThreshold(newly, memberId, "STREAK_7", 4, 0L, streak);` 与 `"STREAK_3"`。FIRST_STEP/TOPIC_CLEAR 走 exists（threshold=1），`awardByThreshold(..., "FIRST_STEP", 1, 0L, checkins)`（checkins>=1 即发，配合 addIfNew 幂等）。

> 注：保留原有"里程/streak 聚合仍从 `PlayerGrowth` 计算"的逻辑不动；只把最终"是否达阈值"从硬编码常量换成 `badge_def` 驱动。里程单位沿用现表（BigDecimal km，阈值 10/50）。

- [ ] **Step 4: 跑测确认通过**

Run: `mvn -pl chengyinhub-admin -am -q test -Dtest=PlayerProgressionServiceImplTest`
Expected: PASS。

- [ ] **Step 5: 跑既有相关单测防回归**

Run: `mvn -pl chengyinhub-admin -am -q test`
Expected: BUILD SUCCESS（全绿；若有依赖 `BADGE_META` 的旧测试，改为 mock `growthBadgeMapper.selectByCode`）。

- [ ] **Step 6: Commit**

```bash
git add chengyinhub-system/.../PlayerProgressionServiceImpl.java \
        chengyinhub-admin/src/test/.../PlayerProgressionServiceImplTest.java
git commit -m "refactor(badge): drive PlayerProgression badges from badge_def + RuleEvaluator"
```

---

## Task 5: `BadgeReadModelService` —— 四层读模型装配 + 单测

**Files:**
- Create: `chengyinhub-system/.../business/domain/vo/BadgeWallItemVO.java`
- Create: `chengyinhub-system/.../business/domain/vo/BadgeWallV2VO.java`
- Create: `chengyinhub-system/.../business/service/IBadgeReadModelService.java`
- Create: `chengyinhub-system/.../business/service/impl/BadgeReadModelServiceImpl.java`
- Test: `chengyinhub-admin/src/test/.../business/service/BadgeReadModelServiceImplTest.java`

**Interfaces:**
- Consumes: `GrowthBadgeMapper.selectAllActive()`（全集定义）、`PlayerBadgeMapper.selectListByMember(memberId)`、`MemberBadgeMapper.selectMemberBadges(memberId)`（已获得事实）。
- Produces: `IBadgeReadModelService.buildWallV2(Long memberId) -> BadgeWallV2VO`，按 `asset_type` 分四组，每枚标 `unlocked` + `unlockTime`。
  - `BadgeWallItemVO { String badgeCode, badgeName, nameEn, statement, iconUrl, assetType, category, boolean unlocked, Date unlockTime }`
  - `BadgeWallV2VO { List<BadgeWallItemVO> identity, growth, collection, honor }`

- [ ] **Step 1: 写失败测试**

`BadgeReadModelServiceImplTest.java`：

```java
package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.MemberBadge;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.vo.BadgeWallV2VO;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.MemberBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.service.impl.BadgeReadModelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BadgeReadModelServiceImplTest {

    private GrowthBadgeMapper defMapper;
    private PlayerBadgeMapper playerBadgeMapper;
    private MemberBadgeMapper memberBadgeMapper;
    private BadgeReadModelServiceImpl svc;

    private GrowthBadge def(String code, String name, String assetType) {
        GrowthBadge g = new GrowthBadge();
        g.setBadgeCode(code); g.setBadgeName(name); g.setAssetType(assetType);
        return g;
    }

    @BeforeEach
    void setUp() {
        defMapper = mock(GrowthBadgeMapper.class);
        playerBadgeMapper = mock(PlayerBadgeMapper.class);
        memberBadgeMapper = mock(MemberBadgeMapper.class);
        svc = new BadgeReadModelServiceImpl();
        ReflectionTestUtils.setField(svc, "growthBadgeMapper", defMapper);
        ReflectionTestUtils.setField(svc, "playerBadgeMapper", playerBadgeMapper);
        ReflectionTestUtils.setField(svc, "memberBadgeMapper", memberBadgeMapper);
    }

    @Test
    void buildWallV2_groupsByAssetType_andMarksUnlocked() {
        when(defMapper.selectAllActive()).thenReturn(Arrays.asList(
            def("FIRST_STEP", "初次启程", "IDENTITY"),
            def("GW_ROUTE_3", "城市脚步·三", "GROWTH")
        ));
        PlayerBadge pb = new PlayerBadge();
        pb.setBadgeCode("FIRST_STEP"); pb.setObtainTime(new Date());
        when(playerBadgeMapper.selectListByMember(1L)).thenReturn(Collections.singletonList(pb));
        when(memberBadgeMapper.selectMemberBadges(1L)).thenReturn(Collections.<MemberBadge>emptyList());

        BadgeWallV2VO vo = svc.buildWallV2(1L);

        assertEquals(1, vo.getIdentity().size());
        assertTrue(vo.getIdentity().get(0).isUnlocked());
        assertEquals(1, vo.getGrowth().size());
        assertFalse(vo.getGrowth().get(0).isUnlocked()); // 未获得,锁定态
    }
}
```

- [ ] **Step 2: 跑测确认失败**

Run: `mvn -pl chengyinhub-admin -am -q test -Dtest=BadgeReadModelServiceImplTest`
Expected: FAIL（类不存在）。

- [ ] **Step 3: 写 VO**

`BadgeWallItemVO.java`：

```java
package com.chengyinhub.business.domain.vo;

import java.util.Date;

public class BadgeWallItemVO {
    private String badgeCode, badgeName, nameEn, statement, iconUrl, assetType, category;
    private boolean unlocked;
    private Date unlockTime;
    // getters/setters (全字段)
    public String getBadgeCode() { return badgeCode; }
    public void setBadgeCode(String v) { this.badgeCode = v; }
    public String getBadgeName() { return badgeName; }
    public void setBadgeName(String v) { this.badgeName = v; }
    public String getNameEn() { return nameEn; }
    public void setNameEn(String v) { this.nameEn = v; }
    public String getStatement() { return statement; }
    public void setStatement(String v) { this.statement = v; }
    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String v) { this.iconUrl = v; }
    public String getAssetType() { return assetType; }
    public void setAssetType(String v) { this.assetType = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public boolean isUnlocked() { return unlocked; }
    public void setUnlocked(boolean v) { this.unlocked = v; }
    public Date getUnlockTime() { return unlockTime; }
    public void setUnlockTime(Date v) { this.unlockTime = v; }
}
```

`BadgeWallV2VO.java`：

```java
package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.List;

public class BadgeWallV2VO {
    private List<BadgeWallItemVO> identity = new ArrayList<>();
    private List<BadgeWallItemVO> growth = new ArrayList<>();
    private List<BadgeWallItemVO> collection = new ArrayList<>();
    private List<BadgeWallItemVO> honor = new ArrayList<>();
    public List<BadgeWallItemVO> getIdentity() { return identity; }
    public void setIdentity(List<BadgeWallItemVO> v) { this.identity = v; }
    public List<BadgeWallItemVO> getGrowth() { return growth; }
    public void setGrowth(List<BadgeWallItemVO> v) { this.growth = v; }
    public List<BadgeWallItemVO> getCollection() { return collection; }
    public void setCollection(List<BadgeWallItemVO> v) { this.collection = v; }
    public List<BadgeWallItemVO> getHonor() { return honor; }
    public void setHonor(List<BadgeWallItemVO> v) { this.honor = v; }
}
```

- [ ] **Step 4: 写接口 + 实现**

`IBadgeReadModelService.java`：

```java
package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.BadgeWallV2VO;

public interface IBadgeReadModelService {
    BadgeWallV2VO buildWallV2(Long memberId);
}
```

`BadgeReadModelServiceImpl.java`：

```java
package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.MemberBadge;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.vo.BadgeWallItemVO;
import com.chengyinhub.business.domain.vo.BadgeWallV2VO;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.MemberBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.service.IBadgeReadModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BadgeReadModelServiceImpl implements IBadgeReadModelService {

    @Autowired private GrowthBadgeMapper growthBadgeMapper;
    @Autowired private PlayerBadgeMapper playerBadgeMapper;
    @Autowired private MemberBadgeMapper memberBadgeMapper;

    @Override
    public BadgeWallV2VO buildWallV2(Long memberId) {
        // 1) 已获得事实:code -> unlockTime
        Map<String, Date> owned = new HashMap<>();
        List<PlayerBadge> pbs = playerBadgeMapper.selectListByMember(memberId);
        if (pbs != null) for (PlayerBadge b : pbs) owned.putIfAbsent(b.getBadgeCode(), b.getObtainTime());
        List<MemberBadge> mbs = memberBadgeMapper.selectMemberBadges(memberId);
        if (mbs != null) for (MemberBadge b : mbs) owned.putIfAbsent(b.getBadgeCode(), b.getUnlockTime());

        // 2) 全集定义 -> 按 asset_type 分组 + 标 unlocked
        BadgeWallV2VO vo = new BadgeWallV2VO();
        List<GrowthBadge> defs = growthBadgeMapper.selectAllActive();
        if (defs != null) for (GrowthBadge d : defs) {
            BadgeWallItemVO item = new BadgeWallItemVO();
            item.setBadgeCode(d.getBadgeCode());
            item.setBadgeName(d.getBadgeName());
            item.setNameEn(d.getNameEn());
            item.setStatement(d.getStatement());
            item.setIconUrl(d.getIconUrl());
            item.setAssetType(d.getAssetType());
            item.setCategory(d.getCategory());
            boolean has = owned.containsKey(d.getBadgeCode());
            item.setUnlocked(has);
            item.setUnlockTime(has ? owned.get(d.getBadgeCode()) : null);
            String t = d.getAssetType() == null ? "" : d.getAssetType();
            switch (t) {
                case "IDENTITY":   vo.getIdentity().add(item); break;
                case "GROWTH":     vo.getGrowth().add(item); break;
                case "COLLECTION": vo.getCollection().add(item); break;
                case "HONOR":      vo.getHonor().add(item); break;
                default: /* 无 asset_type 的旧行归 growth 兜底 */ vo.getGrowth().add(item);
            }
        }
        return vo;
    }
}
```

- [ ] **Step 5: 跑测确认通过**

Run: `mvn -pl chengyinhub-admin -am -q test -Dtest=BadgeReadModelServiceImplTest`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add chengyinhub-system/.../domain/vo/BadgeWall*.java \
        chengyinhub-system/.../service/IBadgeReadModelService.java \
        chengyinhub-system/.../service/impl/BadgeReadModelServiceImpl.java \
        chengyinhub-admin/src/test/.../BadgeReadModelServiceImplTest.java
git commit -m "feat(badge): add BadgeReadModelService four-layer wall-v2 assembly + tests"
```

---

## Task 6: `/api/badge/wall-v2` 接口

**Files:**
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiBadgeController.java`

**Interfaces:**
- Consumes: `IBadgeReadModelService.buildWallV2(memberId)`；`getAppUserId()`（BaseController，参考 `ApiMedalController`）。
- Produces: `POST /api/badge/wall-v2` → `AjaxResult.success(BadgeWallV2VO)`。

- [ ] **Step 1: 写 controller（对齐 `ApiMedalController` 风格）**

```java
package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IBadgeReadModelService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/badge")
public class ApiBadgeController extends BaseController {

    @Autowired
    private IBadgeReadModelService badgeReadModelService;

    @PostMapping("/wall-v2")
    public AjaxResult wallV2() {
        Long memberId = getAppUserId();
        return AjaxResult.success(badgeReadModelService.buildWallV2(memberId));
    }
}
```

> 注：`getAppUserId()` 的确切方法名以 `ApiMedalController` 里实际调用为准（Explore 报告为 `getAppUserId()`）。BaseController / AjaxResult 包路径按项目现有 import 对齐。

- [ ] **Step 2: 编译**

Run: `mvn -pl chengyinhub-admin -am -q compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiBadgeController.java
git commit -m "feat(badge): add POST /api/badge/wall-v2 endpoint"
```

---

## Task 7: 前端成就页四层分区

**Files:**
- Modify: `chengyinhub-xcx/subpackageP3/pages/achievements/index/index.js`
- Modify: `chengyinhub-xcx/subpackageP3/pages/achievements/index/index.wxml`
- Modify: `chengyinhub-xcx/subpackageP3/pages/achievements/index/index.wxss`

**Interfaces:**
- Consumes: `POST /api/badge/wall-v2` → `{ code:'200', data:{ identity[], growth[], collection[], honor[] } }`，item 字段 `badgeCode/badgeName/nameEn/statement/iconUrl/unlocked/unlockTime`。

- [ ] **Step 1: `index.js` 加四层数据 + 拉取**

在 `data` 加：

```js
    layers: { identity: [], growth: [], collection: [], honor: [] },
    layerCounts: { identity: 0, growth: 0, collection: 0, honor: 0 },
    activeLayer: 'identity',
```

在 `loadAll()` 的 `Promise.all` 里追加 `req('/api/badge/wall-v2')`，并在回调映射（沿用 `code == '200'` 判定）：

```js
    // wallV2 = 第 4 个接口返回
    if (wallV2 && String(wallV2.code) === '200' && wallV2.data) {
      var d = wallV2.data;
      this.setData({
        'layers.identity': d.identity || [],
        'layers.growth': d.growth || [],
        'layers.collection': d.collection || [],
        'layers.honor': d.honor || [],
        'layerCounts.identity': (d.identity || []).filter(function (x) { return x.unlocked; }).length,
        'layerCounts.growth': (d.growth || []).filter(function (x) { return x.unlocked; }).length,
        'layerCounts.collection': (d.collection || []).filter(function (x) { return x.unlocked; }).length,
        'layerCounts.honor': (d.honor || []).filter(function (x) { return x.unlocked; }).length
      });
    }
```

加切 tab 方法：

```js
    switchLayer: function (e) {
      this.setData({ activeLayer: e.currentTarget.dataset.layer });
    },
```

- [ ] **Step 2: `index.wxml` 加四层 Tab + 列表**

在现有分区上方插入（class 名与现有 wxss 风格对齐）：

```xml
<view class="layer-tabs">
  <view class="layer-tab {{activeLayer==='identity'?'on':''}}" data-layer="identity" bindtap="switchLayer">身份卡 {{layerCounts.identity}}</view>
  <view class="layer-tab {{activeLayer==='growth'?'on':''}}" data-layer="growth" bindtap="switchLayer">成长 {{layerCounts.growth}}</view>
  <view class="layer-tab {{activeLayer==='collection'?'on':''}}" data-layer="collection" bindtap="switchLayer">纪念 {{layerCounts.collection}}</view>
  <view class="layer-tab {{activeLayer==='honor'?'on':''}}" data-layer="honor" bindtap="switchLayer">荣誉 {{layerCounts.honor}}</view>
</view>

<view class="layer-grid">
  <block wx:for="{{layers[activeLayer]}}" wx:key="badgeCode">
    <view class="layer-card {{item.unlocked?'unlocked':'locked'}}">
      <image wx:if="{{item.iconUrl}}" class="layer-icon" src="{{item.iconUrl}}" mode="aspectFill"></image>
      <view class="layer-name">{{item.badgeName}}</view>
      <view wx:if="{{!item.unlocked}}" class="layer-lock">未点亮</view>
    </view>
  </block>
</view>
```

- [ ] **Step 3: `index.wxss` 加样式**

```css
.layer-tabs { display: flex; gap: 16rpx; padding: 24rpx 32rpx 0; }
.layer-tab { padding: 12rpx 20rpx; border-radius: 999rpx; font-size: 26rpx; color: #9aa; background: rgba(255,255,255,.06); }
.layer-tab.on { color: #fff; background: #7A5CFF; }
.layer-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20rpx; padding: 24rpx 32rpx; }
.layer-card { display: flex; flex-direction: column; align-items: center; padding: 20rpx; border-radius: 16rpx; background: rgba(255,255,255,.04); }
.layer-card.locked { opacity: .4; }
.layer-icon { width: 96rpx; height: 96rpx; border-radius: 12rpx; }
.layer-name { margin-top: 12rpx; font-size: 24rpx; color: #e8e8f0; text-align: center; }
.layer-lock { margin-top: 6rpx; font-size: 20rpx; color: #888; }
```

- [ ] **Step 4: 真机/工具自证（用 wechat-miniprogram-verify skill）**

用 `miniprogram-automator` 打开成就页，`callMethod` 触发 `loadAll` 后 `mp.screenshot()`，断言四个 tab 存在且 `layerCounts.identity >= 1`（老用户有 FIRST_STEP）。不靠肉眼截图。

- [ ] **Step 5: Commit**

```bash
git add chengyinhub-xcx/subpackageP3/pages/achievements/index/index.js \
        chengyinhub-xcx/subpackageP3/pages/achievements/index/index.wxml \
        chengyinhub-xcx/subpackageP3/pages/achievements/index/index.wxss
git commit -m "feat(badge): achievements page four-layer tabs consuming wall-v2"
```

---

## 后续计划（不在本计划内，仅登记）

- **平台事件授予**：`topic_publish_success` / `club_created` / `club_joined` / `referral_activated` / `content_featured` / `coop_fulfilled` 的事件派发 + `BadgeAwardService.dispatch` + 事件驱动的 distinct/count 聚合（`growth_mission_progress`）。
- **漫游接入**：`roam_session_finished` / `roam_poi_discovered(poiType)` 事件 + `ID_ROAM_FIRST` / `ID_CITY_LIT` 授予 + 路线地图分享物料。
- **badge-wall Shader 接 wall-v2**：把 `subpackageP3/pages/badge-wall` 的硬编码 `IDENTITY_CARDS` 装配改为消费 `/api/badge/wall-v2`。
- **L3/L4 上传后台**：运营/俱乐部图案上传接口（我们不出图）。

---

## Self-Review

- **Spec 覆盖**：本计划覆盖 spec 的"引擎地基"（§5 RuleEvaluator/规则表达、§8 badge_def 扩展、§8.3 沿用旧 code 兼容）+"成就页四层分区"（§10.1）。平台事件（§4.2）、漫游授予、分享物料（§10.3）明确登记到后续计划——与用户选择的切片一致。
- **占位扫描**：无 TBD/TODO；每个改代码步骤都给了完整代码；测试步骤都给了完整测试代码。
- **类型一致性**：`RuleEvaluator.parse/isThresholdMet`、`BadgeRule.getAggregate/getThreshold`、`buildWallV2`、`BadgeWallV2VO.getIdentity/getGrowth/...`、`GrowthBadgeMapper.selectAllActive/selectByCode` 在定义与消费处签名一致。
- **风险点**：① `getAppUserId()`、resultMap id、BaseController/AjaxResult 包路径以项目现有代码为准（已在对应步骤标注"以实际为准"）；② Task 4 跑全量单测防回归，若旧测试依赖 `BADGE_META` 需同步改 mock；③ 迁移 SQL 由用户服务器侧执行，jar 上线须后于加列。
