package com.chengyinhub.business.task;

import com.chengyinhub.business.service.ICmsRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 到期未核销场次票自动核销定时任务(RUN-01 / 拍板纪要#5:过期未核销单到期自动核销关闭、不退款)。
 *
 * <p>只收口活动单(owner_type=2):场次活动的结算在支付成功时已入账,过期未扫的票悬在
 * 「已支付未核销」既不退款也挡商家名册/售后收敛。主题票(①②③)的结算写入发生在核销动作里,
 * 不经本任务 —— 见 ICmsRegistrationService.autoVerifyExpiredOne javadoc。
 *
 * <p>逐单经 Service 代理处理(per-单独立事务,单笔失败不影响其它);置位为条件 UPDATE
 * (仍「已支付未核销 + 锚点成立 + 无在途退款」才写),与商家扫码/退款申请/活动改期竞态时
 * 0 行跳过,不会覆盖真实核销,也不会钉死正在退钱的票。
 *
 * <p>在 RuoYi「定时任务管理」(sys_job)注册:invokeTarget = registrationAutoVerifyTask.run(),
 * cron 建议每小时(见 migration_registration_auto_verify_job_20260919.sql)。
 */
@Component("registrationAutoVerifyTask")
public class RegistrationAutoVerifyTask {

    private static final Logger log = LoggerFactory.getLogger(RegistrationAutoVerifyTask.class);

    @Autowired
    private ICmsRegistrationService cmsRegistrationService;

    /** 扫描活动已结束满安全缓冲仍未核销的场次票:逐单自动核销收口(不退款、不发奖励、不写台账)。 */
    public void run() {
        List<Long> ids = cmsRegistrationService.listExpiredUnverifiedIds();
        int verified = 0;
        for (Long id : ids) {
            try {
                if (cmsRegistrationService.autoVerifyExpiredOne(id)) {
                    verified++;
                }
            } catch (Exception e) {
                log.error("到期未核销自动收口失败 registrationId={}", id, e);
            }
        }
        if (!ids.isEmpty()) {
            log.info("到期未核销自动收口完成:扫描 {} 单,收口 {} 单", ids.size(), verified);
        }
    }
}
