package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import org.apache.ibatis.annotations.Param;

/**
 * 商家扫码记录Mapper接口
 * 
 * @author ruoyi
 * @date 2025-10-17
 */
public interface CmsRegistrationScanMapper 
{
    /**
     * 查询商家扫码记录
     * 
     * @param id 商家扫码记录主键
     * @return 商家扫码记录
     */
    public CmsRegistrationScan selectCmsRegistrationScanById(String id);

    /** 只从报名 authoritative topic 的节点/模板创建缺失 scan。 */
    public int insertMissingScansForTopic(@Param("registrationId") Long registrationId,
                                          @Param("topicId") Long topicId,
                                          @Param("createTime") java.util.Date createTime);

    /**
     * 查询商家扫码记录列表
     * 
     * @param cmsRegistrationScan 商家扫码记录
     * @return 商家扫码记录集合
     */
    public List<CmsRegistrationScan> selectCmsRegistrationScanList(CmsRegistrationScan cmsRegistrationScan);

    /** 整单核销被后台撤销:把该票的核销台账行(registration_type=1)标为已撤销(status=2),幂等。 */
    int markWholeOrderScansReversed(@org.apache.ibatis.annotations.Param("registrationId") Long registrationId);

    /**
     * 新增商家扫码记录
     * 
     * @param cmsRegistrationScan 商家扫码记录
     * @return 结果
     */
    public int insertCmsRegistrationScan(CmsRegistrationScan cmsRegistrationScan);

    /**
     * 修改商家扫码记录
     * 
     * @param cmsRegistrationScan 商家扫码记录
     * @return 结果
     */
    public int updateCmsRegistrationScan(CmsRegistrationScan cmsRegistrationScan);

    /**
     * 删除商家扫码记录
     * 
     * @param id 商家扫码记录主键
     * @return 结果
     */
    public int deleteCmsRegistrationScanById(String id);

    /**
     * 批量删除商家扫码记录
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsRegistrationScanByIds(String[] ids);
}
