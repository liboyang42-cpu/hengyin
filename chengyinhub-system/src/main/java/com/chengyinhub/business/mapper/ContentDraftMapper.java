package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.ContentDraft;

public interface ContentDraftMapper {
    int insertDraft(ContentDraft draft);
    ContentDraft selectOwnedById(@Param("id") Long id, @Param("ownerMemberId") Long ownerMemberId);
    ContentDraft selectByBusinessKey(@Param("ownerMemberId") Long ownerMemberId,
                                     @Param("businessType") String businessType,
                                     @Param("clientDraftKey") String clientDraftKey);
    List<ContentDraft> selectActiveList(@Param("ownerMemberId") Long ownerMemberId,
                                        @Param("businessType") String businessType);
    int updateDraftCas(@Param("draft") ContentDraft draft, @Param("expectedVersion") Long expectedVersion);
    int deleteDraftCas(@Param("id") Long id, @Param("ownerMemberId") Long ownerMemberId,
                       @Param("expectedVersion") Long expectedVersion);
    int publishDraftCas(@Param("id") Long id, @Param("ownerMemberId") Long ownerMemberId,
                        @Param("expectedVersion") Long expectedVersion,
                        @Param("publishedResourceId") Long publishedResourceId);
    int countOwnedResource(@Param("businessType") String businessType,
                           @Param("resourceId") Long resourceId,
                           @Param("ownerMemberId") Long ownerMemberId);
}
