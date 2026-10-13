package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.UmsMemberCart;
import com.chengyinhub.business.domain.ViewMemberCart;
import com.chengyinhub.business.domain.vo.MemberCartVO;

/**
 * 购物车Service接口
 * 
 * @author ruoyi
 * @date 2025-09-02
 */
public interface IUmsMemberCartService 
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

    /**
     * 批量删除购物车
     * 
     * @param ids 需要删除的购物车主键集合
     * @return 结果
     */
    public int deleteUmsMemberCartByIds(Long[] ids);

    /**
     * 删除购物车信息
     * 
     * @param id 购物车主键
     * @return 结果
     */
    public int deleteUmsMemberCartById(Long id);

    /**
     * 按当前会员归属删除购物车；原始 ID 参数在 Service 内严格解析并全量校验。
     */
    int deleteCarts(Long memberId, String rawCartIds);

    Long addCart(Long memberId, String productId, String skuId, String quantity, String isBuy);

    Long updateCart(Long memberId, String cartId, String quantity, String skuId);

    MemberCartVO previewSettlement(Long memberId, String rawCartIds);

    Long settleCart(Long memberId, String username, String rawCartIds, String remark, String rawAddressId);
}
