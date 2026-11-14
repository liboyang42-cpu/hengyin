package com.chengyinhub.business.service.channel.platform;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.GuildStanding;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.Overwrite;

import static com.chengyinhub.business.service.channel.platform.ChannelCapability.*;
import static com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.effective;
import static com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.has;
import static org.junit.jupiter.api.Assertions.*;

class ChannelPermissionCalculatorTest
{
    @Test void nonMemberHasNothingEvenWithEveryoneAllow()
    {
        long bits = effective(GuildStanding.NONE, 0, List.of(), List.of(new Overwrite(1, 0, all(), 0)), 0);
        assertEquals(0L, bits);
    }

    @Test void memberGetsBaseButNoManagement()
    {
        long bits = effective(GuildStanding.MEMBER, 0, List.of(), List.of(), 0);
        assertTrue(has(bits, VIEW_CHANNEL) && has(bits, SEND_MESSAGE) && has(bits, READ_HISTORY));
        assertFalse(has(bits, MANAGE_CHANNEL) || has(bits, PIN_MESSAGE) || has(bits, VIEW_AUDIT));
    }

    @Test void everyoneDenyThenRoleAllowRestoresView()
    {
        List<Overwrite> o = List.of(new Overwrite(1, 0, 0, VIEW_CHANNEL.mask()), new Overwrite(2, 7, VIEW_CHANNEL.mask(), 0));
        assertFalse(has(effective(GuildStanding.MEMBER, 0, List.of(), o, 0), VIEW_CHANNEL));
        assertTrue(has(effective(GuildStanding.MEMBER, 0, List.of(7L), o, 0), VIEW_CHANNEL));
    }

    @Test void hiddenChannelDropsChannelBitsButKeepsGuildBitsForAdmins()
    {
        List<Overwrite> o = List.of(new Overwrite(1, 0, 0, VIEW_CHANNEL.mask()));
        long admin = effective(GuildStanding.ADMIN, 0, List.of(), o, 0);
        assertFalse(has(admin, SEND_MESSAGE));
        assertTrue(has(admin, MANAGE_CHANNEL));
    }

    @Test void overwriteCannotGrantGuildScopedBits()
    {
        List<Overwrite> o = List.of(new Overwrite(1, 0, MANAGE_CHANNEL.mask() | PIN_MESSAGE.mask(), 0));
        long bits = effective(GuildStanding.MEMBER, 0, List.of(), o, 0);
        assertFalse(has(bits, MANAGE_CHANNEL));
        assertTrue(has(bits, PIN_MESSAGE));
    }

    @Test void ownerBypassesDenyAndArchiveIsReadOnly()
    {
        List<Overwrite> o = List.of(new Overwrite(1, 0, 0, VIEW_CHANNEL.mask() | SEND_MESSAGE.mask()));
        assertTrue(has(effective(GuildStanding.OWNER, 0, List.of(), o, 0), SEND_MESSAGE));
        long archived = effective(GuildStanding.OWNER, 0, List.of(), List.of(), 1);
        assertTrue(has(archived, VIEW_CHANNEL) && has(archived, READ_HISTORY));
        assertFalse(has(archived, SEND_MESSAGE) || has(archived, PIN_MESSAGE));
        assertEquals(0L, effective(GuildStanding.OWNER, 0, List.of(), List.of(), 3));
    }

    @Test void hexParsingMatchesAppRegistry()
    {
        assertEquals(Long.valueOf(3L), parseHex("0x3"));
        assertNull(parseHex("3"));
        assertNull(parseHex("0x4000000"));
        assertEquals("0x8000000", hex(MANAGE_PROGRESS.mask()));
        assertEquals(List.of("VIEW_CHANNEL", "SEND_MESSAGE"), names(VIEW_CHANNEL.mask() | SEND_MESSAGE.mask()));
    }
}
