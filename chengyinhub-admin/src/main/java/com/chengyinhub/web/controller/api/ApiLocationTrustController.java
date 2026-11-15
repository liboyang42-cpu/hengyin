package com.chengyinhub.web.controller.api;

import java.io.IOException;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.chengyinhub.business.locationtrust.ClaimEvidenceService;
import com.chengyinhub.business.locationtrust.LocationFix;
import com.chengyinhub.business.locationtrust.LocationSignals;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.exception.ServiceException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * P8 SEC-10: CITY claim photo evidence. The claim flow (coordinator) calls this right after the camera shot and
 * passes the returned {@code evidenceId} with the claim; the claim service then calls
 * {@link ClaimEvidenceService#consumeForClaim}. No photo bytes are stored.
 */
@RestController
@Tag(name = "定位可信")
@RequestMapping("/api/location-trust")
public class ApiLocationTrustController extends BaseController {

    @Autowired
    private ClaimEvidenceService claimEvidenceService;

    Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    @Operation(summary = "占领现场取证:相机照片 + 定位,返回一次性取证编号")
    @PostMapping("/claim-evidence")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    public AjaxResult claimEvidence(@RequestParam(value = "photo", required = false) MultipartFile photo,
                                    Double placeLat, Double placeLng, String placeRef, String captureSource,
                                    Long photoTakenAt, Double photoLat, Double photoLng, Double lat, Double lng) {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        byte[] bytes;
        try {
            bytes = photo == null || photo.isEmpty() ? null
                    : photo.getSize() > ClaimEvidenceService.MAX_PHOTO_BYTES ? new byte[ClaimEvidenceService.MAX_PHOTO_BYTES + 1]
                    : photo.getBytes();
        } catch (IOException e) {
            return error("照片读取失败,请重新拍摄");
        }
        LocationFix fix = lat != null && lng != null ? LocationSignals.current(lat, lng) : null;
        try {
            Map<String, Object> data = claimEvidenceService.submit(new ClaimEvidenceService.Submission(memberId, placeRef,
                    placeLat, placeLng, captureSource, bytes, photoTakenAt,
                    validPair(photoLat, photoLng) ? photoLat : null, validPair(photoLat, photoLng) ? photoLng : null, fix));
            return success(data);
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "查询本人一条取证结果(不消耗)")
    @GetMapping("/claim-evidence")
    public AjaxResult readClaimEvidence(Long evidenceId) {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        Map<String, Object> data = claimEvidenceService.read(memberId, evidenceId);
        return data == null ? error("取证记录不存在") : success(data);
    }

    private static boolean validPair(Double lat, Double lng) {
        return lat != null && lng != null && LocationFix.of(lat, lng).validCoordinates();
    }
}
