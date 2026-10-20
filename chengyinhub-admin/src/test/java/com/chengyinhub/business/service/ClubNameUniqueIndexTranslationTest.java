package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubLeaderMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.ClubServiceImpl;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 两位主理人同一时刻抢同一个新名称:countClubsHoldingName 都看到 0,库里的 uk_club_holding_name
 * (migration_club_name_unique_20261020.sql)让后到的那条写入撞键。服务层把这个索引的冲突翻译成与
 * {@link ClubNameUniquenessTest} 同一句 409;别的唯一键冲突原样抛出,不能被吞成「名称已被使用」。
 * 异常文本取自 mysql:8.0.45 实测:Duplicate entry '夜跑团' for key 'club.uk_club_holding_name'。
 */
class ClubNameUniqueIndexTranslationTest {
    private static final long OWNER = 900L;
    private static final String TAKEN = "俱乐部名称已被使用，请换一个";

    private ClubMapper clubs;
    private IClubService clubService;

    @BeforeEach
    void setUp() {
        clubs = mock(ClubMapper.class);
        // 竞态:查重那一刻对方的行还没提交,所以 count 为 0
        when(clubs.countClubsHoldingName(anyString(), any(), any())).thenReturn(0);
        when(clubs.selectOwnedClubIdsForUpdate(anyLong())).thenReturn(Collections.emptyList());

        IUmsMemberService members = mock(IUmsMemberService.class);
        when(members.lockMemberById(OWNER)).thenReturn(OWNER);
        UmsMember account = new UmsMember();
        account.setId(OWNER);
        account.setStatus(1L);
        account.setDelFlag(0);
        when(members.selectUmsMemberById(OWNER)).thenReturn(account);
        ClubLeader leader = new ClubLeader();
        leader.setId(700L);
        leader.setMemberId(OWNER);
        leader.setStatus(1);
        leader.setDelFlag(0);
        ClubLeaderMapper leaders = mock(ClubLeaderMapper.class);
        when(leaders.selectClubLeaderByMemberId(OWNER)).thenReturn(leader);

        ClubServiceImpl target = new ClubServiceImpl();
        ReflectionTestUtils.setField(target, "clubMapper", clubs);
        ReflectionTestUtils.setField(target, "clubLeaderMapper", leaders);
        ReflectionTestUtils.setField(target, "mmsMerchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(target, "merchantOperatorMapper", mock(MerchantOperatorMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberService", members);
        clubService = target;
    }

    @Test
    void aRacedNewClubOnTheHoldingNameIndexIsRefusedWith409() {
        when(clubs.insertClub(any(Club.class))).thenThrow(duplicate("club.uk_club_holding_name"));

        assertTaken(() -> clubService.insertClubWithinOwnerQuota(request("夜跑团"), 2));
    }

    @Test
    void aRacedResubmitOfARejectedApplicationIsRefusedWith409() {
        Club rejected = new Club();
        rejected.setId(55L);
        rejected.setMemberId(OWNER);
        rejected.setName("旧名");
        rejected.setStatus(2L);
        when(clubs.selectOwnedClubIdsForUpdate(OWNER)).thenReturn(List.of(55L));
        when(clubs.selectClubsByIds(anyList())).thenReturn(List.of(rejected));
        when(clubs.updateClub(any(Club.class))).thenThrow(duplicate("club.uk_club_holding_name"));

        assertTaken(() -> clubService.insertClubWithinOwnerQuota(request("夜跑团"), 2));
    }

    @Test
    void aRacedRenameIsRefusedWith409() {
        when(clubs.selectClubById(60L)).thenReturn(existing(60L, "旧名"));
        when(clubs.updateClub(any(Club.class))).thenThrow(duplicate("club.uk_club_holding_name"));

        assertTaken(() -> clubService.updateClub(rename(60L, "夜跑团")));
    }

    @Test
    void aRacedOwnerProfileRenameIsRefusedWith409() {
        when(clubs.selectClubByIdForUpdate(61L)).thenReturn(existing(61L, "旧名"));
        when(clubs.updateClub(any(Club.class))).thenThrow(duplicate("club.uk_club_holding_name"));

        assertTaken(() -> clubService.updateOwnedClubProfile(rename(61L, "夜跑团"), OWNER, null));
    }

    @Test
    void aRacedBackOfficeInsertIsRefusedWith409() {
        when(clubs.insertClub(any(Club.class))).thenThrow(duplicate("club.uk_club_holding_name"));

        assertTaken(() -> clubService.insertClub(request("夜跑团")));
    }

    @Test
    void anyOtherDuplicateKeyOnInsertStillPropagates() {
        DuplicateKeyException other = duplicate("club.PRIMARY");
        when(clubs.insertClub(any(Club.class))).thenThrow(other);

        assertSame(other, assertThrows(DuplicateKeyException.class,
                () -> clubService.insertClubWithinOwnerQuota(request("夜跑团"), 2)));
    }

    @Test
    void anyOtherDuplicateKeyOnUpdateStillPropagates() {
        DuplicateKeyException other = duplicate("club.uk_some_other_key");
        when(clubs.selectClubById(62L)).thenReturn(existing(62L, "旧名"));
        when(clubs.updateClub(any(Club.class))).thenThrow(other);

        assertSame(other, assertThrows(DuplicateKeyException.class,
                () -> clubService.updateClub(rename(62L, "夜跑团"))));
    }

    private static void assertTaken(org.junit.jupiter.api.function.Executable call) {
        ServiceException refused = assertThrows(ServiceException.class, call);
        assertEquals(TAKEN, refused.getMessage());
        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), refused.getCode());
    }

    /** 与 MyBatis + Spring 翻译后的形状一致:索引名只在底层 SQLException 的文本里。 */
    private static DuplicateKeyException duplicate(String key) {
        String text = "Duplicate entry '夜跑团' for key '" + key + "'";
        return new DuplicateKeyException("### Error updating database. Cause: "
                + "java.sql.SQLIntegrityConstraintViolationException",
                new SQLIntegrityConstraintViolationException(text, "23000", 1062));
    }

    private static Club existing(long id, String name) {
        Club club = new Club();
        club.setId(id);
        club.setMemberId(OWNER);
        club.setName(name);
        club.setStatus(1L);
        club.setDelFlag(0);
        return club;
    }

    private static Club rename(long id, String name) {
        Club club = new Club();
        club.setId(id);
        club.setName(name);
        return club;
    }

    private static Club request(String name) {
        Club club = new Club();
        club.setMemberId(OWNER);
        club.setName(name);
        club.setStatus(1L);
        club.setMemberCount(0);
        club.setDelFlag(0);
        return club;
    }
}
