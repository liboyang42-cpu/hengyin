package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.UmsMemberCart;
import org.apache.ibatis.annotations.Param;

/**
 * 购物车Mapper接口
 * 
 * @author ruoyi
 * @date 2025-09-02
 */
public interface UmsMemberCartMapper 
{
    /**
     * 查询购物车
     * 
     * @param id 购物车主键
     * @return 购物车
     */
    public UmsMemberCart selectUmsMemberCartById(Long id);

    /**
     * 查询购物车列表
     * 
     * @param umsMemberCart 购物车
     * @return 购物车集合
     */
    public List<UmsMemberCart> selectUmsMemberCartList(UmsMemberCart umsMemberCart);

    /**
     * 新增购物车
     * 
     * @param umsMemberCart 购物车
     * @return 结果
     */
    public int insertUmsMemberCart(UmsMemberCart umsMemberCart);

    /**
     * 修改购物车
     * 
     * @param umsMemberCart 购物车
     * @return 结果
     */
    public int updateUmsMemberCart(UmsMemberCart umsMemberCart);

    int updateUmsMemberCartByIdAndMemberId(UmsMemberCart umsMemberCart);

    /**
     * 删除购物车
     * 
     * @param id 购物车主键
     * @return 结果
     */
    public int deleteUmsMemberCartById(Long id);

    /**
     * 批量删除购物车
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteUmsMemberCartByIds(Long[] ids);

    List<UmsMemberCart> selectUmsMemberCartByIdsAndMemberId(@Param("memberId") Long memberId,
                                                              @Param("ids") Long[] ids);

    int deleteUmsMemberCartByIdsAndMemberId(@Param("memberId") Long memberId,
                                             @Param("ids") Long[] ids);

    int deleteUmsMemberCartByIdAndMemberId(@Param("memberId") Long memberId,
                                            @Param("id") Long id);

    Long lockUmsMemberCartByIdAndMemberId(@Param("memberId") Long memberId,
                                          @Param("id") Long id);
}
