package com.chengyinhub.business.service;

import com.chengyinhub.business.service.support.TemplateLibraryRepublishReview;


import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.vo.CategoryTemplatesVO;
import com.chengyinhub.business.domain.vo.TemplateHomeVO;

import java.util.List;

public interface ITemplatePublishService {
    Long saveMerchantNodeTemplate(CmsMemberTemplate input, Long memberId, String username);
    /**
     * 发布模板（事务方法）
     * @param request 模板请求参数
     * @param memberId 用户ID
     * @param username 用户名
     * @return 模板ID
     */
    public Long publishTemplateWithTransaction(CmsTemplatePublishRequest request, Long memberId, String username);

    /**
     * 保存或更新草稿(draftStatus=0)。request 带 id 则覆盖更新当前用户自己的草稿,否则新建。
     * @return 草稿模板ID
     */
    public Long saveOrUpdateDraft(CmsTemplatePublishRequest request, Long memberId, String username);

    /** 所有直接接收模板实体的写入口共用：校验 vm7 配置，非 vm7 清空传感器字段。 */
    public void normalizeAndValidateSensorFields(CmsMemberTemplate template);

    /** 后台创建模板：写入审核真源、可选投影并进入统一审核。 */
    public int createAdminTemplate(CmsMemberTemplate template, String username);

    /** 后台编辑审核真源：重置投影并重新提交审核。 */
    public int updateAdminTemplate(CmsMemberTemplate template, String username);

    /** 后台或模板库入口提交模板审核；已有未结任务时复用。 */
    public Long submitTemplateForReview(Long templateId);

    /** 将已个人过审玩法独立提交到模板广场审核。 */
    Long submitTemplateToMarket(Long templateId, Long memberId, String username);

    /** 模板库是投影；编辑必须回写审核真源并重新提交审核。 */
    public int updateTemplateFromLibraryProjection(CmsTemplateLibrary projection, String username);

    /** 从审核真源删除模板及其所有投影。 */
    public int deleteAdminTemplates(Long[] templateIds);

    /** 模板库删除入口只软下架投影；操作人用于回填 update_by，让货架变更可追溯。 */
    public int deleteTemplateFromLibraryProjection(Long projectionId, String username);

    /** 模板库批量删除统一在模板服务的一个事务内完成。 */
    public int deleteTemplateLibraryProjections(Long[] projectionIds, String username);

    /**
     * 删除模板（同步处理模板库中的关联记录）
     * @param templateId 用户模板ID
     * @param memberId 用户ID（用于权限验证）
     * @return 删除结果
     */
    public boolean deleteTemplateWithSync(Long templateId, Long memberId);

    /**
     * 上下架模板库记录（仅限用户自己发布的模板）
     * @param memberTemplateId 用户模板ID
     * @param publishStatus 发布状态：0下架 1上架
     * @param memberId 用户ID（用于权限验证）
     * @return 操作结果
     */
    public boolean updateTemplateLibraryStatus(Long memberTemplateId, Integer publishStatus, Long memberId);

    /** 上架必须绑定锁外复检的完整不可变快照；下架无需复检。 */
    boolean updateTemplateLibraryStatus(Long memberTemplateId, Integer publishStatus, Long memberId,
                                        TemplateLibraryRepublishReview review);


    /**
     * 按分类分组获取模板列表
     * @return 分类模板分组列表
     */
    public List<CategoryTemplatesVO> getTemplatesGroupByCategory();

    /**
     * 模板库首页聚合数据（App Store 风格：精选/必玩/热门榜/分类/总数）
     * @return 首页聚合数据
     */
    public TemplateHomeVO getHomeData();

    /** 公共详情从审核真源补齐不重复持久化的奖励字段。 */
    public void enrichPublicRewardProjection(CmsTemplateLibrary projection);
}
