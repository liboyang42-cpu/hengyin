package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.MerchantOpsVO;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.IMerchantPrerequisiteService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class MerchantPrerequisiteServiceImpl implements IMerchantPrerequisiteService {
    static final String APPLY_PATH = "/pages/merchant/apply/index";
    static final String DECOR_PATH = "/pages/merchant/decor/index";
    static final String DEFAULT_LOGO = "/images/mer1.jpg";

    private final MmsMerchantMapper merchantMapper;

    public MerchantPrerequisiteServiceImpl(MmsMerchantMapper merchantMapper) {
        this.merchantMapper = merchantMapper;
    }

    @Override
    public MerchantOpsVO.PrerequisiteChecklist calculate(Long principalMemberId) {
        if (principalMemberId == null || principalMemberId <= 0) {
            throw new ServiceException("请先登录", 401);
        }
        MmsMerchant merchant = merchantMapper.selectMmsMerchantByMemberId(principalMemberId);
        if (merchant == null || Integer.valueOf(2).equals(merchant.getDelFlag())) {
            throw new ServiceException("未找到商家主体", 404);
        }
        Date checkedAt = new Date();
        List<MerchantOpsVO.Prerequisite> items = new ArrayList<>();
        boolean approved = Long.valueOf(1L).equals(merchant.getStatus());
        boolean active = Long.valueOf(1L).equals(merchant.getAccountStatus());
        items.add(item("MERCHANT_APPROVED", "商家资质审核", approved,
                "审核通过后才能使用商家能力", APPLY_PATH, checkedAt));
        items.add(item("ACCOUNT_ACTIVE", "商家账号启用", active,
                "账号需处于启用状态", null, checkedAt));
        items.add(item("BUSINESS_LICENSE", "营业资质", hasText(merchant.getBusinessLicense()),
                "请提供有效营业资质", approved ? null : APPLY_PATH, checkedAt));
        String profilePath = approved ? DECOR_PATH : APPLY_PATH;
        items.add(item("PROFILE_NAME", "店铺名称", hasText(merchant.getName()),
                "补全店铺名称", profilePath, checkedAt));
        items.add(item("PROFILE_CATEGORY", "店铺分类", merchant.getCategoryId() != null,
                "选择店铺分类", profilePath, checkedAt));
        items.add(item("PROFILE_LOGO", "店铺头像", hasText(merchant.getLogo()) && !DEFAULT_LOGO.equals(merchant.getLogo()),
                "上传店铺头像", profilePath, checkedAt));
        items.add(item("PROFILE_COVER", "店铺封面", hasText(merchant.getCoverImage()),
                "上传店铺封面", profilePath, checkedAt));
        items.add(item("PROFILE_LOCATION", "店铺位置", Integer.valueOf(1).equals(merchant.getLocationVerified()),
                "完成店铺定位", profilePath, checkedAt));
        items.add(item("PROFILE_SLOGAN", "一句话介绍", hasText(merchant.getSlogan()),
                "填写一句话介绍", profilePath, checkedAt));
        items.add(item("PROFILE_DESCRIPTION", "品牌故事", hasText(merchant.getDescription()),
                "填写品牌故事", profilePath, checkedAt));

        MerchantOpsVO.PrerequisiteChecklist result = new MerchantOpsVO.PrerequisiteChecklist();
        result.setMerchantId(merchant.getId());
        result.setCheckedAt(checkedAt);
        result.setItems(items);
        boolean canContinue = true;
        for (MerchantOpsVO.Prerequisite prerequisite : items) {
            if (!"PASS".equals(prerequisite.getStatus())) {
                canContinue = false;
                break;
            }
        }
        result.setCanContinue(canContinue);
        return result;
    }

    private MerchantOpsVO.Prerequisite item(String code, String title, boolean passed,
                                             String description, String path, Date checkedAt) {
        MerchantOpsVO.Prerequisite item = new MerchantOpsVO.Prerequisite();
        item.setCode(code);
        item.setTitle(title);
        item.setDescription(description);
        item.setStatus(passed ? "PASS" : "BLOCKED");
        item.setSeverity(passed ? "INFO" : "BLOCKING");
        item.setPermissionRequired("MERCHANT_OWNER");
        item.setCheckedAt(checkedAt);
        MerchantOpsVO.ActionTarget target = new MerchantOpsVO.ActionTarget();
        target.setType("MINIPROGRAM_PAGE");
        target.setPath(path);
        target.setAvailable(path != null);
        if (path == null) target.setUnavailableReason("NO_EXISTING_RECOVERY_PAGE");
        item.setActionTarget(target);
        return item;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
