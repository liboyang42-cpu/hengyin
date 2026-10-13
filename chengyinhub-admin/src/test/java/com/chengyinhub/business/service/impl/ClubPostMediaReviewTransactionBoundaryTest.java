package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostMediaReviewOutbox;
import com.chengyinhub.business.domain.ClubPostRevision;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubPostMapper;
import com.chengyinhub.business.mapper.ClubPostMediaReviewOutboxMapper;
import com.chengyinhub.business.mapper.ClubPostRevisionMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IClubPostMediaReviewService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClubPostMediaReviewTransactionBoundaryTest {
    private static final long CLUB_ID = 73L;
    private static final long OWNER = 42L;
    private static final long NEW_POST_ID = 81L;
    private static final long EXISTING_POST_ID = 82L;
    private static final String OLD_CONTENT = "旧内容";
    private static final String NEW_CONTENT = "更新后的内容";
    private static final String OLD_MEDIA = "https://cdn.example.test/club/old.jpg";
    private static final String NEW_MEDIA = "https://cdn.example.test/club/new.jpg";
    private static final String OUTBOX_FAILURE = "simulated outbox failure after insert";

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private AtomicInteger outboxAttempts;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:club_post_media_tx_" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        outboxAttempts = new AtomicInteger();

        jdbc.execute("create table club_post(id bigint primary key, club_id bigint not null, "
                + "author_member_id bigint not null, content varchar(300) not null, images varchar(2000), "
                + "type int not null, version int not null, del_flag int not null, "
                + "create_time timestamp, update_time timestamp)");
        jdbc.execute("create table club_post_revision(id bigint auto_increment primary key, "
                + "post_id bigint not null, club_id bigint not null, editor_member_id bigint not null, "
                + "snapshot_version int not null, content varchar(300), images varchar(2000), "
                + "type int not null, create_time timestamp not null)");
        jdbc.execute("create table club_post_media_review_outbox(id bigint auto_increment primary key, "
                + "post_id bigint not null, post_version int not null, submitter_member_id bigint not null, "
                + "media_hash varchar(64) not null, state int not null)");
        jdbc.update("insert into club_post(id,club_id,author_member_id,content,images,type,version,del_flag) "
                        + "values (?,?,?,?,?,?,0,0)",
                EXISTING_POST_ID, CLUB_ID, OWNER, OLD_CONTENT, OLD_MEDIA, 0);
    }

    @Test
    void directBypassLeavesInsertedPostWhenMandatoryOutboxHasNoTransaction() {
        ClubPost post = newPost();

        assertThrows(IllegalTransactionStateException.class,
                () -> target().insertClubPostInActiveClub(post, OWNER));

        assertEquals(1L, independentNumber(
                "select count(*) from club_post where id=" + NEW_POST_ID));
        assertEquals(0L, independentNumber("select count(*) from club_post_media_review_outbox"));
        assertEquals(0, outboxAttempts.get());
    }

    @Test
    void proxyRollsBackPostAndOutboxWhenOutboxFailsAfterInsert() {
        ClubPost post = newPost();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> proxy(target()).insertClubPostInActiveClub(post, OWNER));

        assertEquals(OUTBOX_FAILURE, failure.getMessage());
        assertEquals(1, outboxAttempts.get(), "必须真实到达并写入 outbox seam 后再失败");
        assertEquals(0L, independentNumber(
                "select count(*) from club_post where id=" + NEW_POST_ID));
        assertEquals(0L, independentNumber("select count(*) from club_post_media_review_outbox"));
    }

    @Test
    void directBypassLeavesContentVersionAndRevisionWhenMandatoryOutboxHasNoTransaction() {
        assertThrows(IllegalTransactionStateException.class,
                () -> target().updatePost(OWNER, EXISTING_POST_ID, NEW_CONTENT, NEW_MEDIA,
                        0, "club-post-update-direct"));

        assertEquals(NEW_CONTENT, independentText(
                "select content from club_post where id=" + EXISTING_POST_ID));
        assertEquals(1L, independentNumber(
                "select version from club_post where id=" + EXISTING_POST_ID));
        assertEquals(1L, independentNumber("select count(*) from club_post_revision"));
        assertEquals(0L, independentNumber("select count(*) from club_post_media_review_outbox"));
        assertEquals(0, outboxAttempts.get());
    }

    @Test
    void proxyRollsBackContentVersionRevisionAndOutboxWhenOutboxFailsAfterInsert() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> proxy(target()).updatePost(OWNER, EXISTING_POST_ID, NEW_CONTENT, NEW_MEDIA,
                        0, "club-post-update-proxy"));

        assertEquals(OUTBOX_FAILURE, failure.getMessage());
        assertEquals(1, outboxAttempts.get(), "必须真实到达并写入 outbox seam 后再失败");
        assertEquals(OLD_CONTENT, independentText(
                "select content from club_post where id=" + EXISTING_POST_ID));
        assertEquals(0L, independentNumber(
                "select version from club_post where id=" + EXISTING_POST_ID));
        assertEquals(0L, independentNumber("select count(*) from club_post_revision"));
        assertEquals(0L, independentNumber("select count(*) from club_post_media_review_outbox"));
    }

    private ClubPostServiceImpl target() {
        ClubPostMapper posts = databaseBackedPostMapper();
        ClubMapper clubs = mock(ClubMapper.class);
        Club activeClub = new Club();
        activeClub.setId(CLUB_ID);
        activeClub.setMemberId(OWNER);
        when(clubs.selectClubByIdForUpdate(CLUB_ID)).thenReturn(activeClub);

        IClubAccessService access = mock(IClubAccessService.class);
        when(access.resolve(OWNER, CLUB_ID, null)).thenReturn(new ClubAccessContext(
                OWNER, CLUB_ID, OWNER, null, Collections.singleton(ClubRoleCode.CLUB_OWNER)));

        BusinessAuditEventMapper audits = mock(BusinessAuditEventMapper.class);
        when(audits.selectByDomainActionRequestForUpdate(
                anyString(), anyLong(), anyString(), anyString())).thenReturn(null);

        ClubPostServiceImpl target = new ClubPostServiceImpl();
        ReflectionTestUtils.setField(target, "clubPostMapper", posts);
        ReflectionTestUtils.setField(target, "clubMapper", clubs);
        ReflectionTestUtils.setField(target, "clubMemberMapper", mock(ClubMemberMapper.class));
        ReflectionTestUtils.setField(target, "clubAccessService", access);
        ReflectionTestUtils.setField(target, "clubPostRevisionMapper", databaseBackedRevisionMapper());
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", audits);
        ReflectionTestUtils.setField(target, "clubPostMediaReviewService", failingMediaReviewService(posts));
        return target;
    }

    private IClubPostService proxy(ClubPostServiceImpl target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IClubPostService.class);
        factory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (IClubPostService) factory.getProxy();
    }

    private IClubPostMediaReviewService failingMediaReviewService(ClubPostMapper posts) {
        ClubPostMediaReviewOutboxMapper outbox = mock(ClubPostMediaReviewOutboxMapper.class);
        when(outbox.insertIgnore(any(ClubPostMediaReviewOutbox.class))).thenAnswer(call -> {
            ClubPostMediaReviewOutbox task = call.getArgument(0);
            jdbc.update("insert into club_post_media_review_outbox(post_id,post_version,submitter_member_id,media_hash,state) "
                            + "values (?,?,?,?,?)",
                    task.getPostId(), task.getPostVersion(), task.getSubmitterMemberId(),
                    task.getMediaHash(), task.getState());
            outboxAttempts.incrementAndGet();
            throw new IllegalStateException(OUTBOX_FAILURE);
        });

        ClubPostMediaReviewTransactionService transactionTarget =
                new ClubPostMediaReviewTransactionService(outbox);
        ProxyFactory transactionFactory = new ProxyFactory(transactionTarget);
        transactionFactory.setProxyTargetClass(true);
        transactionFactory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        ClubPostMediaReviewTransactionService transactionService =
                (ClubPostMediaReviewTransactionService) transactionFactory.getProxy();

        return new ClubPostMediaReviewServiceImpl(outbox, transactionService, posts,
                mock(UmsMemberMapper.class), mock(IWxContentSecurityService.class),
                mock(IWxContentCallbackInboxService.class), mock(AlarmService.class));
    }

    private ClubPostMapper databaseBackedPostMapper() {
        ClubPostMapper mapper = mock(ClubPostMapper.class);
        when(mapper.insertClubPost(any(ClubPost.class))).thenAnswer(call -> {
            ClubPost post = call.getArgument(0);
            post.setId(NEW_POST_ID);
            return jdbc.update("insert into club_post(id,club_id,author_member_id,content,images,type,version,"
                            + "del_flag,create_time) values (?,?,?,?,?,?,?,?,?)",
                    post.getId(), post.getClubId(), post.getAuthorMemberId(), post.getContent(),
                    post.getImages(), post.getType(), post.getVersion(), 0, post.getCreateTime());
        });
        when(mapper.selectClubPostByIdForUpdate(anyLong())).thenAnswer(call -> selectPost(call.getArgument(0)));
        when(mapper.selectClubPostById(anyLong())).thenAnswer(call -> selectPost(call.getArgument(0)));
        when(mapper.updateContentCas(anyLong(), anyString(), anyString(), anyInt(), any(Date.class)))
                .thenAnswer(call -> jdbc.update("update club_post set content=?,images=?,version=version+1,"
                                + "update_time=? where id=? and version=? and del_flag=0",
                        call.getArgument(1), call.getArgument(2), call.getArgument(4),
                        call.getArgument(0), call.getArgument(3)));
        return mapper;
    }

    private ClubPostRevisionMapper databaseBackedRevisionMapper() {
        ClubPostRevisionMapper mapper = mock(ClubPostRevisionMapper.class);
        when(mapper.insertClubPostRevision(any(ClubPostRevision.class))).thenAnswer(call -> {
            ClubPostRevision revision = call.getArgument(0);
            return jdbc.update("insert into club_post_revision(post_id,club_id,editor_member_id,snapshot_version,"
                            + "content,images,type,create_time) values (?,?,?,?,?,?,?,?)",
                    revision.getPostId(), revision.getClubId(), revision.getEditorMemberId(),
                    revision.getSnapshotVersion(), revision.getContent(), revision.getImages(),
                    revision.getType(), revision.getCreateTime());
        });
        return mapper;
    }

    private ClubPost selectPost(Long id) {
        return jdbc.queryForObject("select * from club_post where id=?", new Object[] { id }, (rs, rowNum) -> {
            ClubPost post = new ClubPost();
            post.setId(rs.getLong("id"));
            post.setClubId(rs.getLong("club_id"));
            post.setAuthorMemberId(rs.getLong("author_member_id"));
            post.setContent(rs.getString("content"));
            post.setImages(rs.getString("images"));
            post.setType(rs.getInt("type"));
            post.setVersion(rs.getInt("version"));
            post.setDelFlag(rs.getInt("del_flag"));
            post.setIsPinned(0);
            return post;
        });
    }

    private ClubPost newPost() {
        ClubPost post = new ClubPost();
        post.setClubId(CLUB_ID);
        post.setAuthorMemberId(OWNER);
        post.setContent("新动态");
        post.setImages(NEW_MEDIA);
        post.setType(0);
        return post;
    }

    private long independentNumber(String sql) {
        return ((Number) independentScalar(sql)).longValue();
    }

    private String independentText(String sql) {
        return String.valueOf(independentScalar(sql));
    }

    private Object independentScalar(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getObject(1);
        } catch (Exception e) {
            throw new AssertionError("独立连接读回失败", e);
        }
    }
}
