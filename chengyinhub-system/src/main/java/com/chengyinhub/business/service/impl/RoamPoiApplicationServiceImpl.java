package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.domain.dto.RoamPoiApplicationRequest;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IRoamPoiApplicationService;
import com.chengyinhub.business.service.IUmsMemberService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Service
public class RoamPoiApplicationServiceImpl implements IRoamPoiApplicationService {
    private final RoamMapper roamMapper;
    private final IMmsMerchantService merchantService;
    private final IUmsMemberService memberService;
    private final IRoleService roleService;
    private final MerchantCommerceService merchantCommerceService;
    private final ICmsMemberTemplateService templateService;
    private final ICouponRewardAuthorizationService couponAuthorizationService;

    public RoamPoiApplicationServiceImpl(RoamMapper roamMapper,
                                         IMmsMerchantService merchantService,
                                         IUmsMemberService memberService,
                                         IRoleService roleService,
                                         MerchantCommerceService merchantCommerceService,
                                         ICmsMemberTemplateService templateService,
                                         ICouponRewardAuthorizationService couponAuthorizationService) {
        this.roamMapper = roamMapper;
        this.merchantService = merchantService;
        this.memberService = memberService;
        this.roleService = roleService;
        this.merchantCommerceService = merchantCommerceService;
        this.templateService = templateService;
        this.couponAuthorizationService = couponAuthorizationService;
    }

    @Override
    @Transactional
    public Long submitPlacement(Long memberId, RoamPoiApplicationRequest request) {
        if (request == null || request.getName() == null || request.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("请填写据点名称");
        }
        if (request.getLatitude() == null || request.getLongitude() == null) {
            throw new IllegalArgumentException("请在地图上标注店址");
        }
        if (positive(request.getTargetPoiId())) {
            throw new IllegalArgumentException("已上线据点不能复用投放申请入口");
        }
        MmsMerchant merchant = requireNodePermission(memberId);
        requirePlacementScope(request, memberId);
        if (roamMapper.lockMerchantForNodeReview(memberId) == null) {
            throw new IllegalArgumentException("商家不存在");
        }
        if (roamMapper.countPendingPlacements(merchant.getId()) > 0) {
            throw new IllegalStateException("已有待审核投放申请");
        }

        RoamPoi pending = toPendingPoi(request, merchant.getId());
        if (roamMapper.insertPendingMerchantPoi(pending) != 1 || !positive(pending.getId())) {
            throw new IllegalStateException("据点投放申请保存失败");
        }
        return pending.getId();
    }

    @Override
    @Transactional
    public Long submitClaim(Long memberId, Long poiId) {
        MmsMerchant merchant = requireNodePermission(memberId);
        if (!positive(poiId) || roamMapper.selectPoiForClaim(poiId) == null) {
            throw new IllegalArgumentException("节点不存在、已下架、已有待审申请或已被认领");
        }
        if (roamMapper.submitClaimIfAvailable(poiId, merchant.getId()) != 1) {
            throw new IllegalStateException("该节点已有待审核认领申请或已被认领");
        }
        return poiId;
    }

    @Override
    @Transactional
    public boolean cancelClaim(Long memberId, Long poiId) {
        if (!positive(poiId)) {
            throw new IllegalArgumentException("参数有误");
        }
        // 撤回是退路,只认商家主体、不校验 canOpenAsNode —— 权限被撤的商家也要能把自己的待审认领放出来
        // (与 setOwnedNodeStatus 的下线应急口径一致),否则这个 POI 会永远锁在「已有待审申请」上。
        MmsMerchant merchant = requireMerchant(memberId);
        return roamMapper.cancelPendingClaim(poiId, merchant.getId()) == 1;
    }

    @Override
    public List<RoamPoi> listMine(Long memberId) {
        MmsMerchant merchant = requireMerchant(memberId);
        return roamMapper.selectRoamApplications(merchant.getId(), null);
    }

    @Override
    public List<RoamPoi> listForReview(Integer auditStatus) {
        return roamMapper.selectRoamApplications(null, auditStatus);
    }

    @Override
    public List<RoamPoi> listClaimable(String keyword) {
        return roamMapper.selectClaimablePois(trimToNull(keyword), 50);
    }

    @Override
    @Transactional
    public Long approve(Long applicationId) {
        RoamPoi application = requireRoamApplication(applicationId);
        MmsMerchant applicant = merchantService.selectMmsMerchantById(application.getApplicantMerchantId());
        if (applicant == null || !positive(applicant.getMemberId())) {
            throw new IllegalArgumentException("申请商家不存在");
        }
        if (roamMapper.lockMerchantForNodeReview(applicant.getMemberId()) == null) {
            throw new IllegalArgumentException("商家不存在");
        }
        MmsMerchant merchant = requireApprovalScope(application, applicant.getMemberId());
        if (!application.getApplicantMerchantId().equals(merchant.getId())) {
            throw new IllegalStateException("申请商家归属已变化");
        }
        assertActiveQuota(applicant.getMemberId(), merchant);
        if (roamMapper.markRoamApplicationReviewing(applicationId) != 1) {
            throw new IllegalStateException("申请已被处置，请刷新后重试");
        }
        if (roamMapper.approveRoamApplication(applicationId, application.getApplicationType(), merchant.getId()) != 1) {
            throw new IllegalStateException("节点归属已变化，请刷新后重试");
        }
        return applicationId;
    }

    private MmsMerchant requireApprovalScope(RoamPoi application, Long memberId) {
        MmsMerchant merchant = requireNodePermission(memberId);
        if (Integer.valueOf(TYPE_PLACEMENT).equals(application.getApplicationType())) {
            CmsMemberTemplate template = positive(application.getTemplateId())
                    ? templateService.selectCmsMemberTemplateById(application.getTemplateId()) : null;
            if (template == null || template.getMemberId() == null || !memberId.equals(template.getMemberId())) {
                throw new IllegalStateException("互动模板不存在或归属已变化");
            }
            couponAuthorizationService.assertOwnedRewardCoupon(application.getCouponTemplateId(), memberId);
        }
        return merchant;
    }

    private void requirePlacementScope(RoamPoiApplicationRequest request, Long memberId) {
        CmsMemberTemplate template = positive(request.getTemplateId())
                ? templateService.selectCmsMemberTemplateById(request.getTemplateId()) : null;
        if (template == null || template.getMemberId() == null || !memberId.equals(template.getMemberId())) {
            throw new IllegalArgumentException("互动模板不存在或无权使用");
        }
        couponAuthorizationService.assertOwnedRewardCoupon(request.getCouponId(), memberId);
    }

    @Override
    public boolean reject(Long applicationId, String reason) {
        requireRoamApplication(applicationId);
        String normalized = trimToNull(reason);
        if (normalized == null) throw new IllegalArgumentException("请填写驳回原因");
        if (normalized.length() > 200) throw new IllegalArgumentException("驳回原因不能超过200字");
        return roamMapper.rejectRoamApplicationIfPending(applicationId, normalized) == 1;
    }

    @Override
    @Transactional
    public boolean setOwnedNodeStatus(Long memberId, Long poiId, Integer status) {
        if (!positive(poiId) || status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("参数有误");
        }
        if (roamMapper.lockMerchantForNodeReview(memberId) == null) {
            throw new IllegalArgumentException("商家不存在");
        }
        // 撤权后仍必须允许下架自有据点；权限/配额只拦截重新上架。
        MmsMerchant merchant = status == 1 ? requireNodePermission(memberId) : requireMerchant(memberId);
        RoamPoi owned = roamMapper.selectMerchantPoiOwned(poiId, merchant.getId());
        if (owned == null) throw new IllegalArgumentException("据点不存在或无权操作");
        if (status.equals(owned.getStatus())) return true;
        if (status == 1) assertActiveQuota(memberId, merchant);
        return roamMapper.setNodeStatusOwned(poiId, merchant.getId(), status) == 1;
    }

    private RoamPoi requireRoamApplication(Long id) {
        RoamPoi application = positive(id) ? roamMapper.selectRoamApplicationById(id) : null;
        if (application == null || !positive(application.getApplicantMerchantId())
                || (!Integer.valueOf(TYPE_PLACEMENT).equals(application.getApplicationType())
                && !Integer.valueOf(TYPE_CLAIM).equals(application.getApplicationType()))) {
            throw new IllegalArgumentException("据点申请不存在");
        }
        return application;
    }

    private MmsMerchant requireNodePermission(Long memberId) {
        MmsMerchant merchant = requireMerchant(memberId);
        UmsMember member = memberService.selectUmsMemberById(memberId);
        UserPermission permission = member == null ? null : roleService.getPermission(member);
        if (permission == null || !Boolean.TRUE.equals(permission.getCanOpenAsNode())) {
            throw new IllegalStateException("商家已无城市节点权限");
        }
        return merchant;
    }

    private void assertActiveQuota(Long memberId, MmsMerchant merchant) {
        UmsMember member = memberService.selectUmsMemberById(memberId);
        String role = roleService.resolveRole(member);
        int used = roamMapper.countActiveNodesByMerchantForUpdate(merchant.getId());
        int max = merchantCommerceService.entitlement(memberId, role,
                MerchantCommerceService.ENTITLEMENT_CITY_NODE, used).getLimit();
        if (used >= max) throw new IllegalStateException("在架据点已达上限(" + max + ")");
    }

    private MmsMerchant requireMerchant(Long memberId) {
        MmsMerchant merchant = positive(memberId) ? merchantService.selectMmsMerchantByMemberId(memberId) : null;
        if (merchant == null || !positive(merchant.getId())) throw new IllegalArgumentException("请先完成商家入驻");
        return merchant;
    }

    private RoamPoi toPendingPoi(RoamPoiApplicationRequest request, Long applicantMerchantId) {
        RoamPoi poi = new RoamPoi();
        poi.setName(request.getName().trim());
        poi.setLat(request.getLatitude());
        poi.setLng(request.getLongitude());
        poi.setRadiusM(request.getRadiusM() != null && request.getRadiusM() > 0 ? request.getRadiusM() : 80);
        poi.setAddress(trimToNull(request.getAddress()));
        poi.setDescription(trimToNull(request.getDescription()));
        poi.setCouponTemplateId(request.getCouponId());
        poi.setCityCode(trimToNull(request.getCityCode()));
        poi.setStatus(0);
        poi.setAuditStatus(AUDIT_PENDING);
        poi.setApplicationType(TYPE_PLACEMENT);
        poi.setApplicantMerchantId(applicantMerchantId);
        poi.setMerchantId(null);
        poi.setTemplateId(request.getTemplateId());
        poi.setNodeLevel(1);
        poi.setTags(trimToNull(request.getTags()));
        poi.setCoverImg(trimToNull(request.getCoverImg()));
        poi.setSort(0);
        poi.setCreateTime(new Date());
        return poi;
    }

    private boolean positive(Long value) { return value != null && value > 0; }
    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }
}
