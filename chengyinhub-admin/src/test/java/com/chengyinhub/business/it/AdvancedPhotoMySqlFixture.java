package com.chengyinhub.business.it;

import com.chengyinhub.business.mapper.PhotoCheckIntentMapper;
import com.chengyinhub.business.service.ConsentGuard;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.util.function.Consumer;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real intent/receipt SQL and transactions; media, consent, quota and provider are explicit doubles. */
final class AdvancedPhotoMySqlFixture {
    private AdvancedPhotoMySqlFixture() { }
    static PhotoCheckVisionService install(AdvancedGameRuntimeServiceImpl service, SqlSessionTemplate sql, DataSource source,
            PhotoCheckVisionResult result) {
        return install(service, sql, source, result, () -> {});
    }
    static PhotoCheckVisionService install(AdvancedGameRuntimeServiceImpl service, SqlSessionTemplate sql, DataSource source,
            PhotoCheckVisionResult result, Runnable beforeProvider) {
        AppMediaReferenceGuard media = mock(AppMediaReferenceGuard.class);
        when(media.requireAttachment(eq(7L), anyString(), eq(true))).thenReturn("media-asset:701");
        when(media.processingUrl(anyString(), eq(7L), eq("IMAGE"))).thenAnswer(i -> i.getArgument(0));
        AiGatewayService gateway = mock(AiGatewayService.class);
        when(gateway.planQuotaReservation(eq(7L), eq("photo_check"), anyString()))
                .thenAnswer(i -> "ai:gen:limit:photo:7:20261023|" + i.getArgument(2));
        PhotoCheckVisionService vision = mock(PhotoCheckVisionService.class);
        when(vision.reviewPrepared(eq(7L), eq("media-asset:701"), anyString(), anyString(), any())).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Consumer<PhotoCheckVisionService.InputSnapshot> save = i.getArgument(4);
            save.accept(new PhotoCheckVisionService.InputSnapshot("1".repeat(64), "fixture-model", "fixture-prompt", "fixture-encoder"));
            beforeProvider.run();
            return new PhotoCheckVisionService.PreparedReview(result, "RECEIVED", "FIXTURE_RESULT");
        });
        PhotoCheckIntentService intents = new PhotoCheckIntentService(sql.getMapper(PhotoCheckIntentMapper.class), gateway,
                vision, mock(ConsentGuard.class), media, new DataSourceTransactionManager(source));
        ReflectionTestUtils.setField(service, "photoCheckIntentService", intents);
        ReflectionTestUtils.setField(service, "mediaReferences", media);
        return vision;
    }
}
