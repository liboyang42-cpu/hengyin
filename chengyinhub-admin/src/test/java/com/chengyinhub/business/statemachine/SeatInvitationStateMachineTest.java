package com.chengyinhub.business.statemachine;

import com.chengyinhub.business.domain.RegistrationSeatInvitation;
import com.chengyinhub.business.domain.enums.SeatInvitationState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** qx-sm：席位邀请显式状态机——迁移表、非法迁移，以及「状态列只能经登记写入口改」lint。 */
class SeatInvitationStateMachineTest {

    private static final Path ROOT = locateRoot();
    private static final Path SERVICE = ROOT.resolve(
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/RegistrationSeatInvitationServiceImpl.java");
    private static final Path MAPPER = ROOT.resolve(
            "chengyinhub-system/src/main/resources/mapper/business/RegistrationSeatInvitationMapper.xml");

    @Test
    void legacyConstantsMatchEnumCodes() {
        assertEquals(RegistrationSeatInvitation.STATUS_PENDING, SeatInvitationState.PENDING.getCode());
        assertEquals(RegistrationSeatInvitation.STATUS_CLAIMED, SeatInvitationState.CLAIMED.getCode());
        assertEquals(RegistrationSeatInvitation.STATUS_CANCELLED, SeatInvitationState.CANCELLED.getCode());
        assertEquals(RegistrationSeatInvitation.STATUS_EXPIRED, SeatInvitationState.EXPIRED.getCode());
        assertEquals(RegistrationSeatInvitation.STATUS_RELEASED, SeatInvitationState.RELEASED.getCode());
    }

    /** 特征：服务里实际发生的四条迁移全部在表内（行为不变的前提）。 */
    @Test
    void everyTransitionTheServiceUsesIsAllowed() {
        assertTrue(SeatInvitationState.canTransition(0, 1));
        assertTrue(SeatInvitationState.canTransition(0, 2));
        assertTrue(SeatInvitationState.canTransition(0, 3));
        assertTrue(SeatInvitationState.canTransition(1, 4));
    }

    @Test
    void illegalTransitionsAreRejected() {
        for (SeatInvitationState terminal : List.of(SeatInvitationState.CANCELLED, SeatInvitationState.EXPIRED,
                SeatInvitationState.RELEASED)) {
            assertTrue(terminal.isTerminal());
            for (SeatInvitationState to : SeatInvitationState.values()) {
                assertFalse(SeatInvitationState.canTransition(terminal.getCode(), to.getCode()), terminal + "->" + to);
            }
        }
        assertFalse(SeatInvitationState.canTransition(1, 0), "已领取不能回到待领取");
        assertFalse(SeatInvitationState.canTransition(1, 2), "已领取不能被撤销，只能释放");
        assertFalse(SeatInvitationState.canTransition(0, 4));
        assertFalse(SeatInvitationState.canTransition(null, 1));
        assertFalse(SeatInvitationState.canTransition(9, 1));
        assertThrows(IllegalStateException.class, () -> SeatInvitationState.requireTransition(3, 1));
    }

    /** lint：服务里通用 transition 只能经 transitionGuarded 调用一次。 */
    @Test
    void genericTransitionIsOnlyCalledThroughTheGuard() throws IOException {
        String src = Files.readString(SERVICE, StandardCharsets.UTF_8);
        assertEquals(1, count(src, "invitations.transition("), "invitations.transition 必须只在 transitionGuarded 内调用");
        assertTrue(src.contains("SeatInvitationState.requireTransition(fromStatus, toStatus);"));
    }

    /** lint：Mapper 中写 status 的 statement 只允许登记的三个，且都带旧态 CAS。 */
    @Test
    void statusColumnIsOnlyWrittenByRegisteredStatements() throws IOException {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<update\\s+id=\"([^\"]+)\"[^>]*>(.*?)</update>", Pattern.DOTALL).matcher(xml);
        List<String> writers = new ArrayList<>();
        while (m.find()) {
            String[] parts = m.group(2).split("(?i)\\bwhere\\b", 2);
            if (!parts[0].matches("(?is).*\\bset\\b.*\\bstatus\\s*=.*")) continue;
            writers.add(m.group(1));
            assertTrue(parts.length == 2 && parts[1].matches("(?is).*\\bstatus\\s*=.*"), m.group(1) + " 缺旧态 CAS");
        }
        assertEquals(Set.of("claim", "transition", "expirePending"), new TreeSet<>(writers));
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static Path locateRoot() {
        for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("chengyinhub-system"))) return p;
        }
        throw new IllegalStateException("找不到仓库根");
    }
}
