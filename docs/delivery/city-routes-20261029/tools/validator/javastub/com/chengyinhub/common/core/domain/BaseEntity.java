package com.chengyinhub.common.core.domain;
import java.util.*;
public class BaseEntity implements java.io.Serializable {
  private String searchValue, createBy, updateBy, remark; private Date createTime, updateTime; private Map<String,Object> params = new HashMap<>();
  public String getSearchValue(){return searchValue;} public void setSearchValue(String s){searchValue=s;}
  public String getCreateBy(){return createBy;} public void setCreateBy(String s){createBy=s;}
  public String getUpdateBy(){return updateBy;} public void setUpdateBy(String s){updateBy=s;}
  public String getRemark(){return remark;} public void setRemark(String s){remark=s;}
  public Date getCreateTime(){return createTime;} public void setCreateTime(Date d){createTime=d;}
  public Date getUpdateTime(){return updateTime;} public void setUpdateTime(Date d){updateTime=d;}
  public Map<String,Object> getParams(){return params;} public void setParams(Map<String,Object> p){params=p;}
}
