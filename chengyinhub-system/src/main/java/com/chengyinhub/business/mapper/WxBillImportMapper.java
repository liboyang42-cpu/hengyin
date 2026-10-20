package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.WxBillImport;
import java.util.Date;
import org.apache.ibatis.annotations.Param;

public interface WxBillImportMapper {
    int upsertImport(WxBillImport row);
    WxBillImport selectImport(@Param("merchantId") String merchantId, @Param("billType") String billType, @Param("billDate") Date billDate);
}
