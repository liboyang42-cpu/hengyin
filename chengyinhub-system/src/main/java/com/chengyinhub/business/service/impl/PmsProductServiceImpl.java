package com.chengyinhub.business.service.impl;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson2.JSONArray;
import com.chengyinhub.business.domain.PmsSku;
import com.chengyinhub.business.domain.dto.ProductPublishStatusDTO;
import com.chengyinhub.business.domain.vo.ProductAttrVO;
import com.chengyinhub.business.mapper.PmsSkuMapper;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.PmsProductMapper;
import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.service.IPmsProductService;

/**
 * 积分商城商品Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
@Service
public class PmsProductServiceImpl implements IPmsProductService 
{
    @Autowired
    private PmsProductMapper pmsProductMapper;



    @Autowired
    private PmsSkuMapper pmsSkuMapper;



    /**
     * 查询积分商城商品
     * 
     * @param id 积分商城商品主键
     * @return 积分商城商品
     */
    @Override
    public PmsProduct selectPmsProductById(Long id)
    {
        return pmsProductMapper.selectPmsProductById(id);
    }

    /**
     * 查询积分商城商品列表
     * 
     * @param pmsProduct 积分商城商品
     * @return 积分商城商品
     */
    @Override
    public List<PmsProduct> selectPmsProductList(PmsProduct pmsProduct)
    {
        return pmsProductMapper.selectPmsProductList(pmsProduct);
    }

    /**
     * 新增积分商城商品
     * 
     * @param pmsProduct 积分商城商品
     * @return 结果
     */
    @Override
    public int insertPmsProduct(PmsProduct pmsProduct)
    {
        pmsProduct.setCreateTime(DateUtils.getNowDate());

//        if(pmsProduct.getMerchantId()>0){
//            pmsProduct.setMerchantId(pmsProduct.getMerchantId());
//        }
//        else{
//            pmsProduct.setMerchantId(0L);
//        //}

        List<PmsSku> skuList = pmsProduct.getSkuList();
        pmsProductMapper.insertPmsProduct(pmsProduct);
        if (skuList != null) {
            skuList.forEach(sku -> {
                sku.setProductId(pmsProduct.getId());
                sku.setCreateTime(DateUtils.getNowDate());
                StringBuilder skuName = new StringBuilder();
                StringBuilder skuNameAr = new StringBuilder();
                JSONArray.parseArray(pmsProduct.getProductAttr(), ProductAttrVO.class).forEach(attr -> {
                    attr.getOptions().forEach(option -> {
                        if (option.getName().equals(JSONUtil.parseObj(sku.getSpData()).get(attr.getName()))){
                            if (skuName.length() > 0){
                                skuName.append(" ");
                            }
                            if (skuNameAr.length() > 0){
                                skuNameAr.append(" ");
                            }
                            skuName.append(option.getName());
                            skuNameAr.append(option.getNameAr());
                        }
                    });
                });
                sku.setSkuName(skuName.toString());
                sku.setSkuNameAr(skuNameAr.toString());
                pmsSkuMapper.insertPmsSku(sku);
            });
        }

        return 1;
    }

    /**
     * 修改积分商城商品
     * 
     * @param pmsProduct 积分商城商品
     * @return 结果
     */
    @Override
    public int updatePmsProduct(PmsProduct pmsProduct)
    {
        PmsProduct dbProduct= pmsProductMapper.selectPmsProductById(pmsProduct.getId());
        List<Long> idList = pmsProduct.getSkuList().stream().filter(it -> it.getId() != null).map(it -> it.getId()).collect(Collectors.toList());
        if (dbProduct == null) {
            return 0;
        }
        pmsProduct.setUpdateTime(DateUtils.getNowDate());
        pmsProductMapper.updatePmsProduct(pmsProduct);
        //查找库中所有的sku
        PmsSku pmsSku = new PmsSku();
        pmsSku.setProductId(pmsProduct.getId());

        Map<Long, PmsSku> skuMap = pmsSkuMapper.selectPmsSkuList(pmsSku).stream().collect(Collectors.toMap(it -> it.getId(), it -> it));
        //针对已有的进行编辑
        List<PmsSku> updateList = pmsProduct.getSkuList().stream().filter(it -> it.getId() != null).collect(Collectors.toList());
        if (!CollectionUtil.isEmpty(updateList)) {
            //log.info("共有{}个sku需要修改，{}，productId：{}", updateList.size(), JSONUtil.toJsonStr(updateList), productVO.getId());
            updateList.forEach(it -> {
                PmsSku sku = skuMap.get(it.getId());
                StringBuilder skuName = new StringBuilder();
                StringBuilder skuNameAr = new StringBuilder();
                JSONArray.parseArray(pmsProduct.getProductAttr(), ProductAttrVO.class).forEach(attr -> {
                    attr.getOptions().forEach(option -> {
                        if (option.getName().equals(JSONUtil.parseObj(sku.getSpData()).get(attr.getName()))){
                            if (skuName.length() > 0){
                                skuName.append(" ");
                            }
                            if (skuNameAr.length() > 0){
                                skuNameAr.append(" ");
                            }
                            skuName.append(option.getName());
                            skuNameAr.append(option.getNameAr());
                        }
                    });
                });
                sku.setSkuName(skuName.toString());
                sku.setSkuNameAr(skuNameAr.toString());
                sku.setUpdateBy(SecurityUtils.getUsername());
                sku.setUpdateTime(DateUtils.getNowDate());
                sku.setPrice(it.getPrice());
                sku.setSpData(it.getSpData());
                sku.setPic(it.getPic());
                sku.setOutSkuId(it.getOutSkuId());
                sku.setWeight(it.getWeight());
                sku.setStock(it.getStock());
                pmsSkuMapper.updatePmsSku(sku);
            });
        }
        //针对没有的进行新增
        List<PmsSku> addList = pmsProduct.getSkuList().stream().filter(it -> it.getId() == null).collect(Collectors.toList());
        if (!CollectionUtil.isEmpty(addList)) {
            //log.info("共有{}个sku需要新增，{}，productId：{}", addList.size(), JSONUtil.toJsonStr(addList), productVO.getId());
            addList.forEach(sku -> {
                sku.setProductId(pmsProduct.getId());
                sku.setCreateTime(DateUtils.getNowDate());
                pmsSkuMapper.insertPmsSku(sku);
            });
        }
        //删除
        Long[] deleteIds = skuMap.keySet().stream().filter(it -> !idList.contains(it)).collect(Collectors.toList()).toArray(new Long[0]);
        if (!CollectionUtil.isEmpty(Arrays.asList(deleteIds))) {
            //log.info("共有{}个sku需要删除，{}，productId：{}", deleteIds.size(), JSONUtil.toJsonStr(deleteIds), productVO.getId());
            pmsSkuMapper.deletePmsSkuByIds(deleteIds);
        }

        return 1;
    }

    /**
     * 商品目录上下架只写状态与服务端操作人，避免目录动作误触 SKU 增删改。
     * 目录范围由既有 business:product:edit 权限控制；当前 pms_product 没有可验证的后台账号—商家归属，
     * 不能在页面迁移中虚构创建者范围而使历史商品不可运营。
     */
    @Override
    public int updateProductPublishStatus(ProductPublishStatusDTO request, String operator)
    {
        if (request == null || request.getId() == null
                || (!Integer.valueOf(0).equals(request.getPublishStatus())
                && !Integer.valueOf(1).equals(request.getPublishStatus())
                && !Integer.valueOf(2).equals(request.getPublishStatus())))
        {
            throw new ServiceException("商品上下架参数不合法", HttpStatus.BAD_REQUEST);
        }

        PmsProduct stored = pmsProductMapper.selectPmsProductById(request.getId());
        if (stored == null || Integer.valueOf(2).equals(stored.getDelFlag()))
        {
            throw new ServiceException("商品不存在或已删除", HttpStatus.NOT_FOUND);
        }

        int rows = pmsProductMapper.updatePmsProductPublishStatus(request.getId(), stored.getPublishStatus(),
                request.getPublishStatus(), operator, DateUtils.getNowDate());
        if (rows <= 0)
        {
            throw new ServiceException("商品状态已变化，请刷新后重试", HttpStatus.CONFLICT);
        }
        return rows;
    }

    /**
     * 批量删除积分商城商品
     * 
     * @param ids 需要删除的积分商城商品主键
     * @return 结果
     */
    @Override
    public int deletePmsProductByIds(Long[] ids)
    {
        throw new ServiceException("积分商城商品不支持物理删除，请使用下架；删除处置须完成四重死活检查", HttpStatus.FORBIDDEN);
    }

    /**
     * 删除积分商城商品信息
     * 
     * @param id 积分商城商品主键
     * @return 结果
     */
    @Override
    public int deletePmsProductById(Long id)
    {
        throw new ServiceException("积分商城商品不支持物理删除，请使用下架；删除处置须完成四重死活检查", HttpStatus.FORBIDDEN);
    }
}
