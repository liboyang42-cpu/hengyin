package com.chengyinhub.business.mapper.push;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.push.PushDevice;

/** 推送设备登记（push_device）与推送扇出所需的只读投影。 */
public interface PushMapper
{
    /** 同 token+环境 改绑到当前会员、重新启用、刷新 last_seen。 */
    int upsertDevice(@Param("memberId") long memberId, @Param("platform") String platform, @Param("token") String token,
            @Param("environment") String environment, @Param("bundleId") String bundleId, @Param("locale") String locale);

    Long selectDeviceId(@Param("token") String token, @Param("environment") String environment);

    /** 只停用本人名下的该 token（任意环境）。 */
    int disableOwnedToken(@Param("memberId") long memberId, @Param("token") String token);

    /** APNs 判定失效（410 / BadDeviceToken …）时停用。 */
    int disableToken(@Param("token") String token, @Param("environment") String environment);

    List<PushDevice> selectEnabledDevices(@Param("memberIds") Collection<Long> memberIds, @Param("environment") String environment);

    /** 聊天消息与会话类型、发送者昵称。 */
    Map<String, Object> selectChatMessage(@Param("messageId") long messageId);

    /** 会话收件人（不含发送者）+ 会话级与账号默认通知偏好；屏蔽了发送者的人不在内。 */
    List<Map<String, Object>> selectChatAudience(@Param("conversationId") long conversationId, @Param("senderId") long senderId,
            @Param("single") boolean single);

    List<Map<String, Object>> selectMentions(@Param("messageId") long messageId);

    /** W5 C2：@角色 展开成成员（只算启用中的角色）。 */
    List<Long> selectRoleMemberIds(@Param("roleIds") List<Long> roleIds);

    /** 频道所属俱乐部（im_channel.guild_kind = 1）。 */
    Long selectChannelClubId(@Param("conversationId") long conversationId);

    Integer selectClubDefaultLevel(@Param("clubId") long clubId);

    /** 某会员对某范围（2 俱乐部 / 4 账号默认）的偏好行。 */
    Map<String, Object> selectPref(@Param("memberId") long memberId, @Param("scopeType") int scopeType, @Param("scopeId") long scopeId);

    int markCampaignPush(@Param("campaignId") long campaignId);

    int countCampaignPush(@Param("campaignId") long campaignId);
}
