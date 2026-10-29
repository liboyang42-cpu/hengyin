package com.chengyinhub.common.exception;
public class ServiceException extends RuntimeException {
  private Integer code; private String detailMessage;
  public ServiceException(){} public ServiceException(String m){super(m);} public ServiceException(String m, Integer c){super(m);code=c;}
  public ServiceException setDetailMessage(String d){detailMessage=d;return this;} public String getDetailMessage(){return detailMessage;} public Integer getCode(){return code;} private Object data; public ServiceException setData(Object d){data=d;return this;} public Object getData(){return data;}
}
