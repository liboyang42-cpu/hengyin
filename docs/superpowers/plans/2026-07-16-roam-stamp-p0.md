# 漫游集邮 P0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户能在小程序里用「集邮相机」拍一张照片、自动加齿孔咬边变成邮票、存进自己的集邮册并看到它。

**Architecture:** UGC 邮票走**独立表 `roam_stamp`**,绝不进 `growth_badge`(字典表)。后端两个接口(create/list);前端两页(相机 / 集邮册),入口挂 member 成就 tab。齿孔几何与 collage 布局抽成**纯函数**,用 `node --test` 做 TDD;相机与画布走 automator 自证。

**Tech Stack:** Java 8 / Spring Boot(RuoYi 多模块)· MyBatis XML mapper · MySQL 8.0.45 · 微信小程序原生 · canvas 2d · `node --test`

## Global Constraints

- **Java 8 API only**:`target=1.8`,本机 JDK21 无 `--release`,用 Java 9+ API 本地过 CI 挂。
- **UGC 绝不进 `growth_badge`**:`buildWallV2` 是 `selectAllActive()`,无 member 过滤、无分页,全量发给每个人。
- **邮票列表必须按 member 过滤 + 分页**,不能一次拉数百张。
- **默认私密**:邮票只进自己集邮册,不进广场、不进他人信息流。
- **迁移是 ③**:`migration_*` 前缀 + `chengyinhub-admin/sql/` **第一层**,两条缺一即被 CI `migration_layout` 拦。合并前必须先落库。
- **不碰 `/roam/reveal`**:走路散雾逻辑一行不动。
- **不碰 badge-wall**:`engine.js:309 GRID=4` 上限 16 枚,邮票不上那面墙。
- **P0 只做自由拍**:`kind=0`、`geohash7` 恒 `NULL`。盖销(P1)、集邮成就(P2)不在本计划。
- 提交遵循「停在 commit」:不自动 push、不自动开 PR。
- UI 改动走截图闸门:automator 出图 + 给用户看图。

---

### Task 1: ③ 迁移 —— `roam_stamp` 表

**Files:**
- Create: `chengyinhub-admin/sql/migration_p6_roam_stamp.sql`

**Interfaces:**
- Consumes: 无
- Produces: 表 `roam_stamp`,列 `id/member_id/pic_url/caption/kind/geohash7/lat/lng/city_code/check_state/check_trace/del_flag/create_time`;`uk_member_geohash(member_id, geohash7)`;`idx_member_time(member_id, create_time)`

**为什么 P0 就把 `uk` 和盖销列建好**:`uk(member_id, geohash7)` 允许多行 NULL,
所以 P0 的普通票(`geohash7=NULL`)无限多、P1 的盖销一格一枚 —— **一个索引喂两档**,
已在生产库 MySQL 8.0.45 用 TEMPORARY TABLE 实测(含负控:同格第二次 `1062`)。
一次建好省掉 P1 再开一份 ③ 迁移。

- [ ] **Step 1: 写迁移文件**

```sql
-- migration_p6_roam_stamp.sql
-- 走 chengyin-db-migrate:只读预检 `--describe roam_stamp` 确认表不存在后执行。
-- 新建表,不改存量,可逆(DROP TABLE)。必须先于引用它的 jar 上线。
--
-- 【uk 一个索引喂两档】MySQL 唯一索引允许多行 NULL:
--   普通票 geohash7=NULL  → 不受约束,无限多
--   盖销票 geohash7 非空  → 一格一生一枚(P1 用)
-- 已在生产库 8.0.45 实测坐实(TEMPORARY TABLE + 负控:同格第二次报 1062)。
--
-- 【注释与字面量禁 ASCII 分号】2026-07-14 实证:COMMENT 里的半角分号会被按分号
-- 拆句的执行器劈开。本文件一律用中文逗号。
--
-- 上线后校验:
-- SELECT COUNT(*) FROM roam_stamp

CREATE TABLE roam_stamp (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  member_id   BIGINT        NOT NULL COMMENT '归属用户',
  pic_url     VARCHAR(500)  NOT NULL COMMENT 'OSS 原图 URL',
  caption     VARCHAR(140)  NULL COMMENT '用户自填说明',
  kind        TINYINT       NOT NULL DEFAULT 0 COMMENT '0=普通(自由拍),1=盖销(P1)',
  geohash7    CHAR(7)       NULL COMMENT '盖销专用,与 roam_fog_tile 同精度,普通票为 NULL',
  lat         DECIMAL(10,6) NULL,
  lng         DECIMAL(10,6) NULL,
  city_code   VARCHAR(16)   NULL,
  check_state TINYINT       NOT NULL DEFAULT 0 COMMENT '0=未送检,1=通过,2=违规下架',
  check_trace VARCHAR(64)   NULL COMMENT 'checkMediaAsync 的 trace_id',
  del_flag    TINYINT       NOT NULL DEFAULT 0,
  create_time DATETIME      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_geohash (member_id, geohash7),
  KEY idx_member_time (member_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='漫游集邮 UGC 邮票';
```

- [ ] **Step 2: 跑迁移布局门禁,确认不被拦**

Run: `bash ci/migration-layout-check.sh`
Expected: `迁移布局门禁:通过`

- [ ] **Step 3: 用仓库分句器验证可被正确分句(必须先 git add,否则门禁看不见未跟踪文件)**

Run:
```bash
git add chengyinhub-admin/sql/migration_p6_roam_stamp.sql
python3 -c "
import sys; sys.path.insert(0,'ci')
from sql_splitter import split_statements, unterminated_token
t=open('chengyinhub-admin/sql/migration_p6_roam_stamp.sql',encoding='utf-8').read()
print('unterminated:', unterminated_token(t) or 'None')
print('statements:', len(split_statements(t)))
"
```
Expected: `unterminated: None` 且 `statements: 1`

- [ ] **Step 4: 跑迁移安全门禁**

Run: `bash ci/migration-safety-check.sh`
Expected: `迁移安全门禁:通过`,且 `[2/4]` 一节列出 `migration_p6_roam_stamp.sql`(不是「跳过」)

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(stamp): ③ 迁移——roam_stamp 表(UGC 邮票,uk 一个索引喂两档)"
```

---

### Task 2: 后端 —— domain + mapper + 两个接口 + 单测

**Files:**
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/domain/RoamStamp.java`
- Create: `chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/RoamStampMapper.java`
- Create: `chengyinhub-system/src/main/resources/mapper/business/RoamStampMapper.xml`
- Create: `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRoamStampController.java`
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/RoamStampControllerTest.java`

**Interfaces:**
- Consumes: Task 1 的表
- Produces:
  - `ApiRoamStampController.normalizeCaption(String) -> String`(静态纯函数,截断 140 + trim,null 安全)
  - `ApiRoamStampController.clampPageSize(Integer) -> int`(静态纯函数,夹到 [1,50],默认 20)
  - `POST /api/roam/stamp/create`(`picUrl`, `caption`)→ `AjaxResult{ id }`
  - `POST /api/roam/stamp/list`(`pageNum`, `pageSize`)→ `AjaxResult{ list, total }`
  - `RoamStampMapper.insertStamp(RoamStamp) -> int`、`selectByMember(Long, int, int) -> List<RoamStamp>`、`countByMember(Long) -> int`

**测试策略**:仓库既有 `RoamCheckinTest` 的路数是**把纯逻辑抽成 static 方法直接测**(不起 Spring 上下文)。
照这个来:`normalizeCaption` / `clampPageSize` 是本任务真正会出错的地方(越界、null),用 `node`… 不,用 JUnit 直接测。
入库幂等由 `uk` 保证,属数据层,P1 盖销时才真正需要,本任务不测。

- [ ] **Step 1: 写失败的测试**

```java
package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.Test;

import static com.chengyinhub.web.controller.api.ApiRoamStampController.clampPageSize;
import static com.chengyinhub.web.controller.api.ApiRoamStampController.normalizeCaption;
import static org.junit.jupiter.api.Assertions.*;

/** 集邮接口的纯逻辑:caption 归一 + 分页夹取。入库幂等靠 uk,属数据层不在此测。 */
class RoamStampControllerTest {

    @Test
    void normalizeCaption_nullAndBlankBecomeNull() {
        assertNull(normalizeCaption(null));
        assertNull(normalizeCaption("   "), "全空白等于没写");
    }

    @Test
    void normalizeCaption_trimsAndTruncatesTo140() {
        assertEquals("hi", normalizeCaption("  hi  "));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('a');
        assertEquals(140, normalizeCaption(sb.toString()).length(), "超长截到 140,不能让 DB 报 1406");
    }

    @Test
    void clampPageSize_defaultsAndClamps() {
        assertEquals(20, clampPageSize(null), "没传用默认 20");
        assertEquals(20, clampPageSize(0), "0 不合法,回默认");
        assertEquals(1, clampPageSize(1));
        assertEquals(50, clampPageSize(999), "夹到上限 50,防一次拉数百张");
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -o -pl chengyinhub-admin -am test -Dtest='RoamStampControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 —— `找不到符号: ApiRoamStampController`

- [ ] **Step 3: 写 domain**

```java
package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 漫游集邮 UGC 邮票 roam_stamp。普通票 geohash7=NULL,盖销票(P1)一格一枚靠 uk。 */
public class RoamStamp {
    private Long id;
    private Long memberId;
    private String picUrl;
    private String caption;
    private Integer kind;
    private String geohash7;
    private BigDecimal lat;
    private BigDecimal lng;
    private String cityCode;
    private Integer checkState;
    private String checkTrace;
    private Integer delFlag;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getPicUrl() { return picUrl; }
    public void setPicUrl(String picUrl) { this.picUrl = picUrl; }
    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }
    public Integer getKind() { return kind; }
    public void setKind(Integer kind) { this.kind = kind; }
    public String getGeohash7() { return geohash7; }
    public void setGeohash7(String geohash7) { this.geohash7 = geohash7; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }
    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal lng) { this.lng = lng; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public Integer getCheckState() { return checkState; }
    public void setCheckState(Integer checkState) { this.checkState = checkState; }
    public String getCheckTrace() { return checkTrace; }
    public void setCheckTrace(String checkTrace) { this.checkTrace = checkTrace; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
```

- [ ] **Step 4: 写 mapper 接口**

```java
package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RoamStamp;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 漫游集邮 Mapper。列表必须按 member 过滤 + 分页,不能全量拉。 */
public interface RoamStampMapper {

    int insertStamp(RoamStamp stamp);

    /** 我的邮票:按时间倒序分页。只返回未删除且未被判违规的。 */
    List<RoamStamp> selectByMember(@Param("memberId") Long memberId,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    int countByMember(@Param("memberId") Long memberId);
}
```

- [ ] **Step 5: 写 mapper XML**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper
PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
"http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.chengyinhub.business.mapper.RoamStampMapper">

    <resultMap id="RoamStampResult" type="com.chengyinhub.business.domain.RoamStamp">
        <id property="id" column="id"/>
        <result property="memberId" column="member_id"/>
        <result property="picUrl" column="pic_url"/>
        <result property="caption" column="caption"/>
        <result property="kind" column="kind"/>
        <result property="geohash7" column="geohash7"/>
        <result property="lat" column="lat"/>
        <result property="lng" column="lng"/>
        <result property="cityCode" column="city_code"/>
        <result property="checkState" column="check_state"/>
        <result property="checkTrace" column="check_trace"/>
        <result property="delFlag" column="del_flag"/>
        <result property="createTime" column="create_time"/>
    </resultMap>

    <insert id="insertStamp" parameterType="com.chengyinhub.business.domain.RoamStamp"
            useGeneratedKeys="true" keyProperty="id">
        insert into roam_stamp (member_id, pic_url, caption, kind, geohash7, lat, lng, city_code,
                                check_state, check_trace, del_flag, create_time)
        values (#{memberId}, #{picUrl}, #{caption}, #{kind}, #{geohash7}, #{lat}, #{lng}, #{cityCode},
                #{checkState}, #{checkTrace}, #{delFlag}, now())
    </insert>

    <select id="selectByMember" resultMap="RoamStampResult">
        select id, member_id, pic_url, caption, kind, geohash7, lat, lng, city_code,
               check_state, check_trace, del_flag, create_time
        from roam_stamp
        where member_id = #{memberId} and del_flag = 0 and check_state != 2
        order by create_time desc, id desc
        limit #{offset}, #{limit}
    </select>

    <select id="countByMember" resultType="int">
        select count(*) from roam_stamp
        where member_id = #{memberId} and del_flag = 0 and check_state != 2
    </select>
</mapper>
```

- [ ] **Step 6: 写 controller**

```java
package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.RoamStamp;
import com.chengyinhub.business.mapper.RoamStampMapper;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 漫游集邮 · UGC 邮票(P0 只做自由拍)。
 *
 * 【为什么不进 growth_badge】那是定义表(字典),buildWallV2 用 selectAllActive() 全量发给每个人,
 * 无 member 过滤无分页。UGC 进去会让所有人的墙上出现所有人的照片,并撑爆字典表。
 * 邮票走本表 + 集邮册独立界面;将来与勋章的连接靠 RuleEvaluator 计数,不混表。
 *
 * 【默认私密】只返回自己的邮票,不进广场、不进他人信息流。
 */
@RestController
@Tag(name = "漫游集邮")
@RequestMapping("/api/roam/stamp")
public class ApiRoamStampController extends BaseController {

    static final int PAGE_SIZE_DEFAULT = 20;
    static final int PAGE_SIZE_MAX = 50;
    static final int CAPTION_MAX = 140;

    @Autowired
    private RoamStampMapper roamStampMapper;

    private Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    /** caption 归一:trim,全空白视为没写,超长截到 140(DB 列宽 140,不截会报 1406)。 */
    static String normalizeCaption(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        return s.length() > CAPTION_MAX ? s.substring(0, CAPTION_MAX) : s;
    }

    /** 分页夹取:默认 20,上限 50 —— 集邮册数百张,不能让客户端一次拉光。 */
    static int clampPageSize(Integer raw) {
        if (raw == null || raw <= 0) return PAGE_SIZE_DEFAULT;
        return Math.min(raw, PAGE_SIZE_MAX);
    }

    @Operation(summary = "存一枚邮票(自由拍)")
    @PostMapping("/create")
    public AjaxResult create(String picUrl, String caption) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        if (picUrl == null || picUrl.trim().isEmpty()) return error("图片不能为空");

        RoamStamp s = new RoamStamp();
        s.setMemberId(memberId);
        s.setPicUrl(picUrl.trim());
        s.setCaption(normalizeCaption(caption));
        s.setKind(0);            // P0 只有自由拍
        s.setGeohash7(null);     // 普通票不占格,uk 允许多行 NULL
        s.setCheckState(0);
        s.setDelFlag(0);
        roamStampMapper.insertStamp(s);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", s.getId());
        return success(data);
    }

    @Operation(summary = "我的集邮册(分页)")
    @PostMapping("/list")
    public AjaxResult list(Integer pageNum, Integer pageSize) {
        Long memberId = uid();
        if (memberId <= 0) return error("请先登录");
        int size = clampPageSize(pageSize);
        int num = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        List<RoamStamp> rows = roamStampMapper.selectByMember(memberId, (num - 1) * size, size);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("list", rows);
        data.put("total", roamStampMapper.countByMember(memberId));
        data.put("pageNum", num);
        data.put("pageSize", size);
        return success(data);
    }
}
```

- [ ] **Step 7: 跑测试确认通过**

Run: `mvn -o -pl chengyinhub-admin -am test -Dtest='RoamStampControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 8: 负控 —— 证明测试不是橡皮图章**

把 `clampPageSize` 的 `Math.min(raw, PAGE_SIZE_MAX)` 改成 `return raw;`,重跑上面的命令。
Expected: **红**,`clampPageSize_defaultsAndClamps` 报 `expected: <50> but was: <999>`。
然后**改回来**,重跑 → 绿。

- [ ] **Step 9: Commit**

```bash
git add chengyinhub-system/src/main/java/com/chengyinhub/business/domain/RoamStamp.java \
        chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/RoamStampMapper.java \
        chengyinhub-system/src/main/resources/mapper/business/RoamStampMapper.xml \
        chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRoamStampController.java \
        chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/RoamStampControllerTest.java
git commit -m "feat(stamp): ① 集邮后端——roam_stamp domain/mapper + create/list 接口"
```

---

### Task 3: 齿孔咬边纯函数 + 单测

**Files:**
- Create: `chengyinhub-xcx/subpackageP3/pages/stamp-camera/index/perforation.js`
- Test: `chengyinhub-xcx/tests/unit/stamp-perforation.test.js`

**Interfaces:**
- Consumes: 无
- Produces: `perforationCircles(w, h, opts) -> [{x, y, r}]` —— 沿矩形四边的打孔圆心;
  `opts = { r: 齿半径, gap: 齿间距 }`。调用方用 `destination-out` 把这些圆挖掉,即成邮票齿边。

**为什么抽纯函数**:齿孔是这个玩法的**唯一视觉签名**(STAMPA 就靠这一个装置把任何照片变成邮票),
几何算错就全盘皆输;而它又是纯计算,可以脱离小程序环境用 `node --test` 钉死。

- [ ] **Step 1: 写失败的测试**

```js
const test = require('node:test');
const assert = require('node:assert');
const { perforationCircles } = require('../../subpackageP3/pages/stamp-camera/index/perforation.js');

test('四角不重复打孔(重复会把角啃掉一大块)', () => {
  const cs = perforationCircles(200, 300, { r: 6, gap: 20 });
  const key = (c) => c.x.toFixed(3) + ',' + c.y.toFixed(3);
  const seen = new Set(cs.map(key));
  assert.equal(seen.size, cs.length, '不应有重合圆心');
});

test('圆心全部落在边上', () => {
  const w = 200, h = 300;
  const cs = perforationCircles(w, h, { r: 6, gap: 20 });
  for (const c of cs) {
    const onEdge = c.x === 0 || c.x === w || c.y === 0 || c.y === h;
    assert.ok(onEdge, `圆心 ${c.x},${c.y} 不在边上`);
  }
});

test('数量随尺寸增长,且半径透传', () => {
  const small = perforationCircles(100, 100, { r: 6, gap: 20 });
  const big = perforationCircles(400, 400, { r: 6, gap: 20 });
  assert.ok(big.length > small.length, '大图齿更多');
  assert.equal(small[0].r, 6);
});

test('极小尺寸不崩、不产生 0 齿', () => {
  const cs = perforationCircles(10, 10, { r: 6, gap: 20 });
  assert.ok(cs.length >= 4, '再小也要有四角');
});
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd chengyinhub-xcx && npm run test:unit 2>&1 | grep -A3 stamp-perforation`
Expected: FAIL —— `Cannot find module '.../perforation.js'`

- [ ] **Step 3: 写实现**

```js
// 邮票齿孔:沿矩形四边等距打孔的圆心。调用方用 destination-out 挖掉这些圆即成齿边。
// 这是整个集邮玩法的唯一视觉签名(任何照片 + 齿边 = 邮票),几何必须准。
// 四角只打一次:角上重复挖会把角啃掉一大块。
function perforationCircles(w, h, opts) {
  var r = (opts && opts.r) || 6;
  var gap = (opts && opts.gap) || 20;
  var nx = Math.max(1, Math.round(w / gap));
  var ny = Math.max(1, Math.round(h / gap));
  var out = [];
  var i;
  // 上下两边:含四角(i 从 0 到 nx)
  for (i = 0; i <= nx; i++) {
    var x = (w / nx) * i;
    out.push({ x: x, y: 0, r: r });
    out.push({ x: x, y: h, r: r });
  }
  // 左右两边:跳过 0 与 ny,避免与四角重复
  for (i = 1; i < ny; i++) {
    var y = (h / ny) * i;
    out.push({ x: 0, y: y, r: r });
    out.push({ x: w, y: y, r: r });
  }
  return out;
}

module.exports = { perforationCircles: perforationCircles };
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd chengyinhub-xcx && npm run test:unit 2>&1 | grep -E "stamp-perforation|^ℹ (pass|fail)"`
Expected: 4 个 stamp-perforation 用例全 pass,总计 `ℹ fail 0`

- [ ] **Step 5: 负控**

把实现里左右边的 `for (i = 1; i < ny; i++)` 改成 `for (i = 0; i <= ny; i++)`(制造四角重复),重跑。
Expected: **红**,`四角不重复打孔` 失败。改回来 → 绿。

- [ ] **Step 6: Commit**

```bash
git add chengyinhub-xcx/subpackageP3/pages/stamp-camera/index/perforation.js \
        chengyinhub-xcx/tests/unit/stamp-perforation.test.js
git commit -m "feat(stamp): 齿孔咬边纯函数 + 单测(四角不重复/圆心在边上)"
```

---

### Task 4: 集邮册 collage 布局纯函数 + 单测

**Files:**
- Create: `chengyinhub-xcx/subpackageP3/pages/stamp-album/index/layout.js`
- Test: `chengyinhub-xcx/tests/unit/stamp-album-layout.test.js`

**Interfaces:**
- Consumes: 无
- Produces: `collageLayout(count, opts) -> [{x, y, rot, z}]`;
  `opts = { cols, cellW, cellH, jitter, maxRot }`。**确定性**:同 `(count, opts)` 必得同结果。

**为什么要确定性**:STAMPA 的集邮册魅力在「散落、带角度、密集」——**乱才是魅力,不是网格**。
但真随机会导致每次进页面邮票乱跳,且无法测试。所以用 index 派生的伪随机:看起来乱,实际可复现。

- [ ] **Step 1: 写失败的测试**

```js
const test = require('node:test');
const assert = require('node:assert');
const { collageLayout } = require('../../subpackageP3/pages/stamp-album/index/layout.js');

const OPTS = { cols: 4, cellW: 90, cellH: 110, jitter: 16, maxRot: 12 };

test('确定性:同输入同输出(否则每次进页面邮票乱跳)', () => {
  assert.deepEqual(collageLayout(20, OPTS), collageLayout(20, OPTS));
});

test('数量与输入一致', () => {
  assert.equal(collageLayout(37, OPTS).length, 37);
});

test('旋转在 ±maxRot 内(超了会跟邻居叠成一团)', () => {
  for (const it of collageLayout(50, OPTS)) {
    assert.ok(Math.abs(it.rot) <= OPTS.maxRot, `rot ${it.rot} 越界`);
  }
});

test('抖动在 ±jitter/2 内,且不是网格(至少一半有偏移)', () => {
  const items = collageLayout(40, OPTS);
  let offset = 0;
  items.forEach((it, i) => {
    const baseX = (i % OPTS.cols) * OPTS.cellW;
    const dx = it.x - baseX;
    assert.ok(Math.abs(dx) <= OPTS.jitter / 2 + 1e-9, `dx ${dx} 越界`);
    if (Math.abs(dx) > 1) offset++;
  });
  assert.ok(offset > items.length / 2, '几乎没抖动就退化成网格了');
});

test('z 递增,后拍的压在上面', () => {
  const items = collageLayout(5, OPTS);
  for (let i = 1; i < items.length; i++) assert.ok(items[i].z > items[i - 1].z);
});
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd chengyinhub-xcx && npm run test:unit 2>&1 | grep -A3 stamp-album-layout`
Expected: FAIL —— `Cannot find module '.../layout.js'`

- [ ] **Step 3: 写实现**

```js
// 集邮册散落布局:网格打底 + 每格内确定性抖动/旋转。
// 参考 STAMPA 的月份 collage —— 散落、带角度、密集堆叠。【乱才是魅力,不是网格】。
// 但必须确定性:真随机会让每次进页面邮票乱跳,且没法测。用 index 派生伪随机:看着乱,实际可复现。
function rnd(i, salt) {
  var x = Math.sin(i * 12.9898 + salt * 78.233) * 43758.5453;
  return x - Math.floor(x);   // [0,1)
}

function collageLayout(count, opts) {
  var cols = opts.cols, cellW = opts.cellW, cellH = opts.cellH;
  var jitter = opts.jitter, maxRot = opts.maxRot;
  var out = [];
  for (var i = 0; i < count; i++) {
    var c = i % cols, r = Math.floor(i / cols);
    out.push({
      x: c * cellW + (rnd(i, 1) - 0.5) * jitter,
      y: r * cellH + (rnd(i, 2) - 0.5) * jitter,
      rot: (rnd(i, 3) - 0.5) * 2 * maxRot,
      z: i
    });
  }
  return out;
}

module.exports = { collageLayout: collageLayout };
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd chengyinhub-xcx && npm run test:unit 2>&1 | grep -E "stamp-album-layout|^ℹ (pass|fail)"`
Expected: 5 个用例全 pass,`ℹ fail 0`

- [ ] **Step 5: 负控**

把 `rnd` 改成 `return Math.random();`,重跑。
Expected: **红**,`确定性:同输入同输出` 失败。改回来 → 绿。

- [ ] **Step 6: Commit**

```bash
git add chengyinhub-xcx/subpackageP3/pages/stamp-album/index/layout.js \
        chengyinhub-xcx/tests/unit/stamp-album-layout.test.js
git commit -m "feat(stamp): 集邮册散落布局纯函数 + 单测(确定性/不退化成网格)"
```

---

### Task 5: 相机页(仪式)

**Files:**
- Create: `chengyinhub-xcx/subpackageP3/pages/stamp-camera/index/index.js`
- Create: `chengyinhub-xcx/subpackageP3/pages/stamp-camera/index/index.json`
- Create: `chengyinhub-xcx/subpackageP3/pages/stamp-camera/index/index.wxml`
- Create: `chengyinhub-xcx/subpackageP3/pages/stamp-camera/index/index.wxss`
- Modify: `chengyinhub-xcx/app.json`(注册页 + `scope.camera` 授权文案)

**Interfaces:**
- Consumes: `perforationCircles`(Task 3)、`POST /api/roam/stamp/create`(Task 2)、
  `app.getUploadClient().uploadAll(filePaths, { onDone, mapResult })` → `POST /api/common/uploadOSS`,返回 `{code:200, url}`
- Produces: 页面路由 `/subpackageP3/pages/stamp-camera/index/index`;
  自证探针 `__composeProbe(w, h) -> { circles: number }`(automator 用)

**⚠️ 风险最高的一步**:`<camera>` / `createCameraContext` **全仓零先例**。
所以本任务**先验相机能不能起来**,再谈美术。真机 API 在 devtools 上行为可能不同,automator 只能验到「组件挂载 + takePhoto 有回调」。

**取景器规格**(先立骨架,不追 STAMPA 的金属机身):
- 全屏 `<camera>` 打底
- 中央一个邮票取景框(白色齿边描出),框外压暗(四块半透明遮罩)
- 底部大圆快门键
- `Pinch to resize` 用 `bindtouchstart/bindtouchmove` 双指距离改框宽,范围 [40%, 90%] 屏宽
- 拍完 `takePhoto` → `wx.canvasToTempFilePath` 裁剪 + 挖齿孔 → 预览 → 「存入集邮册」→ 上传 → create

- [ ] **Step 1: 写 index.json**

```json
{
  "navigationStyle": "custom",
  "usingComponents": {}
}
```

- [ ] **Step 2: app.json 注册页 + 相机授权文案**

在 `subpackages` 里 `root: "subpackageP3"` 的 `pages` 数组中,于 `"pages/badge-wall/index/index"` **之前**插入:
```
"pages/stamp-camera/index/index",
"pages/stamp-album/index/index",
```
并在顶层 `permission` 对象中增加(与既有 `scope.userLocation` 并列):
```json
"scope.camera": { "desc": "用于集邮相机拍摄城市邮票" }
```

- [ ] **Step 3: 写 index.wxml**

```xml
<view class="sc-root">
  <camera wx:if="{{!shot}}" class="sc-cam" device-position="back" flash="off"
          binderror="onCamErr" bindtouchstart="onTouchStart" bindtouchmove="onTouchMove"></camera>
  <image wx:else class="sc-cam" src="{{shot}}" mode="aspectFill"></image>

  <!-- 取景框:齿边由 canvas 绘制 -->
  <canvas type="2d" id="frame" class="sc-frame"
          style="width:{{frameW}}px;height:{{frameH}}px;left:{{frameL}}px;top:{{frameT}}px;"></canvas>

  <view class="sc-hint" wx:if="{{!shot}}">双指缩放邮票大小</view>

  <view class="sc-err" wx:if="{{camErr}}">
    <text>相机没能启动</text>
    <button size="mini" bindtap="openSetting">去设置里开启</button>
  </view>

  <view class="sc-bar">
    <view wx:if="{{!shot}}" class="sc-shutter" bindtap="onShutter"></view>
    <block wx:else>
      <view class="sc-retake" bindtap="onRetake">重拍</view>
      <view class="sc-save {{saving?'on':''}}" bindtap="onSave">{{saving ? '存入中…' : '存入集邮册'}}</view>
    </block>
  </view>
</view>
```

- [ ] **Step 4: 写 index.wxss**

```css
.sc-root { position: relative; width: 100vw; height: 100vh; background: #000; }
.sc-cam { position: absolute; left: 0; top: 0; width: 100%; height: 100%; }
.sc-frame { position: absolute; pointer-events: none; }
.sc-hint { position: absolute; left: 0; right: 0; bottom: 220rpx; text-align: center;
  color: rgba(255,255,255,.72); font-size: 24rpx; }
.sc-err { position: absolute; left: 0; right: 0; top: 45%; text-align: center; color: #fff; }
.sc-bar { position: absolute; left: 0; right: 0; bottom: 60rpx; height: 160rpx;
  display: flex; align-items: center; justify-content: center; gap: 40rpx; }
.sc-shutter { width: 128rpx; height: 128rpx; border-radius: 50%; background: #fff;
  box-shadow: 0 0 0 8rpx rgba(255,255,255,.28); }
.sc-retake, .sc-save { padding: 20rpx 48rpx; border-radius: 999rpx; font-size: 28rpx; }
.sc-retake { color: #fff; border: 2rpx solid rgba(255,255,255,.4); }
.sc-save { background: #7A5CFF; color: #fff; }
.sc-save.on { opacity: .6; }
```

- [ ] **Step 5: 写 index.js**

```js
// 集邮相机:把「拍照」做成「操作一台集邮机」。参考 STAMPA ——
// 核心不是功能是仪式,构图发生在按快门之前;退化成「从相册选图」就是相册不是集邮。
const app = getApp();
const PERF = require('./perforation.js');

Page({
  data: {
    frameW: 0, frameH: 0, frameL: 0, frameT: 0,
    shot: '', saving: false, camErr: false
  },

  onLoad() {
    const win = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    this.win = win;
    this._ratio = 0.62;                       // 邮票框占屏宽比例
    this.layoutFrame();
  },
  onReady() { this.drawFrame(); },

  layoutFrame() {
    const w = this.win.windowWidth, h = this.win.windowHeight;
    const fw = Math.round(w * this._ratio);
    const fh = Math.round(fw * 1.25);         // 邮票竖版 4:5
    this.setData({ frameW: fw, frameH: fh, frameL: Math.round((w - fw) / 2), frameT: Math.round((h - fh) / 2 - 40) });
  },

  drawFrame() {
    const that = this;
    wx.createSelectorQuery().in(this).select('#frame').fields({ node: true, size: true }).exec(function (res) {
      const item = res && res[0];
      if (!item || !item.node) return;
      const cv = item.node, ctx = cv.getContext('2d');
      const dpr = (that.win.pixelRatio || 2);
      cv.width = item.width * dpr; cv.height = item.height * dpr;
      ctx.scale(dpr, dpr);
      that._cv = cv; that._ctx = ctx;
      that.paint(ctx, item.width, item.height);
    });
  },

  // 白色邮票边 + 齿孔挖空。齿孔是这个玩法的唯一视觉签名。
  paint(ctx, w, h) {
    ctx.clearRect(0, 0, w, h);
    ctx.strokeStyle = 'rgba(255,255,255,.92)';
    ctx.lineWidth = 3;
    ctx.strokeRect(1.5, 1.5, w - 3, h - 3);
    const circles = PERF.perforationCircles(w, h, { r: 5, gap: 18 });
    ctx.globalCompositeOperation = 'destination-out';
    for (let i = 0; i < circles.length; i++) {
      const c = circles[i];
      ctx.beginPath(); ctx.arc(c.x, c.y, c.r, 0, Math.PI * 2); ctx.fill();
    }
    ctx.globalCompositeOperation = 'source-over';
    this._lastCircles = circles.length;
  },

  onTouchStart(e) {
    if (e.touches.length === 2) this._pinch0 = this.dist(e.touches) || 1;
  },
  onTouchMove(e) {
    if (e.touches.length !== 2 || !this._pinch0) return;
    const k = this.dist(e.touches) / this._pinch0;
    this._ratio = Math.min(0.9, Math.max(0.4, this._ratio * (1 + (k - 1) * 0.06)));
    this.layoutFrame();
    this.drawFrame();
  },
  dist(t) {
    const dx = t[0].x - t[1].x, dy = t[0].y - t[1].y;
    return Math.sqrt(dx * dx + dy * dy);
  },

  onCamErr() { this.setData({ camErr: true }); },
  openSetting() { wx.openSetting({}); },

  onShutter() {
    const that = this;
    const cc = wx.createCameraContext();
    cc.takePhoto({
      quality: 'high',
      success(res) { that.setData({ shot: res.tempImagePath }); },
      fail() { wx.showToast({ title: '拍摄失败', icon: 'none' }); }
    });
  },
  onRetake() { this.setData({ shot: '' }); },

  onSave() {
    if (this.data.saving || !this.data.shot) return;
    this.setData({ saving: true });
    const that = this;
    app.getUploadClient().uploadAll([this.data.shot], {
      onDone(r) {
        const url = r && r.results && r.results[0] && r.results[0].url;
        if (!url) { that.setData({ saving: false }); wx.showToast({ title: '上传失败', icon: 'none' }); return; }
        app.sendRequest({
          url: '/api/roam/stamp/create', method: 'POST', data: { picUrl: url },
          success(res) {
            that.setData({ saving: false });
            if (res && res.code == '200') {
              wx.showToast({ title: '已入册' });
              setTimeout(function () { wx.redirectTo({ url: '/subpackageP3/pages/stamp-album/index/index' }); }, 600);
            } else {
              wx.showToast({ title: (res && res.msg) || '保存失败', icon: 'none' });
            }
          },
          fail() { that.setData({ saving: false }); wx.showToast({ title: '保存失败', icon: 'none' }); }
        });
      }
    });
  },

  // 自动化自证:回报当前齿孔数,证明取景框真的按纯函数绘制
  __composeProbe() { return { circles: this._lastCircles || 0, ratio: this._ratio }; }
});
```

- [ ] **Step 6: 编译 + 语法自检**

Run: `cd chengyinhub-xcx && node --check subpackageP3/pages/stamp-camera/index/index.js && node --check subpackageP3/pages/stamp-camera/index/perforation.js && python3 -c "import json;json.load(open('app.json'));print('app.json OK')"`
Expected: 两个 `--check` 无输出(通过)+ `app.json OK`

- [ ] **Step 7: Commit**

```bash
git add chengyinhub-xcx/subpackageP3/pages/stamp-camera chengyinhub-xcx/app.json
git commit -m "feat(stamp): ② 集邮相机页——齿边取景框 + pinch 调节 + 上传入册"
```

---

### Task 6: 集邮册页 + member 入口

**Files:**
- Create: `chengyinhub-xcx/subpackageP3/pages/stamp-album/index/index.js`
- Create: `chengyinhub-xcx/subpackageP3/pages/stamp-album/index/index.json`
- Create: `chengyinhub-xcx/subpackageP3/pages/stamp-album/index/index.wxml`
- Create: `chengyinhub-xcx/subpackageP3/pages/stamp-album/index/index.wxss`
- Modify: `chengyinhub-xcx/pages/member/index/index.js`(加 `goAlbum`,紧邻既有 `goWall`,约 715 行)
- Modify: `chengyinhub-xcx/pages/member/index/index.wxml`(成就 tab 内,勋章墙入口下方加集邮册入口)

**Interfaces:**
- Consumes: `collageLayout`(Task 4)、`POST /api/roam/stamp/list`(Task 2)
- Produces: 页面路由 `/subpackageP3/pages/stamp-album/index/index`;
  自证探针 `__layoutProbe() -> { n: number, total: number }`

**入口位置**:挂 member 成就 tab,与勋章墙并列(`member/index.js:715` 的 `goWall` 旁边)。
**不新增 tabBar** —— 尊重 PR#51 的 §8 IA 收敛。

- [ ] **Step 1: 写 index.json**

```json
{
  "navigationBarTitleText": "集邮册",
  "onReachBottomDistance": 120,
  "usingComponents": {}
}
```

- [ ] **Step 2: 写 index.wxml**

```xml
<view class="al-root">
  <view class="al-hd">
    <text class="al-n">{{total}}</text>
    <text class="al-u">枚</text>
  </view>

  <view class="al-stage" style="height:{{stageH}}px;">
    <view class="al-item" wx:for="{{items}}" wx:key="id"
          style="left:{{item.x}}px;top:{{item.y}}px;width:{{cellImgW}}px;height:{{cellImgH}}px;transform:rotate({{item.rot}}deg);z-index:{{item.z}};">
      <image class="al-img" src="{{item.picUrl}}" mode="aspectFill" lazy-load></image>
    </view>
  </view>

  <view class="al-empty" wx:if="{{loaded && total === 0}}">
    <text class="al-e1">还没有邮票</text>
    <text class="al-e2">上街拍一张,城市就进你的册子了</text>
    <view class="al-go" bindtap="goCamera">打开集邮相机</view>
  </view>

  <view class="al-more" wx:if="{{loading}}">加载中…</view>
  <view class="al-fab" bindtap="goCamera" wx:if="{{total > 0}}">＋</view>
</view>
```

- [ ] **Step 3: 写 index.wxss**

```css
.al-root { min-height: 100vh; background: #F6F5F2; padding: 24rpx; box-sizing: border-box; }
.al-hd { display: flex; align-items: baseline; gap: 8rpx; padding: 16rpx 8rpx 32rpx; }
.al-n { font-size: 72rpx; font-weight: 300; color: #1B1B1F; }
.al-u { font-size: 26rpx; color: #86868B; }
.al-stage { position: relative; }
.al-item { position: absolute; box-shadow: 0 2rpx 10rpx rgba(0,0,0,.12); background: #fff; }
.al-img { width: 100%; height: 100%; display: block; }
.al-empty { padding-top: 160rpx; text-align: center; }
.al-e1 { display: block; font-size: 32rpx; color: #1B1B1F; }
.al-e2 { display: block; margin-top: 12rpx; font-size: 26rpx; color: #86868B; }
.al-go { display: inline-block; margin-top: 40rpx; padding: 20rpx 48rpx; border-radius: 999rpx;
  background: #7A5CFF; color: #fff; font-size: 28rpx; }
.al-more { text-align: center; padding: 32rpx; color: #86868B; font-size: 24rpx; }
.al-fab { position: fixed; right: 40rpx; bottom: 80rpx; width: 104rpx; height: 104rpx;
  border-radius: 50%; background: #7A5CFF; color: #fff; font-size: 48rpx;
  display: flex; align-items: center; justify-content: center; box-shadow: 0 8rpx 24rpx rgba(122,92,255,.4); }
```

- [ ] **Step 4: 写 index.js**

```js
// 集邮册:散落 collage(参考 STAMPA 的月份视图)。【乱才是魅力,不是网格】。
// 数百枚必须分页 + 懒加载,不能一次拉光(接口上限 50/页)。
const app = getApp();
const LAYOUT = require('./layout.js');

const COLS = 4;

Page({
  data: {
    items: [], total: 0, stageH: 0,
    cellImgW: 0, cellImgH: 0,
    pageNum: 1, loading: false, loaded: false, noMore: false
  },

  onLoad() {
    const win = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    const usable = win.windowWidth - 24;          // 减去 padding
    this._cellW = usable / COLS;
    this._cellH = this._cellW * 1.25;
    this.setData({ cellImgW: Math.round(this._cellW * 0.86), cellImgH: Math.round(this._cellH * 0.86) });
    this.load();
  },
  onReachBottom() { if (!this.data.noMore) this.load(); },

  load() {
    if (this.data.loading || this.data.noMore) return;
    this.setData({ loading: true });
    const that = this;
    app.sendRequest({
      url: '/api/roam/stamp/list', method: 'POST',
      data: { pageNum: this.data.pageNum, pageSize: 50 },
      success(res) {
        const d = (res && res.code == '200' && res.data) ? res.data : null;
        if (!d) { that.setData({ loading: false, loaded: true }); return; }
        const rows = d.list || [];
        const merged = that.data.items.concat(rows.map(function (r) { return { id: r.id, picUrl: r.picUrl }; }));
        const pos = LAYOUT.collageLayout(merged.length, {
          cols: COLS, cellW: that._cellW, cellH: that._cellH, jitter: 16, maxRot: 12
        });
        const items = merged.map(function (m, i) {
          return { id: m.id, picUrl: m.picUrl, x: pos[i].x, y: pos[i].y, rot: pos[i].rot, z: pos[i].z };
        });
        that.setData({
          items: items,
          total: d.total || 0,
          stageH: Math.ceil(merged.length / COLS) * that._cellH + 60,
          pageNum: that.data.pageNum + 1,
          noMore: rows.length === 0 || merged.length >= (d.total || 0),
          loading: false, loaded: true
        });
      },
      fail() { that.setData({ loading: false, loaded: true }); }
    });
  },

  goCamera() { wx.navigateTo({ url: '/subpackageP3/pages/stamp-camera/index/index' }); },

  // 自动化自证:回报渲染了几枚 + 后端说共几枚
  __layoutProbe() { return { n: this.data.items.length, total: this.data.total }; }
});
```

- [ ] **Step 5: member 成就 tab 加入口**

在 `chengyinhub-xcx/pages/member/index/index.js` 的 `goWall` 之后加一行:
```js
  goAlbum: function () { wx.navigateTo({ url: '/subpackageP3/pages/stamp-album/index/index' }); },
```

在 `chengyinhub-xcx/pages/member/index/index.wxml` 里,勋章墙入口(含 `pc-wall-t` 的那个 view)**之后**插入:
```xml
        <view class="pc-wall" bindtap="goAlbum">
          <text class="pc-wall-t">集邮册</text>
          <text class="pc-wall-s">上街拍到的城市 · 查看全部邮票</text>
        </view>
```

- [ ] **Step 6: 语法自检**

Run: `cd chengyinhub-xcx && node --check subpackageP3/pages/stamp-album/index/index.js && node --check subpackageP3/pages/stamp-album/index/layout.js && node --check pages/member/index/index.js && npm run test:unit 2>&1 | grep -E "^ℹ (pass|fail)"`
Expected: `--check` 全过;`ℹ fail 0`

- [ ] **Step 7: Commit**

```bash
git add chengyinhub-xcx/subpackageP3/pages/stamp-album chengyinhub-xcx/pages/member/index
git commit -m "feat(stamp): ② 集邮册散落 collage 页 + member 成就 tab 入口"
```

---

### Task 7: automator 自证 + 截图闸门

**Files:**
- Create: `chengyinhub-xcx/scripts/_verify_stamp.js`(driver 按惯例**不入库**,`scripts/_*` 已被 .gitignore 挡)

**Interfaces:**
- Consumes: Task 5 的 `__composeProbe()`、Task 6 的 `__layoutProbe()`

**⚠️ 两条铁律**:
1. `projectPath` **必须指向本 worktree**。仓库既有 driver 全部硬编码主仓
   `/Users/arlan/Downloads/chengyin/chengyinhub-xcx`,照抄会去测 master 的码拿假绿。
2. **检查器必做负控**,否则「全绿」毫无意义。

- [ ] **Step 1: 写 driver**

```js
const automator = require('/Users/arlan/Downloads/chengyin/chengyinhub-xcx/node_modules/miniprogram-automator');
const path = require('path');
const PROJECT = '/Users/arlan/Downloads/chengyin-worktrees/stamp-p0/chengyinhub-xcx';
const DIR = path.join(__dirname, '_shots');
require('fs').mkdirSync(DIR, { recursive: true });

const results = [];
const check = (n, p, d) => { results.push({ n, p }); console.log((p ? '  ✓ ' : '  ✗ ') + n + (d ? '  → ' + d : '')); };

(async () => {
  const mp = await automator.launch({ projectPath: PROJECT, cliPath: '/Applications/wechatwebdevtools.app/Contents/MacOS/cli' });
  const errors = [];
  mp.on('console', m => { if (m.type === 'error') errors.push(String(m.args && m.args[0]).slice(0, 160)); });

  // ── 集邮册:空态 ──
  const album = await mp.reLaunch('/subpackageP3/pages/stamp-album/index/index');
  await album.waitFor(2200);
  await mp.screenshot({ path: path.join(DIR, 'album.png') });
  const ap = await album.callMethod('__layoutProbe');
  check('集邮册渲染数与后端 total 一致', ap.n <= ap.total || ap.total === 0, JSON.stringify(ap));

  // ── 集邮册:布局纯函数已在 Task 4 用 node --test 钉死(确定性/不退化成网格),
  //    此处只验页面真的调用了它、渲染数与后端一致 ──

  // ── 相机页 ──
  const cam = await mp.reLaunch('/subpackageP3/pages/stamp-camera/index/index');
  await cam.waitFor(2500);
  await mp.screenshot({ path: path.join(DIR, 'camera.png') });
  const cp = await cam.callMethod('__composeProbe');
  check('取景框按纯函数打了齿孔(circles > 0)', cp.circles > 0, JSON.stringify(cp));
  check('初始邮票框比例在 [0.4,0.9]', cp.ratio >= 0.4 && cp.ratio <= 0.9, 'ratio=' + cp.ratio);

  console.log('\nCONSOLE_ERRORS: ' + errors.length);
  errors.slice(0, 5).forEach(e => console.log('  ! ' + e));
  const bad = results.filter(r => !r.p);
  console.log('\n=== ' + (results.length - bad.length) + '/' + results.length + (bad.length ? ' FAILED' : ' ALL_GREEN') + ' ===');
  await mp.close();
  process.exit(bad.length || errors.length ? 1 : 0);
})().catch(e => { console.error('DRIVER_ERROR:', e && e.stack); process.exit(2); });
```

- [ ] **Step 2: 跑 driver**

Run: `cd chengyinhub-xcx && node scripts/_verify_stamp.js`
Expected: `ALL_GREEN` + `CONSOLE_ERRORS: 0`

- [ ] **Step 3: 负控 —— 证明检查器不是橡皮图章**

把 `perforation.js` 的 `return out;` 临时改成 `return [];`,重跑 driver。
Expected: **红**,`取景框按纯函数打了齿孔(circles > 0)` 失败。改回来 → 绿。

- [ ] **Step 4: 看图(截图闸门)**

`Read` 这两张图,**真看**,别只看 exit code:
- `chengyinhub-xcx/scripts/_shots/camera.png` —— 取景框齿边是否咬得干净、四角有没有被啃、快门键位置
- `chengyinhub-xcx/scripts/_shots/album.png` —— 集邮册是散落的还是退化成网格

⚠️ **devtools 截图不含 WebGL 同层内容**,但本页用的是 canvas 2d(非 webgl)+ 原生 `<camera>`。
**原生 `<camera>` 组件在 devtools 截图里大概率是黑/占位** —— 这是已知边界,如实报告,别把黑框说成"拍到了"。
相机真实画面必须真机验。

- [ ] **Step 5: 把图给用户看,等点头**

按「UI 改动截图闸门」:给用户看图 + 如实说明哪些是 automator 验到的、哪些必须真机。
**用户点头前不 ship。**

- [ ] **Step 6: 清理**

```bash
git checkout -- chengyinhub-xcx/project.config.json 2>/dev/null || true
git status --short   # 必须干净:driver 与截图已被 .gitignore(scripts/_*)挡掉
```

---

## 收尾(不属于任何 Task)

- **③ 迁移落库**:合并前必须先跑 `chengyin-db-migrate`,否则 jar 一上线 `roam_stamp` 不存在 → create 直接 500。
- **顺序**:迁移落库 → jar 部署 → 小程序微信上传。
- **不自动 ship**:停在 commit,`/ship` 等用户明说。

## Self-Review

**1. Spec 覆盖**

| spec 章节 | 对应 Task |
|---|---|
| §3 Seam(UGC 不进字典表) | Task 2(独立表 + controller 注释写明理由) |
| §4 两档规则 | Task 1 建好 `uk` 与盖销列;**P0 只做自由拍**(spec §9 明示),盖销属 P1 |
| §5 相机仪式 | Task 3(齿孔纯函数)+ Task 5(取景框 + pinch) |
| §6 数据模型 | Task 1 |
| §7 存储与合规 | Task 5 走 `uploadOSS`;**机审见下方缺口** |
| §8 界面分工 | Task 6(集邮册 collage,不碰 badge-wall) |
| §9 分期 | 本计划 = P0 |

**2. 已知缺口(诚实登记,不假装覆盖)**

- **机审(`checkMediaAsync`)本计划未接**。spec §7 写了送检,但送检的判决要靠 `wxa_media_check`
  回调,而回调需要**微信后台「消息推送」配置**(运维侧)。回调没配 = `check_state` 永远停在 0 =
  **送检是演戏**——这正是 `sys_job` 注册了却从没跑过那类病。
  P0 默认私密(只有自己看得见、不传播),先不接;**接的时候必须连回调一起接**,否则别接。
  表里 `check_state`/`check_trace` 已建好,`selectByMember` 也已 `check_state != 2` 过滤,P1 接上即生效。
- **`<camera>` 全仓零先例**,devtools 里原生组件截图大概率是黑框,**相机真实画面只能真机验**(Task 7 Step 4 已写明)。
- **OSS 合规 P0 未清**(合规八件套),本功能会拉高存储量级 —— 属产品/合规决策,不在本计划。

**3. 占位符扫描**:无 TBD/TODO;每个改码步骤都给了完整代码;每个测试步骤都给了完整测试代码。

**4. 类型一致性**:
`perforationCircles(w,h,{r,gap})->[{x,y,r}]`(Task 3 定义 / Task 5 消费)、
`collageLayout(count,{cols,cellW,cellH,jitter,maxRot})->[{x,y,rot,z}]`(Task 4 定义 / Task 6 消费)、
`normalizeCaption`/`clampPageSize`(Task 2 定义 / 同任务测试消费)、
`__composeProbe()->{circles,ratio}`、`__layoutProbe()->{n,total}`(Task 5/6 定义 / Task 7 消费)——
定义与消费处签名一致。
