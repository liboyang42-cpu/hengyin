# 退款订单级整单退(PR B)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把多票订单的退款收敛成「按父订单整单退、只对首单那笔真实微信交易退一次」,让 P0-A(多票从单退款双花/退 0)从根消失,并为 PR C(多票混买 UI)铺好安全的退款地基。

**Architecture:** 纯增量。单票路径(`parent_order_no IS NULL`)**一行不改** —— 那是当前生产唯一活着的路径(前端只发 `ticketId` 单数),零回归。多票路径改为:退父订单内任意一张 → 解析父订单 → 只对**首单**发起一次微信原路退款(金额 = 首单承载的汇总额) → 其余从单只标记/返积分/恢复库存。**首单判据 = `payable_amount > 0`**(`CmsRegistrationServiceImpl:370` 首单=汇总、`:272-273` 从单=0),该判据今天即成立,无需任何前置修复。

**Tech Stack:** Java 8(`target=1.8`)· Spring · MyBatis · JUnit 5 + Mockito · Maven

## Global Constraints

- **Java 8 API only** —— 本机 JDK 21 但 `maven.compiler.target=1.8` 且无 `--release`,用 `List.of`/`Map.of`/`Optional.isEmpty`/`String.isBlank` 会本地过、CI 挂。
- **金额一律 `BigDecimal`**,比较用 `compareTo` 不用 `equals`。
- **不物理删列/字段** —— 回滚/审计需要。
- **测试放 `chengyinhub-admin/src/test/java/`**(`chengyinhub-system` 无 test sourceset,尽管业务代码在那儿)。
- **零 SQL / 零迁移** —— 本 PR 不需要 `chengyin-db-migrate`;属①后端运行时,合并即 Flow 部署。
- ⚠️ **合并前置(全局)**:生产 `chengyin.pii.key` 必须已注入且 `piiBackfillTask` 已回填,否则 master 的 fail-closed `PiiCryptoTypeHandler` 一部署就让报名瘫痪。见 `~/Documents/城瘾/小程序/P0_部署雷_39加密生产没生效_2026-07-15.md`。
- **停在 commit** —— 不 push、不开 PR,等用户明说。

## ⛔ 明确不做(踩过的坑)

**不要「单独修 P0-A」**,即不要把 `CmsRegistrationServiceImpl:277` 的从单 `wechatPaymentAmount` 清零。

该外科修法曾于 `bfa5f066` 实施,合前对抗复审发现它**引入回归**并已 `git revert`(`a8d4618c`):从单 `wechatPaymentAmount=0` → `TeamGroupServiceImpl:259` 的 `hasCash=false` → 走 `:288` 的 `markRefunded(r)` → **不调微信、不进 §6.1 人工兜底队列、静默标记已退**。这把一个**响亮的失败**(微信拒单 → 人工待办 → 有人看得见)换成了**静默的资损**(用户被标已退、钱没退、零信号)。修复比缺陷更危险。

**当前状态是安全的**:#34 §6.1 已把「退款失败 → 转可提现余额(二清)」改成「失败 → 人工兜底队列」,活跃自动出血已关闭。整单退落地后,从单那笔假现金将成为**永不被读取的死数据**(整单退只对首单调微信),P0-A 自然消失。清零它属可选卫生,**不在本 PR**。

## 背景事实(已逐条验证,勿凭文档)

| 事实 | 证据 |
|---|---|
| 前端从不发多票 | `baoming.js:718-733` payload 为 `ticketId` 单数;`pages/activity/` 内 `stepper\|购物车\|quantity` 零命中 |
| 多票分叉点 | `ApiRegistrationController.java:246` `if (param.getTickets() != null && !...isEmpty())`,else 注释「单票模式(兼容旧调用)」 |
| 后端多票契约已就绪 | `RegistrationTicketItem` 已有 `setCount(int)`,PR C 主要是前端工作量 |
| 首单判据 | `:370` `first.setPayableAmount(newPayable)`(汇总);`:272-273` 从单 `payableAmount=0` |
| 首单承载汇总现金 | `:371` `first.setWechatPaymentAmount(newPayable)` |
| `parent_order_no` 从不发给微信 | 纯内部分组键(`:352` 生成 `"P"+yyyyMMdd+uuid8`);微信 `out_trade_no` 恒为 `registrationNo`(`WechatPayServiceImpl:358`),且只有首单被 `createOrder`(`ApiRegistrationController:289`) |
| `selectByParentOrderNo` 无序 | `CmsRegistrationMapper.xml:134-137` 无 ORDER BY |
| 超额闸对实付 0 短路 | `RefundServiceImpl.java:176-179` 条件含 `paidAmount > 0`;`:252-253` 让从单 `paidAmount=0` |
| `refundOne` 零测试覆盖 | `grep TeamGroup\|cancelPaidRegistration` 在 `src/test/` 零命中 |
| 分账冲销已存在但可能不全 | `RefundServiceImpl:211,314,330,363` 有按比例冲销;P0-B 修复 `d4e6fe87`(逐条冲销全部收款方)**不在 master** |

## 设计护栏(源自方案 §3,逐条落到本 PR)

| § | 护栏 | 本 PR 怎么满足 |
|---|---|---|
| 3.1 | 购买快照,退款读快照不现算 | 首单 `wechat_payment_amount` = 下单那刻算死的交易总额;每票快照在各自 `total_amount`。退款只读这两个字段,不做「总价除票数」反推 |
| 3.2 | 一次网关调用 | 整单退只对首单调一次 `wechatPayService.refund`(微信单笔订单退款 **1 次/分钟**频控 + 单笔最多 **50 次**部分退,多调即被拒) |
| 3.3 | 应用层先校验累计 | Task 2 修超额闸,使「退款额 ≤ 实付额」在应用层生效,不依赖网关兜底 |
| 3.4 | 幂等单号 | 沿用 `out_refund_no = "RF" + 首单id`,确定性、重试复用同号 |
| 3.5 | 分账 clawback 联动 | 整单退走 `refundService.reviewRefund` → 既有按比例冲销链路。**P0-B(多方收款方漏冲)不在本 PR 范围**,见「不在本 PR」 |
| 3.7 | 微信 365 天退款窗口 | **本 PR 不实现**,记入「不在本 PR」 |

---

### Task 1: 修复超额退款闸的 `paidAmount=0` 短路

**Files:**
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/RefundServiceImpl.java:176-179`
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/RefundOverpayGuardTest.java`(Create)

**Interfaces:**
- Consumes: 无
- Produces: `saveRefund` 对「实付额为 0 的 registration」拒绝任何 > 0 的退款单(抛 `IllegalArgumentException`)。Task 2 依赖此闸做应用层兜底(护栏 §3.3)。

**为什么排第一:** 它是独立缺陷 —— `saveRefund` 还有别的调用方(人工退款流程),闸门短路本身就该修,且不依赖 Task 2。

- [ ] **Step 1: 写失败测试**

```java
package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 超额退款闸:实付 0 的报名不得落任何 > 0 的退款单(护栏 §3.3 应用层先校验)。
 * 缺陷:saveRefund 的条件含 paidAmount > 0,而 enrichRegistrationRefund 会把
 * payableAmount=0 的多票从单填成 paidAmount=0 ⇒ 闸门整条短路,任意金额都能落库。
 */
class RefundOverpayGuardTest {

    @Test
    void zeroPaidRegistrationRejectsPositiveRefund() {
        CmsRegistrationMapper regMapper = mock(CmsRegistrationMapper.class);
        RefundApplicationMapper appMapper = mock(RefundApplicationMapper.class);

        CmsRegistration sub = new CmsRegistration();
        sub.setId(7L);
        sub.setMemberId(88L);
        sub.setPaymentStatus(2);
        sub.setPayableAmount(BigDecimal.ZERO);              // 从单:应付 0
        sub.setTotalAmount(new BigDecimal("100.00"));
        sub.setOriginalAmount(new BigDecimal("100.00"));
        when(regMapper.selectCmsRegistrationById(7L)).thenReturn(sub);
        when(appMapper.insertRefundApplication(any())).thenReturn(1);

        RefundServiceImpl service = new RefundServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "refundApplicationMapper", appMapper);

        RefundApplication app = new RefundApplication();
        app.setSourceType("registration");
        app.setSourceId(7L);
        app.setRefundAmount(new BigDecimal("100.00"));      // 想退 100,实付 0
        app.setRefundNo("RF7");

        assertThrows(IllegalArgumentException.class, () -> service.saveRefund(app),
                "实付 0 的报名不得落 100 元退款单");
    }
}
```

⚠️ 装配用的字段名(`cmsRegistrationMapper` / `refundApplicationMapper`)以 `RefundServiceImpl` 里 `@Autowired` 的实际字段名为准,写测试前先 Read 该文件确认。

- [ ] **Step 2: 跑测试,确认它是红的**

```bash
cd /Users/arlan/Downloads/chengyin-worktrees/s0715-1414
mvn -q -pl chengyinhub-admin -am test -Dtest=RefundOverpayGuardTest -DfailIfNoTests=false 2>&1 | tail -c 3000
```
Expected: **FAIL** —— 没抛异常,退款单被落库。

- [ ] **Step 3: 修闸门**

`RefundServiceImpl.java:176-179`,把:
```java
        // HF-04(A-32): 退款额不得超过实付额,防客户端传超额 refundAmount
        if (refund.getPaidAmount() != null && refund.getPaidAmount().compareTo(BigDecimal.ZERO) > 0
                && refund.getRefundAmount().compareTo(refund.getPaidAmount()) > 0) {
            throw new IllegalArgumentException("退款金额不得超过实付金额");
        }
```
改为:
```java
        // HF-04(A-32): 退款额不得超过实付额,防客户端传超额 refundAmount。
        // 原条件含 paidAmount > 0,导致实付 0 的单(多票从单 payableAmount=0)整条短路 ——
        // 恰恰是最该拦的情形:实付 0 就不该退出任何钱。0 与正数一并纳入比较。
        if (refund.getPaidAmount() != null
                && refund.getRefundAmount().compareTo(refund.getPaidAmount()) > 0) {
            throw new IllegalArgumentException("退款金额不得超过实付金额");
        }
```

- [ ] **Step 4: 跑测试 + 全量回归**

```bash
mvn -q -pl chengyinhub-admin -am test -Dtest=RefundOverpayGuardTest -DfailIfNoTests=false 2>&1 | tail -c 2000
mvn -q -pl chengyinhub-admin -am test 2>&1 | tail -c 4000
```
Expected: 两条都 PASS / BUILD SUCCESS。

⚠️ 特别关注 `RefundPartialClawbackTest` 与 `ApiCoopRefundAlertTest`。若挂了,说明有现存流程依赖「paidAmount=0 时放行」—— **报告给用户,不要改测试迁就**。

- [ ] **Step 5: Commit**

```bash
git add chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/RefundServiceImpl.java \
        chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/RefundOverpayGuardTest.java
git commit -m "$(cat <<'EOF'
fix(refund): 超额退款闸对实付 0 的单短路 — 0 一并纳入比较

saveRefund 的 HF-04 闸条件含 paidAmount > 0,而 enrichRegistrationRefund 会把
payableAmount=0 的多票从单填成 paidAmount=0 ⇒ 闸门整条失效,任意金额退款单都能落库。
实付 0 恰恰是最该拦的情形。方案 §3.3「应用层先校验累计」的落地。


EOF
)"
```

---

### Task 2: 退款按父订单整单退

**Files:**
- Modify: `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TeamGroupServiceImpl.java`(新增 `refundWholeOrder`;`cancelPaidRegistration:135` 与 `cancelPaidRegistrationByOwner:186` 各加一处分叉)
- Modify: `chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMapper.xml:134-137`(`selectByParentOrderNo` 加 `order by id`)
- Test: `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/WholeOrderRefundTest.java`(Create)

**Interfaces:**
- Consumes: Task 1 的超额闸(应用层兜底)
- Produces: `private void refundWholeOrder(CmsRegistration anyMember, String ticketName, String reason)` —— 传父订单内**任意**一条 registration,整单退。

- [ ] **Step 1: 写失败测试**

先 Read `MultiTicketRefundIntegrityTest`(在 `bfa5f066` 里,`git show bfa5f066:chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/MultiTicketRefundIntegrityTest.java`)抄它的 `ReflectionTestUtils` 装配模式 —— **只抄装配写法,不要抄它的断言**(那个测试断言的是被 revert 的「从单=0」行为,与本 PR 相反)。

```java
package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.IWechatPayService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 整单退(方案 §3.2 一次网关调用):退父订单内任意一张 → 只对首单那笔真实微信交易退一次、
 * 金额=汇总;从单绝不单独调微信(其 registrationNo 从无 out_trade_no,调了必被拒)。
 * 首单判据 = payableAmount > 0(CmsRegistrationServiceImpl:370 汇总 / :272-273 从单=0)。
 */
class WholeOrderRefundTest {

    @Test
    void refundingSubOrderRefundsFirstOrderTransactionExactlyOnce() {
        CmsRegistrationMapper regMapper = mock(CmsRegistrationMapper.class);
        IWechatPayService wechatPayService = mock(IWechatPayService.class);

        CmsRegistration first = new CmsRegistration();
        first.setId(1L);
        first.setRegistrationNo("R001");
        first.setParentOrderNo("P20260715abcd1234");
        first.setPaymentStatus(2);
        first.setPayableAmount(new BigDecimal("200.00"));        // 首单=汇总 → 判据
        first.setWechatPaymentAmount(new BigDecimal("200.00"));  // 唯一真实交易额

        CmsRegistration sub = new CmsRegistration();
        sub.setId(2L);
        sub.setRegistrationNo("R002");
        sub.setParentOrderNo("P20260715abcd1234");
        sub.setPaymentStatus(2);
        sub.setPayableAmount(BigDecimal.ZERO);                   // 从单=0
        sub.setWechatPaymentAmount(new BigDecimal("100.00"));    // ← 假现金,整单退必须无视它

        List<CmsRegistration> order = new ArrayList<>();
        order.add(first);
        order.add(sub);
        when(regMapper.selectByParentOrderNo("P20260715abcd1234")).thenReturn(order);
        when(wechatPayService.refund(anyString(), anyString(), any(), any(), anyString())).thenReturn(true);

        // TODO(实现者): 按 MultiTicketRefundIntegrityTest 的 ReflectionTestUtils.setField 模式装配
        //   TeamGroupServiceImpl,注入 regMapper / wechatPayService / refundService / 其余依赖,
        //   然后调 service.refundWholeOrder(sub, "测试票", "用户申请退款")。
        //   依赖集合以 TeamGroupServiceImpl 的 @Autowired 字段为准,Read 后照填。

        ArgumentCaptor<String> outTradeNo = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(wechatPayService, times(1))
                .refund(outTradeNo.capture(), anyString(), amount.capture(), any(), anyString());

        assertEquals("R001", outTradeNo.getValue(), "只能对首单单号退款");
        assertEquals(0, new BigDecimal("200.00").compareTo(amount.getValue()), "退款额应=汇总 200,而非从单的 100");
    }
}
```

⚠️ 上面留了一个 `TODO(实现者)` —— `refundWholeOrder` 的确切依赖集合要 Read `TeamGroupServiceImpl` 的 `@Autowired` 字段后才能填。**Step 1 的完成标准是把它补成可编译、会失败的测试**,不是留着 TODO 就走。

- [ ] **Step 2: 跑测试,确认红**

```bash
mvn -q -pl chengyinhub-admin -am test -Dtest=WholeOrderRefundTest -DfailIfNoTests=false 2>&1 | tail -c 3000
```
Expected: FAIL —— `cannot find symbol: method refundWholeOrder`(编译失败也算红)。

- [ ] **Step 3: `selectByParentOrderNo` 加确定性排序**

`CmsRegistrationMapper.xml:134-137`,在 `where parent_order_no = #{parentOrderNo}` 后加一行 `order by id`:
```xml
    <select id="selectByParentOrderNo" parameterType="String" resultMap="CmsRegistrationResult">
        <include refid="selectCmsRegistrationVo"/>
        where parent_order_no = #{parentOrderNo}
        order by id
    </select>
```
理由:无 ORDER BY 时返回序依赖存储引擎,不可依赖。首单 = 循环 `i==0` 最先 insert = 最小 id,与 `payableAmount > 0` 判据互为交叉校验。

- [ ] **Step 4: 实现 `refundWholeOrder`**

在 `TeamGroupServiceImpl` 中新增(紧邻 `refundOne` 之后):

```java
    /**
     * 整单退(方案 §3.2):传入父订单内任意一条 registration,退掉整个父订单。
     * 一笔支付 = 一笔微信交易(挂首单 out_trade_no),故只对首单发起一次原路退款,金额=首单承载的汇总额;
     * 其余从单只标记/返积分/恢复库存 —— 它们的 registrationNo 从无微信交易,调了必被拒。
     * 首单判据 = payableAmount > 0(CmsRegistrationServiceImpl:370 汇总 / :272-273 从单=0)。
     */
    private void refundWholeOrder(CmsRegistration anyMember, String ticketName, String reason) {
        List<CmsRegistration> order = cmsRegistrationMapper.selectByParentOrderNo(anyMember.getParentOrderNo());
        if (order == null || order.isEmpty()) {
            refundOne(anyMember, ticketName, reason);   // 兜底:查不到父订单就退本条
            return;
        }
        // 整单可退性:任一已核销则拒绝,否则会把别人已用掉的票一起退了
        for (CmsRegistration r : order) {
            if (r.getVerificationStatus() != null && r.getVerificationStatus() == 1) {
                throw new IllegalArgumentException("同单存在已核销的票,不能整单退");
            }
        }
        // 首单 = payableAmount > 0 的那条(至多一条);纯积分单则无
        CmsRegistration payer = null;
        for (CmsRegistration r : order) {
            BigDecimal p = r.getPayableAmount();
            if (p != null && p.compareTo(BigDecimal.ZERO) > 0) {
                payer = r;
                break;
            }
        }
        // 现金退:只对首单一次(§3.2 一次网关调用)
        if (payer != null) {
            refundOne(payer, ticketName, reason);
        }
        // 其余(含纯积分单)只标记 + 返积分 + 恢复库存,绝不单独调微信
        for (CmsRegistration r : order) {
            if (payer != null && r.getId().equals(payer.getId())) {
                continue;
            }
            markRefunded(r);
            returnPoints(r);
            restoreInventory(r);
            CmsRegistration st = new CmsRegistration();
            st.setId(r.getId());
            st.setRegistrationStatus(3);
            st.setUpdateBy("auto-refund");
            st.setUpdateTime(new Date());
            cmsRegistrationMapper.updateCmsRegistration(st);
        }
    }
```

⚠️ `getVerificationStatus()` 的实际类型/取值以 `CmsRegistration` 实体为准,Read 后照改(前端 `enroll/index.js:164` 用的是 `verificationStatus != 1` 判可退)。

- [ ] **Step 5: 两个入口各加一处分叉**

`cancelPaidRegistration:135` 末尾的 `refundOne(...)` 调用改为:
```java
        if (StringUtils.isNotEmpty(r.getParentOrderNo())) {
            refundWholeOrder(r, ticketName, "用户申请退款");
        } else {
            refundOne(r, ticketName, "用户申请退款");   // 单票:行为完全不变
        }
```
`cancelPaidRegistrationByOwner:186` 同样处理(reason 沿用其原有文案)。

⚠️ 两处的 reason 字符串以现有代码为准,Read 后照填,别自己编。

- [ ] **Step 6: 跑测试 + 全量回归**

```bash
mvn -q -pl chengyinhub-admin -am test -Dtest=WholeOrderRefundTest -DfailIfNoTests=false 2>&1 | tail -c 2000
mvn -q -pl chengyinhub-admin -am test 2>&1 | tail -c 4000
```
Expected: 两条都绿。

- [ ] **Step 7: Commit**

```bash
git add chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TeamGroupServiceImpl.java \
        chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMapper.xml \
        chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/WholeOrderRefundTest.java
git commit -m "$(cat <<'EOF'
feat(refund): 多票订单改整单退 — 只对首单那笔真实微信交易退一次

一笔支付=一笔微信交易(挂首单 out_trade_no),退款的自然粒度是整单。
退父订单内任意一张 → 解析父订单 → 只对首单发起一次原路退款(金额=汇总),
其余从单只标记/返积分/恢复库存,绝不对无交易的单号调微信;同单有已核销票则拒绝整单退。
首单判据 = payableAmount > 0(:370 汇总 / :272-273 从单=0),今天即成立,无需前置修复。

P0-A 由此从根消失:整单退永不读从单的 wechatPaymentAmount,那笔假现金成为死数据。
不采用「从单微信应收清零」的外科修法 —— 它会让 refundOne 走 markRefunded 静默退 0、
丢人工兜底信号(见 bfa5f066 → a8d4618c 的 revert)。

单票路径(parent_order_no IS NULL)一行未改 —— 当前生产唯一活路径,零回归。
selectByParentOrderNo 补 order by id(原先无序不可依赖)。

对齐 Ticketmaster / Fever / 大麦 / Eventbrite 的整单退口径;满足方案 §3.2 一次网关调用
(微信单笔订单退款 1 次/分钟频控、单笔最多 50 次部分退)。


EOF
)"
```

---

## 完成后的验证闸

- [ ] `mvn -q -pl chengyinhub-admin -am test` 全绿,**把尾部输出贴给用户**(不许只说「通过了」)
- [ ] `/code-review` 走一遍本 PR 的 diff(**禁肉眼扫**)
- [ ] 确认零 SQL 改动 ⇒ 不需要 `chengyin-db-migrate`
- [ ] **停在 commit** —— 不 push、不开 PR

## 不在本 PR 范围

- **成团下线(PR A)** —— 会碰同一个 `TeamGroupServiceImpl`。⚠️ **两者有冲突面,需定序**(见下)
- **多票混买 UI(PR C)** —— 依赖本 PR;`RegistrationTicketItem.setCount` 契约已就绪
- **主办方取消活动整单退(PR D)** —— 依赖本 PR;策略已定 0 手续费全额退(方案 §7-2)
- **P0-B 多方分账退款漏冲** —— 修复 `d4e6fe87` 在 `fix/resui-sec-0715`,不在 master。方案 §3.5 要求整单退联动 clawback,但本 PR 只复用既有按比例冲销链路,不修多收款方漏冲。**独立处理**
- **微信 365 天退款窗口预判(§3.7)** —— 未实现,跨年活动/年卡需应用层预判,超期走人工兜底
- **清零从单 `wechatPaymentAmount`** —— 见「⛔ 明确不做」
- **`countPaidByTicket` 按行数不按人数**(`CmsRegistrationMapper.xml:61-63`)—— 成团下线后自然消失,不单独修

## 与 PR A 的定序

`refundOne` 的另一个调用方是 `processOneTicket`(散团退款),而 PR A 要移除它。两个 PR 都改 `TeamGroupServiceImpl`。

**建议 B 先 A 后**:B 只加方法 + 两处分叉,A 再删 `processOneTicket`/`evaluateTeamStatus` 时天然带走对 `refundOne` 的那个调用点,冲突面最小。反过来做则 A 先删了方法、B 还要在被删的邻近区域动刀,rebase 更痛。
