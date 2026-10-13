package com.chengyinhub.web.controller.api;


import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysDictData;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.utils.DictUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.file.FileUtils;
import com.chengyinhub.system.domain.SysBanner;
import com.chengyinhub.system.service.ISysConfigService;
import com.chengyinhub.system.service.ISysDictDataService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

import com.chengyinhub.framework.config.ServerConfig;
import com.chengyinhub.system.service.ISysBannerService;


/**
 * 通用接口
 *
 * @author webxh
 * @date 2025-08-22
 */
@RestController
//@Api(tags = "01.通用")
@Tag(name = "通用")
@RequestMapping("/api/common")
public class ApiCommonController extends BaseController {
    @Autowired
    private AppUploadService appUploadService = new AppUploadService();

    @Autowired
    private ICommunityMediaReceiptService communityMediaReceiptService;

    @Autowired
    private IMerchantAftercareService merchantAftercareService;

    @Autowired
    private ISysDictDataService dictDataService;

    @Autowired
    private ServerConfig serverConfig;

    private static final String FILE_DELIMETER = ",";

    @Autowired
    private ISysBannerService sysBannerService;

    @Autowired
    private ICmsInfomationService cmsInfomationService;

    @Autowired
    private ICmsRegistrationMerchantService cmsRegistrationMerchantService;


    @Autowired
    private ICmsSettingService cmsSettingService;

    @Operation(summary = "根据字典类型获取字典值")
    @PostMapping(value = "/dict")
    @Parameters({
            @Parameter(name = "dictType", description = "字典类型名")
    })
    public AjaxResult dictType(String dictType) {
        List<SysDictData> dictDatas = DictUtils.getDictCache(dictType);
        if (StringUtils.isNotEmpty(dictDatas)) {
            return success(dictDatas);
        }

        SysDictData dictData = new SysDictData();
        dictData.setDictType(dictType);
        List<SysDictData> data = dictDataService.selectDictDataList(dictData);
        if (StringUtils.isNull(data)) {
            data = new ArrayList<SysDictData>();
        }
        return success(data);
    }

    @Operation(summary = "上传文件到OSS")
    @PostMapping("/uploadOSS")
    public ResponseEntity<AjaxResult> uploadOSS(@RequestParam("file") MultipartFile file,
                                                @RequestParam(value = "bizType", required = false) String bizType,
                                                @RequestParam(value = "refundId", required = false) Long refundId) {
        Long memberId = currentAppMemberIdOrNull();
        Long merchantId = null;
        if (UploadImagePolicy.MERCHANT_AFTERCARE_EVIDENCE_BIZ_TYPE.equals(bizType)) {
            // W6f:凭证绑定门店+退款单,写 OSS 前先按当前店员校验该单属于其门店
            if (memberId == null) return badUploadRequest("请先登录商家账号");
            if (refundId == null) return badUploadRequest("请先选择售后单再上传凭证");
            try {
                merchantId = merchantAftercareService.requireEvidenceUploadTarget(memberId, refundId);
            } catch (com.chengyinhub.common.exception.ServiceException e) {
                int code = e.getCode() == null ? HttpStatus.FORBIDDEN.value() : e.getCode();
                return ResponseEntity.status(code).body(AjaxResult.error(code, e.getMessage()));
            }
        }
        try {
            Long communityMemberId = null;
            if (UploadImagePolicy.COMMUNITY_POST_BIZ_TYPE.equals(bizType)) {
                try { communityMemberId = getAppUserId(); } catch (Exception ignored) { }
                if (communityMemberId == null || communityMemberId <= 0) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AjaxResult.error("请先登录"));
                }
            }
            com.alibaba.fastjson2.JSONObject jsonObject =
                    appUploadService.uploadOss(file, bizType, memberId, merchantId, refundId);
            AjaxResult ajax = AjaxResult.success();
            ajax.put("fileName", jsonObject.get("fileName"));
            if (!UploadImagePolicy.MERCHANT_AFTERCARE_EVIDENCE_BIZ_TYPE.equals(bizType)) {
                ajax.put("url", jsonObject.get("url"));
            }
            if (communityMemberId != null) {
                String objectKey = String.valueOf(jsonObject.get("url"));
                ajax.put("byteSize", file.getSize());
                ajax.put("mimeType", StringUtils.defaultIfEmpty(file.getContentType(), "application/octet-stream"));
                ajax.put("uploadReceipt", communityMediaReceiptService.issue(communityMemberId, objectKey, file.getSize()));
            }
            return ResponseEntity.ok(ajax);
        } catch (UploadRejectedException e) {
            return badUploadRequest(e.getMessage());
        } catch (UploadSecurityUnavailableException e) {
            return securityServiceUnavailable(e.getMessage());
        } catch (UploadStorageException e) {
            logger.error("OSS文件上传失败", e);
            return uploadFailed();
        }
    }

    /**
     * 当前登录小程序/App 会员 id;后台等非会员主体(或匿名)返回 null —— 这类上传不写归属,
     * 也不会被 photo 首次提交接受,不误伤 banner/category 等既有后台上传。
     */
    private Long currentAppMemberIdOrNull() {
        try {
            Long memberId = getAppUserId();
            return memberId == null || memberId <= 0 ? null : memberId;
        } catch (Exception notAppMember) {
            return null;
        }
    }

    /**
     * 通用上传请求（单个）
     */
    @Operation(summary = "上传文件到本地（单个）")
    @PostMapping("/upload")
    public ResponseEntity<AjaxResult> uploadFile(MultipartFile file) {
        try {
            // 上传文件路径
            String filePath = RuoYiConfig.getUploadPath();
            String fileName = appUploadService.uploadImage(filePath, file);
            String url = serverConfig.getUrl() + fileName;
            AjaxResult ajax = AjaxResult.success();
            ajax.put("url", url);
            ajax.put("fileName", fileName);
            ajax.put("newFileName", FileUtils.getName(fileName));
            return ResponseEntity.ok(ajax);
        } catch (UploadRejectedException e) {
            return badUploadRequest(e.getMessage());
        } catch (UploadStorageException e) {
            logger.error("本地文件上传失败", e);
            return uploadFailed();
        }
    }

    /**
     * 通用上传请求（多个）
     */
    @Operation(summary = "上传文件到本地（多个）")
    @PostMapping("/uploads")
    public ResponseEntity<AjaxResult> uploadFiles(List<MultipartFile> files) {
        try {
            // 上传文件路径
            String filePath = RuoYiConfig.getUploadPath();
            List<String> urls = new ArrayList<String>();
            List<String> fileNames = appUploadService.uploadImages(filePath, files);
            List<String> newFileNames = new ArrayList<String>();
            for (int index = 0; index < files.size(); index++)
            {
                String fileName = fileNames.get(index);
                urls.add(serverConfig.getUrl() + fileName);
                newFileNames.add(FileUtils.getName(fileName));
            }
            AjaxResult ajax = AjaxResult.success();
            ajax.put("urls", StringUtils.join(urls, FILE_DELIMETER));
            ajax.put("fileNames", StringUtils.join(fileNames, FILE_DELIMETER));
            ajax.put("newFileNames", StringUtils.join(newFileNames, FILE_DELIMETER));
            return ResponseEntity.ok(ajax);
        } catch (UploadRejectedException e) {
            return badUploadRequest(e.getMessage());
        } catch (UploadStorageException e) {
            logger.error("批量本地文件上传失败", e);
            return uploadFailed();
        }
    }

    private ResponseEntity<AjaxResult> badUploadRequest(String message)
    {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(AjaxResult.error(HttpStatus.BAD_REQUEST.value(), message));
    }

    private ResponseEntity<AjaxResult> uploadFailed()
    {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AjaxResult.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "文件上传失败"));
    }

    private ResponseEntity<AjaxResult> securityServiceUnavailable(String message)
    {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(AjaxResult.error(HttpStatus.SERVICE_UNAVAILABLE.value(), message));
    }

    @Operation(summary = "获取轮播图")
    @PostMapping(value = "/banner")
    @Parameters({
            @Parameter(name = "showType", description = "显示类型 1首页头部轮播  2其它"),
            @Parameter(name = "linkType", description = "点击类型 "),
    })
    public AjaxResult getBanner(String showType,Integer linkType) {
        SysBanner sysBanner = new SysBanner();
        sysBanner.setStatus(1);
        sysBanner.setShowType(Convert.toInt(showType, 1));
        if(linkType!=null && linkType==1){
            sysBanner.setLinkType(linkType);
        }
        List<SysBanner> list = sysBannerService.selectSysBannerList(sysBanner);

//        for(SysBanner banner: list){
//            banner.setPicUrl(FileOssUploadUtils.getPresignedUrl(banner.getPicUrl()));
//        }

        return success(list);
    }


    @Operation(summary = "获取玩法说明")
    @PostMapping(value = "/infomation_list")
    public AjaxResult infomationList() {
        CmsInfomation cmsInfomation = new CmsInfomation();
        List<CmsInfomation> list = cmsInfomationService.selectCmsInfomationList(cmsInfomation);
        return success(list);
    }


    @Operation(summary = "获取玩法说明")
    @PostMapping(value = "/infomation_detail")
    public AjaxResult infomationDetail(String id) {
        CmsInfomation cmsInfomation = new CmsInfomation();
        cmsInfomation = cmsInfomationService.selectCmsInfomationById(Long.valueOf(id));
        return success(cmsInfomation);
    }


    // P0-28 安全:原 /api/common/topic_clear、/activity_clear 无 @PreAuthorize、
    // 落在 /api/ App 鉴权命名空间下,任何登录会员即可 POST 触发全平台结算(越权)。
    // 结算由 settlementTask 定时任务承担,这两个手动触发端点全仓零调用方,直接下线;
    // 如运维确需手动触发,应另建后台 @PreAuthorize 管理端点,不放在 App 面。
}
