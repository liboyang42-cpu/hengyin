package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.PmsGift;

/**
 * 众筹礼物Mapper接口
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
public interface PmsGiftMapper 
{
    /**
     * 查询众筹礼物
     * 
     * @param id 众筹礼物主键
     * @return 众筹礼物
     */
    public PmsGift selectPmsGiftById(Long id);

    /**
     * 在礼物目录写入事务内锁定一行，保证调价审计的旧值连续准确。
     *
     * @param id 礼物主键
     * @return 已锁定的礼物
     */
    public PmsGift selectPmsGiftByIdForUpdate(Long id);

    /**
     * 查询众筹礼物列表
     * 
     * @param pmsGift 众筹礼物
     * @return 众筹礼物集合
     */
    public List<PmsGift> selectPmsGiftList(PmsGift pmsGift);

    /**
     * 查询 C 端可实际赠送的礼物目录。
     *
     * <p>后台管理列表不能复用此查询：运营需要看见历史坏数据并修复。</p>
     *
     * @return 可展示且积分配置有效的礼物集合
     */
    public List<PmsGift> selectAvailablePmsGiftList();

    /**
     * 新增众筹礼物
     * 
     * @param pmsGift 众筹礼物
     * @return 结果
     */
    public int insertPmsGift(PmsGift pmsGift);

    /**
     * 修改众筹礼物
     * 
     * @param pmsGift 众筹礼物
     * @return 结果
     */
    public int updatePmsGift(PmsGift pmsGift);

    /**
     * 删除众筹礼物
     * 
     * @param id 众筹礼物主键
     * @return 结果
     */
    public int deletePmsGiftById(Long id);

    /**
     * 批量删除众筹礼物
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deletePmsGiftByIds(Long[] ids);
}
