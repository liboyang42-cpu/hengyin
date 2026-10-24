package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.NpcChatRequest;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.business.service.impl.NpcChatService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 旧PR#28 L03:本人删除后持久读取与迟到写不得恢复旧答案。master 之后把语音回答持久为 audio_asset_id,
 * 它与 safe_text 同属答案副本:删除必须一并擦除,迟到的语音 worker 也不能再把音频绑回已擦除的回执。
 * 用真实 NpcChatRequestMapper.xml 语句(H2 MySQL 模式)执行,不 mock SQL。
 */
class NpcChatDeleteAudioRedactionMyBatisTest {

    private SqlSession open(String db) throws Exception {
        Configuration config = new Configuration();
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new PooledDataSource("org.h2.Driver", "jdbc:h2:mem:" + db + ";MODE=MySQL", "sa", "")));
        String resource = "mapper/business/NpcChatRequestMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table npc_chat_request (id bigint auto_increment primary key,"
                    + " user_id bigint not null, request_id varchar(64) not null, session_id bigint not null,"
                    + " status varchar(16) not null, safety_decision varchar(16), error_code varchar(64),"
                    + " limit_type varchar(32), retry_after_seconds int, retryable tinyint, safe_text varchar(512),"
                    + " ai tinyint, lease_token varchar(64), lease_until datetime, active_slot tinyint,"
                    + " completed_at datetime, create_time datetime, update_time datetime, audio_asset_id bigint)");
        }
        return session;
    }

    private NpcChatRequest succeeded(NpcChatRequestMapper requests) {
        NpcChatRequest row = new NpcChatRequest();
        row.setUserId(9L);
        row.setRequestId(UUID.randomUUID().toString());
        row.setSessionId(-42L);
        row.setStatus("SUCCEEDED");
        row.setSafetyDecision("PASS");
        row.setSafeText("推荐手冲。");
        row.setRetryable(false);
        row.setAi(true);
        assertEquals(1, requests.insertNpcChatRequest(row));
        return row;
    }

    @Test
    void deletionErasesBoundAudioSoSameRequestReplayCannotReturnIt() throws Exception {
        try (SqlSession session = open("npc_delete_audio_redact")) {
            NpcChatRequestMapper requests = session.getMapper(NpcChatRequestMapper.class);
            NpcChatRequest row = succeeded(requests);
            assertEquals(1, requests.bindApprovedTextAudio(9L, row.getRequestId(), 12L));

            AppMediaAssetService media = mock(AppMediaAssetService.class);
            when(media.approvedUrl(12L)).thenReturn("https://owned.invalid/approved.mp3");
            NpcChatService chat = new NpcChatService();
            ReflectionTestUtils.setField(chat, "audioAssets", media);
            NpcChatResp before = ReflectionTestUtils.invokeMethod(chat, "fromStored",
                    requests.selectByUserAndRequestId(9L, row.getRequestId()));
            assertEquals("https://owned.invalid/approved.mp3", before.getAudioUrl(), "负控:删除前同请求可回放已审音频");

            requests.redactChatRequestsByUserId(9L);

            NpcChatRequest stored = requests.selectByUserAndRequestId(9L, row.getRequestId());
            assertNotNull(stored, "删除保留请求身份,防付费请求被重放");
            assertNull(stored.getSafeText());
            assertNull(stored.getAudioAssetId(), "音频引用与 safe_text 同属答案副本,必须一并擦除");
            NpcChatResp after = ReflectionTestUtils.invokeMethod(chat, "fromStored", stored);
            assertNull(after.getSafeText());
            assertNull(after.getAudioUrl(), "删除后同 requestId 回放不得再给出已删回答的音频");
        }
    }

    @Test
    void lateVoiceWorkerCannotBindAudioOntoErasedReceipt() throws Exception {
        try (SqlSession session = open("npc_delete_audio_late_bind")) {
            NpcChatRequestMapper requests = session.getMapper(NpcChatRequestMapper.class);
            NpcChatRequest erased = succeeded(requests);
            requests.redactChatRequestsByUserId(9L);

            assertEquals(0, requests.bindApprovedTextAudio(9L, erased.getRequestId(), 13L),
                    "删除提交后迟到的合成结果不能绑回已擦除回执");
            assertNull(requests.selectByUserAndRequestId(9L, erased.getRequestId()).getAudioAssetId());

            NpcChatRequest fresh = new NpcChatRequest();
            fresh.setUserId(10L);
            fresh.setRequestId(UUID.randomUUID().toString());
            fresh.setSessionId(-42L);
            fresh.setStatus("SUCCEEDED");
            fresh.setSafetyDecision("PASS");
            fresh.setSafeText("推荐手冲。");
            fresh.setRetryable(false);
            fresh.setAi(true);
            assertEquals(1, requests.insertNpcChatRequest(fresh));
            assertEquals(1, requests.bindApprovedTextAudio(10L, fresh.getRequestId(), 14L),
                    "负控:未删除的安全成功回执仍可绑定音频");
        }
    }
}
