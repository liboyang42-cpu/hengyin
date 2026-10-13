package com.chengyinhub.business.security;

import com.chengyinhub.business.domain.MerchantCustomerProfile;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.ViewCollaborators;
import com.chengyinhub.business.mapper.MerchantCustomerProfileMapper;
import com.chengyinhub.business.mapper.ViewCollaboratorsMapper;
import com.chengyinhub.business.service.impl.MerchantCrmServiceImpl;
import com.chengyinhub.business.service.impl.ViewCollaboratorsServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会员侧 PII:密码哈希直出、以及 collaborators / CRM 的手机号直通。
 */
class MemberPiiLeakTest {

    private static final String REAL_PHONE = "13812341234";
    private static final String MASKED_PHONE = "138****1234";
    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuv";

    // ---------- 密码哈希 ----------

    /**
     * ★ 必须置 null 而不是空串。updateUmsMember 的动态 if 是 {@code <if test="password != null">}
     * ——只判 null、不判空串。而后台 member 编辑表单里有密码输入框、会把 getInfo 的返回整体 PUT 回来。
     * 若置成空串,管理员点一次保存就把密码写成空串,该用户再也登不上。
     * (ApiUmsMemberController.sanitizeForOthers 用空串是安全的——那条路径只读、不写回。)
     */
    @Test
    void 会员密码哈希必须置null_不能是空串否则编辑表单会把密码清掉() {
        UmsMember m = new UmsMember();
        m.setPassword(HASH);
        m.setPhone(REAL_PHONE);

        AdminPiiProtector.maskInPlace(m);

        assertNull(m.getPassword(), "必须是 null:空串会被 <if test=\"password != null\"> 放行,把密码写成空串");
    }

    @Test
    void 会员手机与姓名脱敏() {
        UmsMember m = new UmsMember();
        m.setPhone(REAL_PHONE);
        m.setName("张三丰");

        AdminPiiProtector.maskInPlace(m);

        assertEquals(MASKED_PHONE, m.getPhone());
        assertEquals("张*", m.getName());
    }

    @Test
    void 会员stripPii不碰密码_否则无权限者一保存就清掉别人密码() {
        UmsMember submitted = new UmsMember();
        submitted.setPhone(MASKED_PHONE);
        submitted.setName("张*");
        submitted.setPassword("管理员新设的密码");

        AdminPiiProtector.stripPii(submitted);

        assertNull(submitted.getPhone());
        assertNull(submitted.getName());
        assertEquals("管理员新设的密码", submitted.getPassword(),
                "stripPii 是给 PII 用的;密码是管理员主动填的,不该被它顺手抹掉");
    }

    // ---------- ViewCollaborators:后台 4 个 + 小程序 2 个调用方共用 ----------

    /**
     * 这条同时护住小程序:ApiTopicController:588 / ApiActivityController:720 的「阵容」
     * 走的是同一个 service,原先把 memberPhone 明文发给了所有看主题的用户(泄漏面是公众)。
     */
    @Test
    void 合作者视图在service层脱敏_一次覆盖后台与小程序全部6个调用方() {
        ViewCollaboratorsMapper mapper = mock(ViewCollaboratorsMapper.class);
        ViewCollaboratorsServiceImpl svc = new ViewCollaboratorsServiceImpl();
        ReflectionTestUtils.setField(svc, "viewCollaboratorsMapper", mapper);

        ViewCollaborators row = new ViewCollaborators();
        row.setMemberPhone(REAL_PHONE);
        row.setMemberRealName("张三丰");
        row.setMemberNickname("阿三");
        when(mapper.selectViewCollaboratorsList(any())).thenReturn(new ArrayList<>(Collections.singletonList(row)));

        List<ViewCollaborators> out = svc.selectViewCollaboratorsList(new ViewCollaborators());

        assertEquals(MASKED_PHONE, out.get(0).getMemberPhone());
        assertEquals("张*", out.get(0).getMemberRealName());
        assertEquals("阿三", out.get(0).getMemberNickname(), "昵称是自选公开展示名,阵容要靠它认人");
    }

    @Test
    void 合作者详情同样脱敏() {
        ViewCollaboratorsMapper mapper = mock(ViewCollaboratorsMapper.class);
        ViewCollaboratorsServiceImpl svc = new ViewCollaboratorsServiceImpl();
        ReflectionTestUtils.setField(svc, "viewCollaboratorsMapper", mapper);

        ViewCollaborators row = new ViewCollaborators();
        row.setMemberPhone(REAL_PHONE);
        when(mapper.selectViewCollaboratorsById(1L)).thenReturn(row);

        assertEquals(MASKED_PHONE, svc.selectViewCollaboratorsById(1L).getMemberPhone());
    }

    /**
     * ★ 这条钉的是「脱敏动作自己引入的回写风险」。
     * ViewCollaboratorsController 的 getInfo + edit 构成读→写回链;service 层脱敏后 getInfo 返回掩码,
     * 而 updateViewCollaborators 里有 {@code <if test="memberPhone != null">member_phone = ...</if>}
     * ——掩码会被写进 view_collaborators 并经可更新视图传导到基表 ums_member.phone,真号当场毁掉。
     */
    @Test
    void 合作者写回路径必须清掉PII列_否则掩码会被写进基表() {
        ViewCollaborators submitted = new ViewCollaborators();
        submitted.setId(1L);
        submitted.setMemberPhone(MASKED_PHONE);
        submitted.setMemberRealName("张*");
        submitted.setMemberNickname("阿三");

        AdminPiiProtector.stripPii(submitted);

        assertNull(submitted.getMemberPhone(), "掩码不得被写回,否则传导到 ums_member.phone 毁掉真号");
        assertNull(submitted.getMemberRealName());
        assertEquals("阿三", submitted.getMemberNickname(), "非 PII 列不该被顺手清掉");
    }

    /**
     * ★ 最严重的一条:ApiTopicController 把整个 UmsMember 塞进 /api/topic/info-to-user 的返回,
     * 而 UmsMember 零 Jackson 保护注解 ⇒ 任意小程序登录用户遍历主题 id 即可拖走发布者的
     * bcrypt 哈希 + 手机号 + wxOpenId。泄漏面是公众,不是员工。
     * 不能下沉到 selectUmsMemberById 修:它有 90+ 调用方靠明文 wxOpenId/password 干活。
     */
    @Test
    void C端下发的发布者实体必须清成公开资料() {
        UmsMember publisher = new UmsMember();
        publisher.setId(7L);
        publisher.setNickname("阿三");
        publisher.setAvatar("http://x/a.png");
        publisher.setPassword(HASH);
        publisher.setPhone(REAL_PHONE);
        publisher.setWechat("wx123");
        publisher.setWxOpenId("o_abc");
        publisher.setUnionId("u_abc");
        publisher.setLoginIp("1.2.3.4");
        publisher.setName("张三");
        publisher.setSex(1);
        publisher.setBirthday(new Date());
        publisher.setMark("运营备注");
        publisher.setWorkAddress("某公司某楼层");
        publisher.setPoint(999L);
        publisher.setRole("merchant");
        publisher.setCreateBy("admin");
        publisher.setRemark("内部备注");

        AdminPiiProtector.sanitizeMemberForPublic(publisher);

        assertEquals("", publisher.getPassword(), "陌生人不得拿到 bcrypt 哈希");
        assertNull(publisher.getPhone(), "陌生人连脱敏手机号都不该看到");
        assertNull(publisher.getWechat());
        assertNull(publisher.getWxOpenId(), "账号标识符不得外泄");
        assertNull(publisher.getUnionId());
        assertNull(publisher.getLoginIp());
        assertNull(publisher.getName(), "真实姓名不得作为公开昵称下发");
        assertNull(publisher.getSex());
        assertNull(publisher.getBirthday());
        assertNull(publisher.getMark());
        assertNull(publisher.getWorkAddress());
        assertNull(publisher.getPoint());
        assertNull(publisher.getRole());
        assertNull(publisher.getCreateBy());
        assertNull(publisher.getRemark());
        // 小程序只渲染 avatar/name/id,这些必须留着
        assertEquals("阿三", publisher.getNickname());
        assertEquals("http://x/a.png", publisher.getAvatar());
        assertEquals(Long.valueOf(7L), publisher.getId());
    }

    /**
     * ★ 钉住的是「调用点」而不是工具方法。
     *
     * <p>上一条只测了 sanitizeMemberForPublic 本身:把 controller 里的调用摘掉,它照样绿
     * ——那样修复可以被悄悄删掉而没有任何用例报警,正是假绿。本条直接扫源码,禁掉
     * 「把 selectUmsMemberById 的返回值原样塞进对外 VO」这个反模式本身。
     *
     * <p>驱动整个 ApiTopicController 需要 mock 十几个依赖,性价比不如这条结构断言。
     */
    @Test
    void C端不得把会员实体原样塞进对外VO_钉调用点而非工具方法() throws Exception {
        Path src = Paths.get("src/main/java/com/chengyinhub/web/controller/api/ApiTopicController.java");
        assertTrue(Files.exists(src), "源文件路径变了就得改这条断言,别让它静默失效:" + src.toAbsolutePath());
        String code = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);

        assertFalse(code.contains("setUmsMember(umsMemberService.selectUmsMemberById("),
                "禁止把 UmsMember 实体原样塞进对外 VO:该类零 Jackson 保护注解,"
                        + "password/phone/wxOpenId 会一起发给任意小程序用户。"
                        + "先过 AdminPiiProtector.sanitizeMemberForPublic 再 set。");

        int setters = countOccurrences(code, "topicInfoVO.setUmsMember(");
        int sanitizes = countOccurrences(code, "AdminPiiProtector.sanitizeMemberForPublic(");
        int whitelists = countOccurrences(code, "PublicMemberSummaryVO.from(");
        assertEquals(setters, sanitizes + whitelists,
                "每个下发会员资料的地方都必须脱敏或转独立白名单;实际 setUmsMember=" + setters
                        + " 次、sanitize=" + sanitizes + " 次、whitelist=" + whitelists + " 次");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length()))
        {
            n++;
        }
        return n;
    }

    // ---------- CRM ----------

    @Test
    void crm客户手机脱敏() {
        MerchantCustomerProfileMapper mapper = mock(MerchantCustomerProfileMapper.class);
        MerchantCrmServiceImpl svc = new MerchantCrmServiceImpl();
        ReflectionTestUtils.setField(svc, "merchantCustomerProfileMapper", mapper);

        MerchantCustomerProfile row = new MerchantCustomerProfile();
        row.setMemberPhone(REAL_PHONE);
        when(mapper.selectCustomerList(any())).thenReturn(new ArrayList<>(Collections.singletonList(row)));

        MerchantCustomerProfile query = new MerchantCustomerProfile();
        query.setMerchantId(9L);
        List<MerchantCustomerProfile> out = svc.selectCustomerList(query);

        assertEquals(MASKED_PHONE, out.get(0).getMemberPhone());
    }
}
