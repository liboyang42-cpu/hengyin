package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.TopicPublicationService;
import com.chengyinhub.business.service.impl.TopicQueryService;
import com.chengyinhub.business.service.impl.TopicContentCopyServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.HashSet;
import java.util.ArrayList;
import org.mockito.Mockito;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

/** Assemble the real topic owners with the fixture's original collaborator doubles. */
public final class TopicPublicationTestWiring {
    private TopicPublicationTestWiring() { }

    private static CmsTopicServiceImpl facade(Object value) {
        Object target = AopTestUtils.getUltimateTargetObject(value);
        return target instanceof CmsTopicServiceImpl ? (CmsTopicServiceImpl) target : null;
    }

    private static List<Object> owners(CmsTopicServiceImpl facade) {
        TopicPublicationService publication = (TopicPublicationService) ReflectionTestUtils.getField(facade, "topicPublicationService");
        TopicPublicationContentGuard content = (TopicPublicationContentGuard) ReflectionTestUtils.getField(facade, "publicationContentGuard");
        TopicSelfHostActivityService activity = (TopicSelfHostActivityService) ReflectionTestUtils.getField(facade, "selfHostActivityService");
        if (publication == null || plainMock(publication)) publication = new TopicPublicationService();
        if (content == null || plainMock(content)) content = new TopicPublicationContentGuard();
        if (activity == null || plainMock(activity)) activity = new TopicSelfHostActivityService();
        ReflectionTestUtils.setField(facade, "topicPublicationService", publication);
        ReflectionTestUtils.setField(facade, "publicationContentGuard", content);
        ReflectionTestUtils.setField(facade, "selfHostActivityService", activity);
        ReflectionTestUtils.setField(publication, "publicationContentGuard", content);
        ReflectionTestUtils.setField(publication, "selfHostActivityService", activity);
        TopicQueryService query = (TopicQueryService) ReflectionTestUtils.getField(facade, "topicQueryService");
        Object existingCopy = ReflectionTestUtils.getField(facade, "topicContentCopyService");
        if (query == null || plainMock(query)) query = new TopicQueryService();
        if (existingCopy == null || plainMock(existingCopy)) existingCopy = new TopicContentCopyServiceImpl();
        ReflectionTestUtils.setField(facade, "topicQueryService", query);
        ReflectionTestUtils.setField(facade, "topicContentCopyService", existingCopy);
        List<Object> result = new ArrayList<>(List.of(AopTestUtils.getUltimateTargetObject(publication),
                AopTestUtils.getUltimateTargetObject(content), AopTestUtils.getUltimateTargetObject(activity),
                AopTestUtils.getUltimateTargetObject(query),
                AopTestUtils.getUltimateTargetObject(existingCopy)));
        Object graph = ReflectionTestUtils.getField(facade, "contentGraphAuthoringService");
        if (graph != null && !Mockito.mockingDetails(graph).isMock())
            result.add(AopTestUtils.getUltimateTargetObject(graph));
        Object guard = ReflectionTestUtils.getField(facade, "contentWriteGuard");
        if (guard != null && !Mockito.mockingDetails(guard).isMock()) result.add(guard);
        return result;
    }

    public static <T> T wire(T value) {
        CmsTopicServiceImpl facade = facade(value);
        if (facade == null) return value;
        var topicMapper = (com.chengyinhub.business.mapper.CmsTopicMapper) ReflectionTestUtils.getField(facade, "cmsTopicMapper");
        // Existing unit fixtures predate the narrow parent-lock query. Keep their original topic doubles;
        // only supply the newly introduced lock acknowledgement, never override an explicit new stub.
        if (topicMapper != null && Mockito.mockingDetails(topicMapper).isMock()
                && !Mockito.mockingDetails(topicMapper).isSpy()
                && Mockito.mockingDetails(topicMapper).getStubbings().stream().noneMatch(stub ->
                "lockTopicContentBudget".equals(stub.getInvocation().getMethod().getName()))) {
            Mockito.lenient().doAnswer(call -> {
                var current = topicMapper.selectCmsTopicByIdForUpdate(call.getArgument(0));
                return current == null ? null : current.getId();
            }).when(topicMapper).lockTopicContentBudget(org.mockito.ArgumentMatchers.anyLong());
        }
        // Unit-only current-read port: retain the fixture's original topic/owner/status rows.
        Object stateKeys = get(facade, "themeStateKeyService");
        if (topicMapper != null && stateKeys instanceof ThemeStateKeyService && !Mockito.mockingDetails(stateKeys).isMock()) {
            var keys = (com.chengyinhub.business.mapper.ThemeStateKeyMapper)
                    ReflectionTestUtils.getField(stateKeys, "themeStateKeyMapper");
            if (keys != null && Mockito.mockingDetails(keys).isMock() && !Mockito.mockingDetails(keys).isSpy()
                    && Mockito.mockingDetails(keys).getStubbings().stream().noneMatch(stub ->
                    "lockTopicForStateKeyWrite".equals(stub.getInvocation().getMethod().getName()))) {
                Mockito.lenient().doAnswer(call -> {
                    var parent = topicMapper.selectCmsTopicByIdForUpdate(call.getArgument(0));
                    return parent == null ? null : parent.getId();
                }).when(keys).lockTopicForStateKeyWrite(org.mockito.ArgumentMatchers.anyLong());
            }
        }
        // Real MySQL mappers and spies always execute their real FOR UPDATE statement.
        if (topicMapper != null && Mockito.mockingDetails(topicMapper).isMock()
                && !Mockito.mockingDetails(topicMapper).isSpy()
                && Mockito.mockingDetails(topicMapper).getStubbings().stream().noneMatch(stub ->
                "selectCmsTopicByIdForUpdate".equals(stub.getInvocation().getMethod().getName()))) {
            Mockito.lenient().doAnswer(call -> topicMapper.selectCmsTopicById(call.getArgument(0)))
                    .when(topicMapper).selectCmsTopicByIdForUpdate(org.mockito.ArgumentMatchers.anyLong());
        }
        TopicBundleReviewService bundle = (TopicBundleReviewService) get(facade, "topicBundleReviewService");
        if (bundle != null && Mockito.mockingDetails(bundle).isMock() && !Mockito.mockingDetails(bundle).isSpy()
                && Mockito.mockingDetails(bundle).getStubbings().stream().noneMatch(stub ->
                "freezeForSubmission".equals(stub.getInvocation().getMethod().getName())
                        && stub.getInvocation().getArguments().length == 3)) {
            // Unit-only port adaptation: preserve the original fixture snapshot and original assertions.
            Mockito.when(bundle.freezeForSubmission(org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyList()))
                    .thenAnswer(call -> bundle.freezeForSubmission(call.getArgument(0), call.getArgument(1)));
        }
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime =
                (com.chengyinhub.business.service.ITopicRouteRuntimeService) get(facade, "topicRouteRuntimeService");
        if (runtime != null && Mockito.mockingDetails(runtime).isMock() && !Mockito.mockingDetails(runtime).isSpy()
                && Mockito.mockingDetails(runtime).getStubbings().stream().anyMatch(stub ->
                "freezePublication".equals(stub.getInvocation().getMethod().getName()) && stub.getInvocation().getArguments().length == 2)
                && Mockito.mockingDetails(runtime).getStubbings().stream().noneMatch(stub ->
                "freezePublication".equals(stub.getInvocation().getMethod().getName()) && stub.getInvocation().getArguments().length == 3)) {
            // Adapt only an explicitly supplied original unit port. Never fabricate a missing snapshot.
            Mockito.when(runtime.freezePublication(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(String.class),
                    org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> runtime.freezePublication(call.getArgument(0), call.getArgument(1)));
        }
        TopicContentWriteGuard guard = (TopicContentWriteGuard) ReflectionTestUtils.getField(facade, "contentWriteGuard");
        if (guard == null || plainMock(guard)) {
            guard = new TopicContentWriteGuard(topicMapper,
                    (com.chengyinhub.business.mapper.CmsTopicChapterMapper) ReflectionTestUtils.getField(facade, "cmsTopicChapterMapper"),
                    (com.chengyinhub.business.mapper.CmsMemberTemplateMapper) get(facade, "cmsMemberTemplateMapper"),
                    (com.chengyinhub.business.service.IProductRoleService) ReflectionTestUtils.getField(facade, "productRoleService"),
                    (com.chengyinhub.system.service.ISysCategoryService) get(facade, "sysCategoryService"));
            ReflectionTestUtils.setField(facade, "contentWriteGuard", guard);
        }
        ReflectionTestUtils.setField(guard, "cmsTopicNodeMapper", ReflectionTestUtils.getField(facade, "cmsTopicNodeMapper"));
        for (Object owner : owners(facade)) {
            for (Field field : owner.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Field original = ReflectionUtils.findField(CmsTopicServiceImpl.class, field.getName());
                if (original != null) ReflectionTestUtils.setField(owner, field.getName(), ReflectionTestUtils.getField(facade, field.getName()));
                else if (field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) {
                    Object collaborator = get(facade, field.getName());
                    if (collaborator != null) ReflectionTestUtils.setField(owner, field.getName(), collaborator);
                }
            }
        }
        wireCurrentPublication(facade);
        return value;
    }

    private static boolean plainMock(Object value) {
        return value != null && Mockito.mockingDetails(value).isMock() && !Mockito.mockingDetails(value).isSpy();
    }

    private static boolean unstubbed(Object value, String method) {
        return plainMock(value) && Mockito.mockingDetails(value).getStubbings().stream()
                .noneMatch(stub -> method.equals(stub.getInvocation().getMethod().getName()));
    }

    /** Adapt new current-read names to each unit fixture's original facts, never to fabricated rows.
     * Real mapper beans/spies and explicit current-read stubs are left alone. */
    private static void wireCurrentPublication(CmsTopicServiceImpl facade) {
        CmsTopicMapper topics = (CmsTopicMapper) get(facade, "cmsTopicMapper");
        CmsTopicChapterMapper chapters = (CmsTopicChapterMapper) get(facade, "cmsTopicChapterMapper");
        CmsTopicNodeMapper nodes = (CmsTopicNodeMapper) get(facade, "cmsTopicNodeMapper");
        CmsMemberTemplateMapper templates = (CmsMemberTemplateMapper) get(facade, "cmsMemberTemplateMapper");
        if (unstubbed(chapters, "selectByTopicForUpdate")) {
            Mockito.lenient().doAnswer(call -> {
                CmsTopicChapter query = new CmsTopicChapter(); query.setTopicId(call.getArgument(0));
                return chapters.selectCmsTopicChapterList(query);
            }).when(chapters).selectByTopicForUpdate(org.mockito.ArgumentMatchers.anyLong());
        }
        if (unstubbed(nodes, "selectByTopicForUpdate")) {
            Mockito.lenient().doAnswer(call -> {
                CmsTopicNode query = new CmsTopicNode(); query.setTopicId(call.getArgument(0));
                return nodes.selectCmsTopicNodeList(query);
            }).when(nodes).selectByTopicForUpdate(org.mockito.ArgumentMatchers.anyLong());
        }
        if (unstubbed(templates, "selectCmsMemberTemplateByIdsForUpdate")) {
            Mockito.lenient().doAnswer(call -> {
                List<com.chengyinhub.business.domain.CmsMemberTemplate> result = new ArrayList<>();
                for (Long id : (List<Long>) call.getArgument(0)) {
                    var row = templates.selectCmsMemberTemplateById(id);
                    if (row != null) result.add(row);
                }
                return result;
            }).when(templates).selectCmsMemberTemplateByIdsForUpdate(org.mockito.ArgumentMatchers.anyList());
        }
        Object service = get(facade, "themeStateKeyService");
        if (!(service instanceof ThemeStateKeyService) || plainMock(service)) return;
        ThemeStateKeyMapper keys = (ThemeStateKeyMapper) ReflectionTestUtils.getField(service, "themeStateKeyMapper");
        if (unstubbed(keys, "selectByTopicIdForUpdate")) {
            Mockito.lenient().doAnswer(call -> keys.selectByTopicId(call.getArgument(0)))
                    .when(keys).selectByTopicIdForUpdate(org.mockito.ArgumentMatchers.anyLong());
        }
        if (topics == null || chapters == null || nodes == null || templates == null || keys == null) return;
        Object references = get(facade, "topicStateKeyRefService");
        if (references == null || (plainMock(references)
                && Mockito.mockingDetails(references).getStubbings().isEmpty())) {
            references = new TopicStateKeyRefService(nodes, templates, chapters);
            set(facade, "topicStateKeyRefService", references);
        }
        if (references instanceof TopicStateKeyRefService && !plainMock(references)) {
            ReflectionTestUtils.setField(references, "cmsTopicMapper", topics);
            ReflectionTestUtils.setField(references, "cmsTopicChapterMapper", chapters);
            ReflectionTestUtils.setField(references, "cmsTopicNodeMapper", nodes);
            ReflectionTestUtils.setField(references, "cmsMemberTemplateMapper", templates);
        }
        Object runtime = get(facade, "topicRouteRuntimeService");
        if (runtime == null || (plainMock(runtime) && Mockito.mockingDetails(runtime).getStubbings().isEmpty())) {
            TopicRouteRuntimeServiceImpl actual = new TopicRouteRuntimeServiceImpl(null, topics, nodes, null,
                    (com.chengyinhub.business.mapper.CmsActivityMapper) get(facade, "cmsActivityMapper"),
                    (com.chengyinhub.business.mapper.CmsRegistrationMapper) get(facade, "cmsRegistrationMapper"));
            ReflectionTestUtils.setField(actual, "templateMapper", templates);
            ReflectionTestUtils.setField(actual, "themeStateKeyMapper", keys);
            ReflectionTestUtils.setField(actual, "cmsTopicChapterMapper", chapters);
            set(facade, "topicRouteRuntimeService", actual);
            runtime = actual;
        }
        if (runtime instanceof TopicRouteRuntimeServiceImpl && !plainMock(runtime)) {
            // Fixtures may replace a real key service or leaf mapper after their first wire.
            // Keep the existing real runtime (and its other ports), but use one current-content
            // group for publication, reference collection and compilation at the final wire.
            Object actual = AopTestUtils.getUltimateTargetObject(runtime);
            ReflectionTestUtils.setField(actual, "topicMapper", topics);
            ReflectionTestUtils.setField(actual, "nodeMapper", nodes);
            ReflectionTestUtils.setField(actual, "templateMapper", templates);
            ReflectionTestUtils.setField(actual, "themeStateKeyMapper", keys);
            ReflectionTestUtils.setField(actual, "cmsTopicChapterMapper", chapters);
        }
    }

    /** Genuine Spring transaction interception for the original Mockito mapper unit fixtures.
     * The connection is a controlled resource double, not a database or rollback claim. SQL is forbidden.
     * JDBC/H2 fixtures keep their existing real DataSourceTransactionManager/proxy instead. */
    @SuppressWarnings("unchecked")
    public static <T> T transactional(T value) {
        wire(value);
        if (org.springframework.aop.support.AopUtils.isAopProxy(value)) return value;
        CmsTopicServiceImpl target = facade(value);
        if (target == null) throw new IllegalArgumentException("Expected the real topic fixture target");
        org.springframework.transaction.PlatformTransactionManager manager =
                (org.springframework.transaction.PlatformTransactionManager) ReflectionTestUtils.getField(target, "transactionManager");
        if (manager == null || plainMock(manager)) {
            javax.sql.DataSource source = Mockito.mock(javax.sql.DataSource.class);
            try {
                Mockito.doAnswer(call -> {
                    java.sql.Connection connection = Mockito.mock(java.sql.Connection.class);
                    Mockito.when(connection.getAutoCommit()).thenReturn(true);
                    Mockito.doThrow(new AssertionError("Unit mapper fixture must not execute JDBC SQL"))
                            .when(connection).prepareStatement(org.mockito.ArgumentMatchers.anyString());
                    Mockito.doThrow(new AssertionError("Unit mapper fixture must not execute JDBC SQL"))
                            .when(connection).createStatement();
                    return connection;
                }).when(source).getConnection();
            } catch (java.sql.SQLException impossible) { throw new IllegalStateException(impossible); }
            manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
            ReflectionTestUtils.setField(target, "transactionManager", manager);
        }
        org.springframework.aop.framework.ProxyFactory factory = new org.springframework.aop.framework.ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy(CmsTopicServiceImpl.class.getClassLoader());
    }

    /** Preserve the old "mock every injected collaborator" fixtures after fields move to their real owner. */
    public static void wireRelocatedCollaborators(Object value, java.util.function.Function<Field, Object> originalFactory) {
        CmsTopicServiceImpl facade = facade(value);
        if (facade == null) throw new IllegalArgumentException("Expected the real topic fixture target");
        var initialized = new HashSet<String>();
        for (Object owner : owners(facade)) for (Field field : owner.getClass().getDeclaredFields()) {
            if (ReflectionUtils.findField(CmsTopicServiceImpl.class, field.getName()) == null
                    && field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)
                    && initialized.add(field.getName()) && get(facade, field.getName()) == null)
                set(facade, field.getName(), originalFactory.apply(field));
        }
        wire(value);
    }

    public static void set(Object value, String name, Object collaborator) {
        CmsTopicServiceImpl facade = facade(value);
        if (facade == null) {
            ReflectionTestUtils.setField(value, name, collaborator);
            return;
        }
        boolean found = ReflectionUtils.findField(CmsTopicServiceImpl.class, name) != null;
        if (found) ReflectionTestUtils.setField(facade, name, collaborator);
        for (Object owner : owners(facade)) if (ReflectionUtils.findField(owner.getClass(), name) != null) {
            ReflectionTestUtils.setField(owner, name, collaborator);
            found = true;
        }
        if (!found) throw new IllegalArgumentException("No original publication collaborator: " + name);
    }

    public static Object get(Object value, String name) {
        CmsTopicServiceImpl facade = facade(value);
        if (facade == null || ReflectionUtils.findField(CmsTopicServiceImpl.class, name) != null)
            return ReflectionTestUtils.getField(facade == null ? value : facade, name);
        boolean found = false;
        for (Object owner : owners(facade)) if (ReflectionUtils.findField(owner.getClass(), name) != null) {
            found = true;
            Object collaborator = ReflectionTestUtils.getField(owner, name);
            if (collaborator != null) return collaborator;
        }
        if (found) return null;
        throw new IllegalArgumentException("No original publication collaborator: " + name);
    }

    public static Object owner(Object value, String method) {
        wire(value);
        CmsTopicServiceImpl facade = facade(value);
        if (facade == null) return value;
        for (Object owner : owners(facade)) for (java.lang.reflect.Method candidate : owner.getClass().getDeclaredMethods())
            if (candidate.getName().equals(method)) return owner;
        return value;
    }
}
