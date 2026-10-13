package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.Date;

import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantPermissionPolicy;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.domain.dto.MerchantSearchQuery;
import com.chengyinhub.business.mapper.MerchantOperatorInviteMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;


/**
 * 商家Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-22
 */
@Service
public class MmsMerchantServiceImpl implements IMmsMerchantService
{
    private static final String OWNER_CHANGE_REASON = "MERCHANT_OWNER_CHANGED";
    private static final String OWNER_CHANGE_ACTOR = "SYSTEM_OWNER_CHANGE";
    /** 与 mms_merchant.disable_reason 的 varchar(500) 对齐。 */
    private static final int PLATFORM_DISABLE_REASON_MAX_LENGTH = 500;

    @Autowired
    private MmsMerchantMapper mmsMerchantMapper;

    @Autowired
    private MerchantOperatorMapper merchantOperatorMapper;

    @Autowired
    private MerchantOperatorInviteMapper merchantOperatorInviteMapper;

    @Autowired
    private UmsMemberMapper umsMemberMapper;

    @Autowired
    private ClubMapper clubMapper;

    @Autowired
    private ICapabilityService capabilityService;

    @Autowired private com.chengyinhub.business.service.IRoleService roleService;
    @Autowired private IUmsMemberService umsMemberService;
    @Autowired private IUmsMemberPointsDetailService umsMemberPointsDetailService;
    @Autowired private ICmsPointsSettingService cmsPointsSettingService;

    /**
     * 查询商家
     * 
     * @param id 商家主键
     * @return 商家
     */
    @Override
    public MmsMerchant selectMmsMerchantById(Long id)
    {
        return mmsMerchantMapper.selectMmsMerchantById(id);
    }

    @Override
    public MmsMerchant selectMmsMerchantByMemberId(Long id)
    {
        return mmsMerchantMapper.selectMmsMerchantByMemberId(id);
    }


    /**
     * 查询商家列表
     * 
     * @param mmsMerchant 商家
     * @return 商家
     */
    @Override
    public List<MmsMerchant> selectMmsMerchantList(MmsMerchant mmsMerchant)
    {
        return mmsMerchantMapper.selectMmsMerchantList(mmsMerchant);
    }

    @Override
    public List<MerchantSearchQuery> selectMerchantListByDistance(MerchantSearchQuery query)
    {
        return mmsMerchantMapper.selectMerchantListByDistance(query);
    }

    /**
     * 新增商家
     * 
     * @param mmsMerchant 商家
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertMmsMerchant(MmsMerchant mmsMerchant)
    {
        if (mmsMerchant.getMemberId() != null
                && !mmsMerchant.getMemberId().equals(umsMemberMapper.lockActiveMemberById(mmsMerchant.getMemberId()))) {
            throw new ServiceException("商家所属会员不可用", HttpStatus.FORBIDDEN);
        }
        mmsMerchant.setCreateTime(DateUtils.getNowDate());
        int rows = mmsMerchantMapper.insertMmsMerchant(mmsMerchant);
        if (rows == 1) {
            MmsMerchant inserted = mmsMerchantMapper.selectMmsMerchantByIdForUpdate(mmsMerchant.getId());
            if (inserted == null) throw new ServiceException("商家创建状态无法确认");
            if (isActiveMerchant(inserted)) activateMerchantOwner(inserted.getMemberId());
        }
        return rows;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void activateMerchantOwner(Long memberId) {
        if (memberId == null || !memberId.equals(umsMemberMapper.lockActiveMemberById(memberId))) {
            throw new ServiceException("商家所属会员不可用", HttpStatus.FORBIDDEN);
        }
        List<Long> ownedClubs = clubMapper.selectOwnedClubIdsForUpdate(memberId);
        if (ownedClubs == null) throw new ServiceException("无法确认俱乐部归属，请重试");
        if (!ownedClubs.isEmpty()) {
            throw new ServiceException("请先完成全部俱乐部主理人移交，待移交俱乐部：" + ownedClubs, HttpStatus.CONFLICT);
        }
        if (merchantOperatorMapper.selectActiveByMemberIdForUpdate(memberId) != null) {
            throw new ServiceException("请先退出商家经营团队，再切换店主身份", HttpStatus.CONFLICT);
        }
        roleService.changeRole(memberId, com.chengyinhub.business.service.IRoleService.ROLE_MERCHANT, 0L);
    }

    /**
     * 修改商家
     * 
     * @param mmsMerchant 商家
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateMmsMerchant(MmsMerchant mmsMerchant)
    {
        if (mmsMerchant == null || mmsMerchant.getId() == null) {
            return 0;
        }
        MmsMerchant beforeLock = mmsMerchantMapper.selectMmsMerchantById(mmsMerchant.getId());
        if (beforeLock == null) return 0;
        // 与入驻、认领、建会和转让共用会员锁；Owner变更按会员ID排序，先会员后商家。
        TreeSet<Long> members = new TreeSet<>();
        if (beforeLock.getMemberId() != null) members.add(beforeLock.getMemberId());
        if (mmsMerchant.getMemberId() != null) members.add(mmsMerchant.getMemberId());
        for (Long memberId : members) umsMemberMapper.lockMemberById(memberId);
        MmsMerchant current = mmsMerchantMapper.selectMmsMerchantByIdForUpdate(mmsMerchant.getId());
        if (current == null || !mmsMerchant.getId().equals(current.getId())) {
            return 0;
        }
        if (!Objects.equals(beforeLock.getMemberId(), current.getMemberId())) {
            throw new ServiceException("商家归属已变化，请刷新重试", HttpStatus.CONFLICT);
        }
        boolean ownerChanged = mmsMerchant.getMemberId() != null
                && !mmsMerchant.getMemberId().equals(current.getMemberId());
        if (Long.valueOf(1L).equals(mmsMerchant.getStatus()) && !Long.valueOf(1L).equals(current.getStatus())
                && Long.valueOf(0L).equals(current.getAccountStatus())
                && (mmsMerchant.getAccountStatus() == null || Long.valueOf(0L).equals(mmsMerchant.getAccountStatus()))) {
            mmsMerchant.setAccountStatus(1L);
        }
        Long nextStatus = mmsMerchant.getStatus() == null ? current.getStatus() : mmsMerchant.getStatus();
        Long nextAccountStatus = mmsMerchant.getAccountStatus() == null ? current.getAccountStatus() : mmsMerchant.getAccountStatus();
        Integer nextDelFlag = mmsMerchant.getDelFlag() == null ? current.getDelFlag() : mmsMerchant.getDelFlag();
        Long nextOwner = mmsMerchant.getMemberId() == null ? current.getMemberId() : mmsMerchant.getMemberId();
        boolean activating = nextOwner != null && Long.valueOf(1L).equals(nextStatus)
                && Long.valueOf(1L).equals(nextAccountStatus) && Integer.valueOf(0).equals(nextDelFlag)
                && (ownerChanged || !isActiveMerchant(current));
        if (ownerChanged) {
            String updateBy = mmsMerchant.getUpdateBy();
            if (updateBy == null || updateBy.trim().isEmpty()) {
                updateBy = OWNER_CHANGE_ACTOR;
            }
            merchantOperatorMapper.revokeAllActiveByMerchantId(
                    current.getId(), OWNER_CHANGE_REASON, updateBy);
            merchantOperatorInviteMapper.revokeAllPendingByMerchantId(
                    current.getId(), OWNER_CHANGE_REASON);
        }
        if (activating) activateMerchantOwner(nextOwner);
        mmsMerchant.setUpdateTime(DateUtils.getNowDate());
        int rows = mmsMerchantMapper.updateMmsMerchant(mmsMerchant);
        if ((ownerChanged || activating) && rows != 1) {
            throw new ServiceException("商家 Owner 变更失败");
        }
        if (rows == 1 && nextOwner != null && Long.valueOf(1L).equals(nextStatus)
                && !Long.valueOf(1L).equals(current.getStatus())) {
            awardApprovalReferral(nextOwner);
        }
        return rows;
    }

    private void awardApprovalReferral(Long memberId) {
        UmsMember umsMember = umsMemberService.selectUmsMemberById(memberId);
        //增加积分
        String inviteUserId = umsMember == null ? null : umsMember.getInviteUserId();
        if (StringUtils.isNotBlank(inviteUserId)) {
            UmsMember inveteUmsMember = umsMemberService.selectUmsMemberById(Long.valueOf(inviteUserId));
            if (inveteUmsMember != null) {
                UmsMemberPointsDetail umsMemberPointsDetail = new UmsMemberPointsDetail();
                umsMemberPointsDetail.setMemberId(inveteUmsMember.getId());
                // 去重键必须与下方写入记录的 changeReason 一致,否则永远查不到→每次审核重复发积分
                umsMemberPointsDetail.setChangeReason("推荐商户注册");
                umsMemberPointsDetail.setEventId(umsMember.getId());
                List<UmsMemberPointsDetail> umsMemberPointsDetailList = umsMemberPointsDetailService.selectUmsMemberPointsDetailList(umsMemberPointsDetail);

                if (umsMemberPointsDetailList.size() <= 0) {

                    int points = 0;
                    CmsPointsSetting cmsPointsSetting = cmsPointsSettingService.selectCmsPointsSettingById(7L);
                    if (cmsPointsSetting != null) {
                        points = cmsPointsSetting.getValue().intValue();
                    }

                    umsMemberService.addPoints(inveteUmsMember.getId(), points);

                    //赠送积分
                    umsMemberPointsDetail = new UmsMemberPointsDetail();
                    umsMemberPointsDetail.setMemberId(inveteUmsMember.getId());
                    umsMemberPointsDetail.setCurrentPoints(inveteUmsMember.getPoint());
                    umsMemberPointsDetail.setAfterPoints(inveteUmsMember.getPoint() + points);
                    umsMemberPointsDetail.setChangePoints(Long.valueOf(points));
                    umsMemberPointsDetail.setChangeReason("推荐商户注册");
                    umsMemberPointsDetail.setEventType(Long.valueOf(7));
                    umsMemberPointsDetail.setEventId(umsMember.getId());
                    umsMemberPointsDetail.setChangeType(1L);
                    umsMemberPointsDetail.setDelFlag(0);
                    umsMemberPointsDetail.setCreateTime(new Date());
                    if (umsMemberPointsDetailService.insertUmsMemberPointsDetail(umsMemberPointsDetail) != 1) {
                        throw new ServiceException("商家审核推荐积分明细保存失败");
                    }
                }
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateAuthorizedProfile(MmsMerchant patch, Long principalMemberId, String requiredPermission)
    {
        if (patch == null || patch.getId() == null || patch.getId() <= 0
                || principalMemberId == null || principalMemberId <= 0
                || !(MerchantPermission.PROFILE_WRITE.equals(requiredPermission)
                    || MerchantPermission.COOP_MANAGE.equals(requiredPermission))) {
            throw new ServiceException("商家资料参数或权限无效", HttpStatus.BAD_REQUEST);
        }
        if (!principalMemberId.equals(umsMemberMapper.lockActiveMemberById(principalMemberId))) {
            throw new ServiceException("当前会员不可用", HttpStatus.FORBIDDEN);
        }
        MmsMerchant current = mmsMerchantMapper.selectMmsMerchantByIdForUpdate(patch.getId());
        if (!isActiveMerchant(current) || !patch.getId().equals(current.getId())) {
            throw new ServiceException("商家已停用或归属已变化", HttpStatus.FORBIDDEN);
        }

        String currentRole;
        if (principalMemberId.equals(current.getMemberId())) {
            currentRole = MerchantRoleCode.MERCHANT_OWNER;
        } else {
            MerchantOperator operator = merchantOperatorMapper.selectActiveByMemberIdForUpdate(principalMemberId);
            if (operator == null || operator.getId() == null
                    || !patch.getId().equals(operator.getMerchantId())
                    || !principalMemberId.equals(operator.getOperatorMemberId())
                    || !principalMemberId.equals(operator.getActiveOperatorMemberId())
                    || !MerchantOperator.STATUS_ACTIVE.equals(operator.getStatus())) {
                throw new ServiceException("商家员工身份已失效", HttpStatus.FORBIDDEN);
            }
            currentRole = operator.getRoleCode();
        }
        if (!MerchantPermissionPolicy.hasPermission(currentRole, requiredPermission)) {
            throw new ServiceException("商家身份无效或权限不足", HttpStatus.FORBIDDEN);
        }
        MmsMerchant allowed = authorizedProfilePatch(patch);
        allowed.setUpdateBy(String.valueOf(principalMemberId));
        boolean clearCapacity = MerchantPermission.COOP_MANAGE.equals(requiredPermission)
                && patch.getCapacity() == null
                && Boolean.TRUE.equals(patch.getParams().get(MmsMerchant.CLEAR_CAPACITY))
                && current.getCapacity() != null;
        // CU-M-78:type=0/id=null 是接口层定义的「解除主推」(validateFeaturedSelection),但 featured_id
        // 走动态 if,传 null 就是「不改」⇒ 旧活动 id 永远留在库里。与 clearCapacity 同法显式清一次。
        boolean clearFeatured = Integer.valueOf(0).equals(patch.getFeaturedType())
                && patch.getFeaturedId() == null
                && current.getFeaturedId() != null;
        if (!clearCapacity && !clearFeatured && profilePatchAlreadyApplied(current, allowed)) {
            return 1;
        }
        allowed.setUpdateTime(DateUtils.getNowDate());
        if (mmsMerchantMapper.updateMmsMerchant(allowed) != 1
                || (clearCapacity && mmsMerchantMapper.clearMerchantCapacity(allowed) != 1)
                || (clearFeatured && mmsMerchantMapper.clearMerchantFeatured(allowed) != 1)) {
            throw new ServiceException("商家归属或资料已变化，请刷新", HttpStatus.CONFLICT);
        }
        return 1;
    }

    private MmsMerchant authorizedProfilePatch(MmsMerchant source)
    {
        MmsMerchant allowed = new MmsMerchant();
        allowed.setId(source.getId());
        allowed.setLogo(source.getLogo());
        allowed.setName(source.getName());
        allowed.setDescription(source.getDescription());
        allowed.setDerivatives(source.getDerivatives());
        allowed.setDerivativeBenefits(source.getDerivativeBenefits());
        allowed.setWebsite(source.getWebsite());
        allowed.setPreference(source.getPreference());
        allowed.setAddress(source.getAddress());
        allowed.setBusinessTime(source.getBusinessTime());
        allowed.setBusinessStatus(source.getBusinessStatus());
        allowed.setCapacity(source.getCapacity());
        allowed.setAvailableTime(source.getAvailableTime());
        allowed.setSuitActivityTypes(source.getSuitActivityTypes());
        allowed.setChargeType(source.getChargeType());
        allowed.setDemand(source.getDemand());
        allowed.setCoopOpen(source.getCoopOpen());
        allowed.setCoverImage(source.getCoverImage());
        allowed.setSlogan(source.getSlogan());
        allowed.setCityRole(source.getCityRole());
        allowed.setStoryTitle(source.getStoryTitle());
        allowed.setGallery(source.getGallery());
        allowed.setTags(source.getTags());
        allowed.setFeaturedType(source.getFeaturedType());
        allowed.setFeaturedId(source.getFeaturedId());
        allowed.setServiceTag(source.getServiceTag());
        allowed.setServiceText(source.getServiceText());
        allowed.setLocationLat(source.getLocationLat());
        allowed.setLocationLng(source.getLocationLng());
        allowed.setLocationVerified(source.getLocationVerified());
        allowed.setCategoryId(source.getCategoryId());
        return allowed;
    }

    private boolean isActiveMerchant(MmsMerchant merchant)
    {
        return merchant != null && merchant.getId() != null && merchant.getMemberId() != null
                && Long.valueOf(1L).equals(merchant.getStatus())
                && Long.valueOf(1L).equals(merchant.getAccountStatus())
                && Integer.valueOf(0).equals(merchant.getDelFlag());
    }

    /** 仅比较四个小程序经营资料入口允许携带的非空字段。 */
    private boolean profilePatchAlreadyApplied(MmsMerchant current, MmsMerchant patch)
    {
        return matches(patch.getLogo(), current.getLogo())
                && matches(patch.getName(), current.getName())
                && matches(patch.getDescription(), current.getDescription())
                && matches(patch.getDerivatives(), current.getDerivatives())
                && matches(patch.getDerivativeBenefits(), current.getDerivativeBenefits())
                && matches(patch.getWebsite(), current.getWebsite())
                && matches(patch.getPreference(), current.getPreference())
                && matches(patch.getAddress(), current.getAddress())
                && matches(patch.getBusinessTime(), current.getBusinessTime())
                && matches(patch.getBusinessStatus(), current.getBusinessStatus())
                && matches(patch.getCapacity(), current.getCapacity())
                && matches(patch.getAvailableTime(), current.getAvailableTime())
                && matches(patch.getSuitActivityTypes(), current.getSuitActivityTypes())
                && matches(patch.getChargeType(), current.getChargeType())
                && matches(patch.getDemand(), current.getDemand())
                && matches(patch.getCoopOpen(), current.getCoopOpen())
                && matches(patch.getCoverImage(), current.getCoverImage())
                && matches(patch.getSlogan(), current.getSlogan())
                && matches(patch.getCityRole(), current.getCityRole())
                && matches(patch.getStoryTitle(), current.getStoryTitle())
                && matches(patch.getGallery(), current.getGallery())
                && matches(patch.getTags(), current.getTags())
                && matches(patch.getFeaturedType(), current.getFeaturedType())
                && matches(patch.getFeaturedId(), current.getFeaturedId())
                && matches(patch.getServiceTag(), current.getServiceTag())
                && matches(patch.getServiceText(), current.getServiceText())
                && matches(patch.getLocationLat(), current.getLocationLat())
                && matches(patch.getLocationLng(), current.getLocationLng())
                && matches(patch.getLocationVerified(), current.getLocationVerified())
                && matches(patch.getCategoryId(), current.getCategoryId());
    }

    private boolean matches(Object requested, Object current)
    {
        return requested == null || requested.equals(current);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateOwnerRegistrationProfile(MmsMerchant mmsMerchant, Long expectedOwnerMemberId)
    {
        if (mmsMerchant == null || mmsMerchant.getId() == null || mmsMerchant.getId() <= 0
                || expectedOwnerMemberId == null || expectedOwnerMemberId <= 0) {
            throw new ServiceException("商家资料参数不完整", HttpStatus.BAD_REQUEST);
        }
        if (!expectedOwnerMemberId.equals(umsMemberMapper.lockActiveMemberById(expectedOwnerMemberId))) {
            throw new ServiceException("当前会员不可用", HttpStatus.FORBIDDEN);
        }
        MmsMerchant current = mmsMerchantMapper.selectMmsMerchantByIdForUpdate(mmsMerchant.getId());
        if (current == null || !mmsMerchant.getId().equals(current.getId())
                || !expectedOwnerMemberId.equals(current.getMemberId())) {
            throw new ServiceException("商家 Owner 已变化或无权修改", HttpStatus.FORBIDDEN);
        }
        if (!isEditableRegistration(current)) {
            throw new ServiceException("商家审核或账号状态已变化，请刷新", HttpStatus.CONFLICT);
        }

        MmsMerchant allowed = ownerRegistrationPatch(mmsMerchant, expectedOwnerMemberId);
        int rows = mmsMerchantMapper.updateOwnerRegistrationProfile(allowed, expectedOwnerMemberId);
        if (rows != 1) {
            throw new ServiceException("商家归属或状态已变化，请刷新", HttpStatus.CONFLICT);
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updatePlatformAccountStatus(Long id, Long accountStatus, String disableReason, String updateBy)
    {
        if (id == null || id <= 0) {
            throw new ServiceException("商家参数无效", HttpStatus.BAD_REQUEST);
        }
        if (!Long.valueOf(1L).equals(accountStatus) && !Long.valueOf(2L).equals(accountStatus)) {
            throw new ServiceException("账号状态只支持启用或停用", HttpStatus.BAD_REQUEST);
        }
        boolean disabling = Long.valueOf(2L).equals(accountStatus);
        String reason = disableReason == null ? "" : disableReason.trim();
        if (disabling && reason.isEmpty()) {
            throw new ServiceException("请填写停用原因", HttpStatus.BAD_REQUEST);
        }
        if (reason.length() > PLATFORM_DISABLE_REASON_MAX_LENGTH) {
            throw new ServiceException("停用原因最多 " + PLATFORM_DISABLE_REASON_MAX_LENGTH + " 字", HttpStatus.BAD_REQUEST);
        }
        MmsMerchant current = mmsMerchantMapper.selectMmsMerchantByIdForUpdate(id);
        if (current == null || !Integer.valueOf(0).equals(current.getDelFlag())) {
            throw new ServiceException("商家不存在或已删除", HttpStatus.NOT_FOUND);
        }
        // 停用写原因;启用清空原因。驳回原因 reson 只由审核入口写,本方法永不读写它。
        String nextReason = disabling ? reason : "";
        int rows = mmsMerchantMapper.updatePlatformAccountStatus(
                id, accountStatus, nextReason, updateBy, DateUtils.getNowDate());
        if (rows != 1) {
            throw new ServiceException("商家账号状态更新失败", HttpStatus.CONFLICT);
        }
        return rows;
    }

    private boolean isEditableRegistration(MmsMerchant current)
    {
        return Integer.valueOf(0).equals(current.getDelFlag())
                && current.getAccountStatus() != null
                && !Long.valueOf(2L).equals(current.getAccountStatus())
                && (Long.valueOf(0L).equals(current.getStatus())
                    || Long.valueOf(2L).equals(current.getStatus()));
    }

    private MmsMerchant ownerRegistrationPatch(MmsMerchant source, Long expectedOwnerMemberId)
    {
        MmsMerchant allowed = new MmsMerchant();
        allowed.setId(source.getId());
        allowed.setName(source.getName());
        allowed.setWechat(source.getWechat());
        allowed.setCategoryId(source.getCategoryId());
        allowed.setDescription(source.getDescription());
        allowed.setDerivatives(source.getDerivatives());
        allowed.setPreference(source.getPreference());
        allowed.setPhone(source.getPhone());
        allowed.setAddress(source.getAddress());
        allowed.setBusinessTime(source.getBusinessTime());
        allowed.setBusinessLicense(source.getBusinessLicense());
        allowed.setStatus(0L);
        allowed.setReson("");
        allowed.setLocationLat(source.getLocationLat());
        allowed.setLocationLng(source.getLocationLng());
        allowed.setLocationVerified(source.getLocationVerified());
        allowed.setUpdateBy(String.valueOf(expectedOwnerMemberId));
        allowed.setUpdateTime(DateUtils.getNowDate());
        return allowed;
    }

    /**
     * 批量删除商家
     * 
     * @param ids 需要删除的商家主键
     * @return 结果
     */
    @Override
    public int deleteMmsMerchantByIds(Long[] ids)
    {
        return mmsMerchantMapper.deleteMmsMerchantByIds(ids);
    }

    /**
     * 删除商家信息
     * 
     * @param id 商家主键
     * @return 结果
     */
    @Override
    public int deleteMmsMerchantById(Long id)
    {
        return mmsMerchantMapper.deleteMmsMerchantById(id);
    }
}
