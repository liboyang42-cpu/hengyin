package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMemberIdentity;
import com.chengyinhub.business.mapper.UmsMemberIdentityMapper;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布者实名登记的服务层四条口径:必须单独同意、号码自洽、幂等而不覆盖、只回状态不回显。
 */
class MemberIdentityServiceTest {

    private static final long MEMBER_ID = 8801L;
    private static final String ID_CARD = "110105199003070017";
    private static final String SOURCE = MemberIdentityService.SOURCE_TOPIC_PUBLISH;

    private UmsMemberIdentityMapper mapper;
    private IConsentRecordService consent;
    private MemberIdentityService service;

    @BeforeEach
    void setUp() {
        mapper = mock(UmsMemberIdentityMapper.class);
        consent = mock(IConsentRecordService.class);
        service = new MemberIdentityService();
        ReflectionTestUtils.setField(service, "identityMapper", mapper);
        ReflectionTestUtils.setField(service, "consentRecordService", consent);
    }

    @Test
    void 登记成功落库并留一条单独同意() {
        service.register(MEMBER_ID, "张三", ID_CARD, true, SOURCE, "127.0.0.1");

        ArgumentCaptor<UmsMemberIdentity> captor = ArgumentCaptor.forClass(UmsMemberIdentity.class);
        verify(mapper).insertIdentity(captor.capture());
        UmsMemberIdentity stored = captor.getValue();
        assertEquals(MEMBER_ID, stored.getMemberId().longValue());
        assertEquals("张三", stored.getRealName());
        // 明文交给 typeHandler 加密,服务层只负责归一化(小写 x → 大写)
        assertEquals(ID_CARD, stored.getIdCard());
        assertEquals(SOURCE, stored.getSource());
        assertNotNull(stored.getConsentTime());
        verify(consent).record(eq(MEMBER_ID), eq(MemberIdentityService.CONSENT_DOC_TYPE),
                eq(MemberIdentityService.CONSENT_DOC_VERSION), eq(SOURCE), eq("AGREE"), anyString(), eq("127.0.0.1"));
    }

    /** PIPL 第29条:敏感个人信息要单独同意。没勾就不许落库,也不许留下同意记录。 */
    @Test
    void 没勾单独同意就拒_一行都不写() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.register(MEMBER_ID, "张三", ID_CARD, false, SOURCE, null));
        assertTrue(e.getMessage().contains("同意"));
        verify(mapper, never()).insertIdentity(any());
        verify(consent, never()).record(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void 身份证号不自洽就拒() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.register(MEMBER_ID, "张三", "110105199003070018", true, SOURCE, null));
        assertEquals("身份证号格式不正确,请核对后重填", e.getMessage());
        verify(mapper, never()).insertIdentity(any());
    }

    @Test
    void 姓名空或带数字都拒() {
        assertThrows(ServiceException.class,
                () -> service.register(MEMBER_ID, "  ", ID_CARD, true, SOURCE, null));
        assertThrows(ServiceException.class,
                () -> service.register(MEMBER_ID, "张3", ID_CARD, true, SOURCE, null));
        verify(mapper, never()).insertIdentity(any());
    }

    /** 入口写死白名单:source 会进库,不能由客户端随意填成任何字符串。 */
    @Test
    void 未知入口直接拒() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.register(MEMBER_ID, "张三", ID_CARD, true, "admin_backdoor", null));
        assertTrue(e.getMessage().contains("入口"));
        verify(mapper, never()).insertIdentity(any());
    }

    /**
     * 双击、弱网重发会打两次同一份资料。uk_member 撞车时必须幂等成功,
     * 而不是把「已登记」当错误抛给用户,也不是再写一条同意留痕。
     */
    @Test
    void 同一张证件重复提交幂等成功() {
        when(mapper.selectByMemberId(MEMBER_ID)).thenReturn(stored(ID_CARD));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("Duplicate entry for key 'uk_member'"))
                .when(mapper).insertIdentity(any());

        service.register(MEMBER_ID, "张三", ID_CARD.toLowerCase(), true, SOURCE, null);

        verify(consent, never()).record(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    /** 批复③「改绑走人工」:换一张证件不是更新,是另一次登记,必须拒。 */
    @Test
    void 换一张证件提交按改绑拒() {
        when(mapper.selectByMemberId(MEMBER_ID)).thenReturn(stored(ID_CARD));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("Duplicate entry for key 'uk_member'"))
                .when(mapper).insertIdentity(any());

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.register(MEMBER_ID, "李四", "31010119750101001X", true, SOURCE, null));
        assertTrue(e.getMessage().contains("改绑申请"), "X036:换证提示指向改绑申请(人工审核)");
    }

    @Test
    void 已登记的发布者过闸未登记的拿到统一文案() {
        when(mapper.existsByMemberId(MEMBER_ID)).thenReturn(true);
        assertNull(service.checkRegistered(MEMBER_ID));
        assertTrue(service.isRegistered(MEMBER_ID));

        when(mapper.existsByMemberId(9999L)).thenReturn(false);
        assertEquals(MemberIdentityService.MSG_NOT_REGISTERED, service.checkRegistered(9999L));
        assertFalse(service.isRegistered(9999L));
    }

    /** 未登录 memberId 不能靠 register 造出一条登记。 */
    @Test
    void 无主体直接拒() {
        assertThrows(ServiceException.class,
                () -> service.register(null, "张三", ID_CARD, true, SOURCE, null));
        assertThrows(ServiceException.class,
                () -> service.register(0L, "张三", ID_CARD, true, SOURCE, null));
        assertFalse(service.isRegistered(null));
        verify(mapper, never()).insertIdentity(any());
    }

    @Test
    void 状态读取不取证件且查询故障仍抛出() {
        when(mapper.existsByMemberId(MEMBER_ID)).thenReturn(true);
        when(mapper.selectByMemberId(MEMBER_ID)).thenThrow(new IllegalStateException("PII 解密失败"));
        assertNull(service.checkRegistered(MEMBER_ID));
        verify(mapper, never()).selectByMemberId(anyLong());
        assertFalse(service.isRegistered(null));
        assertFalse(service.isRegistered(0L));
        assertFalse(service.isRegistered(-1L));
        verify(mapper, never()).existsByMemberId(0L);
        verify(mapper, never()).existsByMemberId(-1L);
        when(mapper.existsByMemberId(9999L)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> service.isRegistered(9999L));
    }

    private static UmsMemberIdentity stored(String idCard) {
        UmsMemberIdentity identity = new UmsMemberIdentity();
        identity.setId(1L);
        identity.setMemberId(MEMBER_ID);
        identity.setRealName("张三");
        identity.setIdCard(idCard);
        identity.setSource(SOURCE);
        identity.setConsentTime(new Date());
        return identity;
    }
}
