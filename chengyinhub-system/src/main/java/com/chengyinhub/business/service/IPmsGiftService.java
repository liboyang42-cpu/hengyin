package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.PmsGift;

/**
 * 众筹礼物Service接口
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
public interface IPmsGiftService 
{
    /**
     * 查询众筹礼物
     * 
     * @param id 众筹礼物主键
     * @return 众筹礼物
     */
    public PmsGift selectPmsGiftById(Long id);

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
     * @return 礼物集合
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
     * 批量删除众筹礼物
     * 
     * @param ids 需要删除的众筹礼物主键集合
     * @return 结果
     */
    public int deletePmsGiftByIds(Long[] ids);

    /**
     * 删除众筹礼物信息
     * 
     * @param id 众筹礼物主键
     * @return 结果
     */
    public int deletePmsGiftById(Long id);
}
