package com.chengyinhub.business.task;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.security.PiiCryptoService;

/**
 * 报名信息 PII 历史明文加密回填(合规§6.3「历史明文迁移按批次执行」)。
 *
 * 一次性运维任务:在 RuoYi sys_job 手动触发 piiBackfillTask.run() 跑到收敛(或多次触发)。
 * 幂等:只挑 phone_hash 为空(未迁移)的行;读出明文(历史无 v1: 前缀,解密器原样返回)→
 * 密文覆盖 real_name/phone/email/address + 写盲索引 phone_hash。已迁移的行不再命中。
 *
 * ⚠️ 前置:chengyin.pii.key 已配 + migration_registration_pii_encryption.sql 已落(列已加宽 + phone_hash 列)。
 * 未配密钥时直接跳过(不误把明文当已迁移)。
 */
@Component("piiBackfillTask")
public class PiiBackfillTask {

    private static final Logger log = LoggerFactory.getLogger(PiiBackfillTask.class);

    @Value("${chengyin.pii.backfill.batch-size:500}")
    private int batchSize;

    @Value("${chengyin.pii.backfill.max-batches:200}")
    private int maxBatches;

    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    @Autowired
    private OmsOrderMapper omsOrderMapper;

    @Autowired
    private PiiCryptoService piiCryptoService;

    public void run() {
        if (!piiCryptoService.isEnabled()) {
            log.warn("PII 回填跳过:chengyin.pii.key 未配置,加密未启用");
            return;
        }
        boolean regDone = backfillRegistrations();
        boolean orderDone = backfillOrders();
        if (regDone && orderDone) {
            log.info("PII 回填本轮收敛:报名/订单均无残留明文");
        } else {
            log.warn("PII 回填本轮【未收敛】:已达 maxBatches 上限,仍有历史明文未加密,请再次触发 piiBackfillTask.run() 直到日志显示收敛");
        }
    }

    /** @return true=已收敛(无残留);false=命中 maxBatches 上限被截断,还有残留需再次触发 */
    private boolean backfillRegistrations() {
        int total = 0, failed = 0;
        for (int b = 0; b < maxBatches; b++) {
            List<Long> ids = cmsRegistrationMapper.selectLegacyPiiIds(batchSize);
            if (ids == null || ids.isEmpty()) {
                return true;
            }
            for (Long id : ids) {
                try {
                    // selectById 经解密器:历史明文(无 v1:)原样返回 → 拿到明文
                    CmsRegistration r = cmsRegistrationMapper.selectCmsRegistrationById(id);
                    if (r == null) {
                        continue;
                    }
                    cmsRegistrationMapper.backfillRegistrationPii(
                            id, r.getRealName(), r.getPhone(), r.getEmail(), r.getAddress());
                    total++;
                } catch (Exception e) {
                    failed++;
                    log.error("PII 回填失败 registrationId={}", id, e);
                }
            }
            log.info("报名 PII 回填进行中:本批 {} 条,累计 {} 条,失败 {} 条", ids.size(), total, failed);
            if (ids.size() < batchSize) {
                return true;
            }
        }
        return false; // 跑满 maxBatches 仍有满批 → 被截断,未收敛
    }

    /** @return true=已收敛;false=被 maxBatches 截断,还有残留 */
    private boolean backfillOrders() {
        int total = 0, failed = 0;
        for (int b = 0; b < maxBatches; b++) {
            List<Long> ids = omsOrderMapper.selectLegacyReceiverPiiIds(batchSize);
            if (ids == null || ids.isEmpty()) {
                return true;
            }
            for (Long id : ids) {
                try {
                    OmsOrder o = omsOrderMapper.selectOmsOrderById(id);
                    if (o == null) {
                        continue;
                    }
                    omsOrderMapper.backfillOrderReceiverPii(
                            id, o.getReceiverName(), o.getReceiverPhone(), o.getReceiverDetailAddress());
                    total++;
                } catch (Exception e) {
                    failed++;
                    log.error("PII 回填失败 orderId={}", id, e);
                }
            }
            log.info("订单 PII 回填进行中:本批 {} 条,累计 {} 条,失败 {} 条", ids.size(), total, failed);
            if (ids.size() < batchSize) {
                return true;
            }
        }
        return false;
    }
}
