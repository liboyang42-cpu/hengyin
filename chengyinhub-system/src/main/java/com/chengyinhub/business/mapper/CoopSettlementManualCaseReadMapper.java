package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.CoopSettlementManualCaseView;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Separate read projection; none of the original writer/fence statements are changed. */
public interface CoopSettlementManualCaseReadMapper {
    List<CoopSettlementManualCaseView> selectList(@Param("topicId") Long topicId, @Param("state") String state);
    Record selectById(@Param("id") Long id);
    class Record extends CoopSettlementManualCaseView {
        private String sourceSnapshot;
        @JsonIgnore public String getSourceSnapshot(){return sourceSnapshot;}
        public void setSourceSnapshot(String value){sourceSnapshot=value;}
    }
}
