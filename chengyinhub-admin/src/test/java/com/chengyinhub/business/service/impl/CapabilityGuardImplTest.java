package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * X03 能力位闸的行为契约。
 *
 * <p>病灶原样：界面的权益清单告诉低阶身份「未解锁勋章设计」，
 * 而后端照收 medalName/medalImg。这些用例钉住服务端和那句承诺一致，
 * 并且**只**和那句承诺一致 —— 清单没提的机制不许被顺手拦掉。
 */
class CapabilityGuardImplTest
{
    private CapabilityGuardImpl guard;
    private IRoleService roleService;
    private IUmsMemberService memberService;
    private UserPermission perm;

    private static final long MEMBER = 42L;

    @BeforeEach
    void setUp()
    {
        guard = new CapabilityGuardImpl();
        roleService = mock(IRoleService.class);
        memberService = mock(IUmsMemberService.class);
        perm = new UserPermission();
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        when(memberService.selectUmsMemberById(MEMBER)).thenReturn(member);
        when(roleService.getPermission(any(UmsMember.class))).thenReturn(perm);
        ReflectionTestUtils.setField(guard, "umsMemberService", memberService);
        ReflectionTestUtils.setField(guard, "roleService", roleService);
    }

    /**
     * 各机制的配置都必须是 AdvancedGameConfigValidator 认可的**合法**形状 ——
     * 非法配置会在校验期就被 fail-closed 拦下，那样测到的是「解析失败」这条路径，
     * 不是能力位。这些形状逐条对着 validateXxx 的必填项抄的。
     */
    private static String config(String mechanic)
    {
        switch (mechanic)
        {
            case "timer":
                return json("timer", "\"durationSeconds\":60,\"timeoutResult\":\"EXPIRED\"");
            case "leaderboard":
                return json("leaderboard", "\"metric\":\"ELAPSED_TIME\",\"scope\":\"TOPIC\",\"limit\":10");
            case "multiplayer":
                // 人数/角色/轮次都要自洽:totalMin<=minPlayers 且 totalMax>=maxPlayers,
                // turnOrder 只能引用已声明的角色。
                return json("multiplayer",
                        "\"mode\":\"SEQUENTIAL\",\"minPlayers\":2,\"maxPlayers\":4,"
                        + "\"assignment\":\"AUTO\","
                        + "\"roles\":[{\"id\":\"r1\",\"label\":\"领队\",\"min\":1,\"max\":2},"
                        + "{\"id\":\"r2\",\"label\":\"队员\",\"min\":1,\"max\":2}],"
                        + "\"turnOrder\":[\"r1\",\"r2\"]");
            case "dailySign":
                return json("dailySign", "\"poems\":[[\"城中有风\"]]");
            case "steps":
                return json("steps", "\"goal\":3000");
            case "musicCorner":
                return json("musicCorner", "\"title\":\"街角\"");
            default:
                throw new IllegalArgumentException("用例未定义该机制的合法形状: " + mechanic);
        }
    }

    private static String json(String mechanic, String body)
    {
        return "{\"schemaVersion\":1,\"" + mechanic + "\":{\"enabled\":true," + body + "}}";
    }

    @Test
    void 空配置直接放行且不查权限()
    {
        assertNull(guard.checkAdvancedMechanics(MEMBER, null));
        assertNull(guard.checkAdvancedMechanics(MEMBER, ""));
        // 没启用任何机制就不该为它查一次权限 —— 每个模板保存都查是白花的库查询
        verify(memberService, never()).selectUmsMemberById(any());
    }

    @Test
    void 未解锁分支时拦下分支配置()
    {
        perm.setCanBranch(Boolean.FALSE);
        String msg = guard.checkAdvancedMechanics(MEMBER, branchConfig());
        assertNotNull(msg);
        assertTrue(msg.contains("分支"), msg);
    }

    @Test
    void 解锁分支后放行()
    {
        perm.setCanBranch(Boolean.TRUE);
        assertNull(guard.checkAdvancedMechanics(MEMBER, branchConfig()));
    }

    @Test
    void 博弈三机制任一启用都受同一位管辖()
    {
        perm.setCanGameMechanic(Boolean.FALSE);
        for (String mechanic : new String[] { "timer", "leaderboard", "multiplayer" })
        {
            String msg = guard.checkAdvancedMechanics(MEMBER, config(mechanic));
            assertNotNull(msg, mechanic + " 未解锁时必须拦下");
            assertTrue(msg.contains("博弈机制"), mechanic + " -> " + msg);
        }
        perm.setCanGameMechanic(Boolean.TRUE);
        for (String mechanic : new String[] { "timer", "leaderboard", "multiplayer" })
        {
            assertNull(guard.checkAdvancedMechanics(MEMBER, config(mechanic)), mechanic + " 解锁后必须放行");
        }
    }

    @Test
    void 权益清单没提的机制一概不拦()
    {
        // ★ 负控：dailySign / steps / musicCorner 这些是 Figma v5.1 新玩法，
        // 权益清单里根本没有对应条目。拦它们就是我在发明产品规则，不是在补强制点。
        perm.setCanBranch(Boolean.FALSE);
        perm.setCanGameMechanic(Boolean.FALSE);
        for (String mechanic : new String[] { "dailySign", "steps", "musicCorner" })
        {
            assertNull(guard.checkAdvancedMechanics(MEMBER, config(mechanic)),
                    mechanic + " 不在权益清单里，不该被能力位拦下");
        }
    }

    @Test
    void 机制未启用时不拦()
    {
        // ★ 负控：enabled=false 是「配了但关着」，等同没配。
        perm.setCanBranch(Boolean.FALSE);
        assertNull(guard.checkAdvancedMechanics(MEMBER,
                "{\"schemaVersion\":1,\"branch\":{\"enabled\":false}}"));
    }

    @Test
    void 勋章与建群两位各自独立()
    {
        perm.setCanDesignMedal(Boolean.FALSE);
        perm.setCanCreateGroup(Boolean.TRUE);
        assertNotNull(guard.checkDesignMedal(MEMBER));
        assertNull(guard.checkCreateGroup(MEMBER));

        perm.setCanDesignMedal(Boolean.TRUE);
        perm.setCanCreateGroup(Boolean.FALSE);
        assertNull(guard.checkDesignMedal(MEMBER));
        assertNotNull(guard.checkCreateGroup(MEMBER));
    }

    @Test
    void 能力位为null时按未解锁处理()
    {
        // ★ 负控：三值布尔的 null 不能被当成 true。
        // user_permission 行缺列、或新加的位还没回填，都会是 null——
        // 那时 fail-open 等于这道闸不存在。
        assertNotNull(guard.checkDesignMedal(MEMBER));
        assertNotNull(guard.checkCreateGroup(MEMBER));
        assertNotNull(guard.checkAdvancedMechanics(MEMBER, branchConfig()));
    }

    @Test
    void 取不到权限时不放行()
    {
        when(roleService.getPermission(any(UmsMember.class))).thenReturn(null);
        assertNotNull(guard.checkDesignMedal(MEMBER));
        assertNotNull(guard.checkCreateGroup(MEMBER));
    }

    @Test
    void 未登录不放行()
    {
        assertNotNull(guard.checkDesignMedal(null));
        assertNotNull(guard.checkCreateGroup(0L));
    }

    /**
     * 形状按 validateBranch 抄全:steps 数组 + startStepId + 终点要 outcomeCode、
     * 且终点不能带 options、非终点至少一个 option、必须存在可达终点。
     */
    private static String branchConfig()
    {
        return "{\"schemaVersion\":1,\"branch\":{\"enabled\":true,\"startStepId\":\"s1\","
                + "\"steps\":["
                + "{\"id\":\"s1\",\"title\":\"起点\",\"options\":["
                + "{\"id\":\"o1\",\"label\":\"往左\",\"nextStepId\":\"s2\"}]},"
                + "{\"id\":\"s2\",\"terminal\":true,\"outcomeCode\":\"END_A\",\"options\":[]}"
                + "]}}";
    }

    @Test
    void 配置读不懂时不放行()
    {
        // ★ 负控:这道闸早先写成「解析失败就放行,交回上游判」——
        // 那是把「我看不懂」当成「没启用受管机制」,读不懂输入就放行的闸等于没有。
        perm.setCanBranch(Boolean.FALSE);
        assertNotNull(guard.checkAdvancedMechanics(MEMBER, "{ 这不是 JSON"));
        assertNotNull(guard.checkAdvancedMechanics(MEMBER, "{\"schemaVersion\":999}"));
    }
}
