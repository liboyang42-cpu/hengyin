package com.chengyinhub.business.service.friend;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.channel.platform.ChannelInteractionService;
import com.chengyinhub.business.service.channel.platform.ProgressPrivacyService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务端指纹必须与 App 本地指纹逐字节一致（App 只在 payload_hash/requestHash 相等时才认回执）。
 * 期望值由独立实现（按 Swift 源码逐行复刻的 Python）算出，不是本类自证。
 */
class FriendProofsTest
{
    @Test void friendCommandProofsMatchSwift()
    {
        assertEquals("7c5a623617d52f4e51e6c3d02d9b6b2f15adf32e1c5a5cb8985e2aa9d49e208f",
                FriendProofs.command(42, "request", "一起玩", null, null, 0, 0, null));
        assertEquals("f4f331ddc3c631400d8e3145003d67076fa9fb6493865c9ad3c705e5860dd727",
                FriendProofs.command(42, "accept", "", 7L, null, 0, 0, null));
        assertEquals("d67f649e149652bf188f3306fbe63a995c1d743da78f2d282f196b73e295a9ef",
                FriendProofs.command(42, "request", "hi", null, null, 4, 9, "communityV1"));
    }

    @Test void removalAndMetadataFingerprintsMatchSwift()
    {
        assertEquals("cae81a8072539e9b4f5deaef0b27a49283a47bce9f75e73ccaec0fb1de1e5fd0", FriendProofs.removal(42, 7));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", "A1B2C3D4-0000-4000-8000-000000000001");
        body.put("expected_version", 0);
        body.put("peer_id", 42);
        body.put("expected_friend_version", 3);
        body.put("remark", "小二");
        body.put("group_id", null);
        assertEquals("8c6f07296b4a77786b1cc073381449662a2680cd699e74c1d98121b89fd76843", FriendProofs.metadata(body));
        body.put("starred", true);
        assertThrows(IllegalArgumentException.class, () -> FriendProofs.metadata(body), "booleans are not part of the v1 encoding");
    }

    @Test void channelInviteChainCheckinAndPrivacyDigestsMatchSwift()
    {
        assertEquals("9f526cdbb38ccef8fa6f4683987850b3dfca4394fb9bc6a9242590f8a932b2c3",
                FriendProofs.channelInvite("CREATE", 1001, "", List.of(new long[] { 5, 1 }, new long[] { 6, 2 }), List.of()));
        assertEquals("19034e8e4211b0f833cde9751ced8f996540bd1d4285d09741a6a919c2e77757",
                ChannelInteractionService.chainDigest("JOIN", "1001", "55", null, null, null, "我来"));
        assertEquals("5bab7f2ae2db333f5946086389109e30e6279a5a8323de3ef82278da512ef355",
                ChannelInteractionService.checkinDigest("1001", "2026-11-13"));
        Map<String, Object> privacy = new LinkedHashMap<>();
        privacy.put("request_id", "A1B2C3D4-0000-4000-8000-000000000001");
        privacy.put("aggregate_scope", "FRIEND");
        privacy.put("expected_updated_at", null);
        privacy.put("scope_type", "USER");
        privacy.put("scope_id", 42L);
        assertEquals("8055cf27a022ff22e152b344e258cac1337106c980cd665dc43423f68c79fe87", ProgressPrivacyService.fingerprint(privacy));
    }
}
