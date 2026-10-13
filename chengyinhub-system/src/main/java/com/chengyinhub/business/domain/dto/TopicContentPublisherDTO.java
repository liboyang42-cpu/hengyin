package com.chengyinhub.business.domain.dto;

/**
 * app 端「来源」栏所需的最小事实：生效 CONTENT 槽的主体类型 + 两个候选原始名。
 *
 * <p><b>为什么不直接在 SQL 里算好显示名</b>：后台的
 * {@code TopicOperationalSummaryMapper} 已经在 SQL 里用 CASE 算过一次五态显示名。
 * 若这里再抄一份 CASE，两处口径会各自漂移，而它们描述的是同一件事。
 * 所以本 DTO 只搬运<b>原始</b>列，显示名的五态映射统一收在
 * {@code TopicContentPublisherProjectionServiceImpl} 一个 Java 方法里，可被单测钉死。
 *
 * <p>⚠️ <b>刻意不带 entityId</b>。主体识别对 app 端只暴露「类型 + 公开名」，
 * 下发裸 ID 会把主体识别退化成可枚举的内部标识。
 *
 * @author chengyinhub
 * @date 2026-07-26
 */
public class TopicContentPublisherDTO {

    /** 主题 id。 */
    private Long topicId;

    /** 生效 CONTENT 槽的主体类型：PLATFORM / MEMBER / CLUB；异常值原样带出，由服务层归一。 */
    private String entityType;

    /** entityType=CLUB 时的俱乐部名；其余情况为 null。未 join 到（已删/脏数据）也为 null。 */
    private String clubName;

    /** entityType=MEMBER 时的会员昵称；其余情况为 null。未 join 到（已删/脏数据）也为 null。 */
    private String memberNickname;

    public Long getTopicId() { return topicId; }

    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getEntityType() { return entityType; }

    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getClubName() { return clubName; }

    public void setClubName(String clubName) { this.clubName = clubName; }

    public String getMemberNickname() { return memberNickname; }

    public void setMemberNickname(String memberNickname) { this.memberNickname = memberNickname; }
}
