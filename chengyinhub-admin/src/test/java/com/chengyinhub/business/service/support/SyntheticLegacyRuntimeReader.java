package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import java.util.Collections;
import java.util.List;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Test-only migration of existing gameplay fixtures. All fixture IDs explicitly represent legacy
 * rows; an unstubbed row represents an existing empty gameplay configuration, not a missing row.
 * This is not a provenance/database test. Dedicated runtime gate tests supply rejecting readers.
 */
public final class SyntheticLegacyRuntimeReader {
    private SyntheticLegacyRuntimeReader() { }
    public static TopicRouteRuntimeServiceImpl attach(TopicRouteRuntimeServiceImpl runtime,
                                                       CmsMemberTemplateMapper fixtureBodies) {
        LegacyCmsTemplateBodyReader reader = mock(LegacyCmsTemplateBodyReader.class);
        when(reader.findLegacy(anyLong())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            if (fixtureBodies != null) {
                CmsMemberTemplate direct = fixtureBodies.selectCmsMemberTemplateById(id);
                if (direct != null) return direct;
                List<CmsMemberTemplate> rows = fixtureBodies.selectCmsMemberTemplateByIds(Collections.singletonList(id));
                if (rows != null) for (CmsMemberTemplate row : rows) {
                    if (row != null && id.equals(row.getId())) return row;
                }
            }
            CmsMemberTemplate empty = new CmsMemberTemplate(); empty.setId(id); return empty;
        });
        ReflectionTestUtils.setField(runtime, "legacyTemplateBodyReader", reader);
        return runtime;
    }
}
