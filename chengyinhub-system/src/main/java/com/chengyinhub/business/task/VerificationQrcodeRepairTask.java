package com.chengyinhub.business.task;

import com.chengyinhub.business.service.ICmsRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 核销码补偿定时任务:支付回调期 generateVerificationQrcode 失败被吞的报名单
 * (payment_status=2 已支付、qrcode_url 为空),用户钱已扣但拿不到核销二维码,
 * 且回调幂等门会对微信重推直接回 SUCCESS,无自然重试路径——只能靠本任务补跑。
 *
 * 在 RuoYi「定时任务管理」(sys_job)新增一条:invokeTarget = verificationQrcodeRepairTask.repairMissing(),
 * cron 建议每 10 分钟。generateVerificationQrcode 幂等(已生成直接短路返回),补跑安全;
 * 逐单 try/catch,单笔失败不影响其它。只扫近 7 天(mapper 限定),历史旧单不回填。
 */
@Component("verificationQrcodeRepairTask")
public class VerificationQrcodeRepairTask {

    private static final Logger log = LoggerFactory.getLogger(VerificationQrcodeRepairTask.class);

    @Autowired
    private ICmsRegistrationService cmsRegistrationService;

    /** 扫描已支付但核销码为空的报名单,逐单补生成核销码 */
    public void repairMissing() {
        List<Long> ids = cmsRegistrationService.listPaidMissingQrcodeIds();
        int repaired = 0;
        for (Long id : ids) {
            try {
                cmsRegistrationService.generateVerificationQrcode(id);
                repaired++;
            } catch (Exception e) {
                log.error("核销码补偿失败 registrationId={}", id, e);
            }
        }
        if (!ids.isEmpty()) {
            log.info("核销码补偿完成:扫描 {} 单,补齐 {} 单", ids.size(), repaired);
        }
        if (ids.size() >= 100) {
            // 满批=本轮没扫完,还有积压(mapper 限批 100/轮);持续满批说明上游在批量失败,需要人看
            log.warn("核销码补偿满批({} 单),可能仍有积压,下一轮继续", ids.size());
        }
    }
}
