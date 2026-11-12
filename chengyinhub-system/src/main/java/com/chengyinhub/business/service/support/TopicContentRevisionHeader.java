package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.vo.TopicContentRevisionView;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 把「当前发布修订头」绑进新局的冻结快照头(BE-06)。
 *
 * <p>为什么只写进快照头、不改既有字段:进行中的个人/共享局继续读自己那份
 * {@code moduleConfigSnapshotJson} / {@code configSnapshotJson},不会因为之后重新发布而切换内容。
 * 这里加的四个键只是让「这局按哪条不可变修订冻结」可追溯,不参与任何判题/范围计算。
 *
 * <p>没有修订行(存量发布头未导入 / 非发布冻结的配置)时<b>原样返回</b>,
 * 不伪造 id、不拿 live 模板现编 hash —— 老局行为一字不变。
 */
public final class TopicContentRevisionHeader {

    public static final String FIELD_REVISION_ID = "contentRevisionId";
    public static final String FIELD_REVISION_NO = "contentRevisionNo";
    public static final String FIELD_SCHEMA_VERSION = "contentSchemaVersion";
    public static final String FIELD_SHA256 = "contentSha256";

    private TopicContentRevisionHeader() {
    }

    /**
     * @param frozen   新局即将写入的冻结快照(会被就地补键)
     * @param revision 当前发布修订窄视图;null = 老主题,快照保持原样
     */
    public static void bind(ObjectNode frozen, TopicContentRevisionView revision) {
        if (frozen == null || revision == null || revision.getId() == null) return;
        frozen.put(FIELD_REVISION_ID, revision.getId());
        if (revision.getRevisionNo() != null) frozen.put(FIELD_REVISION_NO, revision.getRevisionNo());
        if (revision.getSchemaVersion() != null) frozen.put(FIELD_SCHEMA_VERSION, revision.getSchemaVersion());
        if (revision.getContentSha256() != null) frozen.put(FIELD_SHA256, revision.getContentSha256());
        // F1:冻结地理随新局落进快照的 $.themeConfig.contentGeography(BE-14 冻结口径)。
        // 只从该修订自己的 manifest 取(发布当时的值),绝不读当前 live 的 cms_topic.city_code;
        // 冻结图已有值则不覆盖,payload 没有 themeConfig 时不创建(创建会改变旅程/玩法判定)。
        TopicContentGeography geography = TopicContentGeography.fromManifestJson(revision.getPublicManifestJson());
        if (geography != null) geography.writeIntoRunSnapshot(frozen);
    }
}
