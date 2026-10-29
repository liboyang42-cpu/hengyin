package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.AppMediaAssetMapper;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AppMediaAssetService {
    @org.springframework.beans.factory.annotation.Value("${app.media.public-base-url:${APP_MEDIA_PUBLIC_BASE_URL:}}")
    private String publicBaseUrl;
    private final AppMediaAssetMapper mapper;
    private final ObjectProvider<IAuditTaskService> audits;
    public AppMediaAssetService(AppMediaAssetMapper mapper,ObjectProvider<IAuditTaskService> audits) {
        this.mapper=mapper; this.audits=audits;
    }
    @Transactional
    public String registerApprovedSystemImage(Long userId,String objectKey,String publicUrl) {
        if(userId==null || userId<=0 || objectKey==null || publicUrl==null)
            throw new ServiceException("后台图片审核事实缺少真实归属");
        if(!objectKey.matches("upload/[0-9a-f]{32}\\.(jpg|jpeg|png|gif)")
                && !objectKey.matches("/profile/upload/[0-9]{4}/[0-9]{2}/[0-9]{2}/[0-9a-f]{32}\\.(jpg|jpeg|png|gif)"))
            throw new ServiceException("后台图片文件来源无效");
        try {
            java.net.URI uri=java.net.URI.create(publicUrl);
            String expectedPath=objectKey.startsWith("/")?objectKey:"/"+objectKey;
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null
                    || (uri.getPath()==null || !uri.getPath().endsWith(expectedPath) || uri.getPath().contains("..")))throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {throw new ServiceException("后台图片地址来源无效");}
        boolean local=objectKey.startsWith("/profile/");
        Map<String,Object> row=new LinkedHashMap<>();row.put("memberId",userId);row.put("objectKey",objectKey);
        row.put("publicUrl",local?localApprovedUrl(objectKey):null);
        row.put("storageType",objectKey.startsWith("/profile/")?"LOCAL":"OSS");
        if(mapper.insertApprovedSystemImage(row)!=1)throw new ServiceException("后台图片审核事实保存失败");
        return local?localApprovedUrl(objectKey):FileOssUploadUtils.generatePresignedUrl(objectKey);
    }
    /** Internal upload receipt: called only after actual synchronous image approval. */
    @Transactional
    public Map<String,Object> stageApprovedImage(Long memberId,String objectKey) {
        if(memberId==null || memberId<=0 || objectKey==null || !objectKey.matches("upload/[0-9a-f]{32}\\.(jpg|jpeg|png|gif)"))
            throw new ServiceException("图片归属或文件来源无效");
        Map<String,Object> row=new LinkedHashMap<>();row.put("memberId",memberId);row.put("objectKey",objectKey);
        row.put("mediaType","IMAGE");row.put("reviewState","APPROVED");
        if(mapper.insertApprovedImage(row)!=1 || !(row.get("id") instanceof Number))throw new ServiceException("图片审核事实保存失败");
        return publicStatus(row);
    }
    public void lockForAudit(AuditTask task) {
        Map<String,Object> row=mapper.selectForUpdate(task.getBizId());
        if(row==null || !(row.get("auditTaskId") instanceof Number) || task.getId()==null
                || ((Number)row.get("auditTaskId")).longValue()!=task.getId().longValue())throw new ServiceException("媒体审核单已变化");
    }
    @Transactional
    public Map<String,Object> stage(Long memberId,String objectKey) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if(memberId==null || memberId<=0 || objectKey==null || !objectKey.matches("upload/[0-9a-f]{32}\\.(mp3|m4a|aac|wav|glb|jpg|jpeg|png|gif)"))
            throw new ServiceException("媒体归属或文件来源无效");
        Map<String,Object> row=new LinkedHashMap<>(); row.put("memberId",memberId);row.put("objectKey",objectKey);
        row.put("mediaType",objectKey.endsWith(".glb")?"MODEL3D":objectKey.matches(".*\\.(jpg|jpeg|png|gif)")?"IMAGE":"AUDIO");row.put("version",0);row.put("reviewState","HOLD");
        if(mapper.insert(row)!=1 || !(row.get("id") instanceof Number))throw new ServiceException("媒体审核记录保存失败");
        submit(row);
        return publicStatus(row);
    }
    private void submit(Map<String,Object> row) {
        Long id=((Number)row.get("id")).longValue(); Integer version=((Number)row.get("version")).intValue();
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("assetId",id);snapshot.put("assetVersion",version);
        snapshot.put("mediaType",row.get("mediaType"));
        Long task=audits.getObject().submitSafetyHold("MEDIA_ASSET",id,((Number)row.get("memberId")).longValue(),"NORMAL",com.alibaba.fastjson2.JSON.toJSONString(snapshot));
        if(task==null || mapper.bindAuditTask(id,version,task)!=1)throw new ServiceException("媒体审核单保存失败");
        row.put("auditTaskId",task);
    }
    @Transactional
    public Map<String,Object> retry(Long id,Long memberId) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        Map<String,Object> row=owned(mapper.selectForUpdate(id),memberId);
        int version=((Number)row.get("version")).intValue();
        if(mapper.retry(id,memberId,version)!=1)throw new ServiceException("媒体当前状态不允许重新提审");
        row.put("version",version+1);row.put("reviewState","HOLD");row.put("reviewReason",null);
        submit(row);return publicStatus(row);
    }
    public java.util.List<Map<String,Object>> ownedRecent(Long memberId) {return ownedRecent(memberId,null);}
    public java.util.List<Map<String,Object>> ownedRecent(Long memberId,Long beforeId) {
        if(memberId==null || memberId<=0)throw new ServiceException("请先登录查看本人媒体");
        if(beforeId!=null && beforeId<=0)throw new ServiceException("媒体分页位置无效，请刷新重试");
        java.util.List<Map<String,Object>> result=new java.util.ArrayList<>();
        java.util.List<Map<String,Object>> rows=beforeId==null?mapper.selectOwnedRecent(memberId):mapper.selectOwnedBefore(memberId,beforeId);
        for(Map<String,Object> row:rows) {
            Map<String,Object> item=publicStatus(row);item.put("assetId",String.valueOf(row.get("id")));
            item.put("updatedAt",row.get("updatedAt"));result.add(item);
        }
        return result;
    }
    public Map<String,Object> status(Long id,Long memberId) {return publicStatus(owned(mapper.select(id),memberId));}
    public String ownerPreview(Long id,Long memberId) {
        Map<String,Object> row=owned(mapper.select(id),memberId);
        return FileOssUploadUtils.generateShortLivedPresignedUrl(String.valueOf(row.get("objectKey")),300);
    }
    public String reviewerPreview(Long id) {
        Map<String,Object> row=mapper.select(id);if(row==null)throw new ServiceException("媒体不存在");
        return FileOssUploadUtils.generateShortLivedPresignedUrl(String.valueOf(row.get("objectKey")),300);
    }
    /**
     * S531-3:申诉附件给审核员看 —— 与 reviewerPreview 同一条 5 分钟短签名;调用方须已校验媒体审核读取权限。
     * 只返回编号、媒体类型与预览地址,不回 objectKey/归属等内部字段;媒体不存在时 previewUrl 为空。
     */
    public Map<String,Object> reviewerAttachmentPreview(Long id) {
        Map<String,Object> item=new LinkedHashMap<>();item.put("assetId",id);
        Map<String,Object> row=id==null?null:mapper.select(id);
        item.put("mediaType",row==null?null:row.get("mediaType"));
        String objectKey=row==null?null:String.valueOf(row.get("objectKey"));
        item.put("previewUrl",row==null?null:"LOCAL".equals(row.get("storageType"))?localApprovedUrl(objectKey)
                :FileOssUploadUtils.generateShortLivedPresignedUrl(objectKey,300));
        return item;
    }
    public String approvedUrl(Long id) {
        Map<String,Object> row=mapper.select(id);
        if(row==null || !"APPROVED".equals(row.get("reviewState")))throw new ServiceException("媒体尚未审核通过");
        if("LOCAL".equals(row.get("storageType")))return localApprovedUrl(String.valueOf(row.get("objectKey")));
        return FileOssUploadUtils.generatePresignedUrl(String.valueOf(row.get("objectKey")));
    }
    /**
     * G-3:未登记的本站系统图(后台上传、模板自带、登记表上线前的旧图)按存储 key 出公开地址;
     * 本地发布地址没配时返回 null,由读路径跳过这张图,不让整页失败。
     */
    public String systemImageUrl(String objectKey) {
        if(objectKey.startsWith("/profile/"))return publicBaseUrl==null || publicBaseUrl.trim().isEmpty()?null:localPublicBase()+objectKey;
        return FileOssUploadUtils.generatePresignedUrl(objectKey);
    }
    public void requireLocalPublicationOrigin() {localPublicBase();}
    private String localApprovedUrl(String objectKey) {
        if(objectKey==null || !objectKey.matches("/profile/upload/[0-9]{4}/[0-9]{2}/[0-9]{2}/[0-9a-f]{32}\\.(jpg|jpeg|png|gif)"))
            throw new ServiceException("图片文件来源无效");
        return localPublicBase()+objectKey;
    }
    private String localPublicBase() {
        try {
            if(publicBaseUrl==null || publicBaseUrl.trim().isEmpty())throw new IllegalArgumentException();
            java.net.URI base=java.net.URI.create(publicBaseUrl.trim());
            String path=base.getPath()==null?"":base.getPath().replaceAll("/+$","");
            if (!("https".equals(base.getScheme()) || "http".equals(base.getScheme())) || base.getHost()==null
                    || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null
                    || base.getPort()==0 || base.getPort()>65535 || !path.matches("(?:/[A-Za-z0-9_-]+)*"))throw new IllegalArgumentException();
            return base.getScheme()+"://"+base.getRawAuthority()+path;
        } catch(IllegalArgumentException e) {throw new ServiceException("图片发布地址未配置或无效，请联系管理员");}
    }
    /** Called inside the existing audit transaction; an old review cannot publish a newer version. */
    public void applyAudit(AuditTask task,boolean approved,String reason) {
        Map<String,Object> snapshot=com.alibaba.fastjson2.JSON.parseObject(task.getSnapshotJson());
        Object version=snapshot==null?null:snapshot.get("assetVersion");
        if(!(version instanceof Number) || mapper.decide(task.getBizId(),((Number)version).intValue(),task.getId(),approved?"APPROVED":"REJECTED",reason)!=1)
            throw new ServiceException("媒体版本已变化，审核未提交");
    }
    private static Map<String,Object> owned(Map<String,Object> row,Long memberId) {
        if(row==null || memberId==null || !"APP".equals(row.get("ownerType")) || !(row.get("memberId") instanceof Number)
                || ((Number)row.get("memberId")).longValue()!=memberId.longValue())throw new ServiceException("无权读取该媒体");
        return row;
    }
    private static Map<String,Object> publicStatus(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("assetId",row.get("id"));result.put("reviewState",row.get("reviewState"));
        result.put("reviewReason",row.get("reviewReason"));result.put("mediaType",row.get("mediaType"));return result;
    }
}
