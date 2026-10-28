package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.VoiceCloneClient;
import com.chengyinhub.business.support.NpcAvatar;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主题节点 NPC(CR-927,产品拍板 2026-09-15 #28「做」)。
 *
 * <p>数据落点在 {@code npc_profile} 的 scope_type=4(scope_id = cms_topic_node.id,一节点一条):
 * 名称/头像/台词复用门店分身那三列,语音复用 voice_id / voice_status / voice_sample 三列,
 * 可见性复用 audit_status/enabled —— 不新开表,也不发明第二套读路径。
 *
 * <p><b>审核口径与门店分身一致(先发后审,不自创)</b>:文本同步 msgSecCheck 拦违规;
 * 非预设头像异步送检(BIZ_TYPE_NODE_NPC,违规回调自动下架);新落库即 audit_status=1。
 * 玩家读统一只看 audit_status=1 AND enabled=1 AND del_flag='0'。
 * ⚠️ 下架过/未过审的行重新保存**不自动恢复**:回到待审(audit_status=0 + enabled=0),过审前玩家不可见(N2)。
 *
 * <p><b>编辑权</b>跟着「生效的承接」走。写入口(save/enroll/reset)用
 * {@link IChapterMerchantNodeService#requireOwnLiveNode}(归属 + 承接 + 冻结三闸,与点位内容同一口径);
 * 读入口(detail/voiceStatus)用 {@link IChapterMerchantNodeService#requireOwnNode}(归属 + 承接,不判冻结)
 * —— 主题开跑后不能改内容,但已经配好的必须读得回来。本类不自己重写这两条闸(两边口径漂了必漏一边)。
 *
 * <p>⚠️ 本类不碰 {@code node_audit_status}:编辑 NPC 不该把已过审点位打回待审,
 * 否则改一句台词就让整条点位从玩家侧消失。
 */
@Service
public class NodeNpcService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.ConsentGuard consentGuard;


    /** scope_type=4 = 主题节点 NPC(挂 cms_topic_node.id)。与 migration_node_npc_20260915.sql 注释同源。 */
    public static final int SCOPE_NODE = 4;

    /** voice_status:0=未配置 1=复刻中 2=可用 3=复刻失败(与门店分身同状态机)。 */
    public static final int VOICE_NONE = 0;
    public static final int VOICE_PENDING = 1;
    public static final int VOICE_READY = 2;
    public static final int VOICE_FAILED = 3;

    private final IChapterMerchantNodeService chapterMerchantNodeService;
    private final NpcProfileMapper npcProfileMapper;
    private final IWxContentSecurityService wxContentSecurityService;
    private final VoiceCloneClient voiceCloneClient;

    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.ai.NpcVoiceEnrollmentService voiceEnrollmentService;

    public NodeNpcService(IChapterMerchantNodeService chapterMerchantNodeService,
                          NpcProfileMapper npcProfileMapper,
                          IWxContentSecurityService wxContentSecurityService,
                          VoiceCloneClient voiceCloneClient) {
        this.chapterMerchantNodeService = chapterMerchantNodeService;
        this.npcProfileMapper = npcProfileMapper;
        this.wxContentSecurityService = wxContentSecurityService;
        this.voiceCloneClient = voiceCloneClient;
    }

    /** 商家读取本节点 NPC(含停用,给编辑回填);没有配过返回 null。 */
    public NpcProfile detailForMerchant(Long ownerMemberId, Long nodeId) {
        // 读闸,不是写闸:主题开跑后内容冻结,但商家仍要能看见自己配了什么(否则只有失败半屏)。
        chapterMerchantNodeService.requireOwnNode(ownerMemberId, nodeId);
        return npcProfileMapper.selectNodeNpcByNodeId(nodeId);
    }

    /**
     * 商家保存本节点 NPC(名称/头像/台词)。
     *
     * <p>不做成一个大事务:库里只有一条 insert/update,而送检是 best-effort 的外呼 ——
     * 把外呼圈进事务只会让一条行锁陪着等网络,换不来任何一致性。
     */
    public NpcProfile save(Long ownerMemberId, Long nodeId, NpcProfile draft, String openid) {
        chapterMerchantNodeService.requireOwnLiveNode(ownerMemberId, nodeId);
        if (draft == null) throw new IllegalStateException("参数有误");

        String name = trimToNull(draft.getName());
        String avatar = trimToNull(draft.getAvatar());
        String greeting = draft.getGreeting() == null ? null : draft.getGreeting().trim();
        if (name == null) throw new IllegalStateException("请填写角色名字");
        if (name.length() > 32) throw new IllegalStateException("名字最多 32 个字");
        if (avatar == null) throw new IllegalStateException("请选择形象");
        if (avatar.length() > 255) throw new IllegalStateException("形象地址过长");
        if (greeting != null && greeting.length() > 255) throw new IllegalStateException("台词最多 255 个字");
        if (!NpcAvatar.isAllowed(avatar)) throw new IllegalStateException("形象格式不支持");

        // 文本同步送检(与门店分身同一句口径):过了才落库,玩家侧看不到未检文本。
        String secMsg = wxContentSecurityService.checkText(
                IWxContentSecurityService.joinText(name, greeting), openid);
        if (secMsg != null) throw new IllegalStateException(secMsg);

        NpcProfile existing = npcProfileMapper.selectNodeNpcByNodeId(nodeId);
        NpcProfile saved;
        if (existing == null) {
            NpcProfile insert = new NpcProfile();
            insert.setScopeType(SCOPE_NODE);
            insert.setScopeId(nodeId);
            insert.setName(name);
            insert.setAvatar(avatar);
            insert.setPersona("");   // persona NOT NULL;节点 NPC 只出名称/形象/台词,不喂人设提示词
            insert.setGreeting(greeting == null ? "" : greeting);
            insert.setSort(0);
            // 先发后审:文本已过 msgSecCheck,图片进异步送检;违规由回调把 enabled 拉回 0。
            insert.setAuditStatus(1);
            insert.setEnabled(1);
            try {
                if (npcProfileMapper.insertNodeNpc(insert) != 1) {
                    throw new IllegalStateException("保存失败，请重试");
                }
            } catch (DuplicateKeyException e) {
                // 并发双击:uk_active_scope 把第二条挡在库侧。给可行动的话 ——
                // 让 1062 冒到全局处理器会变成「系统繁忙」,商家只会再点一次。
                throw new IllegalStateException("保存冲突，请重试");
            }
            saved = insert;
        } else {
            // 防 IDOR:只按服务端查到的这一条更新。scope_id 对不上说明行被换绑过(或数据畸形),
            // 这时宁可报错也不能改 —— 改下去就是替别人写内容。
            if (existing.getScopeId() == null || !existing.getScopeId().equals(nodeId)) {
                throw new IllegalStateException("无权修改该形象");
            }
            NpcProfile upd = new NpcProfile();
            upd.setId(existing.getId());
            upd.setName(name);
            upd.setAvatar(avatar);
            upd.setGreeting(greeting == null ? "" : greeting);
            // ★N2:重新保存 ≠ 重新上架。玩家读闸是 audit_status=1 AND enabled=1,两把锁都在才可见。
            // 若这行当前不在架(回调下架 enabled=0,或未过审 audit_status≠1),保存只能把它送回待审:
            // 写 audit_status=0 + enabled=0,过审前玩家读不到 —— 否则换一张预设头像(不送检)再存一次,
            // 内容安全回调的下架就被一笔勾销。在架的行编辑不动这两列(先发后审口径不变)。
            boolean onShelf = Integer.valueOf(1).equals(existing.getEnabled())
                    && Integer.valueOf(1).equals(existing.getAuditStatus());
            if (!onShelf) {
                upd.setAuditStatus(0);
                upd.setEnabled(0);
            }
            if (npcProfileMapper.updateNodeNpc(upd) != 1) {
                throw new IllegalStateException("保存失败，请重试");
            }
            existing.setName(name);
            existing.setAvatar(avatar);
            existing.setGreeting(upd.getGreeting());
            if (!onShelf) {
                existing.setAuditStatus(0);
                existing.setEnabled(0);
            }
            saved = existing;
        }

        // 上传的照片走异步内容安全;预设是内置资源,没有送检的意义(与门店分身同)。
        if (!NpcAvatar.isPreset(avatar)) {
            try {
                wxContentSecurityService.submitMediaCheckOne(
                        IWxContentSecurityService.BIZ_TYPE_NODE_NPC, saved.getId(), avatar, openid);
            } catch (Exception ignore) {
                // 送检登记失败不阻断保存:内容安全回调是补充闸,不是保存的前置条件
                // (与门店分身一致)。没登记上的那条由读路径的 audit_status/人工巡检兜。
            }
        }
        return saved;
    }

    /**
     * 发起声音复刻(可选)。
     *
     * <p>原身份与领取先独立提交，外呼在事务外；结果未知保留原任务与样本，不因重放重新复刻。
     * 已知音色先独立存好再查询；voice_version 继续阻止清除后的迟到结果挂回。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.service.support.AppMediaReferenceGuard mediaReferences;

    public NpcProfile voiceEnroll(Long actorMemberId, Long ownerMemberId, Long nodeId, String rawSample) {
        return voiceEnroll(actorMemberId, ownerMemberId, nodeId, rawSample, null, null, false);
    }

    public NpcProfile voiceEnroll(Long actorMemberId, Long ownerMemberId, Long nodeId, String rawSample,
            String requestId, Long previousIntentId, boolean duplicateChargeConsent) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        chapterMerchantNodeService.requireOwnLiveNode(ownerMemberId, nodeId);
        consentGuard.requireCurrentAgreement(actorMemberId, "voice_clone_processing", "merchant_voice");

        String sample = trimToNull(rawSample);
        if (sample == null) throw new IllegalStateException("请先录一段话");
        if (sample.length() > 4096) throw new IllegalStateException("录音地址过长");
        if (!sample.matches("media-asset:[1-9][0-9]{0,18}") && !sample.startsWith("/") && !sample.startsWith("https://") && !sample.startsWith("http://")) {
            throw new IllegalStateException("录音地址不支持");
        }

        NpcProfile mine = npcProfileMapper.selectNodeNpcByNodeId(nodeId);
        if (mine == null || mine.getScopeId() == null || !mine.getScopeId().equals(nodeId)) {
            throw new IllegalStateException("请先保存节点 NPC,再给他配声音");
        }
        sample = mediaReferences.requireAttachment(actorMemberId, sample, true);
        if (sample.length() > 255) throw new IllegalStateException("录音引用过长");
        final String canonicalSample = sample;
        return voiceEnrollmentService.enroll(actorMemberId, SCOPE_NODE, nodeId, mine.getId(), canonicalSample, requestId, previousIntentId, duplicateChargeConsent,
                () -> mediaReferences.processingUrl(canonicalSample, actorMemberId, "AUDIO"));
    }

    /** 查声音状态(复刻中会顺手问一次上游;上游挂了保持原状,不因为查不到就判失败)。没配过 NPC 返回 null。 */
    public NpcProfile voiceStatus(Long ownerMemberId, Long nodeId) {
        // 读闸,同 detailForMerchant:冻结只挡写,复刻中的声音必须能继续查状态,否则永远卡在「复刻中」。
        chapterMerchantNodeService.requireOwnNode(ownerMemberId, nodeId);
        NpcProfile mine = npcProfileMapper.selectNodeNpcByNodeId(nodeId);
        if (mine == null) return null;
        return voiceEnrollmentService.status(SCOPE_NODE, nodeId, mine.getId());
    }

    public NpcProfile voiceStatus(Long actorMemberId, Long ownerMemberId, Long nodeId, String requestId) {
        chapterMerchantNodeService.requireOwnNode(ownerMemberId, nodeId);
        NpcProfile mine = npcProfileMapper.selectNodeNpcByNodeId(nodeId);
        return mine == null ? null : voiceEnrollmentService.status(actorMemberId, SCOPE_NODE, nodeId, mine.getId(), requestId);
    }

    public java.util.List<java.util.Map<String,Object>> voiceHistory(Long actorMemberId, Long ownerMemberId, Long nodeId) {
        chapterMerchantNodeService.requireOwnNode(ownerMemberId, nodeId);
        NpcProfile mine = npcProfileMapper.selectNodeNpcByNodeId(nodeId);
        return mine == null ? java.util.List.of() : voiceEnrollmentService.history(actorMemberId, SCOPE_NODE, nodeId, mine.getId());
    }

    /** 清除音色,允许重录(旧音色继续留着会被拿去合成,商家想换声音时得先清)。 */
    @Transactional(rollbackFor = Exception.class)
    public NpcProfile voiceReset(Long ownerMemberId, Long nodeId) {
        chapterMerchantNodeService.requireOwnLiveNode(ownerMemberId, nodeId);
        NpcProfile mine = npcProfileMapper.selectNodeNpcByNodeId(nodeId);
        if (mine == null) return null;
        NpcProfile upd = new NpcProfile();
        upd.setId(mine.getId());
        upd.setVoiceVersion(mine.getVoiceVersion());
        upd.setVoiceId(null);
        upd.setVoiceStatus(VOICE_NONE);
        if (npcProfileMapper.updateNodeNpcVoice(upd) != 1) {
            throw new IllegalStateException("声音未清除，请重试");
        }
        mine.setVoiceId(null);
        mine.setVoiceStatus(VOICE_NONE);
        return mine;
    }

    /** 空白当作没填。前端传空串和不传是同一个意思,别把空串写进库。 */
    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
