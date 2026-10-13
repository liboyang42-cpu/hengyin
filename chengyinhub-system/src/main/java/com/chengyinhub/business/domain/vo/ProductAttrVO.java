package com.chengyinhub.business.domain.vo;


import java.util.List;


public class ProductAttrVO {
    private String name;

    private String nameAr;

    private List<ProductAttrVO> options;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNameAr() {
        return nameAr;
    }

    public void setNameAr(String nameAr) {
        this.nameAr = nameAr;
    }

    public List<ProductAttrVO> getOptions() {
        return options;
    }

    public void setOptions(List<ProductAttrVO> options) {
        this.options = options;
    }


}
