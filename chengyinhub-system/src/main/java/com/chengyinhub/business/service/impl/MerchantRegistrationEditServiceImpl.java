package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.RegistrationMerchantDTO;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMerchantRegistrationEditService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

@Service
public class MerchantRegistrationEditServiceImpl implements IMerchantRegistrationEditService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ICmsRegistrationMerchantService registrationMerchantService;
    private final ICmsTopicService topicService;

    public MerchantRegistrationEditServiceImpl(ICmsRegistrationMerchantService registrationMerchantService,
                                               ICmsTopicService topicService) {
        this.registrationMerchantService = registrationMerchantService;
        this.topicService = topicService;
    }

    @Override
    public boolean updateByMerchant(Long registrationId, Long memberId,
                                    RegistrationMerchantDTO param, String username) {
        if (registrationId == null || registrationId <= 0L) {
            throw new RuntimeException("缺少报名 id");
        }
        CmsRegistrationMerchant registration =
                registrationMerchantService.selectCmsRegistrationMerchantById(registrationId);
        if (registration == null || (registration.getDelFlag() != null && registration.getDelFlag() == 2)) {
            throw new RuntimeException("报名记录不存在");
        }
        if (registration.getMemberId() == null || !registration.getMemberId().equals(memberId)) {
            throw new RuntimeException("无权修改他人报名");
        }

        // 闸一:主题一开跑就冻结 —— 玩家已经在按这份内容到店了,这时候改现场图/营业时间
        // 等于把玩家手里的信息改掉。
        CmsTopic topic = registration.getTopicId() == null ? null
                : topicService.selectCmsTopicById(registration.getTopicId());
        if (topic == null) {
            throw new RuntimeException("主题不存在");
        }
        if (hasStarted(topic)) {
            throw new RuntimeException("主题已开始,承接内容不能再修改");
        }

        // 闸二:只有「审核中」和「已驳回」可改。这里必须同时看 status 与 audit_status:
        // 调配驳回的真实落库值是 status=3,audit_status=2；只认 status=2 会让真实驳回记录
        // 永远进不了重提链路。
        // 已中标(status=1)不许改 —— 主办方是看着你提交的内容审过的,审后再改等于绕过审核。
        long status = registration.getStatus() == null ? 0L : registration.getStatus();
        long auditStatus = registration.getAuditStatus() == null ? 0L : registration.getAuditStatus();
        boolean pending = status == 0L && auditStatus == 0L;
        boolean rejected = (status == 3L || status == 2L) && auditStatus == 2L;
        if (!pending && !rejected) {
            throw new RuntimeException("该报名已通过审核,内容不能再修改");
        }

        CmsRegistrationMerchant patch = new CmsRegistrationMerchant();
        patch.setId(registrationId);
        // 白名单:只有「我这家店怎么接待」这类字段可改。
        // topicId/nodeId/templateId 是承接标的,status/auditStatus 是审核结论,
        // sharingRate/sharingAmount 是钱 —— 一律不接受客户端改。
        patch.setPicUrl(param.getPicUrl());
        patch.setAddress(param.getAddress());
        patch.setAddressName(param.getAddressName());
        patch.setLongitude(param.getLongitude());
        patch.setLatitude(param.getLatitude());
        patch.setCooperateDate(param.getCooperateDate());
        patch.setActivityDesc(param.getActivityDesc());
        patch.setLimitNum(param.getLimitNum());
        patch.setStartDate(param.getStartDate());
        patch.setEndDate(param.getEndDate());

        // 驳回后重新提交 = 重新进入审核,并把上一次的驳回原因清掉 ——
        // 留着它,商家改完仍然看到「未通过原因」,像是没提交成功。
        // 这三个字段的重置写在 mapper 的 <if test="rejected"> 里(status=0/audit_status=0/reason=''),
        // 不经由 patch —— 在这里再 set 一遍是死写入,只会让测试断言到一个 SQL 根本不读的值。

        patch.setUpdateBy(username);
        patch.setUpdateTime(new Date());
        int updated = registrationMerchantService.updateEditableFieldsIfStateMatches(
                patch, memberId, status, auditStatus, rejected);
        if (updated <= 0) {
            throw new RuntimeException("报名状态已变化,请刷新后重试");
        }
        return true;
    }

    /** 主题开始日按自然日算:开始当天 00:00 起就算已开始。 */
    private boolean hasStarted(CmsTopic topic) {
        Date start = topic.getStartDate();
        if (start == null) {
            return false;
        }
        LocalDate startDay = Instant.ofEpochMilli(start.getTime()).atZone(BUSINESS_ZONE).toLocalDate();
        LocalDate today = currentInstant().atZone(BUSINESS_ZONE).toLocalDate();
        return !today.isBefore(startDay);
    }

    /** 仅作为自然日边界的可测时钟缝；生产始终读取当前时刻。 */
    protected Instant currentInstant() {
        return Instant.now();
    }
}
