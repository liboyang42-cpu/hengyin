#!/usr/bin/env python3
"""Fixed combined-HTTP budget owner SOURCE. No class/command/profile/URL selection or provisioning.

Only the reviewed hosted script calls this adapter. The original workflow owns MySQL/network;
the original fresh-schema and HTTP XML/cleanup verifier remain authoritative for business results.
"""
import argparse
import contextlib
import datetime
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time
import urllib.parse
import uuid
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CLASS = 'CrossDomainCombinedLoadMySqlIT'
PROVIDER_PROBE = 'com.chengyinhub.business.it.CombinedSurefireRuntimeProbeTest'
CONFIGURATION_UNIT = 'com.chengyinhub.business.it.CombinedContextConfigurationTest'
CONFIGURATION_SOURCE = 'com/chengyinhub/business/it/CombinedContextConfigurationTest.java'
PHOTO_UNIT = 'com.chengyinhub.business.service.ai.PhotoCheckIntentServiceTest'
PHOTO_SOURCE = 'com/chengyinhub/business/service/ai/PhotoCheckIntentServiceTest.java'
PHOTO_METHODS = (
    'mandatoryAndNeverGuardsRejectBeforeAnyWork',
    'consentRefusalHasNoIntentQuotaOrProviderEffects',
    'approvedButNotOwnedMediaRefusalPrecedesPlanning',
    'prepareStoresCanonicalFullContextAndOriginalPlanBeforeConsumption',
    'sameKeySameHashReturnsOriginalWithoutNewSecurityOrQuotaActions',
    'sameKeyDifferentFingerprintRejectsWithoutEffects',
    'readRequiresOriginalOwnerAndFingerprintAndDoesNoExternalWork',
    'damagedSessionBindingFailsClosed',
    'inFlightAndUnknownNeverResendOrSynthesizeDegraded',
    'losingCasOnlyReadsOriginalInFlight',
    'firstActualReviewPersistsSnapshotBeforeOriginalResultAndReplaysIt',
    'firstActualDegradedResultAndProviderUnknownAreStoredSeparately',
    'thrownSecurityRefusalIsNotConvertedToFallbackResult',
    'snapshotPersistenceFailureStopsBeforeModelWork',
    'markAppliedRequiresOriginalReadyResultAndMatchingReceiptAndSupportsSameReceiptReplay',
    'applyCasFailurePropagatesToBusinessTransaction',
    'losingClaimUsesThreeIndependentBoundariesAndKeepsPublicReadCallerOwned',
    'firstOwnedReadFailureRollsBackBeforeClaimOrProviderAndPreservesOriginalError',
    'finalOwnedReadFailureCannotEraseCommittedResultOrPermitAnotherProviderCall',
    'photoExecuteProxyReleasesOwnReadsBeforeProviderAndAfterResultReadback',
    'photoExecuteProxyLosingClaimReadsCurrentOriginalInShortTransaction',
    'photoExecuteProxyUnknownResultKeepsIdentityAndNeverReexecutes',
    'photoExecuteProxyThrownFailureClosesOwnResourcesAndRetainsUnknown',
)
CONFIGURATION_METHODS = ('boundedPoolOverridesBothIncompatibleInheritedFloorsWithoutChangingOtherOwners',
                         'originalDruidPropertiesAssemblesAValidUninitializedThreeConnectionPool',
                         'schedulerChildHeapIsFixedOnlyForOriginalCombinedScope',
                         'combinationIdentityFileRetainsFrozenBytesAcrossIntegerNodeWidths',
                         'combinationIdentityFileRejectsExistingAndPendingFilesBeforeSql',
                         'protectedFactDigestPreservesJdbcScalarAndTimeFacts',
                         'protectedFactDigestRetainsQueryAndSerializationFailures',
                         'photoNeverReadRetainsConnectionAcrossIndependentTransaction',
                         'photoPrepareFailureProjectionKeepsOnlyReviewedCodesAndCause',
                         'precompiledContentSecurityFixtureKeepsOriginalChecksAndRejectsUnprovenOutputs',
                         'providerClasspathCapturePreservesExactBytesAndRejectsUnownedEntries')
# Candidate for root's fixed-JVM policy decision: keep all original allocations and lifecycle,
# but avoid C2 compilation/profiling work in BOTH concurrently resident runtime JVMs.
# The package/compiler owner still uses its original 512 MiB configuration.
RUNTIME_MAVEN_OPTIONS = '-Xms16m -Xmx128m -XX:MaxDirectMemorySize=16m -XX:ReservedCodeCacheSize=32m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.io.tmpdir=/tmp -Duser.home=/tmp/home'
SUREFIRE_MEMORY_ARGUMENTS = '-Xms16m -Xmx384m -XX:MaxDirectMemorySize=64m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC -XX:TieredStopAtLevel=1'
SCHEDULER_MEMORY_ARGUMENTS = '-Xmx64m'
SCHEDULER_PROBE = 'com.chengyinhub.quartz.util.SchedulerFenceProcessProbe'
SUREFIRE_FORK_ARGUMENTS = SUREFIRE_MEMORY_ARGUMENTS+' -Djava.security.manager=allow --add-opens=java.base/java.lang=ALL-UNNAMED -Djava.io.tmpdir=/tmp -Djava.awt.headless=true'
TEST_PROFILE = 'combined-http-fixture-closure'
CONTENT_SECURITY_SOURCE = 'scripts/e2e/E2eWxContentSecurityStub.java.stub'
CONTENT_SECURITY_SOURCE_SHA256 = '4b4bb11e6bd4a211fc07164f90f9310d34a0bf6cad3e2eb415722d4d2c9a66af'
CONTENT_SECURITY_CLASS = 'com/chengyinhub/e2e/E2eWxContentSecurityStub.class'
TEST_SOURCES = (
    'com/chengyinhub/business/it/CrossDomainCombinedLoadMySqlIT.java',
    'com/chengyinhub/business/it/CombinedCoreHttpWorkload.java',
    'com/chengyinhub/business/it/CombinedControlledProviders.java',
    'com/chengyinhub/business/it/CrossDomainStorageFixture.java',
    'com/chengyinhub/business/it/CrossDomainTransportStage.java',
    'com/chengyinhub/business/it/GameplayProcessRecoveryChild.java',
    'com/chengyinhub/business/it/GenericGameplayHttpE2eIT.java',
    'com/chengyinhub/business/it/MybatisItDatabase.java',
    'com/chengyinhub/business/it/P0AcceptanceSchema.java',
    'com/chengyinhub/business/it/ItSchema.java',
    'com/chengyinhub/business/it/R5WeRunFixture.java',
    'com/chengyinhub/business/service/MysqlIntegrationTestConfig.java',
    'com/chengyinhub/business/service/ai/AiNotSentGatewayFixture.java',
    'com/chengyinhub/business/service/impl/CrossDomainSubscriptionProvider.java',
    'com/chengyinhub/business/service/impl/WechatVerifiedFactTestSupport.java',
    'com/chengyinhub/business/service/impl/WechatPayTestWiring.java',
    'com/chengyinhub/quartz/util/CrossDomainSchedulerStage.java',
    'com/chengyinhub/quartz/util/SchedulerFenceProcessProbe.java',
    'com/chengyinhub/business/it/CombinedSurefireRuntimeProbeTest.java',
    PHOTO_SOURCE,
    CONFIGURATION_SOURCE,
)
REQUIRED_TEST_CLASSES = (
    'com.chengyinhub.business.it.CrossDomainCombinedLoadMySqlIT',
    'com.chengyinhub.business.it.CrossDomainCombinedLoadMySqlIT$Initializer',
    'com.chengyinhub.business.it.CombinedCoreHttpWorkload',
    'com.chengyinhub.business.it.CombinedCoreHttpWorkload$HttpStatusFailure',
    'com.chengyinhub.business.it.CombinedControlledProviders',
    'com.chengyinhub.business.it.CombinedControlledProviders$Mode',
    'com.chengyinhub.business.it.CombinedControlledProviders$1',
    'com.chengyinhub.business.it.CrossDomainStorageFixture',
    'com.chengyinhub.business.it.CrossDomainStorageFixture$ParentSqlObservation',
    'com.chengyinhub.business.it.CrossDomainTransportStage',
    'com.chengyinhub.business.it.CrossDomainTransportStage$RequestProtocolException',
    'com.chengyinhub.business.it.CrossDomainTransportStage$RequestInput',
    'com.chengyinhub.business.it.CrossDomainTransportStage$MapClient',
    'com.chengyinhub.business.it.GameplayProcessRecoveryChild',
    'com.chengyinhub.business.it.GameplayProcessRecoveryChild$RecoveryConfiguration',
    'com.chengyinhub.business.it.GameplayProcessRecoveryChild$LostReplyObserver',
    'com.chengyinhub.business.it.GenericGameplayHttpE2eIT',
    'com.chengyinhub.business.it.GenericGameplayHttpE2eIT$LocalInitializer',
    'com.chengyinhub.business.it.GenericGameplayHttpE2eIT$ReviewedMediaFixture',
    'com.chengyinhub.business.it.GenericGameplayHttpE2eIT$Lifecycle',
    'com.chengyinhub.business.it.GenericGameplayHttpE2eIT$HttpSourceStage',
    'com.chengyinhub.business.it.GenericGameplayHttpE2eIT$HttpSourceEndpoint',
    'com.chengyinhub.business.it.MybatisItDatabase',
    'com.chengyinhub.business.it.P0AcceptanceSchema',
    'com.chengyinhub.business.it.ItSchema',
    'com.chengyinhub.business.it.R5WeRunFixture',
    'com.chengyinhub.business.service.MysqlIntegrationTestConfig',
    'com.chengyinhub.business.service.ai.AiNotSentGatewayFixture',
    'com.chengyinhub.business.service.impl.CrossDomainSubscriptionProvider',
    'com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport',
    'com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport$MemoryMapper',
    'com.chengyinhub.business.service.impl.WechatPayTestWiring',
    'com.chengyinhub.quartz.util.CrossDomainSchedulerStage',
    'com.chengyinhub.quartz.util.CrossDomainSchedulerStage$Child',
    'com.chengyinhub.quartz.util.SchedulerFenceProcessProbe',
    'com.chengyinhub.quartz.util.SchedulerFenceProcessProbe$ProbeTask',
    'com.chengyinhub.quartz.util.SchedulerFenceProcessProbe$FaultSource',
)
JDK = 'maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237'
MYSQL = 'mysql:8.0.45@sha256:4af1f8815716546f5b12410f7621f37f93db8dd11a184706ef59111930b8c2ff'
REDIS = 'redis:7.4-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499'
DOCKER = ['docker', '--context', 'default']
MODULES = ('chengyinhub-admin','chengyinhub-framework','chengyinhub-system','chengyinhub-quartz','chengyinhub-generator','chengyinhub-common')
OUT = ROOT / 'chengyinhub-admin/target/http-e2e'
PUBLIC = OUT / 'public'
MEMORY = 1073741824
LIMIT_SECONDS = 1200  # Owner ceiling, not a replacement for the original Java/workload deadlines.
BOOTSTRAP_REPORT = PUBLIC/'combined-http-bootstrap.json'
MYSQL_PRIVATE = OUT/'combined-mysql-private'
EMPTY_SETTINGS='<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>\n'
CACHE_REPORT=PUBLIC/'combined-http-cache.json'
CORE_VERSION='3.9.11'
REVIEWED_PLUGINS={
    'org.apache.maven.plugins:maven-resources-plugin':{'3.3.1'},
    'org.apache.maven.plugins:maven-jar-plugin':{'3.4.1'},
    'org.apache.maven.plugins:maven-install-plugin':{'3.1.2'},
    'org.apache.maven.plugins:maven-compiler-plugin':{'3.15.0'},
    'org.apache.maven.plugins:maven-surefire-plugin':{'3.5.6'},
    'org.apache.maven.plugins:maven-dependency-plugin':{'3.10.0'},
    'org.apache.maven.plugins:maven-war-plugin':{'3.1.0'},
    'org.springframework.boot:spring-boot-maven-plugin':{'4.1.1'},
    'io.github.git-commit-id:git-commit-id-maven-plugin':{'5.0.0'}}
SAFE_RESOURCE_CODES = frozenset({'FIXED_COMMAND_FAILED','FIXED_HOSTED_CLASS_ONLY','FULL_SOURCE_REQUIRED',
    'RUN_ATTEMPT_REQUIRED','FIXED_BACKING_IDENTITY_REQUIRED','BACKING_MYSQL_FOREIGN','BACKING_NAME_CID_CHANGED',
    'BACKING_MYSQL_BUDGET_MISMATCH','BACKING_LOOPBACK_SCOPE_CHANGED','BACKING_MYSQL_EXACT_EPHEMERAL_STORAGE_REQUIRED',
    'BACKING_ACTUAL_IMAGE_ID_CHANGED','OWNED_INTERNAL_NETWORK_REQUIRED','BACKING_MYSQL_NOT_RUNNING',
    'BACKING_NETWORK_CONFIG_CHANGED','NETWORK_IDENTITY_REQUIRED','FOREIGN_NETWORK','DOCKER_INSPECTION_UNKNOWN',
    'BOOTSTRAP_SOURCE_MISMATCH','BOOTSTRAP_NOT_VERIFIED','MYSQL_PROCESS_EXITED_BEFORE_READY',
    'MYSQL_TCP_READY_BUDGET_EXHAUSTED','MYSQL_TCP_IDENTITY_MISMATCH','ORIGINAL_SYNTHETIC_MYSQL_CREDENTIAL_REQUIRED',
    'LOCAL_DOCKER_ONLY','FOREIGN_CONTAINER_OR_IMAGE','FOREIGN_LABEL','ACTUAL_CID_MISMATCH',
    'ABSENCE_NOT_INDEPENDENTLY_PROVEN','NETWORK_NOT_EXACT_OR_NOT_EMPTY','MYSQL_CREATE_ID_UNKNOWN',
    'SHELL_BOOTSTRAP_FAILED','PRIVATE_RESOURCE_PATH_UNSAFE','NEVER_ADOPT_PREVIOUS_BOOTSTRAP',
    'FIXED_CACHE_TOOLCHAIN_REQUIRED','OFFLINE_CACHE_PREFLIGHT_FAILED','OFFLINE_CACHE_PREFLIGHT_REQUIRED',
    'OFFLINE_CACHE_PROOF_CHANGED','BOUNDED_OFFLINE_CACHE_REQUIRED','EMPTY_OFFLINE_CACHE',
    'SYMLINK_OFFLINE_CACHE_FORBIDDEN','NONREGULAR_OFFLINE_CACHE_FORBIDDEN',
    'WRONG_CHECKOUT','DIRTY_SOURCE','ORIGINAL_TARGET_REQUIRED','CREATED_CID_NOT_PROVEN',
    'PRIVATE_MOUNT_BOUNDARY_CHANGED','PRIVATE_ENV_BOUNDARY_CHANGED','HARD_BUDGET_MISMATCH',
    'UNSAFE_CONTAINER_CAPABILITY','WRONG_OWNED_NETWORK_NAMESPACE','CGROUP_V2_REQUIRED',
    'PID_OUTSIDE_EXACT_CGROUP','FOREIGN_CGROUP_PATH','ACTUAL_OOM_COUNTER_REQUIRED',
    'ACTUAL_CGROUP_MEMORY_SWAP_PIDS_MISMATCH','ACTUAL_CGROUP_CPU_MISMATCH',
    'ACTUAL_CGROUP_CHILD_OOM_KILL','ACTUAL_MAVEN_EXIT_REQUIRED','COMPILED_POSTIMAGE_DRIFT',
    'PRIVATE_LOG_NOT_COMPLETE','CACHE_EXECUTION_DEADLINE','CACHE_PROCESS_EXIT_BEFORE_RECEIPT',
    'CACHE_STATE_NOT_PROVEN','CACHE_COMPLETED_SHELL_EXIT_MISMATCH','APP_OOM_OR_UNKNOWN_EXIT',
    'CACHE_OFFLINE_PACKAGE_PROBE_EXIT_REQUIRED','FIXED_TEST_COMPILATION_PROFILE_REQUIRED',
    'FIXED_TEST_COMPILATION_SOURCE_REQUIRED','FIXED_TEST_COMPILATION_OUTPUT_REQUIRED',
    'STALE_TEST_OUTPUT_BOUNDARY_CHANGED','UNREVIEWED_TEST_COMPILATION_SOURCE',
    'UNREVIEWED_TEST_COMPILATION_OUTPUT','PROVIDER_RUNTIME_PROOF_REQUIRED','PROVIDER_RUNTIME_XML_REQUIRED',
    'PROVIDER_RUNTIME_CLASSPATH_CHANGED','ORIGINAL_SUREFIRE_FORK_REQUIRED','SAME_TARGET_BOOTER_REQUIRED',
    'BOUNDED_BOOTER_MANIFEST_REQUIRED','ORIGINAL_SUREFIRE_MAIN_REQUIRED','REAL_TEST_CLASSPATH_BINDING_REQUIRED',
    'REMOTE_BOOTER_CLASSPATH_FORBIDDEN','FOREIGN_BOOTER_CLASSPATH','REAL_BOOTER_CLASSPATH_ENTRY_REQUIRED',
    'ORIGINAL_MOCKITO_STARTUP_AGENT_REQUIRED','CACHED_ORIGINAL_AGENT_REQUIRED','ACTUAL_APP_HOST_PID_REQUIRED',
    'MYSQL_PROCESS_REPLACED','BOUNDED_PROCESS_IDENTITY_REQUIRED','BOUNDED_JAVA_IDENTITY_REQUIRED',
    'FORK_ESCAPED_FIXED_BUDGET','ACTUAL_NAMESPACE_PID_REQUIRED','FIXED_CLASS_NOT_ON_ACTUAL_MAVEN_PROCESS',
    'NOT_SAME_OWNED_LOOPBACK_NAMESPACE','APP_EXIT_BEFORE_MAVEN_STATUS','OWNED_EXECUTION_DEADLINE',
    'REAL_MAVEN_AND_JUNIT_PID_PROOF_REQUIRED','MYSQL_ADDRESS_OR_UUID_DRIFT','ORIGINAL_EXECUTION_NAMESPACE_NOT_LIVE',
    'MYSQL_PROCESS_REPLACED_BEFORE_PORT_PROOF','PORT_PROBE_NAMESPACE_CHANGED','NAMESPACE_PROBE_POSTIMAGE_CHANGED',
    'ACTUAL_NAMESPACE_BIND_PROOF_REQUIRED','PRIVATE_APP_IDENTITY_OR_ROOTFS_CHANGED','FIXED_OFFLINE_MAVEN_COMMAND_CHANGED',
    'UNCLASSIFIED_RESOURCE_FAILURE','CONFIGURATION_UNIT_PROOF_REQUIRED','CONFIGURATION_UNIT_XML_REQUIRED',
    'FIXED_JVM_MEMORY_ARGUMENTS_REQUIRED','PROVIDER_EXACT_CLASSPATH_REQUIRED',
    'CONTROLLED_CONTENT_SECURITY_PROOF_REQUIRED','CONTROLLED_CONTENT_SECURITY_COMPILE_FAILED'})
CACHE_SUBSTAGES=frozenset({'SOURCE_INPUTS','LOCAL_ENGINE','PINNED_IMAGE','CREATE_INPUTS','CREATE',
    'OWNER_TOOLCHAIN','MOUNT_ENV','START','CGROUP','WAIT_EXIT','MAVEN_EXIT','CORE_IDENTITY',
    'MANIFEST','PROVIDER_RUNTIME','CONFIGURATION_UNIT','CONTENT_SECURITY','PRIVATE_LOG','CLEANUP','TERMINAL'})
CACHE_FAILURE_FUNCTIONS=frozenset({'warm_cache','cache_inputs','cache_manifest','cache_process_state',
    'cache_terminal','cache_offline_probe_exit','fixed_test_compilation_inputs','reset_fixed_test_outputs',
    'fixed_test_compilation_outputs','provider_runtime_proof','configuration_unit_proof','exact_test_xml','read_owned_test_xml',
    'provider_classpath_proof','controlled_content_proof','compile_controlled_content',
    'command','inspect','validate_owner','validate_container','read_cgroup','validate_cgroup_limits'})
CACHE_GOALS={
    'maven-resources-plugin':{'resources','testResources'},'maven-compiler-plugin':{'compile','testCompile'},
    'maven-surefire-plugin':{'test'},'maven-jar-plugin':{'jar'},'maven-install-plugin':{'install'},
    'maven-war-plugin':{'war'},'maven-dependency-plugin':{'properties','get'},
    'spring-boot-maven-plugin':{'repackage'},'git-commit-id-maven-plugin':{'revision'}}


def fixed_test_compilation_inputs():
    # The sole named admin profile is source, not a CLI/user-selected include expression.
    ns={'m':'http://maven.apache.org/POM/4.0.0'}
    pom=ROOT/'chengyinhub-admin/pom.xml'
    require(pom.is_file() and not pom.is_symlink(),'FIXED_TEST_COMPILATION_PROFILE_REQUIRED')
    try:root=ET.parse(pom).getroot()
    except ET.ParseError:raise ValueError('FIXED_TEST_COMPILATION_PROFILE_REQUIRED') from None
    profiles=[p for p in root.findall('m:profiles/m:profile',ns) if p.findtext('m:id',namespaces=ns)==TEST_PROFILE]
    require(len(profiles)==1,'FIXED_TEST_COMPILATION_PROFILE_REQUIRED')
    plugins=[p for p in profiles[0].findall('m:build/m:plugins/m:plugin',ns)
             if p.findtext('m:artifactId',namespaces=ns)=='maven-compiler-plugin']
    require(len(plugins)==1,'FIXED_TEST_COMPILATION_PROFILE_REQUIRED')
    executions=[e for e in plugins[0].findall('m:executions/m:execution',ns)
                if e.findtext('m:id',namespaces=ns)=='default-testCompile']
    require(len(executions)==1,'FIXED_TEST_COMPILATION_PROFILE_REQUIRED')
    config=executions[0].find('m:configuration',ns)
    require(config is not None and config.findtext('m:implicit',namespaces=ns)=='none','FIXED_TEST_COMPILATION_PROFILE_REQUIRED')
    includes=[p.text for p in config.findall('m:testIncludes/m:testInclude',ns)]
    require(tuple(includes)==TEST_SOURCES,'FIXED_TEST_COMPILATION_SOURCE_REQUIRED')
    sources={};source_root=ROOT/'chengyinhub-admin/src/test/java'
    require(source_root.resolve()==source_root and not source_root.is_symlink(),'FIXED_TEST_COMPILATION_SOURCE_REQUIRED')
    for relative in includes:
        require(isinstance(relative,str) and re.fullmatch(r'com/chengyinhub/(?:[A-Za-z0-9_]+/)*[A-Za-z0-9_]+\.java',relative),
                'FIXED_TEST_COMPILATION_SOURCE_REQUIRED')
        source=source_root/relative
        require(source.is_file() and not source.is_symlink()
                and source.resolve().is_relative_to(source_root),'FIXED_TEST_COMPILATION_SOURCE_REQUIRED')
        sources[relative]=digest(source)
    return {'profile':TEST_PROFILE,'sources':sources}


def reset_fixed_test_outputs():
    # Only the original admin test output and testCompile state; never production classes/JARs,
    # the dependency cache, source resources, public evidence or any other owner's module.
    target=ROOT/'chengyinhub-admin/target'
    paths=[target/suffix for suffix in ('test-classes','generated-test-sources','maven-status/maven-compiler-plugin/testCompile','provider-runtime','controlled-content-security')]
    for path in paths:
        for current in (path,*path.parents):
            if current==ROOT:break
            require(not current.is_symlink(),'STALE_TEST_OUTPUT_BOUNDARY_CHANGED')
        if path.exists():
            require(path.is_dir() and path.resolve().is_relative_to(target.resolve()),'STALE_TEST_OUTPUT_BOUNDARY_CHANGED')
    for path in paths:
        if path.exists():shutil.rmtree(path)


def fixed_test_compilation_outputs(inputs):
    output=ROOT/'chengyinhub-admin/target/test-classes';classes={}
    require(output.is_dir() and output.resolve()==output and not output.is_symlink(),'FIXED_TEST_COMPILATION_OUTPUT_REQUIRED')
    status=ROOT/'chengyinhub-admin/target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst'
    require(status.is_file() and not status.is_symlink() and status.resolve()==status,'FIXED_TEST_COMPILATION_OUTPUT_REQUIRED')
    observed=status.read_text().splitlines()
    source_root=ROOT/'chengyinhub-admin/src/test/java'
    require(len(observed)==len(TEST_SOURCES) and set(observed)=={str(source_root/path) for path in TEST_SOURCES},
            'UNREVIEWED_TEST_COMPILATION_SOURCE')
    generated=ROOT/'chengyinhub-admin/target/generated-test-sources'
    require(not generated.is_symlink() and generated.resolve()==generated
            and not any(p.is_symlink() or p.suffix=='.java' for p in generated.rglob('*')),'UNREVIEWED_TEST_COMPILATION_SOURCE')
    # Keep every output, including unnamed/local classes; reject another compilation unit, never prune it.
    allowed={relative[:-5] for relative in TEST_SOURCES}
    all_classes={}
    for path in sorted(output.rglob('*.class')):
        relative=path.relative_to(output).as_posix()[:-6].split('$',1)[0]
        require(relative in allowed,'UNREVIEWED_TEST_COMPILATION_OUTPUT')
        require(not path.is_symlink(),'FIXED_TEST_COMPILATION_OUTPUT_REQUIRED')
        all_classes[path.relative_to(output).as_posix()]=digest(path)
    required={name.replace('.','/')+'.class' for name in REQUIRED_TEST_CLASSES}
    for relative in sorted(required):
        path=output/relative
        require(relative in all_classes and path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(output.resolve()),
                'FIXED_TEST_COMPILATION_OUTPUT_REQUIRED')
        classes[relative]=all_classes[relative]
    probe=PROVIDER_PROBE.replace('.','/')+'.class'
    require(probe in all_classes,'FIXED_TEST_COMPILATION_OUTPUT_REQUIRED')
    configuration=CONFIGURATION_UNIT.replace('.','/')+'.class'
    require(configuration in all_classes,'FIXED_TEST_COMPILATION_OUTPUT_REQUIRED')
    photo=PHOTO_UNIT.replace('.','/')+'.class'
    require(photo in all_classes,'FIXED_TEST_COMPILATION_OUTPUT_REQUIRED')
    return {**inputs,'compilerInputs':sorted(TEST_SOURCES),'classes':classes,
            'providerProbeClassSha256':all_classes[probe],
            'configurationTestClassSha256':all_classes[configuration],
            'photoServiceTestClassSha256':all_classes[photo],
            'classTree':{'classes':len(all_classes),'sha256':hashlib.sha256(json.dumps(list(all_classes.items()),separators=(',',':')).encode()).hexdigest()}}


def read_owned_test_xml(path,private,code):
    # Raw Surefire reports are owner-created 0644; copied acceptance proofs remain strictly private.
    require(path.is_file() and not path.is_symlink() and path.resolve()==path
            and path.stat().st_size<2*1024*1024,code)
    require(not path.stat().st_mode & (0o077 if private else 0o022)
            and (private or path.stat().st_uid==os.getuid()),code)
    with path.open('rb') as stream:raw=stream.read(2*1024*1024)
    require(len(raw)<2*1024*1024 and b'<!DOCTYPE' not in raw and b'<!ENTITY' not in raw,code)
    try:suite=ET.fromstring(raw)
    except ET.ParseError:raise ValueError(code) from None
    return raw,suite


def exact_test_xml(path,test_class,methods,code):
    # Fixed internal callers only. Never export XML properties, output or arbitrary method names.
    raw,suite=read_owned_test_xml(path,True,code)
    cases=suite.findall('testcase');names=[case.get('name') for case in cases]
    require(suite.tag=='testsuite' and suite.get('name')==test_class and suite.get('tests')==str(len(methods))
            and all(suite.get(key)=='0' for key in ('failures','errors','skipped'))
            and len(cases)==len(methods) and len(set(names))==len(methods) and set(names)==set(methods)
            and len(list(suite.iter('testcase')))==len(cases)
            and all(case.get('classname')==test_class for case in cases)
            and not any(element.tag in ('failure','error','skipped','rerunFailure','rerunError','flakyFailure','flakyError')
                        for element in suite.iter()),code)
    return {'tests':len(methods),'failures':0,'errors':0,'skipped':0,'xmlSha256':hashlib.sha256(raw).hexdigest()}


PREPARATION_FAILURE_TYPES=frozenset({'java.lang.AssertionError','java.lang.NullPointerException',
    'java.lang.IllegalStateException','java.lang.IllegalArgumentException','java.lang.SecurityException',
    'java.lang.UnsupportedOperationException','java.lang.ClassCastException','java.lang.RuntimeException',
    'java.sql.SQLException','java.sql.SQLSyntaxErrorException','java.sql.SQLTransientConnectionException',
    'org.opentest4j.AssertionFailedError','org.opentest4j.MultipleFailuresError',
    'org.springframework.transaction.IllegalTransactionStateException',
    'org.mockito.exceptions.verification.WantedButNotInvoked','org.mockito.exceptions.verification.NeverWantedButInvoked',
    'org.mockito.exceptions.verification.TooManyActualInvocations','org.mockito.exceptions.verification.NoInteractionsWanted',
    'org.mockito.exceptions.misusing.UnnecessaryStubbingException','org.mockito.exceptions.misusing.PotentialStubbingProblem',
    'org.mockito.exceptions.misusing.InvalidUseOfMatchersException','org.mockito.exceptions.base.MockitoException'})


def preparation_report_specs():
    # Exactly the ONLINE selection; no caller-provided suite, source path, class or method filter.
    return ((PROVIDER_PROBE,PROVIDER_PROBE.replace('.','/')+'.java',('bootsActualProjectPlatformRuntime',),'providerProbeClassSha256'),
            (CONFIGURATION_UNIT,CONFIGURATION_SOURCE,CONFIGURATION_METHODS,'configurationTestClassSha256'),
            (PHOTO_UNIT,PHOTO_SOURCE,PHOTO_METHODS,'photoServiceTestClassSha256'))


def preparation_suite_evidence(test_class,methods,source_lines):
    row={'testClass':test_class,'state':'UNKNOWN'}
    path=ROOT/'chengyinhub-admin/target/surefire-reports'/('TEST-'+test_class+'.xml')
    try:raw,suite=read_owned_test_xml(path,False,'PREPARATION_REPORT_UNPROVEN')
    except Exception:return {**row,'unavailableReason':'MISSING_UNSAFE_OR_MALFORMED_REPORT'}
    try:
        require(suite.tag=='testsuite' and suite.get('name')==test_class
                and suite.get('tests')==str(len(methods)),'PREPARATION_REPORT_UNPROVEN')
        cases=suite.findall('testcase');names=[case.get('name') for case in cases]
        require(len(cases)==len(methods) and len(set(names))==len(methods) and set(names)==set(methods)
                and len(list(suite.iter('testcase')))==len(cases) and len(list(suite.iter('testsuite')))==1
                and all(case.get('classname')==test_class for case in cases)
                and all(child.tag in ('properties','testcase','system-out','system-err') for child in suite),
                'PREPARATION_REPORT_UNPROVEN')
        counts={'tests':len(methods),'failures':0,'errors':0,'skipped':0,'retries':0};outcomes=[]
        terminal={'failure':'FAILURE','error':'ERROR','skipped':'SKIPPED'}
        retry={'rerunFailure','rerunError','flakyFailure','flakyError'}
        def detail(node,method):
            value={'type':node.get('type') if node.get('type') in PREPARATION_FAILURE_TYPES else 'UNKNOWN'}
            pattern=r'^\s*at '+re.escape(test_class)+r'\.'+re.escape(method)+r'\('+re.escape(test_class.rsplit('.',1)[1])+r'\.java:([0-9]{1,7})\)\s*$'
            for line in (node.text or '').splitlines():
                found=re.fullmatch(pattern,line)
                if found and 1<=int(found[1])<=source_lines:
                    value['origin']={'class':test_class,'method':method,'line':int(found[1])};break
            return value
        for case in cases:
            method=case.get('name');ends=[node for node in case if node.tag in terminal]
            retries=[node for node in case if node.tag in retry]
            require(len(ends)<=1 and len(retries)<=16
                    and all(node.tag in (*terminal,*retry,'system-out','system-err') for node in case),
                    'PREPARATION_REPORT_UNPROVEN')
            if ends:
                node=ends[0];key={'failure':'failures','error':'errors','skipped':'skipped'}[node.tag];counts[key]+=1
                outcome={'method':method,'kind':terminal[node.tag]}
                if node.tag!='skipped':outcome.update(detail(node,method))
                outcomes.append(outcome)
            if retries:
                counts['retries']+=len(retries);items=[detail(node,method) for node in retries]
                outcome={'method':method,'kind':'RETRY','attempts':len(retries),'types':sorted({item['type'] for item in items})}
                origin=next((item['origin'] for item in items if 'origin' in item),None)
                if origin is not None:outcome['origin']=origin
                outcomes.append(outcome)
        require(all(suite.get(key)==str(counts[key]) for key in ('failures','errors','skipped')),'PREPARATION_REPORT_UNPROVEN')
        return {**row,'state':'OBSERVED','xmlSha256':hashlib.sha256(raw).hexdigest(),'counts':counts,'nonPassing':outcomes}
    except Exception:return {**row,'unavailableReason':'UNREVIEWED_OR_INCONSISTENT_REPORT'}


def preparation_test_evidence(report,inputs,expected,state,obj,phase):
    unknown={'purpose':'PREPARATION_JUNIT_DIAGNOSTIC_ONLY','state':'UNKNOWN','businessAccepted':False}
    try:
        require(report.get('mavenExitObserved') is True and type(report.get('mavenExit')) is int
                and 0<report['mavenExit']<256 and phase in ('ONLINE_PROVIDER_RUNTIME','ONLINE_CONFIGURATION_UNITS') and report.get('phase')==phase,
                'PREPARATION_EVIDENCE_UNPROVEN')
        identity_gate(os.environ);bound=identity(os.environ)
        require({key:report.get(key) for key in bound}==bound,'PREPARATION_EVIDENCE_UNPROVEN')
        slot=state['slots']['app']
        owner=validate_container(obj,slot['name'],JDK,labels(os.environ,state['nonce']),MEMORY,'bridge',slot['cid'])
        require(owner==report['budget'] and obj['Image']==report['imageId']
                and obj['Config']['Cmd']==fixed_cache_command() and obj['Config']['Entrypoint']==['/bin/bash']
                and obj['Config']['User']==str(os.getuid())+':'+str(os.getgid())
                and obj['State']['Running'] is True and obj['State']['Pid']>0,'PREPARATION_EVIDENCE_UNPROVEN')
        environment=dict(value.split('=',1) for value in obj['Config']['Env'])
        require(all(environment.get(key)==value for key,value in
                {'SOURCE_SHA':bound['sourceSha'],'GITHUB_SHA':bound['sourceSha'],'GITHUB_RUN_ID':bound['runId'],'GITHUB_RUN_ATTEMPT':bound['attempt']}.items())
                and re.fullmatch('[0-9a-f]{64}',report.get('mavenCoreSha256','')),'PREPARATION_EVIDENCE_UNPROVEN')
        require(cache_inputs()==report['pomSha256'] and fixed_test_compilation_inputs()==inputs
                and expected is not None and fixed_test_compilation_outputs(inputs)==expected,'PREPARATION_EVIDENCE_UNPROVEN')
        bindings=[]
        for test_class,source_path,methods,key in preparation_report_specs():
            if (test_class==PROVIDER_PROBE)!=(phase=='ONLINE_PROVIDER_RUNTIME'):continue
            source=ROOT/'chengyinhub-admin/src/test/java'/source_path
            compiled=ROOT/'chengyinhub-admin/target/test-classes'/(test_class.replace('.','/')+'.class')
            require(source.is_file() and not source.is_symlink() and source.resolve()==source
                    and compiled.is_file() and not compiled.is_symlink() and compiled.resolve()==compiled
                    and inputs['sources'].get(source_path)==digest(source) and expected.get(key)==digest(compiled),
                    'PREPARATION_EVIDENCE_UNPROVEN')
            bindings.append((test_class,methods,len(source.read_text().splitlines()),digest(source),digest(compiled)))
    except Exception:return {**unknown,'unavailableReason':'EXIT_STAGE_SOURCE_OR_COMPILE_IDENTITY_UNPROVEN'}
    suites=[{**preparation_suite_evidence(test_class,methods,lines),'sourceSha256':source_hash,'testClassSha256':class_hash}
            for test_class,methods,lines,source_hash,class_hash in bindings]
    observed=sum(row['state']=='OBSERVED' for row in suites)
    return {**unknown,**bound,'state':'OBSERVED' if observed==len(suites) else ('PARTIAL_OR_UNKNOWN' if observed else 'UNKNOWN'),
            'phase':phase,'mavenExitObserved':True,'mavenExit':report['mavenExit'],
            'compilationSha256':hashlib.sha256(json.dumps(expected,sort_keys=True,separators=(',',':')).encode()).hexdigest(),
            'suites':suites}


def configuration_unit_proof(compilation):
    directory=ROOT/'chengyinhub-admin/target/provider-runtime'
    require(directory.is_dir() and not directory.is_symlink() and directory.resolve()==directory
            and not directory.stat().st_mode & 0o077,'CONFIGURATION_UNIT_PROOF_REQUIRED')
    source=ROOT/'chengyinhub-admin/src/test/java'/CONFIGURATION_SOURCE
    test_class=ROOT/'chengyinhub-admin/target/test-classes'/(CONFIGURATION_UNIT.replace('.','/')+'.class')
    require(all(path.is_file() and not path.is_symlink() and path.resolve()==path for path in (source,test_class))
            and compilation['sources'].get(CONFIGURATION_SOURCE)==digest(source)
            and compilation.get('configurationTestClassSha256')==digest(test_class),'CONFIGURATION_UNIT_PROOF_REQUIRED')
    xml=exact_test_xml(directory/'configuration.xml',CONFIGURATION_UNIT,CONFIGURATION_METHODS,'CONFIGURATION_UNIT_XML_REQUIRED')
    photo_source=ROOT/'chengyinhub-admin/src/test/java'/PHOTO_SOURCE
    photo_class=ROOT/'chengyinhub-admin/target/test-classes'/(PHOTO_UNIT.replace('.','/')+'.class')
    require(all(path.is_file() and not path.is_symlink() and path.resolve()==path for path in (photo_source,photo_class))
            and compilation['sources'].get(PHOTO_SOURCE)==digest(photo_source)
            and compilation.get('photoServiceTestClassSha256')==digest(photo_class),'CONFIGURATION_UNIT_PROOF_REQUIRED')
    photo_xml=exact_test_xml(directory/'photo-service.xml',PHOTO_UNIT,PHOTO_METHODS,'CONFIGURATION_UNIT_XML_REQUIRED')
    return {'purpose':'CONFIG_ASSEMBLY_ONLY','businessExecuted':False,
            'sourceSha':os.environ['SOURCE_SHA'],'runId':os.environ['GITHUB_RUN_ID'],'attempt':os.environ['GITHUB_RUN_ATTEMPT'],
            'testClass':CONFIGURATION_UNIT,'methods':list(CONFIGURATION_METHODS),
            'sourceSha256':digest(source),'testClassSha256':digest(test_class),**xml,
            'relatedPhotoUnit':{'purpose':'PHOTO_STATE_UNIT_ONLY','businessExecuted':False,
                'testClass':PHOTO_UNIT,'methods':list(PHOTO_METHODS),'sourceSha256':digest(photo_source),
                'testClassSha256':digest(photo_class),**photo_xml}}


def provider_classpath_proof(cache,phase,proof):
    # The complete original Surefire property, never a cache scan or reconstructed classpath.
    require(phase in ('ONLINE','OFFLINE'),'PROVIDER_EXACT_CLASSPATH_REQUIRED')
    directory=ROOT/'chengyinhub-admin/target/provider-runtime';path=directory/(phase.lower()+'.classpath')
    require(directory.is_dir() and not directory.is_symlink() and directory.resolve()==directory
            and directory.stat().st_mode & 0o777==0o700 and path.is_file() and not path.is_symlink()
            and path.resolve()==path and path.stat().st_uid==os.getuid() and path.stat().st_mode & 0o777==0o600
            and 0<path.stat().st_size<1024*1024,'PROVIDER_EXACT_CLASSPATH_REQUIRED')
    with path.open('rb') as stream:raw=stream.read(1024*1024)
    require(0<len(raw)<1024*1024,'PROVIDER_EXACT_CLASSPATH_REQUIRED')
    # Surefire 3.5.6 Classpath.writeToSystemProperty appends one separator per element.
    # Keep its complete bytes for proof; remove exactly that one formatter terminator.
    text=raw.decode('utf-8')
    require(text.endswith(':') and not any(value in text for value in ('\0','\r','\n')),
            'PROVIDER_EXACT_CLASSPATH_REQUIRED')
    execution=text[:-1];entries=execution.split(':')
    expected={'fileName':phase.lower()+'.classpath','sha256':hashlib.sha256(raw).hexdigest(),'byteLength':len(raw),'entryCount':len(entries)}
    require(proof.get('testClasspath')==expected and all(entries),'PROVIDER_EXACT_CLASSPATH_REQUIRED')
    resolved=[]
    for entry in entries:
        local=Path(entry)
        require(entry and local.is_absolute() and str(local)==entry,'PROVIDER_EXACT_CLASSPATH_REQUIRED')
        if local.is_relative_to('/cache/repository'):
            mapped=cache/local.relative_to('/cache/repository')
        else:
            require(any(local.is_relative_to(ROOT/module/'target') for module in MODULES),'PROVIDER_EXACT_CLASSPATH_REQUIRED');mapped=local
        require(not mapped.is_symlink() and mapped.resolve()==mapped and (mapped.is_dir() or mapped.is_file()),'PROVIDER_EXACT_CLASSPATH_REQUIRED')
        resolved.append(entry)
    require({str(ROOT/'chengyinhub-admin/target/classes'),str(ROOT/'chengyinhub-admin/target/test-classes')}
            <=set(resolved),'PROVIDER_EXACT_CLASSPATH_REQUIRED')
    return expected,raw,execution


def controlled_content_proof():
    code='CONTROLLED_CONTENT_SECURITY_PROOF_REQUIRED'
    root=ROOT/'chengyinhub-admin/target/controlled-content-security'
    source=ROOT/CONTENT_SECURITY_SOURCE;compiled=root/'classes'/CONTENT_SECURITY_CLASS;proof=root/'proof.properties'
    require(root.is_dir() and not root.is_symlink() and root.resolve()==root,code)
    require(source.is_file() and not source.is_symlink() and source.resolve()==source and source.stat().st_size==2059
            and digest(source)==CONTENT_SECURITY_SOURCE_SHA256,code)
    allowed={compiled,proof};directories=set(compiled.parents)-set(root.parents)
    for path in root.rglob('*'):
        require(not path.is_symlink() and path.resolve()==path
                and ((path.is_file() and path in allowed) or (path.is_dir() and path in directories)),code)
    require(all(path.is_file() and not path.is_symlink() and path.resolve()==path for path in allowed)
            and 4<compiled.stat().st_size<=1024*1024 and compiled.read_bytes()[:4]==b'\xca\xfe\xba\xbe'
            and proof.stat().st_size<=1024,code)
    text=proof.read_text(encoding='utf-8');lines=text.splitlines()
    require(len(lines)==3 and '\r' not in text
            and all(re.fullmatch(r'(?:sourceSha|sourceSha256|classSha256)=[0-9a-f]+',line) for line in lines),code)
    values=dict(line.split('=',1) for line in lines)
    require(len(values)==3 and values=={'sourceSha':os.environ['SOURCE_SHA'],'sourceSha256':digest(source),'classSha256':digest(compiled)},code)
    return {'purpose':'SAME_SOURCE_PRECOMPILED_CONTENT_SECURITY','sourceSha':os.environ['SOURCE_SHA'],
            'sourceSha256':digest(source),'classSha256':digest(compiled),'proofSha256':digest(proof)}


def compile_controlled_content(cid,cache,deadline):
    # Called once at the fixed boundary, after the original ONLINE Maven/fork returned zero.
    code='CONTROLLED_CONTENT_SECURITY_PROOF_REQUIRED';directory=ROOT/'chengyinhub-admin/target/provider-runtime'
    online=directory/'online.json';xml=directory/'online.xml'
    require(online.is_file() and not online.is_symlink() and online.resolve()==online
            and not online.stat().st_mode & 0o077 and online.stat().st_size<32768,code)
    record=json.loads(online.read_text());expected_class=ROOT/'chengyinhub-admin/target/test-classes'/(PROVIDER_PROBE.replace('.','/')+'.class')
    require({key:record.get(key) for key in ('sourceSha','runId','attempt')}==
            {key:identity(os.environ)[key] for key in ('sourceSha','runId','attempt')}
            and record.get('phase')=='ONLINE' and record.get('javaFeature')==21
            and record.get('forkedBooter') is True and record.get('businessExecuted') is False
            and record.get('testClassSha256')==digest(expected_class),code)
    exact_test_xml(xml,PROVIDER_PROBE,('bootsActualProjectPlatformRuntime',),'PROVIDER_RUNTIME_XML_REQUIRED')
    classpath,_,execution_classpath=provider_classpath_proof(cache,'ONLINE',record)
    source=ROOT/CONTENT_SECURITY_SOURCE;root=ROOT/'chengyinhub-admin/target/controlled-content-security'
    require(source.is_file() and not source.is_symlink() and source.resolve()==source
            and source.stat().st_size==2059 and digest(source)==CONTENT_SECURITY_SOURCE_SHA256
            and not root.exists() and not root.is_symlink()
            and root.parent.resolve()==root.parent,code)
    before=digest(source);classes=root/'classes';classes.mkdir(parents=True,mode=0o700)
    # No interpolation of raw classpath, credentials or source bytes. Same pinned JDK, fixed source,
    # original proc:none; the short compiler JVM exits before OFFLINE Maven or the business fork.
    script=('umask 077; scratch=/tmp/cy-controlled-content-security-source; '
            '[ ! -e "$scratch" ] && [ ! -L "$scratch" ] || exit 98; mkdir "$scratch" || exit 98; '
            'cp -- "$1" "$scratch/E2eWxContentSecurityStub.java" || exit 98; '
            'javac -J-Xmx128m -proc:none -classpath "$2" -d "$3" "$scratch/E2eWxContentSecurityStub.java"; status=$?; '
            'rm -f -- "$scratch/E2eWxContentSecurityStub.java"; rmdir "$scratch" || exit 98; '
            'printf "%s\\n" "$status" >/tmp/cy-cache.javac.exit')
    remaining=deadline-time.monotonic();require(remaining>0,'CACHE_EXECUTION_DEADLINE')
    command(DOCKER+['exec',cid,'/bin/bash','-c',script,'--',str(source),execution_classpath,str(classes)],timeout=remaining)
    raw=command(DOCKER+['exec',cid,'cat','/tmp/cy-cache.javac.exit']).decode().strip()
    require(re.fullmatch('[0-9]{1,3}',raw) and int(raw)<256,code)
    result={'purpose':'PREPARATION_COMPILER_ONLY','exitObserved':True,'exit':int(raw),'testClasspath':classpath}
    if int(raw)==0:
        compiled=classes/CONTENT_SECURITY_CLASS
        require(digest(source)==before and compiled.is_file() and not compiled.is_symlink() and compiled.resolve()==compiled,code)
        proof=root/'proof.properties'
        with proof.open('x',encoding='utf-8') as stream:
            proof.chmod(0o600);stream.write('sourceSha='+os.environ['SOURCE_SHA']+'\nsourceSha256='+before+'\nclassSha256='+digest(compiled)+'\n')
        result['proof']=controlled_content_proof()
    # Release only this owner's fixed shell boundary. A real javac failure remains a failure;
    # it is not recorded as a Maven exit or converted into a successful cache receipt.
    command(DOCKER+['exec',cid,'/bin/bash','-c','printf "%s\\n" "$1" >/tmp/cy-cache.content.exit','--',raw])
    return result


def provider_runtime_proof(cache):
    """A real fork/test in preparation, not business acceptance or guessed runtime artifact coordinates."""
    directory=ROOT/'chengyinhub-admin/target/provider-runtime'
    require(directory.is_dir() and not directory.is_symlink() and directory.resolve()==directory
            and not directory.stat().st_mode & 0o077,'PROVIDER_RUNTIME_PROOF_REQUIRED')
    artifacts={'launcher':('org/junit/platform/junit-platform-launcher','junit-platform-launcher'),
               'engine':('org/junit/jupiter/junit-jupiter-engine','junit-jupiter-engine'),
               'platformEngine':('org/junit/platform/junit-platform-engine','junit-platform-engine'),
               'api':('org/junit/jupiter/junit-jupiter-api','junit-jupiter-api'),
               'mockito':('org/mockito/mockito-core','mockito-core')}
    expected_class=ROOT/'chengyinhub-admin/target/test-classes'/(PROVIDER_PROBE.replace('.','/')+'.class')
    require(expected_class.is_file() and not expected_class.is_symlink(),'PROVIDER_RUNTIME_PROOF_REQUIRED')
    rows={};boots=[]
    for phase in ('ONLINE','OFFLINE'):
        path=directory/(phase.lower()+'.json');xml=directory/(phase.lower()+'.xml')
        for file,limit in ((path,32768),(xml,2*1024*1024)):
            require(file.is_file() and not file.is_symlink() and file.resolve().parent==directory
                    and not file.stat().st_mode & 0o077 and file.stat().st_size<limit,'PROVIDER_RUNTIME_PROOF_REQUIRED')
        proof=json.loads(path.read_text())
        exact_test_xml(xml,PROVIDER_PROBE,('bootsActualProjectPlatformRuntime',),'PROVIDER_RUNTIME_XML_REQUIRED')
        require(set(proof)=={'sourceSha','runId','attempt','phase','javaFeature','pid','parentPid','startedAt','forkedBooter',
                'commandSha256','testClassSha256','runtime','businessExecuted','testClasspath'}
                and proof['sourceSha']==os.environ['SOURCE_SHA'] and proof['phase']==phase
                and proof['runId']==os.environ['GITHUB_RUN_ID'] and proof['attempt']==os.environ['GITHUB_RUN_ATTEMPT']
                and proof['javaFeature']==21 and proof['forkedBooter'] is True and proof['businessExecuted'] is False
                and all(type(proof[key]) is int and proof[key]>0 for key in ('pid','parentPid'))
                and proof['pid']!=proof['parentPid']
                and re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z',proof['startedAt'])
                and re.fullmatch('[0-9a-f]{64}',proof['commandSha256'])
                and proof['testClassSha256']==digest(expected_class) and set(proof['runtime'])==set(artifacts),
                'PROVIDER_RUNTIME_PROOF_REQUIRED')
        for key,(relative,artifact) in artifacts.items():
            record=proof['runtime'][key]
            require(set(record)=={'version','sha256'} and re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+',record['version'])
                    and re.fullmatch('[0-9a-f]{64}',record['sha256']),'PROVIDER_RUNTIME_PROOF_REQUIRED')
            jar=cache/relative/record['version']/(artifact+'-'+record['version']+'.jar')
            require(jar.is_file() and not jar.is_symlink() and jar.resolve().is_relative_to(cache.resolve())
                    and digest(jar)==record['sha256'],'PROVIDER_RUNTIME_CLASSPATH_CHANGED')
        boots.append((proof['pid'],proof['startedAt']))
        classpath,_,_=provider_classpath_proof(cache,phase,proof)
        rows[phase.lower()]={'xmlSha256':digest(xml),'proofSha256':digest(path),'testClasspath':classpath,
            'testClassSha256':proof['testClassSha256'],'runtime':proof['runtime'],
            'forkIdentitySha256':hashlib.sha256(json.dumps(boots[-1],separators=(',',':')).encode()).hexdigest()}
    require(boots[0]!=boots[1] and rows['online']['runtime']==rows['offline']['runtime']
            and {key:value for key,value in rows['online']['testClasspath'].items() if key!='fileName'}==
                {key:value for key,value in rows['offline']['testClasspath'].items() if key!='fileName'},'PROVIDER_RUNTIME_CLASSPATH_CHANGED')
    return {'purpose':'PREPARATION_PROVIDER_RUNTIME_ONLY','businessExecuted':False,**rows}


def require(ok, code):
    if not ok: raise ValueError(code)


def command(argv, timeout=20, data=None):
    result = subprocess.run(argv, input=data, capture_output=True, timeout=timeout)
    require(result.returncode == 0, 'FIXED_COMMAND_FAILED')
    return result.stdout


def write(path, value):
    path = Path(path); require(not path.is_symlink(), 'SYMLINK_REPORT')
    temporary = path.with_suffix(path.suffix + '.next')
    with temporary.open('x') as stream:
        temporary.chmod(0o600); json.dump(value, stream, sort_keys=True); stream.flush(); os.fsync(stream.fileno())
    os.replace(temporary, path)


def digest(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def safe_plugin_resolution(text):
    """Only exact reviewed plugin coordinates in Maven's standard resolution error, never its cause."""
    result={}
    text=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',text[-1024*1024:])
    matches=re.findall(r'\bPlugin ([a-z][a-z0-9_.-]{0,99}):([a-z][a-z0-9-]{0,79}):([0-9][A-Za-z0-9_.-]{0,39}) or one of its dependencies could not be resolved:',text)
    for group,artifact,version in reversed(matches):
        key=group+':'+artifact
        if version in REVIEWED_PLUGINS.get(key,set()):
            result={'plugin':key,'pluginVersion':version,'pluginPhase':'RESOLUTION_BEFORE_GOAL'};break
    return result


def safe_cache_progress(text):
    """Last reviewed Maven goal header only; not a claim that this goal caused the timeout."""
    text=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',text[-1024*1024:])
    headers=re.findall(r'--- ([a-z][a-z0-9-]{0,79}):([0-9][A-Za-z0-9_.-]{0,39}):([A-Za-z][A-Za-z0-9]{0,39})[^\r\n]{0,160} @ ([a-z][a-z0-9-]{0,79}) ---',text)
    for prefix,version,goal,module in reversed(headers):
        if module not in (*MODULES,'chengyinhub'):continue
        for coordinate,versions in REVIEWED_PLUGINS.items():
            artifact=coordinate.split(':')[1]
            aliases={artifact,artifact.removeprefix('maven-').removesuffix('-plugin'),artifact.removesuffix('-maven-plugin')}
            if prefix in aliases and version in versions and goal in CACHE_GOALS.get(artifact,set()):
                return {'plugin':coordinate,'pluginVersion':version,'goal':goal,'module':module}
    return {}


def compile_failure_evidence(report,inputs,state,obj,phase,text):
    """Bounded javac locations only. Not a compiled postimage, acceptance gate or inferred old cause."""
    unknown={'purpose':'PREPARATION_COMPILER_DIAGNOSTIC_ONLY','businessAccepted':False,'state':'UNKNOWN'}
    try:
        require(report.get('mavenExitObserved') is True and type(report.get('mavenExit')) is int
                and 0<report['mavenExit']<256 and report.get('originalExit')==report['mavenExit']
                and phase in ('OFFLINE_PACKAGE_PROBE','ONLINE_PACKAGE','ONLINE_PROVIDER_RUNTIME',
                              'ONLINE_CONFIGURATION_UNITS','OFFLINE_PROVIDER_RUNTIME','OFFLINE_TEST_PREFLIGHT')
                and report.get('phase')==phase,'COMPILER_DIAGNOSTIC_IDENTITY_UNPROVEN')
        identity_gate(os.environ);bound=identity(os.environ)
        require({key:report.get(key) for key in bound}==bound,'COMPILER_DIAGNOSTIC_IDENTITY_UNPROVEN')
        slot=state['slots']['app']
        owner=validate_container(obj,slot['name'],JDK,labels(os.environ,state['nonce']),MEMORY,'bridge',slot['cid'])
        require(owner==report['budget'] and obj['Image']==report['imageId']
                and obj['Config']['Cmd']==fixed_cache_command() and obj['Config']['Entrypoint']==['/bin/bash']
                and obj['Config']['User']==str(os.getuid())+':'+str(os.getgid())
                and obj['State']['Running'] is True and obj['State']['Pid']>0
                and report.get('image')==JDK and report.get('mavenVersion')==CORE_VERSION
                and report.get('settingsSha256')==hashlib.sha256(EMPTY_SETTINGS.encode()).hexdigest()
                and re.fullmatch('[0-9a-f]{64}',report.get('mavenCoreSha256','')),'COMPILER_DIAGNOSTIC_IDENTITY_UNPROVEN')
        environment=dict(value.split('=',1) for value in obj['Config']['Env'])
        require(all(environment.get(key)==value for key,value in
                {'SOURCE_SHA':bound['sourceSha'],'GITHUB_SHA':bound['sourceSha'],'GITHUB_RUN_ID':bound['runId'],'GITHUB_RUN_ATTEMPT':bound['attempt']}.items())
                and report.get('pomSha256')==cache_inputs() and fixed_test_compilation_inputs()==inputs
                and inputs.get('profile')==TEST_PROFILE and set(inputs.get('sources',{}))==set(TEST_SOURCES),
                'COMPILER_DIAGNOSTIC_IDENTITY_UNPROVEN')
    except Exception:return {**unknown,'unavailableReason':'EXIT_STAGE_SOURCE_OR_OWNER_UNPROVEN'}
    try:
        require(isinstance(text,str) and 0<len(text)<=1024*1024,'COMPILER_DIAGNOSTIC_UNPROVEN')
        text=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',text)
        goal={'plugin':'org.apache.maven.plugins:maven-compiler-plugin','pluginVersion':'3.15.0',
              'goal':'testCompile','module':'chengyinhub-admin'}
        require(safe_cache_progress(text)==goal,'COMPILER_DIAGNOSTIC_UNPROVEN')
        headers=list(re.finditer(r'^\[INFO\] --- (?:compiler|maven-compiler-plugin):3\.15\.0:testCompile \(default-testCompile\) @ chengyinhub-admin ---\s*$',text,re.MULTILINE))
        goals=list(re.finditer(r'^\[INFO\] --- [^\r\n]{1,512} ---[ \t]*$',text,re.MULTILINE))
        require(headers and goals and goals[-1].start()==headers[-1].start(),'COMPILER_DIAGNOSTIC_UNPROVEN')
        lines=text[headers[-1].end():].splitlines()
        markers=[index for index,line in enumerate(lines) if re.fullmatch(r'\[ERROR\] COMPILATION ERROR :\s*',line)]
        require(len(markers)==1,'COMPILER_DIAGNOSTIC_UNPROVEN');section=lines[markers[0]+1:]
        ends=[(index,re.fullmatch(r'\[INFO\] ([0-9]{1,7}) errors?\s*',line)) for index,line in enumerate(section)]
        ends=[(index,match) for index,match in ends if match is not None]
        require(ends and 1<=int(ends[0][1][1])<=8,'COMPILER_DIAGNOSTIC_UNPROVEN')
        expected=int(ends[0][1][1]);rows=[];seen=set();root=ROOT/'chengyinhub-admin/src/test/java'
        for line in section[:ends[0][0]]:
            if not line.startswith('[ERROR] '):continue
            match=re.fullmatch(r'\[ERROR\] ([^\r\n]{1,4096}\.java):\[([0-9]{1,7}),([0-9]{1,7})\] ([^\r\n]{1,4096})',line)
            if match is None:
                # Continuation text is private. A malformed location cannot hide among valid rows.
                require('.java:' not in line,'COMPILER_DIAGNOSTIC_UNPROVEN');continue
            location=Path(match[1]);require(location.is_absolute() and str(location)==match[1]
                and '..' not in location.parts and location.is_relative_to(root),'COMPILER_DIAGNOSTIC_UNPROVEN')
            relative=location.relative_to(root).as_posix()
            require(relative in TEST_SOURCES and location.is_file() and not location.is_symlink()
                    and location.resolve()==location,'COMPILER_DIAGNOSTIC_UNPROVEN')
            raw=location.read_bytes();source_hash=hashlib.sha256(raw).hexdigest()
            require(inputs['sources'].get(relative)==source_hash,'COMPILER_DIAGNOSTIC_UNPROVEN')
            source_lines=raw.decode('utf-8').split('\n');number=int(match[2]);column=int(match[3])
            require(1<=number<=len(source_lines),'COMPILER_DIAGNOSTIC_UNPROVEN');width=0
            for character in source_lines[number-1].rstrip('\r'):
                width=((width//8)+1)*8 if character=='\t' else width+len(character.encode('utf-16-le'))//2
            require(1<=column<=width+1,'COMPILER_DIAGNOSTIC_UNPROVEN')
            message=match[4].removeprefix('error: ');category=None
            if message=='cannot find symbol':category='CANNOT_FIND_SYMBOL'
            elif re.fullmatch(r'incompatible types: .{1,4000}',message):category='INCOMPATIBLE_TYPES'
            elif re.fullmatch(r'duplicate class: .{1,2000}|(?:variable|method|constructor|class) .{1,2000} is already defined in .{1,2000}',message):category='DUPLICATE_DEFINITION'
            elif re.fullmatch(r'unreported exception .{1,2000}; must be caught or declared to be thrown',message):category='UNDECLARED_CHECKED_EXCEPTION'
            elif message in ("';' expected",'not a statement','illegal start of expression','illegal start of type',
                             'reached end of file while parsing','class, interface, enum, or record expected'):
                category='SYNTAX_ERROR'
            require(category is not None,'COMPILER_DIAGNOSTIC_UNPROVEN')
            key=(relative,number,column,category)
            require(key not in seen and len(rows)<8,'COMPILER_DIAGNOSTIC_UNPROVEN');seen.add(key)
            rows.append({'source':relative,'sourceSha256':source_hash,'line':number,'column':column,'category':category})
        require(len(rows)==expected,'COMPILER_DIAGNOSTIC_UNPROVEN')
        return {**unknown,**bound,'state':'OBSERVED','phase':phase,'mavenExitObserved':True,
                'mavenExit':report['mavenExit'],'compiler':goal,'errors':rows}
    except Exception:return {**unknown,'unavailableReason':'MISSING_OR_UNREVIEWED_COMPILER_ERROR_SECTION'}


def cache_manifest(cache):
    require(cache.is_dir() and not cache.is_symlink(),'EXISTING_OFFLINE_CACHE_REQUIRED')
    rows=[];total=0
    for path in sorted(cache.rglob('*')):
        require(not path.is_symlink(),'SYMLINK_OFFLINE_CACHE_FORBIDDEN')
        if path.is_dir():continue
        require(path.is_file(),'NONREGULAR_OFFLINE_CACHE_FORBIDDEN')
        total+=path.stat().st_size;rows.append((str(path.relative_to(cache)),path.stat().st_size,digest(path)))
        require(len(rows)<=100000 and total<=8*1024**3,'BOUNDED_OFFLINE_CACHE_REQUIRED')
    require(rows,'EMPTY_OFFLINE_CACHE')
    return {'files':len(rows),'bytes':total,'sha256':hashlib.sha256(json.dumps(rows,separators=(',',':')).encode()).hexdigest()}


def cache_inputs():
    require(command(['git','-C',str(ROOT),'rev-parse','HEAD']).decode().strip()==os.environ['SOURCE_SHA'],'WRONG_CHECKOUT')
    require(subprocess.run(['git','-C',str(ROOT),'diff','--quiet','HEAD','--'],capture_output=True).returncode==0,'DIRTY_SOURCE')
    return {str(p.relative_to(ROOT)):digest(p) for p in [ROOT/'pom.xml']+[ROOT/m/'pom.xml' for m in MODULES]}


def cache_maven_arguments():
    return ['-B','--no-transfer-progress','-Dstyle.color=never','--settings','/cache-owner/settings.xml','--global-settings','/cache-owner/settings.xml',
            '-Dmaven.repo.local=/cache/repository']


def fixed_cache_command():
    common=cache_maven_arguments()
    # Same package in the pinned owner: prefer the existing offline closure, never trust a prior JAR.
    # skipTests belongs only to preparation/preflight. The original real test command never gains it.
    goals=[common+['-P'+TEST_PROFILE,'-pl','chengyinhub-admin','-am','package','-DskipTests'],
           common+['-N','org.apache.maven.plugins:maven-dependency-plugin:3.10.0:get',
                   '-Dartifact=org.apache.maven.surefire:surefire-junit-platform:3.5.6','-Dtransitive=true'],
           common+['-o','-P'+TEST_PROFILE,'-pl','chengyinhub-admin','-am','test','-DskipTests','-Dtest='+CLASS,
                   '-DfailIfNoTests=false','-Dsurefire.failIfNoSpecifiedTests=false']]
    # Structured argv are passed to a fixed shell without interpolation of source/env/credentials.
    package='package=('+ ' '.join("'"+value+"'" for value in ['mvn']+goals[0])+'); '
    # Only the original Central offline-miss diagnostic permits online closure repair. Compile/package
    # failures are not retried online; every path still compiles/packages this source in this container.
    missing=r'Cannot access central \(https://repo\.maven\.apache\.org/maven2\) in offline mode and the artifact [^[:space:]]+ has not been downloaded from it before\.'
    encoded=package+'printf "%s\\n" "OFFLINE_PACKAGE_PROBE" >/tmp/cy-cache.phase; '+\
            '"${package[@]}" -o 2>&1 | tee /tmp/cy-cache.offline.log; codes=("${PIPESTATUS[@]}"); probe=${codes[0]}; '+\
            '[ "${codes[1]}" -eq 0 ] || exit 98; '+\
            'printf "%s\\n" "$probe" >/tmp/cy-cache.offline.exit.next; mv /tmp/cy-cache.offline.exit.next /tmp/cy-cache.offline.exit; '+\
            'if [ "$probe" -ne 0 ]; then '+\
            "if grep -Eq '"+missing+"' /tmp/cy-cache.offline.log; then "+\
            'printf "%s\\n" "ONLINE_PACKAGE" >/tmp/cy-cache.phase; "${package[@]}" || exit $?; '+\
            'else exit "$probe"; fi; fi; '
    encoded+='; '.join('printf "%s\\n" "'+stage+'" >/tmp/cy-cache.phase; '+
                     ' '.join("'"+value+"'" for value in ['mvn']+goal)+' || exit $?'
                     for stage,goal in zip(('ORIGINAL_PROVIDER_GET',),goals[1:2]))
    # Real provider selection and fork, not skipTests. Same reactor/profile/test classpath, no business.
    report='chengyinhub-admin/target/surefire-reports/TEST-'+PROVIDER_PROBE+'.xml'
    configuration_report='chengyinhub-admin/target/surefire-reports/TEST-'+CONFIGURATION_UNIT+'.xml'
    photo_report='chengyinhub-admin/target/surefire-reports/TEST-'+PHOTO_UNIT+'.xml'
    for stage,phase,offline in (('ONLINE_PROVIDER_RUNTIME','ONLINE',False),('OFFLINE_PROVIDER_RUNTIME','OFFLINE',True)):
        selection=PROVIDER_PROBE
        args=common+(['-o'] if offline else [])+['-P'+TEST_PROFILE,'-pl','chengyinhub-admin','-am','test',
            '-DargLine='+SUREFIRE_FORK_ARGUMENTS+' -Dcombined.provider.phase='+phase,
            '-Dtest='+selection,'-DforkCount=1','-DreuseForks=true','-DfailIfNoTests=false','-Dsurefire.failIfNoSpecifiedTests=false']
        remove="rm -f '"+report+"'"+(" '"+configuration_report+"' '"+photo_report+"'" if not offline else '')
        encoded+='; printf "%s\\n" "'+stage+'" >/tmp/cy-cache.phase; '+remove+'; '+\
            "MAVEN_OPTS='"+RUNTIME_MAVEN_OPTIONS+"' "+\
            ' '.join("'"+value+"'" for value in ['mvn']+args)+'; status=$?; '+\
            'printf "%s\\n" "$status" >/tmp/cy-cache.runtime-maven.exit; [ "$status" -eq 0 ] || exit "$status"; '+\
            "cp '"+report+"' 'chengyinhub-admin/target/provider-runtime/"+phase.lower()+".xml' || exit $?; "+\
            "chmod 600 'chengyinhub-admin/target/provider-runtime/"+phase.lower()+".xml' || exit $?"
        if not offline:
            encoded+='; printf "%s\\n" "CONTENT_SECURITY_COMPILE" >/tmp/cy-cache.phase; '+\
                     'while [ ! -f /tmp/cy-cache.content.exit ]; do sleep .1; done; '+\
                     'status=$(cat /tmp/cy-cache.content.exit) || exit 98; [ "$status" -eq 0 ] || exit "$status"'
            unit_args=common+['-P'+TEST_PROFILE,'-pl','chengyinhub-admin','-am','test',
                '-DargLine='+SUREFIRE_FORK_ARGUMENTS,'-Dtest='+CONFIGURATION_UNIT+','+PHOTO_UNIT,
                '-DforkCount=1','-DreuseForks=true','-DfailIfNoTests=false','-Dsurefire.failIfNoSpecifiedTests=false']
            encoded+='; printf "%s\\n" "ONLINE_CONFIGURATION_UNITS" >/tmp/cy-cache.phase; '+\
                     "rm -f '"+configuration_report+"' '"+photo_report+"'; MAVEN_OPTS='"+RUNTIME_MAVEN_OPTIONS+"' "+\
                     ' '.join("'"+value+"'" for value in ['mvn']+unit_args)+'; status=$?; '+\
                     'printf "%s\\n" "$status" >/tmp/cy-cache.runtime-maven.exit; [ "$status" -eq 0 ] || exit "$status"; '+\
                     "cp '"+configuration_report+"' 'chengyinhub-admin/target/provider-runtime/configuration.xml' || exit $?; "+\
                     "chmod 600 'chengyinhub-admin/target/provider-runtime/configuration.xml' || exit $?; "+\
                     "cp '"+photo_report+"' 'chengyinhub-admin/target/provider-runtime/photo-service.xml' || exit $?; "+\
                     "chmod 600 'chengyinhub-admin/target/provider-runtime/photo-service.xml' || exit $?"
    encoded+='; printf "%s\\n" "OFFLINE_TEST_PREFLIGHT" >/tmp/cy-cache.phase; '+\
        ' '.join("'"+value+"'" for value in ['mvn']+goals[2])+' || exit $?'
    return ['-c','mkdir -p /tmp/home /tmp/m2 || exit 98; '
            'child=; trap \'[ -z "$child" ] || kill -TERM "$child" 2>/dev/null; exit 143\' TERM INT HUP; '
            '('+encoded+') & child=$!; wait "$child"; status=$?; child=; '
            'printf "%s\\n" "$status" >/tmp/cy-cache.exit.next; mv /tmp/cy-cache.exit.next /tmp/cy-cache.exit; '
            'trap \'exit "$status"\' TERM INT HUP; '
            'for i in {1..1200}; do [ -f /cache-owner/stop ] && break; sleep .1; done; exit "$status"']


def cache_failure(failure,phase,substage):
    """Fixed predicate and reviewed function/line only; never a traceback, message, argv or private path."""
    result=resource_failure(failure,phase)
    result['substage']=substage if substage in CACHE_SUBSTAGES else 'UNKNOWN'
    if result['type'] not in {'ValueError','OSError','FileNotFoundError','PermissionError','TimeoutExpired',
                              'InterruptedError','JSONDecodeError','KeyError','TypeError'}:result['type']='UNKNOWN'
    origin=None;trace=failure.__traceback__
    while trace is not None:
        code=trace.tb_frame.f_code
        if code.co_filename==__file__ and code.co_name in CACHE_FAILURE_FUNCTIONS:
            origin={'function':code.co_name,'line':trace.tb_lineno}
        trace=trace.tb_next
    if origin is not None:result['origin']=origin
    return result


def cache_process_state(obj):
    state=obj.get('State',{})
    require(type(state.get('Running')) is bool and type(state.get('OOMKilled')) is bool
            and type(state.get('Pid')) is int and state['Pid']>=0
            and type(state.get('ExitCode')) is int and 0<=state['ExitCode']<256,'CACHE_STATE_NOT_PROVEN')
    return {key:state[key] for key in ('Running','OOMKilled','ExitCode')} | {'pidPresent':state['Pid']>0}


def cache_offline_probe_exit(cid,required=True):
    value=command(DOCKER+['exec',cid,'/bin/bash','-c',
        'if [ -f /tmp/cy-cache.offline.exit ]; then cat /tmp/cy-cache.offline.exit; fi']).decode().strip()
    if not required and not value:return None
    require(re.fullmatch('[0-9]{1,3}',value) and int(value)<256,'CACHE_OFFLINE_PACKAGE_PROBE_EXIT_REQUIRED')
    return int(value)


def cache_terminal(report,cleaned,preparation_failed):
    # Exact owned resource absence is independent of why the workload stopped.
    # A synthetic host failure exit is never represented as a Maven exit or compared to SIGKILL.
    if cleaned['result']!='EXACT_CID_REMOVED_AND_ABSENT':return
    require(cleaned['terminal'].get('OOMKilled') is False,'APP_OOM_OR_UNKNOWN_EXIT')
    if not preparation_failed:
        require(report.get('mavenExitObserved') is True and report.get('mavenExit')==report['originalExit']
                and cleaned['terminal']['ExitCode'] in (report['mavenExit'],143),'CACHE_COMPLETED_SHELL_EXIT_MISMATCH')


def warm_cache():
    identity_gate(os.environ);PUBLIC.mkdir(parents=True,exist_ok=True)
    private=private_directory(Path(command(['mktemp','-d','/tmp/cy-http-budget.XXXXXXXX']).decode().strip()))
    cache=Path.home()/'.m2/repository'
    report={**resource_envelope(os.environ),'result':'NOT_COMPLETED','originalExit':None,'failures':[],
            'preparationOnly':True,'mavenExitObserved':False,'mavenExit':None,'offlinePackageProbeExit':None,
            'mavenVersion':CORE_VERSION,'image':JDK,'settingsSha256':hashlib.sha256(EMPTY_SETTINGS.encode()).hexdigest()}
    state={'nonce':uuid.uuid4().hex,'slots':{}};first=None;cleaned=None;failed_postimage=None;compiler_log_tail=None
    substage='SOURCE_INPUTS'
    def publish():
        try:write(CACHE_REPORT,report)
        except Exception as failure:report['failures'].append(resource_failure(failure,'CACHE_REPORT_WRITE'))
    try:
        publish();cache.mkdir(parents=True,exist_ok=True)
        require(not cache.is_symlink() and cache.resolve()==cache,'SYMLINK_OFFLINE_CACHE_FORBIDDEN')
        report['pomSha256']=cache_inputs()
        compilation=fixed_test_compilation_inputs();reset_fixed_test_outputs()
        substage='LOCAL_ENGINE'
        require(command(DOCKER+['context','inspect','--format','{{.Endpoints.docker.Host}}']).decode().strip()
                =='unix:///var/run/docker.sock','LOCAL_DOCKER_ONLY')
        substage='PINNED_IMAGE';found=subprocess.run(DOCKER+['image','inspect',JDK],capture_output=True,timeout=20)
        if found.returncode:command(DOCKER+['pull',JDK],timeout=300)
        image=json.loads(command(DOCKER+['image','inspect',JDK]))[0];report['imageId']=image['Id']
        substage='CREATE_INPUTS';(private/'settings.xml').write_text(EMPTY_SETTINGS);os.chmod(private/'settings.xml',0o600)
        name='cy-http-cache-'+os.environ['GITHUB_RUN_ID']+'-'+os.environ['GITHUB_RUN_ATTEMPT']+'-'+state['nonce'][:12]
        absent('container',name);state['slots']['app']={'name':name,'cid':None,'attempted':True}
        args=DOCKER+['create','--name',name,'--network','bridge','--memory=1g','--memory-swap=1g','--cpus=1',
                    '--pids-limit=256','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true',
                    '--tmpfs','/tmp:rw,size=256m','--user',str(os.getuid())+':'+str(os.getgid()),
                    '--entrypoint','/bin/bash','--workdir',str(ROOT),'--cidfile',str(private/'app.cid')]
        for key,value in labels(os.environ,state['nonce']).items():args+=['--label',key+'='+value]
        mounts=[(ROOT,ROOT,'readonly'),(cache,Path('/cache/repository'),'readonly=false'),(private,Path('/cache-owner'),'readonly')]
        for module in MODULES:
            target=ROOT/module/'target';target.mkdir(parents=True,exist_ok=True)
            require(not target.is_symlink(),'ORIGINAL_TARGET_REQUIRED');mounts.append((target,target,'readonly=false'))
        for source,target,mode in mounts:args+=['--mount','type=bind,src='+str(source)+',dst='+str(target)+','+mode]
        env={'HOME':'/tmp/home','MAVEN_CONFIG':'/tmp/m2','MAVEN_OPTS':'-Xmx512m -Djava.io.tmpdir=/tmp -Duser.home=/tmp/home',
             'SOURCE_SHA':os.environ['SOURCE_SHA'],'GITHUB_SHA':os.environ['SOURCE_SHA'],
             'GITHUB_RUN_ID':os.environ['GITHUB_RUN_ID'],'GITHUB_RUN_ATTEMPT':os.environ['GITHUB_RUN_ATTEMPT']}
        for key,value in env.items():args+=['--env',key+'='+value]
        substage='CREATE';cid=command(args+[JDK]+fixed_cache_command(),timeout=30).decode().strip()
        require(re.fullmatch('[0-9a-f]{64}',cid) and (private/'app.cid').read_text().strip()==cid,'CREATED_CID_NOT_PROVEN')
        substage='OWNER_TOOLCHAIN';state['slots']['app']['cid']=cid;obj=inspect(cid)
        report['budget']=validate_container(obj,name,JDK,labels(os.environ,state['nonce']),MEMORY,'bridge',cid)
        require(obj['Image']==image['Id'] and obj['Config']['Cmd']==fixed_cache_command()
                and obj['Config']['Entrypoint']==['/bin/bash']
                and obj['Config']['User']==str(os.getuid())+':'+str(os.getgid()),'FIXED_CACHE_TOOLCHAIN_REQUIRED')
        substage='MOUNT_ENV';expected_mounts={(str(s),str(t),mode!='readonly') for s,t,mode in mounts}
        require({(m['Source'],m['Destination'],m['RW']) for m in obj['Mounts'] if m['Type']=='bind'}==expected_mounts
                and all(m['Type']=='bind' or (m['Type']=='tmpfs' and m['Destination']=='/tmp') for m in obj['Mounts'])
                and obj['HostConfig']['ReadonlyRootfs'] is True and not obj['HostConfig'].get('Devices')
                and not obj['HostConfig'].get('VolumesFrom'),'PRIVATE_MOUNT_BOUNDARY_CHANGED')
        require(dict(item.split('=',1) for item in obj['Config']['Env'])==
                {**dict(item.split('=',1) for item in image['Config'].get('Env',[])),**env},'PRIVATE_ENV_BOUNDARY_CHANGED')
        substage='START';publish();command(DOCKER+['start',cid]);deadline=time.monotonic()+LIMIT_SECONDS
        substage='CGROUP';obj=inspect(cid);report['cgroup']=read_cgroup(obj['State']['Pid'],cid);publish()
        substage='WAIT_EXIT'
        while True:
            obj=inspect(cid);validate_owner(obj,name,JDK,labels(os.environ,state['nonce']),cid)
            report['lastContainerState']=cache_process_state(obj)
            require(obj['State']['Running'],'CACHE_PROCESS_EXIT_BEFORE_RECEIPT')
            require(time.monotonic()<deadline,'CACHE_EXECUTION_DEADLINE')
            status=command(DOCKER+['exec',cid,'/bin/bash','-c','if [ -f /tmp/cy-cache.exit ]; then cat /tmp/cy-cache.exit; fi']).decode().strip()
            if status:
                require(re.fullmatch('[0-9]{1,3}',status) and int(status)<256,'ACTUAL_MAVEN_EXIT_REQUIRED');break
            phase=command(DOCKER+['exec',cid,'/bin/bash','-c','if [ -f /tmp/cy-cache.phase ]; then cat /tmp/cy-cache.phase; fi']).decode().strip()
            if phase=='CONTENT_SECURITY_COMPILE' and 'controlledContentSecurityCompiler' not in report:
                substage='CONTENT_SECURITY'
                previous=command(DOCKER+['exec',cid,'cat','/tmp/cy-cache.runtime-maven.exit']).decode().strip()
                require(previous=='0','ACTUAL_MAVEN_EXIT_REQUIRED')
                report['mavenExitObserved']=True;report['mavenExit']=0
                require(cache_inputs()==report['pomSha256'] and fixed_test_compilation_inputs()==compilation,'COMPILED_POSTIMAGE_DRIFT')
                snapshot=fixed_test_compilation_outputs(compilation)
                report['controlledContentSecurityCompiler']=compile_controlled_content(cid,cache,deadline)
                require(fixed_test_compilation_outputs(compilation)==snapshot,'COMPILED_POSTIMAGE_DRIFT')
                publish();substage='WAIT_EXIT'
            time.sleep(.5)
        substage='MAVEN_EXIT';report['originalExit']=int(status)
        compiler=report.get('controlledContentSecurityCompiler')
        if compiler is None or compiler.get('exit')==0:
            report['mavenExit']=int(status);report['mavenExitObserved']=True
        report['offlinePackageProbeExit']=cache_offline_probe_exit(cid)
        report['cgroup']=read_cgroup(obj['State']['Pid'],cid)
        require(int(report['cgroup']['memoryEvents'].get('oom_kill','-1'))==0,'ACTUAL_CGROUP_CHILD_OOM_KILL')
        substage='CORE_IDENTITY';core=command(DOCKER+['exec',cid,'sha256sum','/usr/share/maven/lib/maven-core-'+CORE_VERSION+'.jar']).decode().split()[0]
        require(re.fullmatch('[0-9a-f]{64}',core),'FIXED_CACHE_TOOLCHAIN_REQUIRED');report['mavenCoreSha256']=core
        if compiler is not None:
            substage='CONTENT_SECURITY'
            require(compiler.get('exitObserved') is True and compiler.get('exit')==0,'CONTROLLED_CONTENT_SECURITY_COMPILE_FAILED')
        if report['mavenExit']!=0:
            # Freeze the current compile postimage for diagnostics only. Never replace the original
            # Maven failure with a missing/invalid compile receipt or promote this to an acceptance proof.
            try:
                require(cache_inputs()==report['pomSha256'] and fixed_test_compilation_inputs()==compilation,
                        'COMPILED_POSTIMAGE_DRIFT')
                failed_postimage=fixed_test_compilation_outputs(compilation)
            except Exception:failed_postimage=None
        substage='MAVEN_EXIT';require(report['originalExit']==0,'OFFLINE_CACHE_PREFLIGHT_FAILED')
        substage='MANIFEST'
        require(fixed_test_compilation_inputs()==compilation,'COMPILED_POSTIMAGE_DRIFT')
        report['testCompilation']=fixed_test_compilation_outputs(compilation)
        substage='CONTENT_SECURITY';report['controlledContentSecurity']=controlled_content_proof()
        require(compiler is not None and compiler.get('exitObserved') is True and compiler.get('exit')==0
                and compiler.get('proof')==report['controlledContentSecurity'],'CONTROLLED_CONTENT_SECURITY_PROOF_REQUIRED')
        substage='PROVIDER_RUNTIME';report['providerRuntime']=provider_runtime_proof(cache)
        substage='CONFIGURATION_UNIT';report['configurationUnit']=configuration_unit_proof(report['testCompilation'])
        substage='MANIFEST'
        report['cache']=cache_manifest(cache);require(cache_inputs()==report['pomSha256'],'COMPILED_POSTIMAGE_DRIFT')
    except Exception as failure:
        first=failure;report['firstFailure']=cache_failure(failure,'PINNED_CACHE_PREPARATION',substage)
        if report['originalExit'] in (None,0):
            report['originalExit']=failure.errno if isinstance(failure,InterruptedError) and failure.errno in (129,130,143) else 2
    finally:
        # Preserve bounded private diagnostics even on timeout/OOM/create failure, BEFORE exact cleanup.
        try:
            slot=state['slots'].get('app',{})
            if slot.get('cid'):
                obj=inspect(slot['cid']);validate_owner(obj,slot['name'],JDK,labels(os.environ,state['nonce']),slot['cid'])
                log=private/'cache.log'
                with log.open('xb') as stream:
                    os.chmod(log,0o600);logs=subprocess.run(DOCKER+['logs',slot['cid']],stdout=stream,stderr=subprocess.STDOUT,timeout=30)
                require(logs.returncode==0 and log.stat().st_size<32*1024**2,'PRIVATE_LOG_NOT_COMPLETE')
                with log.open('rb') as stream:
                    stream.seek(max(0,log.stat().st_size-1024*1024));tail=stream.read().decode(errors='replace');compiler_log_tail=tail
                    report['pluginFailure']=safe_plugin_resolution(tail);report['lastReviewedMavenGoal']=safe_cache_progress(tail)
                if obj['State']['Running']:
                    phase=command(DOCKER+['exec',slot['cid'],'cat','/tmp/cy-cache.phase']).decode().strip()
                    report['phase']=phase if phase in ('OFFLINE_PACKAGE_PROBE','ONLINE_PACKAGE','ORIGINAL_PROVIDER_GET',
                            'ONLINE_PROVIDER_RUNTIME','CONTENT_SECURITY_COMPILE','ONLINE_CONFIGURATION_UNITS','OFFLINE_PROVIDER_RUNTIME','OFFLINE_TEST_PREFLIGHT') else 'UNKNOWN'
                    if report['offlinePackageProbeExit'] is None and not report['mavenExitObserved']:
                        report['offlinePackageProbeExit']=cache_offline_probe_exit(slot['cid'],required=False)
        except Exception as failure:report['failures'].append(cache_failure(failure,'CACHE_PRIVATE_LOG','PRIVATE_LOG'))
        if first is not None and report.get('mavenExitObserved') is True and type(report.get('mavenExit')) is int and report['mavenExit']>0:
            # Independent of raw-log capture. Read fixed host reports while the exact owner still
            # exists, before cleanup; any diagnostic error remains UNKNOWN and changes no gate.
            try:
                slot=state['slots']['app'];obj=inspect(slot['cid'])
                validate_owner(obj,slot['name'],JDK,labels(os.environ,state['nonce']),slot['cid'])
                require(obj['State']['Running'],'CACHE_PROCESS_EXIT_BEFORE_RECEIPT')
                phase=command(DOCKER+['exec',slot['cid'],'cat','/tmp/cy-cache.phase']).decode().strip()
                if phase in ('OFFLINE_PACKAGE_PROBE','ONLINE_PACKAGE','ORIGINAL_PROVIDER_GET',
                             'ONLINE_PROVIDER_RUNTIME','ONLINE_CONFIGURATION_UNITS','OFFLINE_PROVIDER_RUNTIME','OFFLINE_TEST_PREFLIGHT'):report['phase']=phase
                report['compileFailure']=compile_failure_evidence(report,compilation,state,obj,phase,compiler_log_tail)
                report['preparationTests']=preparation_test_evidence(report,compilation,failed_postimage,state,obj,phase)
            except Exception:
                report.setdefault('compileFailure',{'purpose':'PREPARATION_COMPILER_DIAGNOSTIC_ONLY','businessAccepted':False,
                    'state':'UNKNOWN','unavailableReason':'EXIT_STAGE_SOURCE_OR_OWNER_UNPROVEN'})
                report['preparationTests']={'purpose':'PREPARATION_JUNIT_DIAGNOSTIC_ONLY','state':'UNKNOWN',
                    'businessAccepted':False,'unavailableReason':'EXIT_STAGE_SOURCE_OR_COMPILE_IDENTITY_UNPROVEN'}
        try:
            cleaned=cleanup_slot(private,state,'app');report['cleanup']=cleaned
        except Exception as failure:report['failures'].append(cache_failure(failure,'CACHE_CONTAINER_CLEANUP','CLEANUP'))
        if cleaned is not None:
            try:cache_terminal(report,cleaned,first is not None)
            except Exception as failure:report['failures'].append(cache_failure(failure,'CACHE_PROCESS_TERMINAL','TERMINAL'))
        accepted=first is None and report['mavenExitObserved'] and report['mavenExit']==report['originalExit']==0 and not report['failures'] and cleaned is not None and cleaned['result']=='EXACT_CID_REMOVED_AND_ABSENT'
        report['result']='PINNED_OFFLINE_TEST_PREFLIGHT_PROVEN' if accepted else 'NOT_ACCEPTED';publish()
        # A failed preparation retains its own private diagnostic journal; public never gets raw logs/settings.
        if accepted and not report['failures']:shutil.rmtree(private_directory(private))
    require(report['result']=='PINNED_OFFLINE_TEST_PREFLIGHT_PROVEN' and not report['failures'],'OFFLINE_CACHE_PREFLIGHT_FAILED')


def verified_cache(cache):
    require(CACHE_REPORT.is_file() and not CACHE_REPORT.is_symlink() and CACHE_REPORT.stat().st_size<2*1024*1024,'OFFLINE_CACHE_PREFLIGHT_REQUIRED')
    report=json.loads(CACHE_REPORT.read_text())
    require({k:report.get(k) for k in ('sourceSha','runId','attempt','class')}==identity(os.environ)
            and report.get('result')=='PINNED_OFFLINE_TEST_PREFLIGHT_PROVEN' and report.get('originalExit')==0
            and report.get('mavenExitObserved') is True and report.get('mavenExit')==0
            and type(report.get('offlinePackageProbeExit')) is int and 0<=report['offlinePackageProbeExit']<256
            and not report.get('failures') and report.get('image')==JDK and report.get('mavenVersion')==CORE_VERSION
            and report.get('settingsSha256')==hashlib.sha256(EMPTY_SETTINGS.encode()).hexdigest()
            and report.get('pomSha256')==cache_inputs() and report.get('cache')==cache_manifest(cache)
            and report.get('testCompilation')==fixed_test_compilation_outputs(fixed_test_compilation_inputs())
            and report.get('providerRuntime')==provider_runtime_proof(cache)
            and report.get('configurationUnit')==configuration_unit_proof(report['testCompilation'])
            and report.get('controlledContentSecurity')==controlled_content_proof()
            and report.get('controlledContentSecurityCompiler',{}).get('exitObserved') is True
            and report['controlledContentSecurityCompiler'].get('exit')==0
            and report['controlledContentSecurityCompiler'].get('proof')==report['controlledContentSecurity']
            and report['controlledContentSecurityCompiler'].get('testClasspath')==report['providerRuntime']['online']['testClasspath']
            and report.get('cleanup',{}).get('result')=='EXACT_CID_REMOVED_AND_ABSENT'
            and report['cleanup'].get('terminal',{}).get('OOMKilled') is False
            and report['cleanup'].get('terminal',{}).get('ExitCode') in (0,143)
            and re.fullmatch('sha256:[0-9a-f]{64}',report.get('imageId',''))
            and re.fullmatch('[0-9a-f]{64}',report.get('mavenCoreSha256',''))
            and report.get('budget',{}).get('memory')==report['budget'].get('memorySwap')==MEMORY
            and report['budget'].get('nanoCpus')==1000000000 and report['budget'].get('pids')==256,'OFFLINE_CACHE_PREFLIGHT_REQUIRED')
    validate_cgroup_limits(report['cgroup']['limits'])
    return {key:report[key] for key in ('image','imageId','mavenVersion','mavenCoreSha256','settingsSha256','cache','controlledContentSecurity')} | {'reportSha256':digest(CACHE_REPORT)}


def cache_stop_signal(number,_frame):
    require(number in (signal.SIGINT,signal.SIGTERM,signal.SIGHUP),'FIXED_CACHE_SIGNAL_ONLY')
    raise InterruptedError(128+number,'FIXED_CACHE_PREPARATION_STOP')


def resource_failure(failure, phase):
    value = failure.args[0] if isinstance(failure,ValueError) and failure.args else None
    return {'phase':phase,'type':type(failure).__name__,
            'reasonCode':value if isinstance(value,str) and value in SAFE_RESOURCE_CODES else 'UNCLASSIFIED_RESOURCE_FAILURE'}


def resource_envelope(env):
    # A failed identity gate still gets bounded UNKNOWN evidence, never untrusted raw env values.
    return {'sourceSha':env.get('SOURCE_SHA') if re.fullmatch('[0-9a-f]{40}',env.get('SOURCE_SHA','')) else None,
            'runId':env.get('GITHUB_RUN_ID') if re.fullmatch('[1-9][0-9]*',env.get('GITHUB_RUN_ID','')) else None,
            'attempt':env.get('GITHUB_RUN_ATTEMPT') if re.fullmatch('[1-9][0-9]*',env.get('GITHUB_RUN_ATTEMPT','')) else None,
            'class':CLASS,'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'businessExecution':'NOT_ATTESTED_BY_STORAGE_OWNER','wholeSystemCapacityAccepted':False,'q4q8Complete':False}


def mysql_names(env):
    return ('cy-http-'+env['GITHUB_RUN_ID']+'-'+env['GITHUB_RUN_ATTEMPT']+'-'+CLASS.lower(),
            'cy-http-budget-'+env['GITHUB_RUN_ID']+'-'+env['GITHUB_RUN_ATTEMPT'])


def exact_absence(kind,target,result):
    """One native inspect, one exact original target; never substring 'not found'."""
    require(kind in ('container','network') and isinstance(target,str) and
            re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]*',target),'DOCKER_INSPECTION_UNKNOWN')
    escaped=re.escape(target)
    patterns=[r'Error: No such '+kind+r': '+escaped]
    if kind=='container':
        patterns += [r'Error: No such object: '+escaped,r'Error response from daemon: No such container: '+escaped]
    else:patterns += [r'Error response from daemon: network '+escaped+r' not found']
    return (result.returncode==1 and re.fullmatch('|'.join(patterns),result.stderr.decode(errors='replace').strip()) is not None
            and result.stdout.strip() in (b'',b'[]'))


def native_operation(kind,target,result):
    # Safe result classification: no stdout/stderr or untrusted daemon message enters the public report.
    text=result.stderr.decode(errors='replace').lower()
    category=('OK' if result.returncode==0 else 'EXACT_NATIVE_TARGET_ABSENT' if exact_absence(kind,target,result)
              else 'DOCKER_UNAVAILABLE' if 'cannot connect to the docker daemon' in text
              else 'ACTIVE_ENDPOINTS' if 'has active endpoints' in text
              else 'PERMISSION_REJECTED' if 'permission denied' in text
              else 'UNCLASSIFIED_NATIVE_FAILURE')
    return {'exit':result.returncode,'category':category}


def object_or_absent(kind,target,observations=None):
    result=subprocess.run(DOCKER+[kind,'inspect',target],capture_output=True,timeout=15)
    if observations is not None:observations.append({'operation':'INSPECT','kind':kind,**native_operation(kind,target,result)})
    if result.returncode:
        require(exact_absence(kind,target,result),'DOCKER_INSPECTION_UNKNOWN')
        return None
    value=json.loads(result.stdout);require(len(value)==1,'DOCKER_INSPECTION_UNKNOWN');return value[0]


def owned_mysql(env):
    name,network=mysql_names(env)
    require(env.get('HTTP_MYSQL_CONTAINER')==name and env.get('HTTP_BUDGET_NETWORK')==network
            and re.fullmatch('[0-9a-f]{64}',env.get('HTTP_MYSQL_CID','')),'FIXED_BACKING_IDENTITY_REQUIRED')
    obj=object_or_absent('container',env['HTTP_MYSQL_CID'])
    if obj is None:
        absent('container',name);return None
    validate_owner(obj,name,MYSQL,labels(env),env['HTTP_MYSQL_CID'])
    require(inspect(name)['Id']==obj['Id'],'BACKING_NAME_CID_CHANGED')
    modes={network}
    if re.fullmatch('[0-9a-f]{64}',env.get('HTTP_BUDGET_NETWORK_ID','')):modes.add(env['HTTP_BUDGET_NETWORK_ID'])
    require(obj['HostConfig']['NetworkMode'] in modes,'BACKING_NETWORK_CONFIG_CHANGED')
    require(re.fullmatch('sha256:[0-9a-f]{64}',obj.get('Image','')),'BACKING_ACTUAL_IMAGE_ID_CHANGED')
    return obj


def owned_network(env,observations=None):
    _,name=mysql_names(env)
    require(env.get('HTTP_BUDGET_NETWORK')==name and re.fullmatch('[0-9a-f]{64}',env.get('HTTP_BUDGET_NETWORK_ID','')),
            'NETWORK_IDENTITY_REQUIRED')
    obj=object_or_absent('network',env['HTTP_BUDGET_NETWORK_ID'],observations)
    if obj is None:
        absent('network',name,observations);return None
    require(obj['Id']==env['HTTP_BUDGET_NETWORK_ID'] and obj['Name']==name
            and all(obj.get('Labels',{}).get(k)==v for k,v in labels(env).items()),'FOREIGN_NETWORK')
    require(json.loads(command(DOCKER+['network','inspect',name]))[0]['Id']==obj['Id'],'FOREIGN_NETWORK')
    return obj


def original_auth_owner():
    """Load the existing transport owner by fixed reviewed source path; no profile/URL/plugin selector."""
    spec=importlib.util.spec_from_file_location('combined_original_auth_transport',ROOT/'ci/authenticated_redis_recovery_probe.py')
    owner=importlib.util.module_from_spec(spec);spec.loader.exec_module(owner)
    return owner


def mysql_relay_endpoint():
    obj,proof=backing_mysql(os.environ)
    network=json.loads(command(DOCKER+['network','inspect',proof['networkId']]))[0]
    require(network['Id']==proof['networkId'] and network['Driver']=='bridge'
            and network.get('Options',{}).get('com.docker.network.bridge.gateway_mode_ipv4','nat')!='isolated',
            'OWNED_INTERNAL_NETWORK_REQUIRED')
    attachment=obj['NetworkSettings']['Networks'][mysql_names(os.environ)[1]]
    address=ipaddress.ip_address(attachment['IPAddress'])
    require(address.version==4 and address.is_private and not address.is_loopback
            and any(address in ipaddress.ip_network(c['Subnet']) for c in network['IPAM']['Config'] if ':' not in c['Subnet'])
            and obj['State']['Pid']>0 and not obj['State'].get('Paused') and not obj['State'].get('Restarting')
            and not obj['State'].get('OOMKilled'),'OWNED_INTERNAL_NETWORK_REQUIRED')
    return {'id':obj['Id'],'networkId':proof['networkId'],'ip':str(address),'pid':obj['State']['Pid']}


@contextlib.contextmanager
def mysql_host_path():
    identity_gate(os.environ)
    expected=mysql_relay_endpoint()
    owner=original_auth_owner()
    # The original owner rechecks the full CID/network/IP/PID before bind, every connection, and after use;
    # it closes and joins its exact sockets/threads. The shared review.patch adds only this fixed bind port.
    with owner._owned_mysql_relay(expected,mysql_relay_endpoint,loopback_port=33080) as server:
        require(server.server_address==('127.0.0.1',33080),'ORIGINAL_LOOPBACK_MYSQL_REQUIRED')
        yield {'result':'ORIGINAL_OWNED_MYSQL_RELAY','hostEndpoint':'127.0.0.1:33080',
               'mysqlCid':expected['id'],'networkId':expected['networkId'],'mysqlPid':expected['pid']}


def mysql_client_environment():
    env=dict(os.environ)
    for key in ('MYSQL_PWD','MYSQL_HOME','MYSQL_TEST_LOGIN_FILE'):env.pop(key,None)
    env['MYSQL_TEST_LOGIN_FILE']='/dev/null'
    return env


def mysql_client_arguments():
    # 8.0.45 supports MYSQL_TEST_LOGIN_FILE, not the 8.2+ --no-login-paths switch.
    return ['mysql','--defaults-file=/dev/stdin','--protocol=TCP','--host=127.0.0.1','--user=root',
            '--connect-timeout=1','--batch','--skip-column-names','--execute','SELECT @@server_uuid,VERSION(),@@port']


def namespace_mysql_arguments(cid,arguments):
    return DOCKER+['exec','-i','--env','MYSQL_TEST_LOGIN_FILE=/dev/null','--env','MYSQL_PWD=',
                   '--env','MYSQL_HOME=',cid]+arguments+['--port=3306']


def mysql_proof(obj,network_id):
    host=obj['HostConfig']
    return {'cid':obj['Id'],'image':obj['Config']['Image'],'imageId':obj['Image'],'networkId':network_id,
            'memory':host.get('Memory'),'memorySwap':host.get('MemorySwap'),'nanoCpus':host.get('NanoCpus'),'pids':host.get('PidsLimit')}


def safe_mysql_state(obj):
    state=obj['State']; health=state.get('Health',{})
    return {**{k:state.get(k) for k in ('Status','Running','Paused','Restarting','OOMKilled','Dead','Pid','ExitCode','StartedAt','FinishedAt')},
            'runtimeError':'PRESENT' if state.get('Error') else 'NONE',
            'health':{'status':health.get('Status') if health.get('Status') in ('starting','healthy','unhealthy') else 'NOT_CONFIGURED_OR_UNKNOWN',
                      'failingStreak':health.get('FailingStreak') if type(health.get('FailingStreak')) is int else None}}


def mysql_private_directory():
    OUT.mkdir(parents=True,exist_ok=True)
    if not MYSQL_PRIVATE.exists(): MYSQL_PRIVATE.mkdir(mode=0o700)
    require(not MYSQL_PRIVATE.is_symlink() and MYSQL_PRIVATE.is_dir()
            and MYSQL_PRIVATE.stat().st_mode & 0o777==0o700 and MYSQL_PRIVATE.stat().st_uid==os.getuid(),
            'PRIVATE_RESOURCE_PATH_UNSAFE')
    return MYSQL_PRIVATE


def classify_mysql_stderr(raw):
    # Emit only these categories, never client error text/usernames/queries or daemon messages.
    text=raw.decode(errors='replace').lower()
    for marker,code in (('error 1045','AUTHENTICATION_REJECTED'),('error 1040','CONNECTION_LIMIT'),
                        ('error 2003','TCP_CONNECT_FAILED'),('error 2002','CONNECT_FAILED'),
                        ('unknown option','CLIENT_OPTION_REJECTED'),('unknown variable','CLIENT_OPTION_REJECTED'),
                        ('cannot connect to the docker daemon','DOCKER_UNAVAILABLE'),('no such container','CONTAINER_NOT_FOUND')):
        if marker in text:return code
    return 'STDERR_PRESENT_UNCLASSIFIED' if raw else 'NO_STDERR'


def mysql_diagnostic(obj):
    declared=mysql_names(os.environ)[1];runtime=obj.get('NetworkSettings',{}).get('Networks',{})
    tmpfs=obj['HostConfig'].get('Tmpfs',{})
    result={'state':safe_mysql_state(obj),'configuredNetwork':obj['HostConfig'].get('NetworkMode'),
            'declaredNetworkEndpoint':{key:runtime.get(declared,{}).get(key) for key in ('NetworkID','EndpointID','IPAddress')},
            'additionalNetworkCount':len(set(runtime)-{declared}),
            'hostBudget':{key:obj['HostConfig'].get(key) for key in ('Memory','MemorySwap','NanoCpus','PidsLimit')},
            'mysqlTmpfs':{'present':'/var/lib/mysql' in tmpfs,'matchesConfigured768m':tmpfs.get('/var/lib/mysql')=='rw,size=768m'},
            'noDockerPublishedPorts':not obj['HostConfig'].get('PortBindings'),
            'rootCause':'UNDETERMINED_REQUIRES_OBSERVED_DIAGNOSTICS'}
    try:
        private=mysql_private_directory();log=private/'mysql.log'
        require(not log.is_symlink(),'PRIVATE_RESOURCE_PATH_UNSAFE')
        with log.open('wb') as stream:
            os.chmod(log,0o600)
            status=subprocess.run(DOCKER+['logs','--tail','2000',obj['Id']],stdout=stream,stderr=subprocess.STDOUT,timeout=15)
        with log.open('rb') as stream:
            size=log.stat().st_size;stream.seek(max(0,size-2*1024*1024));raw=stream.read()
        text=raw.decode(errors='replace')
        markers=('Starting temporary server','Temporary server started','Temporary server stopped',
                 'MySQL init process done. Ready for start up.','ready for connections',
                 'No space left on device','Cannot allocate memory','Out of memory')
        result['log']={'result':'PRIVATE_TAIL_SAVED' if status.returncode==0 else 'PRIVATE_LOG_COMMAND_FAILED',
                       'tailOnly':True,'sampleBytes':len(raw),'sampleSha256':hashlib.sha256(raw).hexdigest(),
                       'markers':{marker:marker in text for marker in markers},
                       'mysqlErrorCodes':sorted(set(re.findall(r'\bMY-[0-9]{6}\b',text)))[:64]}
    except Exception as failure:result['log']={'result':'UNKNOWN','type':type(failure).__name__}
    if obj['State'].get('Running') and obj['State'].get('Pid',0)>0:
        try:
            source_hash=command(DOCKER+['exec',obj['Id'],'sha256sum','/usr/local/bin/docker-entrypoint.sh']).decode().split()[0]
            require(re.fullmatch('[0-9a-f]{64}',source_hash),'UNCLASSIFIED_RESOURCE_FAILURE')
            result['entrypointSha256']=source_hash
        except Exception as failure:result['entrypointSha256']={'result':'UNKNOWN','type':type(failure).__name__}
        try:result['cgroup']=read_cgroup(obj['State']['Pid'],obj['Id'])
        except Exception as failure:result['cgroup']={'result':'UNKNOWN','type':type(failure).__name__}
        try:
            output=command(DOCKER+['exec',obj['Id'],'df','-Pk','/var/lib/mysql']).decode().splitlines()[-1].split()
            require(len(output)>=6 and all(x.isdigit() for x in output[1:4]),'UNCLASSIFIED_RESOURCE_FAILURE')
            result['disk']={'blocksKiB':int(output[1]),'usedKiB':int(output[2]),'availableKiB':int(output[3])}
        except Exception as failure:result['disk']={'result':'UNKNOWN','type':type(failure).__name__}
    else:
        result['cgroup']={'result':'UNKNOWN_PROCESS_NOT_RUNNING'};result['disk']={'result':'UNKNOWN_PROCESS_NOT_RUNNING'}
    result['hostDiskFreeBytes']=shutil.disk_usage(ROOT).free
    return result


def read_bootstrap(required=True):
    if not BOOTSTRAP_REPORT.is_file() or BOOTSTRAP_REPORT.is_symlink():
        require(not required,'BOOTSTRAP_NOT_VERIFIED');return None
    require(BOOTSTRAP_REPORT.stat().st_size<2*1024*1024,'BOOTSTRAP_NOT_VERIFIED')
    report=json.loads(BOOTSTRAP_REPORT.read_text())
    require({k:report.get(k) for k in ('sourceSha','runId','attempt','class')}==identity(os.environ),'BOOTSTRAP_SOURCE_MISMATCH')
    return report


def require_mysql_bootstrap():
    report=read_bootstrap()
    require(report.get('result')=='READY' and report.get('originalExit')==0 and not report.get('firstFailure')
            and report.get('readiness',{}).get('result')=='BOTH_LOOPBACK_TCP_IDENTITIES_VERIFIED'
            and report['readiness'].get('cid')==os.environ.get('HTTP_MYSQL_CID')
            and report.get('hostPath',{}).get('result')=='ORIGINAL_OWNED_MYSQL_RELAY'
            and report['hostPath'].get('mysqlCid')==report['readiness']['cid']
            and report['hostPath'].get('hostEndpoint')=='127.0.0.1:33080'
            and report['hostPath'].get('closedAfterReadiness') is True,'BOOTSTRAP_NOT_VERIFIED')
    return {'sourceSha':report['sourceSha'],'runId':report['runId'],'attempt':report['attempt'],'class':report['class'],
            'result':report['result'],'originalExit':report['originalExit'],'readiness':report['readiness'],'hostPath':report['hostPath'],
            'reportSha256':digest(BOOTSTRAP_REPORT)}


def bootstrap_init():
    PUBLIC.mkdir(parents=True,exist_ok=True)
    report={**resource_envelope(os.environ),'result':'NOT_COMPLETED','phase':'BOOTSTRAP_INIT',
            'readiness':{'result':'UNKNOWN'},'originalExit':None,'businessExecution':'NOT_ENTERED_BY_BOOTSTRAP'}
    try:
        identity_gate(os.environ)
        require(not BOOTSTRAP_REPORT.exists() and not BOOTSTRAP_REPORT.is_symlink(),'NEVER_ADOPT_PREVIOUS_BOOTSTRAP')
        write(BOOTSTRAP_REPORT,report)
        require(command(DOCKER+['context','inspect','--format','{{.Endpoints.docker.Host}}']).decode().strip()
                =='unix:///var/run/docker.sock','LOCAL_DOCKER_ONLY')
        mysql_private_directory();report['hostDiskFreeBytes']=shutil.disk_usage(ROOT).free
        report['phase']='SHELL_RESOURCE_CREATE';write(BOOTSTRAP_REPORT,report)
    except Exception as failure:
        report['firstFailure']=resource_failure(failure,'BOOTSTRAP_INIT');report['originalExit']=2
        report['result']='NOT_READY';write(BOOTSTRAP_REPORT,report);raise


def bootstrap_failure(original_exit):
    require(type(original_exit) is int and 1<=original_exit<=255,'ORIGINAL_SHELL_EXIT_REQUIRED')
    PUBLIC.mkdir(parents=True,exist_ok=True)
    report=read_bootstrap(required=False) or {**resource_envelope(os.environ),'readiness':{'result':'UNKNOWN'}}
    report.setdefault('firstFailure',{'phase':'SHELL_RESOURCE_CREATE','type':'ShellExit','reasonCode':'SHELL_BOOTSTRAP_FAILED'})
    if report.get('originalExit') in (None,0):report['originalExit']=original_exit
    report['result']='NOT_READY'
    write(BOOTSTRAP_REPORT,report) # Persist the original failure before slower private forensic capture.
    try:
        identity_gate(os.environ);net=owned_network(os.environ)
        if net is not None:report['network']={'cid':net['Id'],'name':net['Name']}
    except Exception as failure:report['networkCaptureFailure']=resource_failure(failure,'BOOTSTRAP_NETWORK_CAPTURE')
    write(BOOTSTRAP_REPORT,report)
    try:
        identity_gate(os.environ);obj=owned_mysql(os.environ)
        if obj is not None:
            report['mysql']=mysql_proof(obj,os.environ.get('HTTP_BUDGET_NETWORK_ID'))
            report['diagnostic']={'state':safe_mysql_state(obj)};write(BOOTSTRAP_REPORT,report)
            report['diagnostic']=mysql_diagnostic(obj)
    except Exception as failure:report['captureFailure']=resource_failure(failure,'BOOTSTRAP_FAILURE_CAPTURE')
    write(BOOTSTRAP_REPORT,report)


def mysql_ready_probe(cid,deadline,before_host=None):
    require(re.fullmatch('[0-9a-f]{48}',os.environ.get('CHENGYIN_MYBATIS_IT_PASSWORD',''))
            and os.environ.get('CHENGYIN_MYBATIS_IT_USER')=='root','ORIGINAL_SYNTHETIC_MYSQL_CREDENTIAL_REQUIRED')
    config=('[client]\npassword='+os.environ['CHENGYIN_MYBATIS_IT_PASSWORD']+'\n').encode()
    base=mysql_client_arguments()
    answers={};observed={};private=mysql_private_directory()
    # Do not open the original relay until MySQL is really accepting namespace TCP; expected init
    # connection-refused retries must not poison the relay owner's successful-phase invariant.
    for side,args in (('namespace',namespace_mysql_arguments(cid,base)),('host',base+['--port=33080'])):
        if side=='host' and 'namespace' not in answers:
            observed[side]={'result':'WAITING_FOR_NAMESPACE_TCP'};continue
        if side=='host' and before_host is not None:before_host()
        remaining=deadline-time.monotonic()
        if remaining<=0: observed[side]={'result':'DEADLINE_EXHAUSTED'};continue
        try:
            result=subprocess.run(args,input=config,capture_output=True,timeout=min(3,remaining),env=mysql_client_environment())
            observed[side]={'exit':result.returncode,'stderrCategory':classify_mysql_stderr(result.stderr)}
            code=re.search(rb'\bERROR\s+([0-9]{3,5})\b',result.stderr)
            if code:observed[side]['mysqlClientCode']=int(code[1])
            error_path=private/(side+'-probe.stderr');require(not error_path.is_symlink(),'PRIVATE_RESOURCE_PATH_UNSAFE')
            with error_path.open('wb') as stream:os.chmod(stream.name,0o600);stream.write(result.stderr)
            if result.returncode==0:
                values=result.stdout.decode().strip().split('\t')
                if len(values)==3 and re.fullmatch('[0-9a-f-]{36}',values[0]) and values[1].split('-')[0]=='8.0.45' and values[2]=='3306': answers[side]=values
                else:observed[side]['result']='INVALID_METADATA_RESPONSE'
        except subprocess.TimeoutExpired:observed[side]={'result':'CLIENT_TIMEOUT'}
    verified=bool(len(answers)==2 and answers['host']==answers['namespace'])
    return observed,({'cid':cid,'serverUuid':answers['host'][0],'version':answers['host'][1],
                      'hostEndpoint':'127.0.0.1:33080','executionEndpoint':'127.0.0.1:3306'} if verified else None)


def bootstrap_ready():
    report=read_bootstrap();deadline=time.monotonic()+90;report['phase']='MYSQL_TCP_READINESS';report['probes']=[]
    host_path=contextlib.ExitStack();host_proof=None
    def before_host():
        nonlocal host_proof
        if host_proof is None:host_proof=host_path.enter_context(mysql_host_path());report['hostPath']=host_proof
    try:
        identity_gate(os.environ)
        for attempt in range(1,91):
            obj,proof=backing_mysql(os.environ);report['mysql']=proof
            observed,address=mysql_ready_probe(obj['Id'],deadline,before_host)
            report['probes'].append({'attempt':attempt,'elapsedSeconds':90-max(0,deadline-time.monotonic()),'endpoints':observed})
            if address is not None:
                require(time.monotonic()<=deadline,'MYSQL_TCP_READY_BUDGET_EXHAUSTED')
                report.update(result='READY',originalExit=0,readiness={'result':'BOTH_LOOPBACK_TCP_IDENTITIES_VERIFIED',**address})
                write(BOOTSTRAP_REPORT,report)
                current=owned_mysql(os.environ)
                require(current is not None and current['State'].get('Running') is True,'MYSQL_PROCESS_EXITED_BEFORE_READY')
                report['diagnostic']={'state':safe_mysql_state(current)}
                try:report['diagnostic']=mysql_diagnostic(current)
                except Exception as capture:report['captureFailure']=resource_failure(capture,'MYSQL_READY_DIAGNOSTIC')
                host_path.close();report['hostPath']['closedAfterReadiness']=True
                write(BOOTSTRAP_REPORT,report);return 0
            write(BOOTSTRAP_REPORT,report)
            remaining=deadline-time.monotonic()
            if remaining<=0:break
            time.sleep(min(1,remaining))
        raise ValueError('MYSQL_TCP_READY_BUDGET_EXHAUSTED')
    except Exception as failure:
        report.update(result='NOT_READY',originalExit=1)
        report.setdefault('firstFailure',resource_failure(failure,'MYSQL_TCP_READINESS'))
        write(BOOTSTRAP_REPORT,report)
        try:
            obj=owned_mysql(os.environ)
            if obj is not None:
                report['diagnostic']={'state':safe_mysql_state(obj)};write(BOOTSTRAP_REPORT,report)
                report['diagnostic']=mysql_diagnostic(obj)
        except Exception as capture:report['captureFailure']=resource_failure(capture,'MYSQL_FAILURE_CAPTURE')
        write(BOOTSTRAP_REPORT,report)
        print('Combined MySQL bootstrap: '+json.dumps(report['firstFailure'],sort_keys=True),file=__import__('sys').stderr)
        return 1
    finally:
        # No listener/relay survives a workflow step. Errors remain failures, never a fallback endpoint.
        host_path.close()


def run_host_path():
    identity_gate(os.environ);bootstrap=require_mysql_bootstrap()
    report={**resource_envelope(os.environ),'result':'NOT_COMPLETED','originalExit':None}
    def publish():
        try:write(PUBLIC/'combined-http-host-path.json',report)
        except Exception as failure:
            print('Combined host path evidence: '+json.dumps(resource_failure(failure,'HOST_PATH_EVIDENCE_WRITE'),sort_keys=True),
                  file=__import__('sys').stderr)
            if report['originalExit'] in (None,0):raise
    try:
        with mysql_host_path() as transport:
            report['transport']=transport
            require(transport['mysqlCid']==bootstrap['readiness']['cid'],'BOOTSTRAP_NOT_VERIFIED')
            require(mysql_address_proof(transport['mysqlCid'])=={key:value for key,value in bootstrap['readiness'].items() if key!='result'},
                    'BOOTSTRAP_NOT_VERIFIED')
            env=dict(os.environ,MYSQL_TEST_LOGIN_FILE='/dev/null');env.pop('MYSQL_HOME',None)
            result=subprocess.run(['bash',str(ROOT/'chengyinhub-admin/src/test/tools/generic-gameplay-http-e2e.sh')],env=env)
            # Original fixed shell keeps creation/test/finalize/schema cleanup inside this same path.
            report['originalExit']=result.returncode if result.returncode>=0 else 128-result.returncode
        report['result']='ORIGINAL_SHELL_AND_HOST_PATH_CLOSED' if report['originalExit']==0 else 'ORIGINAL_SHELL_FAILED'
        publish()
        return report['originalExit']
    except Exception as failure:
        report.update(result='NOT_ACCEPTED',failure=resource_failure(failure,'FIXED_HOST_PATH'))
        publish()
        if report['originalExit'] not in (None,0):return report['originalExit']
        raise


def identity_gate(env):
    require(env.get('GITHUB_ACTIONS') == 'true' and env.get('RUNNER_ENVIRONMENT') == 'github-hosted'
            and env.get('GITHUB_REPOSITORY') == 'liboyang42-cpu/hengyin'
            and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and env.get('HTTP_SCOPE') == 'controlled-combination'
            and env.get('CHENGYIN_HTTP_E2E_PROFILE') == 'github-hosted'
            and env.get('CHENGYIN_HTTP_E2E_TESTS') == CLASS and not env.get('CHENGYIN_VPS_TEST_RUN'), 'FIXED_HOSTED_CLASS_ONLY')
    require(re.fullmatch('[0-9a-f]{40}', env.get('SOURCE_SHA','')), 'FULL_SOURCE_REQUIRED')
    require(all(re.fullmatch('[1-9][0-9]*', env.get(k,'')) for k in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT')), 'RUN_ATTEMPT_REQUIRED')


def gate(env):
    identity_gate(env)
    require(env.get('CHENGYIN_MYBATIS_IT_HOST') == '127.0.0.1' and env.get('CHENGYIN_MYBATIS_IT_PORT') == '33080', 'ORIGINAL_LOOPBACK_MYSQL_REQUIRED')
    require(env.get('B_PAYMENT_FACT_DDL_APPROVED') == '1', 'ORIGINAL_DDL_GATE_REQUIRED')
    require(env.get('CHENGYIN_AI_REDIS_IT_DATABASE') == '15', 'ORIGINAL_COMBINED_REDIS_DB_REQUIRED')
    require(env.get('CHENGYIN_MYBATIS_IT_USER') == 'root' and re.fullmatch('[0-9a-f]{48}',env.get('CHENGYIN_MYBATIS_IT_PASSWORD','')),
            'ORIGINAL_SYNTHETIC_MYSQL_CREDENTIAL_REQUIRED')
    port=env.get('CHENGYIN_HTTP_E2E_REDIS_PORT','')
    require(re.fullmatch('[0-9]{1,5}',port) and 1<=int(port)<=65535
            and env.get('CHENGYIN_AI_REDIS_IT_PORT') == port, 'SAME_EXPLICIT_PRIVATE_REDIS_REQUIRED')
    require(not env.get('CHENGYIN_AI_REDIS_IT_PASSWORD'), 'ORIGINAL_CASHLESS_PRIVATE_REDIS_ONLY')


def identity(env):
    return {'sourceSha':env['SOURCE_SHA'], 'runId':env['GITHUB_RUN_ID'], 'attempt':env['GITHUB_RUN_ATTEMPT'], 'class':CLASS}


def labels(env, nonce=None):
    result = {'chengyin.http.run':env['GITHUB_RUN_ID'], 'chengyin.http.attempt':env['GITHUB_RUN_ATTEMPT'],
              'chengyin.http.source':env['SOURCE_SHA'], 'chengyin.http.class':CLASS}
    if nonce: result['chengyin.http.budget'] = nonce
    return result


def inspect(target):
    value = json.loads(command(DOCKER + ['inspect', target]))
    require(len(value) == 1 and re.fullmatch('[0-9a-f]{64}', value[0].get('Id','')), 'ACTUAL_ID_REQUIRED')
    return value[0]


def absent(kind, target, observations=None):
    result = subprocess.run(DOCKER + [kind, 'inspect', target], capture_output=True, timeout=15)
    if observations is not None:observations.append({'operation':'ABSENCE_INSPECT','kind':kind,**native_operation(kind,target,result)})
    require(exact_absence(kind,target,result),'ABSENCE_NOT_INDEPENDENTLY_PROVEN')
    return {'exit':result.returncode,'category':'EXACT_NATIVE_TARGET_ABSENT'}


def validate_container(obj, name, image, expected_labels, memory, network, cid=None):
    validate_owner(obj, name, image, expected_labels, cid)
    host = obj['HostConfig']
    require(host['Memory'] == memory and host['MemorySwap'] == memory and host['NanoCpus'] == 1000000000
            and host['PidsLimit'] == 256, 'HARD_BUDGET_MISMATCH')
    require(not host.get('Privileged') and not host.get('PidMode') and not host.get('CapAdd')
            and set(host.get('CapDrop',[])) == {'ALL'} and not host.get('PortBindings'), 'UNSAFE_CONTAINER_CAPABILITY')
    require(host['NetworkMode'] == network, 'WRONG_OWNED_NETWORK_NAMESPACE')
    return {'cid':obj['Id'], 'image':obj['Config']['Image'], 'imageId':obj['Image'],
            'memory':host['Memory'], 'memorySwap':host['MemorySwap'], 'nanoCpus':host['NanoCpus'],
            'pids':host['PidsLimit'], 'networkMode':host['NetworkMode']}


def validate_owner(obj, name, image, expected_labels, cid=None):
    require(re.fullmatch('[0-9a-f]{64}', obj.get('Id','')) and (cid is None or obj['Id'] == cid), 'ACTUAL_CID_MISMATCH')
    require(obj.get('Name') == '/'+name and obj['Config']['Image'] == image, 'FOREIGN_CONTAINER_OR_IMAGE')
    require(all(obj['Config'].get('Labels',{}).get(k) == v for k,v in expected_labels.items()), 'FOREIGN_LABEL')


def backing_mysql(env, cleanup=False):
    expected_name = 'cy-http-'+env['GITHUB_RUN_ID']+'-'+env['GITHUB_RUN_ATTEMPT']+'-'+CLASS.lower()
    network_name = 'cy-http-budget-'+env['GITHUB_RUN_ID']+'-'+env['GITHUB_RUN_ATTEMPT']
    require(env.get('HTTP_MYSQL_CONTAINER') == expected_name and env.get('HTTP_BUDGET_NETWORK') == network_name
            and re.fullmatch('[0-9a-f]{64}',env.get('HTTP_MYSQL_CID','')), 'FIXED_BACKING_IDENTITY_REQUIRED')
    obj = inspect(env['HTTP_MYSQL_CID']); host = obj['HostConfig']
    require(obj['Name'] == '/'+expected_name and obj['Config']['Image'] == MYSQL
            and all(obj['Config']['Labels'].get(k) == v for k,v in labels(env).items()), 'BACKING_MYSQL_FOREIGN')
    require(inspect(expected_name)['Id'] == obj['Id'], 'BACKING_NAME_CID_CHANGED')
    require(json.loads(command(DOCKER+['image','inspect',MYSQL]))[0]['Id']==obj['Image'],'BACKING_ACTUAL_IMAGE_ID_CHANGED')
    if not cleanup:
        require(host['Memory'] == MEMORY and host['MemorySwap'] == MEMORY and host['NanoCpus'] == 1000000000
                and host['PidsLimit'] == 256 and not host.get('Privileged'), 'BACKING_MYSQL_BUDGET_MISMATCH')
        require(not host.get('PortBindings')
                and set(obj['NetworkSettings']['Networks']) == {network_name}, 'BACKING_LOOPBACK_SCOPE_CHANGED')
        require(host.get('Tmpfs',{}).get('/var/lib/mysql')=='rw,size=768m'
                and not any(m.get('Type')=='volume' for m in obj.get('Mounts',[])),'BACKING_MYSQL_EXACT_EPHEMERAL_STORAGE_REQUIRED')
    net = json.loads(command(DOCKER + ['network','inspect',network_name]))[0]
    require(net['Internal'] is True and all(net['Labels'].get(k) == v for k,v in labels(env).items())
            and net['Id']==env.get('HTTP_BUDGET_NETWORK_ID')
            and obj['NetworkSettings']['Networks'][network_name]['NetworkID'] == net['Id'], 'OWNED_INTERNAL_NETWORK_REQUIRED')
    if not cleanup: require(obj['State']['Running'] is True, 'BACKING_MYSQL_NOT_RUNNING')
    return obj, {'cid':obj['Id'],'image':MYSQL,'imageId':obj['Image'],'networkId':net['Id'],
                 'memory':host['Memory'],'memorySwap':host['MemorySwap'],'nanoCpus':host['NanoCpus'],'pids':host['PidsLimit']}


def private_directory(path):
    path = Path(path)
    require(path.is_absolute() and path.parent == Path('/tmp') and not path.is_symlink()
            and path.resolve() == path and re.fullmatch(r'cy-http-budget\.[A-Za-z0-9]{8}',path.name)
            and path.is_dir() and path.stat().st_mode & 0o777 == 0o700
            and path.stat().st_uid == os.getuid(), 'EXACT_PRIVATE_DIRECTORY_REQUIRED')
    return path


def source_manifest():
    require(command(['git','-C',str(ROOT),'rev-parse','HEAD']).decode().strip() == os.environ['SOURCE_SHA'], 'WRONG_CHECKOUT')
    require(subprocess.run(['git','-C',str(ROOT),'diff','--quiet','HEAD','--'],capture_output=True).returncode == 0, 'DIRTY_SOURCE')
    props = ROOT/'chengyinhub-admin/target/classes/git.properties'
    values = dict(line.split('=',1) for line in props.read_text().splitlines() if '=' in line and not line.startswith('#'))
    require(values.get('git.commit.id',values.get('git.commit.id.full')) == os.environ['SOURCE_SHA'], 'COMPILED_SOURCE_MISMATCH')
    selected = ROOT/'chengyinhub-admin/target/test-classes/com/chengyinhub/business/it'/ (CLASS+'.class')
    require(selected.is_file() and not selected.is_symlink(), 'PRECOMPILED_FIXED_CLASS_REQUIRED')
    jar = ROOT/'chengyinhub-admin/target/chengyinhub-admin.jar'
    require(jar.is_file() and not jar.is_symlink(),'ORIGINAL_SAME_SOURCE_PACKAGED_JAR_REQUIRED')
    with zipfile.ZipFile(jar) as archive:
        properties = dict(line.split('=',1) for line in archive.read('BOOT-INF/classes/git.properties').decode().splitlines()
                          if '=' in line and not line.startswith('#'))
    require(properties.get('git.commit.id',properties.get('git.commit.id.full')) == os.environ['SOURCE_SHA'],'PACKAGED_SOURCE_MISMATCH')
    trees = {}; artifacts = {}
    for module in MODULES:
        paths = sorted(p for area in ('classes','test-classes') for p in (ROOT/module/'target'/area).rglob('*.class'))
        require(paths, 'PRECOMPILED_REACTOR_REQUIRED')
        rows = []
        for path in paths:
            require(not path.is_symlink(), 'SYMLINK_COMPILED_CLASS')
            rows.append((str(path.relative_to(ROOT)),digest(path)))
        trees[module] = {'classes':len(rows),'sha256':hashlib.sha256(json.dumps(rows,separators=(',',':')).encode()).hexdigest()}
        jars = sorted((ROOT/module/'target').glob('*.jar')); require(jars,'ORIGINAL_PACKAGED_REACTOR_REQUIRED')
        for artifact in jars:
            require(not artifact.is_symlink(),'SYMLINK_REACTOR_ARTIFACT');artifacts[str(artifact.relative_to(ROOT))]=digest(artifact)
    return {'classTrees':trees,'reactorJars':artifacts,'selectedClassSha256':digest(selected),'gitPropertiesSha256':digest(props),'artifactJarSha256':digest(jar),
            'controlledContentSecurity':controlled_content_proof(),
            'testCompilation':fixed_test_compilation_outputs(fixed_test_compilation_inputs()),
            'freshSchemaReportSha256':digest(OUT/'fresh-schema.json'),
            'execution':'ORIGINAL_SUREFIRE_TEST_CLASSPATH_NOT_PACKAGED_APPLICATION_JAR'}


def child_environment(env):
    # No host PATH/HOME, settings.xml, tokens, SDK credentials, JAVA_TOOL_OPTIONS or arbitrary env.
    child = {k:env[k] for k in ('GITHUB_ACTIONS','GITHUB_REPOSITORY','GITHUB_EVENT_NAME','GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT',
             'RUNNER_ENVIRONMENT','SOURCE_SHA','CHENGYIN_HTTP_E2E_PROFILE','CHENGYIN_HTTP_E2E_TESTS',
             'CHENGYIN_MYBATIS_IT_USER','CHENGYIN_MYBATIS_IT_PASSWORD','CHENGYIN_HTTP_E2E_DATABASE',
             'CHENGYIN_HTTP_E2E_SCHEMA_REPORT','B_PAYMENT_FACT_DDL_APPROVED','CHENGYIN_AI_REDIS_IT_DATABASE')}
    require(re.fullmatch(r'cy_e2e_w1_http_[0-9_]+',child['CHENGYIN_HTTP_E2E_DATABASE']), 'ORIGINAL_SCHEMA_SCOPE_REQUIRED')
    require(Path(child['CHENGYIN_HTTP_E2E_SCHEMA_REPORT']) == OUT/'fresh-schema.json','ORIGINAL_SCHEMA_REPORT_REQUIRED')
    child.update(CHENGYIN_MYBATIS_IT_HOST='127.0.0.1',CHENGYIN_MYBATIS_IT_PORT='3306',
                 CHENGYIN_HTTP_E2E_REDIS_PORT=env['CHENGYIN_HTTP_E2E_REDIS_PORT'],CHENGYIN_AI_REDIS_IT_PORT=env['CHENGYIN_HTTP_E2E_REDIS_PORT'],
                 SERVER_ADDRESS='127.0.0.1',TZ='Asia/Shanghai',HOME='/tmp/home',MAVEN_CONFIG='/tmp/m2',
                 GITHUB_SHA=env['SOURCE_SHA'],
                 MAVEN_OPTS=RUNTIME_MAVEN_OPTIONS)
    require(all('\n' not in value and '\r' not in value and '\0' not in value for value in child.values()), 'INVALID_PRIVATE_ENV')
    return child


def load_private(path):
    path = private_directory(path); state = path/'budget.json'
    require(state.is_file() and not state.is_symlink() and state.stat().st_mode & 0o777 == 0o600, 'PRIVATE_STATE_REQUIRED')
    value = json.loads(state.read_text()); require(value['identity'] == identity(os.environ), 'WRONG_SOURCE_RUN_STATE')
    require(value['redisPort']==int(os.environ['CHENGYIN_HTTP_E2E_REDIS_PORT'])
            and value['schema']==os.environ['CHENGYIN_HTTP_E2E_DATABASE'],'WRONG_PRIVATE_RUNTIME_SCOPE')
    return path,value


def save(path,value): write(path/'budget.json',value)


def create_slot(path,state,role,image,argv,mounts=(),env_file=None):
    name = state['name']+'-'+role; absent('container',name)
    state['slots'][role] = {'name':name,'attempted':True,'cid':None}; save(path,state)
    args = DOCKER+['create','--name',name,'--network','container:'+state['mysql']['cid'],
            '--memory='+('64m' if role == 'redis' else '1g'),'--memory-swap='+('64m' if role == 'redis' else '1g'),
            '--cpus=1','--pids-limit=256','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true',
            '--tmpfs','/tmp:rw,size='+('32m' if role == 'redis' else '256m'),
            '--user',str(os.getuid())+':'+str(os.getgid()),'--cidfile',str(path/(role+'.cid'))]
    for key,value in labels(os.environ,state['nonce']).items(): args += ['--label',key+'='+value]
    for source,target,mode in mounts: args += ['--mount','type=bind,src='+str(source)+',dst='+str(target)+','+mode]
    if env_file: args += ['--env-file',str(env_file)]
    if role == 'app': args += ['--workdir',str(ROOT),'--entrypoint','/bin/bash']
    else: args += ['--entrypoint','redis-server']
    cid = command(args+[image]+argv,timeout=30).decode().strip()
    require(re.fullmatch('[0-9a-f]{64}',cid) and (path/(role+'.cid')).read_text().strip() == cid, 'CREATED_CID_NOT_PROVEN')
    state['slots'][role]['cid'] = cid; save(path,state)
    obj = inspect(cid)
    state['slots'][role]['budget'] = validate_container(obj,name,image,labels(os.environ,state['nonce']),
            67108864 if role == 'redis' else MEMORY,'container:'+state['mysql']['cid'],cid)
    require(obj['Image']==state['imageIds'][role],'ACTUAL_PINNED_IMAGE_ID_CHANGED')
    require(inspect(name)['Id'] == cid,'CREATED_NAME_ID_CHANGED')
    save(path,state); command(DOCKER+['start',cid]); return cid


def prepare(path):
    gate(os.environ); path = private_directory(path)
    require(command(DOCKER+['context','inspect','--format','{{.Endpoints.docker.Host}}']).decode().strip()
            == 'unix:///var/run/docker.sock','LOCAL_DOCKER_ONLY')
    require(not (path/'budget.json').exists(),'NEVER_ADOPT_PREVIOUS_STATE')
    require(not (OUT/'run.log').exists() and not any((PUBLIC/name).exists() for name in
            ('combined-http-budget.json','combined-http-storage-cleanup.json')),'NEVER_ADOPT_PREVIOUS_OUTPUT')
    mysql,proof = backing_mysql(os.environ)
    bootstrap=require_mysql_bootstrap()
    address=mysql_address_proof(mysql['Id'])
    require(all(address.get(key)==bootstrap['readiness'].get(key) for key in
            ('cid','serverUuid','version','hostEndpoint','executionEndpoint')),'BOOTSTRAP_NOT_VERIFIED')
    cache = Path.home()/'.m2/repository'
    require(cache.is_dir() and not cache.is_symlink(), 'EXISTING_OFFLINE_CACHE_REQUIRED')
    cache_proof=verified_cache(cache)
    require((cache/'org/apache/maven/surefire/surefire-junit-platform/3.5.6/surefire-junit-platform-3.5.6.jar').is_file(),
            'EXISTING_OFFLINE_SUREFIRE_PROVIDER_REQUIRED')
    images = {image:json.loads(command(DOCKER+['image','inspect',image]))[0] for image in (JDK,REDIS)} # Never pull here.
    require(images[JDK]['Id']==cache_proof['imageId'],'FIXED_CACHE_TOOLCHAIN_REQUIRED')
    nonce = uuid.uuid4().hex
    state = {'identity':identity(os.environ),'nonce':nonce,
             'name':'cy-http-combined-'+os.environ['GITHUB_RUN_ID']+'-a'+os.environ['GITHUB_RUN_ATTEMPT']+'-'+nonce[:12],
             'mysql':proof,'mysqlHostPid':mysql['State']['Pid'],'cache':str(cache.resolve()),'cacheProof':cache_proof,
             'mysqlBootstrap':bootstrap,
             'redisPort':int(os.environ['CHENGYIN_HTTP_E2E_REDIS_PORT']),'schema':os.environ['CHENGYIN_HTTP_E2E_DATABASE'],
             'source':None,'slots':{},'javaProcesses':[],'result':'NOT_ACCEPTED','phase':'PREPARE',
             'imageIds':{'app':images[JDK]['Id'],'redis':images[REDIS]['Id']},
             'jdkEnvironment':dict(item.split('=',1) for item in images[JDK]['Config'].get('Env',[]))}
    save(path,state)
    require(command(['git','-C',str(ROOT),'rev-parse','HEAD']).decode().strip()==os.environ['SOURCE_SHA'],'WRONG_PREPARATION_SOURCE')
    environment = child_environment(os.environ)
    with (path/'private.env').open('x') as stream:
        os.chmod(path/'private.env',0o600);stream.write(''.join(k+'='+v+'\n' for k,v in sorted(environment.items())))
    (path/'launch').mkdir(mode=0o700)
    probe = ROOT/'ci/CombinedHttpNamespacePortProbe.java'
    (path/'launch/CombinedHttpNamespacePortProbe.java').write_bytes(probe.read_bytes())
    os.chmod(path/'launch/CombinedHttpNamespacePortProbe.java',0o600)
    state['namespaceProbeSourceSha256']=digest(probe);save(path,state)
    (path/'launch/settings.xml').write_text(EMPTY_SETTINGS)
    os.chmod(path/'launch/settings.xml',0o600)
    cid = create_slot(path,state,'redis',REDIS,['--bind','127.0.0.1','--port',str(state['redisPort']),'--protected-mode','yes',
                                              '--save','','--appendonly','no','--dir','/tmp'])
    ready = False
    for _ in range(50):
        result = subprocess.run(DOCKER+['exec',cid,'redis-cli','-h','127.0.0.1','-p',str(state['redisPort']),'-n','15','PING'],capture_output=True,timeout=5)
        if result.returncode == 0 and result.stdout.strip() == b'PONG': ready=True;break
        require(inspect(cid)['State']['Running'],'PRIVATE_REDIS_EXITED')
        time.sleep(.1)
    require(ready,'PRIVATE_BOUNDED_REDIS_NOT_READY'); state['phase']='PREPARED';save(path,state)


def cgroup_memory_usage(root):
    # Bounded latest observation only. file includes shmem; these counters are not additive attribution.
    result={'currentBytes':None,'peakBytes':None,'stat':{key:None for key in
            ('anon','file','shmem','kernel','kernel_stack','pagetables','sock')}}
    for filename,key in (('memory.current','currentBytes'),('memory.peak','peakBytes')):
        try:
            with (root/filename).open() as stream:raw=stream.read(26)
            value=raw.strip()
            if len(raw)<26 and len(value)<=24 and value.isdigit():result[key]=int(value)
        except (OSError,UnicodeError):pass # Optional observation; mandatory limits/OOM events are never suppressed.
    try:
        with (root/'memory.stat').open() as stream:text=stream.read(65537)
        if len(text)<=65536:
            for line in text.splitlines():
                values=line.split()
                if len(values)==2 and values[0] in result['stat'] and len(values[1])<=24 and values[1].isdigit():result['stat'][values[0]]=int(values[1])
    except (OSError,UnicodeError):pass
    result['state']='OBSERVED' if all(value is not None for value in
            (result['currentBytes'],result['peakBytes'],*result['stat'].values())) else 'PARTIAL_OR_UNKNOWN'
    return result


def read_cgroup(pid, cid):
    text = Path('/proc',str(pid),'cgroup').read_text()
    lines = text.splitlines(); require(len(lines) == 1 and lines[0].startswith('0::/'), 'CGROUP_V2_REQUIRED')
    relative = lines[0][3:]; require(cid in relative and '..' not in Path(relative).parts, 'PID_OUTSIDE_EXACT_CGROUP')
    root = Path('/sys/fs/cgroup')/relative.lstrip('/')
    require(root.resolve().is_relative_to(Path('/sys/fs/cgroup')), 'FOREIGN_CGROUP_PATH')
    limits = {name:(root/name).read_text().strip() for name in ('memory.max','memory.swap.max','cpu.max','pids.max')}
    validate_cgroup_limits(limits)
    events = dict(line.split() for line in (root/'memory.events').read_text().splitlines())
    require('oom_kill' in events and all(value.isdigit() for value in events.values()),'ACTUAL_OOM_COUNTER_REQUIRED')
    return {'pathSha256':hashlib.sha256(relative.encode()).hexdigest(),'limits':limits,'memoryEvents':events,
            'memoryUsage':cgroup_memory_usage(root)}


def validate_cgroup_limits(limits):
    require(limits['memory.max'] == str(MEMORY) and limits['memory.swap.max'] == '0'
            and limits['pids.max'] == '256','ACTUAL_CGROUP_MEMORY_SWAP_PIDS_MISMATCH')
    cpu = limits['cpu.max'].split()
    require(len(cpu) == 2 and all(x.isdigit() for x in cpu) and int(cpu[0]) == int(cpu[1]) > 0,
            'ACTUAL_CGROUP_CPU_MISMATCH')


EXECUTION_SUBSTAGES=frozenset({'BACKING_IDENTITY','SOURCE_MANIFEST','MYSQL_ADDRESS','APP_CREATE','APP_LIMITS',
    'APP_INPUTS','NETWORK_NAMESPACE','APP_RELEASE','APP_INSPECT','APP_OWNER','APP_LIVE','DEADLINE',
    'JAVA_SAMPLE','JAVA_CGROUP','MYSQL_PROCESS','PROCESS_LIST','JAVA_PROC','JAVA_PROC_CGROUP',
    'JAVA_NAMESPACE_PID','SUREFIRE_BOOTER_BINDING','JVM_MEMORY_ARGUMENTS','MAVEN_COMMAND','CGROUP_OOM','MAVEN_STATUS',
    'MAVEN_STATUS_VALUE','PRIVATE_LOG','POSTIMAGE','CACHE_RECHECK','REQUIRED_JVM_ROLES','APP_OOM',
    'FINAL_MYSQL_ADDRESS','NAMESPACE_PORT_PROOF'})
EXECUTION_FAILURE_FUNCTIONS=frozenset({'run_owned_app','sample_java','booter_binding','jvm_memory_binding','read_cgroup',
    'validate_cgroup_limits','validate_container','validate_owner','validate_app_inputs','inspect','command',
    'source_manifest','verified_cache','mysql_address_proof','backing_mysql','create_slot','namespace_port_proof'})


def execution_failure(failure,phase,substage):
    # Only fixed owner predicates and reviewed source locations, never str(failure), argv or raw logs.
    value=failure.args[0] if isinstance(failure,ValueError) and failure.args else None
    result={'phase':phase if phase in ('PREPARE','PREPARED','APP_CREATE','APP_EXECUTION','MAVEN_EXITED_AWAIT_CLEANUP',
            'NAMESPACE_PORT_PROOF') else 'UNKNOWN',
            'substage':substage if substage in EXECUTION_SUBSTAGES else 'UNKNOWN',
            'type':type(failure).__name__ if type(failure).__name__ in ('ValueError','OSError','FileNotFoundError',
                'PermissionError','TimeoutExpired','JSONDecodeError','KeyError','TypeError','UnicodeDecodeError',
                'BadZipFile','StopIteration') else 'UNKNOWN',
            'reasonCode':value if isinstance(value,str) and value in SAFE_RESOURCE_CODES else 'UNCLASSIFIED_RESOURCE_FAILURE'}
    trace=failure.__traceback__;origin=None
    while trace is not None:
        if trace.tb_frame.f_code.co_filename==__file__ and trace.tb_frame.f_code.co_name in EXECUTION_FAILURE_FUNCTIONS:
            origin={'function':trace.tb_frame.f_code.co_name,'line':trace.tb_lineno}
        trace=trace.tb_next
    if origin is not None:result['origin']=origin
    return result


def booter_binding(argv, cache):
    require(argv.count('-jar')==1 and argv.index('-jar')+1 < len(argv), 'ORIGINAL_SUREFIRE_FORK_REQUIRED')
    path = Path(argv[argv.index('-jar')+1])
    require(path.resolve().is_relative_to(ROOT/'chengyinhub-admin/target') and path.name.startswith('surefirebooter-')
            and path.suffix == '.jar' and path.is_file() and not path.is_symlink() and path.stat().st_size < 16*1024*1024,
            'SAME_TARGET_BOOTER_REQUIRED')
    with zipfile.ZipFile(path) as archive:
        entries=[info for info in archive.infolist() if info.filename=='META-INF/MANIFEST.MF']
        require(len(entries)==1 and entries[0].file_size<1024*1024,'BOUNDED_BOOTER_MANIFEST_REQUIRED')
        raw = archive.read(entries[0])
    # Surefire folds manifest lines. Decode only its fixed local Class-Path, never a remote URL.
    text = raw.decode().replace('\r\n','\n').replace('\n ','')
    main=text.split('\n\n',1)[0].splitlines()
    require([x[len('Main-Class: '):] for x in main if x.startswith('Main-Class: ')]==
            ['org.apache.maven.surefire.booter.ForkedBooter'],'ORIGINAL_SUREFIRE_MAIN_REQUIRED')
    lines=[x[len('Class-Path: '):] for x in main if x.startswith('Class-Path: ')]
    require(len(lines)==1 and lines[0].split(),'REAL_TEST_CLASSPATH_BINDING_REQUIRED')
    line=lines[0];resolved=[]
    for entry in line.split():
        parsed = urllib.parse.urlsplit(entry)
        require(parsed.scheme in ('','file') and not parsed.netloc and not parsed.query and not parsed.fragment,
                'REMOTE_BOOTER_CLASSPATH_FORBIDDEN')
        decoded=urllib.parse.unquote(parsed.path,errors='strict')
        require(decoded and not any(value in decoded for value in ('\0','\r','\n','\\')),'FOREIGN_BOOTER_CLASSPATH')
        local = Path(decoded)
        if not local.is_absolute(): local = path.parent/local
        # Surefire 3.5.6 relativizes URI entries against the manifest JAR directory.
        # Normalize in the CONTAINER namespace before mapping its read-only cache to the host.
        local = Path(os.path.normpath(local))
        require(local.is_relative_to(Path('/cache/repository')) or
                any(local.is_relative_to(ROOT/module/'target') for module in MODULES),'FOREIGN_BOOTER_CLASSPATH')
        mapped=cache/local.relative_to('/cache/repository') if local.is_relative_to('/cache/repository') else local
        require(not mapped.is_symlink() and mapped.resolve()==mapped and
                (mapped.is_dir() or mapped.is_file()),'REAL_BOOTER_CLASSPATH_ENTRY_REQUIRED')
        resolved.append(local.as_posix())
    require({str(ROOT/'chengyinhub-admin/target/test-classes'),str(ROOT/'chengyinhub-admin/target/classes')}
            <=set(resolved),'REAL_TEST_CLASSPATH_BINDING_REQUIRED')
    agents = [x[len('-javaagent:'):] for x in argv if x.startswith('-javaagent:')]
    require(len(agents) == 1 and re.fullmatch(r'/cache/repository/org/mockito/mockito-core/[A-Za-z0-9_.-]+/mockito-core-[A-Za-z0-9_.-]+\.jar',agents[0]),
            'ORIGINAL_MOCKITO_STARTUP_AGENT_REQUIRED')
    agent = cache/Path(agents[0]).relative_to('/cache/repository')
    require(agent.is_file() and not agent.is_symlink() and agent.resolve()==agent,'CACHED_ORIGINAL_AGENT_REQUIRED')
    return {'booterJarSha256':digest(path),'manifestSha256':hashlib.sha256(raw).hexdigest(),'mockitoAgentSha256':digest(agent),
            'classpathEntries':len(resolved),
            'resolvedClasspathSha256':hashlib.sha256(json.dumps(resolved,separators=(',',':')).encode()).hexdigest()}


def jvm_memory_binding(argv,role):
    if role=='MAVEN':options=RUNTIME_MAVEN_OPTIONS;heap,direct,code=128,16,32;profile='MAVEN_RUNTIME'
    elif role=='SUREFIRE':options=SUREFIRE_MEMORY_ARGUMENTS;heap,direct,code=384,64,64;profile='SUREFIRE_RUNTIME'
    elif role=='DESCENDANT_JVM' and SCHEDULER_PROBE in argv:
        require(argv.count(SCHEDULER_PROBE)==1,'FIXED_JVM_MEMORY_ARGUMENTS_REQUIRED')
        options=SCHEDULER_MEMORY_ARGUMENTS;heap,direct,code=64,None,None;profile='SCHEDULER_PROBE'
    else:return None # No memory-profile acceptance claim for another descendant; original cgroup guards remain.
    prefixes=('-Xms','-Xmx','-XX:InitialHeapSize=','-XX:MaxHeapSize=','-XX:MaxRAM=',
              '-XX:MaxRAMPercentage=','-XX:InitialRAMPercentage=','-XX:MinRAMPercentage=',
              '-XX:MaxDirectMemorySize=','-XX:ReservedCodeCacheSize=',
              '-XX:TieredStopAtLevel=','-XX:CompilationMode=')
    collectors=tuple('-XX:'+sign+name for sign in ('+','-') for name in
                     ('UseSerialGC','UseG1GC','UseParallelGC','UseZGC','UseShenandoahGC','UseEpsilonGC'))
    compilation=('-XX:+TieredCompilation','-XX:-TieredCompilation','-Xint','-Xcomp',
                 '-XX:+UseCompiler','-XX:-UseCompiler','-XX:+ProfileInterpreter','-XX:-ProfileInterpreter',
                 '-XX:+UseJVMCICompiler','-XX:-UseJVMCICompiler')
    selected=lambda values:[value for value in values if value.startswith(prefixes) or value in collectors or value in compilation]
    require(selected(argv)==selected(options.split()),'FIXED_JVM_MEMORY_ARGUMENTS_REQUIRED')
    return {'profile':profile,'maxHeapBytes':heap*1024**2,
            'maxDirectMemoryBytes':direct*1024**2 if direct is not None else None,
            'reservedCodeCacheBytes':code*1024**2 if code is not None else None,
            'collector':'SerialGC' if profile!='SCHEDULER_PROBE' else 'DEFAULT_UNATTESTED',
            'tieredStopAtLevel':1 if profile!='SCHEDULER_PROBE' else None,
            'compilationMode':'C1_SIMPLE' if profile!='SCHEDULER_PROBE' else 'DEFAULT_UNATTESTED'}


def process_memory(status):
    # Optional fixed counters only, not an inference of heap/native attribution or successful execution.
    result={}
    for field,key in (('VmRSS','rssBytes'),('RssAnon','anonymousBytes'),('RssFile','fileBytes'),
                      ('RssShmem','sharedMemoryBytes'),('VmSwap','swapBytes')):
        found=re.findall(r'^'+field+r':\s*([0-9]+) kB\s*$',status,re.MULTILINE)
        result[key]=int(found[0])*1024 if len(found)==1 and len(found[0])<=24 else None
    found=re.findall(r'^Threads:\s*([0-9]+)\s*$',status,re.MULTILINE)
    result['threads']=int(found[0]) if len(found)==1 and len(found[0])<=24 else None
    return result


def sample_java(state, obj):
    state['executionSubstage']='JAVA_CGROUP'
    cid = obj['Id']; init = obj['State']['Pid']; require(init > 0,'ACTUAL_APP_HOST_PID_REQUIRED')
    base = read_cgroup(init,cid)
    state['cgroupMemoryEvents'] = base['memoryEvents']
    state['cgroupMemoryUsage']=base.get('memoryUsage',{'state':'UNKNOWN'})
    # Shared network namespace must still belong to the same current MySQL process.
    state['executionSubstage']='MYSQL_PROCESS';mysql = inspect(state['mysql']['cid'])
    require(mysql['State']['Running'] and mysql['State']['Pid'] == state['mysqlHostPid'], 'MYSQL_PROCESS_REPLACED')
    state['executionSubstage']='PROCESS_LIST';rows = command(DOCKER+['top',cid,'-eo','pid,ppid,comm']).decode().splitlines()[1:]
    for row in rows:
        fields = row.split(); require(len(fields) == 3,'BOUNDED_PROCESS_IDENTITY_REQUIRED')
        if fields[2] != 'java': continue
        pid = int(fields[0]); proc = Path('/proc',str(pid))
        try:
            state['executionSubstage']='JAVA_PROC'
            stat = (proc/'stat').read_text(); tail = stat[stat.rfind(')')+2:].split()
            start = int(tail[19]); raw = (proc/'cmdline').read_bytes(); require(len(raw)<1024*1024,'BOUNDED_JAVA_IDENTITY_REQUIRED')
            argv = [x.decode() for x in raw.split(b'\0') if x]
            state['executionSubstage']='JAVA_PROC_CGROUP'
            cg = read_cgroup(pid,cid); require(cg['pathSha256'] == base['pathSha256'] and cg['limits']==base['limits'],'FORK_ESCAPED_FIXED_BUDGET')
            state['executionSubstage']='JAVA_NAMESPACE_PID'
            status=(proc/'status').read_text()
            namespace_pids = next(x.split()[1:] for x in status.splitlines() if x.startswith('NSpid:'))
            require(namespace_pids and int(namespace_pids[0]) == pid,'ACTUAL_NAMESPACE_PID_REQUIRED')
            role = 'MAVEN' if any('org.codehaus.plexus.classworlds.launcher.Launcher' == x for x in argv) else (
                    'SUREFIRE' if any('surefirebooter-' in x and x.endswith('.jar') for x in argv) else 'DESCENDANT_JVM')
            candidate={'hostPid':pid,'parentHostPid':int(fields[1]),'namespacePid':int(namespace_pids[-1]),'startTicks':start,
                       'role':role,'argvSha256':hashlib.sha256(raw).hexdigest(),'cgroup':cg}
            state['executionSubstage']='SUREFIRE_BOOTER_BINDING' if role=='SUREFIRE' else 'MAVEN_COMMAND'
            try:
                binding = booter_binding(argv,Path(state['cache'])) if role == 'SUREFIRE' else {}
                state['executionSubstage']='JVM_MEMORY_ARGUMENTS';memory=jvm_memory_binding(argv,role)
                if memory is not None:binding['jvmMemory']=memory
            except Exception:
                state.setdefault('rejectedJavaCandidate',{**candidate,'accepted':False})
                raise # An observed candidate is not an accepted fork and supplies no success evidence.
            if role == 'MAVEN': require('-Dtest='+CLASS in argv,'FIXED_CLASS_NOT_ON_ACTUAL_MAVEN_PROCESS')
            measured=process_memory(status)
            proof = {**candidate,**binding,'memory':measured,'peakObservedRssBytes':measured['rssBytes']}
            key = (pid,start)
            previous=next((x for x in state['javaProcesses'] if (x['hostPid'],x['startTicks'])==key),None)
            if previous is None:state['javaProcesses'].append(proof)
            else:
                values=[value for value in (previous.get('peakObservedRssBytes'),measured['rssBytes']) if value is not None]
                previous.update(memory=measured,peakObservedRssBytes=max(values) if values else None)
        except FileNotFoundError:
            # A process can exit between top/proc reads. It supplies no identity proof.
            continue


def fixed_maven_command():
    return ['-c','mkdir -p /tmp/home /tmp/m2 || exit 98; while [ ! -f /budget/release ]; do sleep .1; done; '
            'mvn "$@" & child=$!; wait "$child"; status=$?; printf "%s\\n" "$status" > /tmp/cy-budget.exit.next; '
            'mv /tmp/cy-budget.exit.next /tmp/cy-budget.exit; '
            'for i in {1..1200}; do [ -f /budget/stop ] && break; sleep .1; done; exit "$status"','--',
            '-o','-q','--settings','/budget/settings.xml','--global-settings','/budget/settings.xml',
            '-Dmaven.repo.local=/cache/repository','-P'+TEST_PROFILE,'-pl','chengyinhub-admin','-am','test',
            '-Dcombined.execution.precompiled=true',
            '-DargLine='+SUREFIRE_FORK_ARGUMENTS,
            '-Dtest='+CLASS,'-DforkCount=1','-DreuseForks=true','-DfailIfNoTests=false','-Dsurefire.failIfNoSpecifiedTests=false']


def private_run_log(create=False):
    path=OUT/'run.log'
    require(OUT.is_dir() and not OUT.is_symlink() and OUT.resolve()==OUT
            and OUT.stat().st_uid==os.getuid(),'PRIVATE_LOG_NOT_COMPLETE')
    if create:
        with path.open('xb') as stream:os.fchmod(stream.fileno(),0o600)
    require(path.is_file() and not path.is_symlink() and path.resolve()==path
            and path.stat().st_uid==os.getuid() and path.stat().st_mode & 0o777==0o600
            and path.stat().st_size<32*1024*1024,'PRIVATE_LOG_NOT_COMPLETE')
    return path


def capture_app_log(path,state):
    # Numeric MXBean rows already reside here; never truncate/adopt the original private log.
    log=private_run_log();slot=state['slots']['app'];cid=slot['cid']
    obj=inspect(cid);validate_owner(obj,slot['name'],JDK,labels(os.environ,state['nonce']),cid)
    with os.fdopen(os.open(log,os.O_WRONLY|os.O_APPEND|os.O_NOFOLLOW),'ab') as stream:
        opened=os.fstat(stream.fileno());current=log.stat()
        require((opened.st_dev,opened.st_ino)==(current.st_dev,current.st_ino)
                and opened.st_uid==os.getuid() and opened.st_mode & 0o777==0o600,'PRIVATE_LOG_NOT_COMPLETE')
        result=subprocess.run(DOCKER+['logs',cid],stdout=stream,stderr=subprocess.STDOUT,timeout=30)
    require(result.returncode==0 and log.stat().st_size<32*1024*1024,'PRIVATE_LOG_NOT_COMPLETE')
    state['runLogCaptured']=True;save(path,state)


def mxbean_readback(state):
    # Optional observation only: never changes firstFailure, originalExit, OOM or acceptance.
    unknown={'state':'UNKNOWN','reasonCode':'MEMORY_READBACK_UNAVAILABLE','records':[]}
    try:
        require(state.get('identity')==identity(os.environ),'WRONG_SOURCE_RUN_STATE')
        cid=state.get('slots',{}).get('app',{}).get('cid')
        require(isinstance(cid,str) and re.fullmatch('[0-9a-f]{64}',cid),'CREATE_IDENTITY_UNKNOWN')
        records=[];seen=set();partial=state.get('runLogCaptured') is not True
        log=private_run_log()
        with os.fdopen(os.open(log,os.O_RDONLY|os.O_NOFOLLOW),'r',encoding='ascii',errors='replace') as stream:
            opened=os.fstat(stream.fileno());current=log.stat()
            require((opened.st_dev,opened.st_ino)==(current.st_dev,current.st_ino)
                    and opened.st_uid==os.getuid() and opened.st_mode & 0o777==0o600,'PRIVATE_LOG_NOT_COMPLETE')
            while True:
                line=stream.readline(8193)
                if not line:break
                if not line.startswith('CONTROLLED_COMBINATION_MEMORY '):continue
                if not line.endswith('\n'):
                    partial=True;break # A killed writer cannot erase earlier complete rows or supply a new row.
                require(len(line)<=4096+len('CONTROLLED_COMBINATION_MEMORY ')+1 and len(records)<128,'PRIVATE_LOG_NOT_COMPLETE')
                fields=line[len('CONTROLLED_COMBINATION_MEMORY '):-1].split(' ')
                require(len(fields)>=18 and fields[:3]==[os.environ['SOURCE_SHA'],os.environ['GITHUB_RUN_ID'],os.environ['GITHUB_RUN_ATTEMPT']],
                        'WRONG_SOURCE_RUN_STATE')
                role,phase=fields[3:5]
                phases={'SUREFIRE':{'BEFORE','HELD','RECOVERED'},'SCHEDULER_PROBE':
                        {'READY','LEADER','ARMED','RELEASED','ENTER','EFFECT','RESULT','STOPPED','TASK_SESSION_KILLED','FATAL'}}
                require(role in phases and phase in phases[role],'BOUNDED_PROCESS_IDENTITY_REQUIRED')
                require(all(re.fullmatch('-?[0-9]{1,19}',value) and -(2**63)<=int(value)<2**63 for value in fields[5:]),
                        'BOUNDED_PROCESS_IDENTITY_REQUIRED')
                values=[int(value) for value in fields[5:]];pid,uptime,nano=values[:3]
                require(pid>0 and uptime>=-1 and all(value>=-1 for value in values[3:]),'BOUNDED_PROCESS_IDENTITY_REQUIRED')
                def usage(numbers):return dict(zip(('initBytes','usedBytes','committedBytes','maxBytes'),numbers))
                row={'role':role,'phase':phase,'namespacePid':pid,'uptimeMillis':uptime,'monotonicNanos':nano,
                     'heap':usage(values[3:7]),'nonheap':usage(values[7:11]),'pools':[],'buffers':[]}
                index=12;count=values[11];require(-1<=count<=16,'BOUNDED_PROCESS_IDENTITY_REQUIRED')
                require(len(values)>=index+max(count,0)*5+1,'BOUNDED_PROCESS_IDENTITY_REQUIRED')
                if count<0:partial=True
                for _ in range(max(count,0)):
                    code=values[index];require(0<=code<=15,'BOUNDED_PROCESS_IDENTITY_REQUIRED')
                    row['pools'].append({'kindCode':code,**usage(values[index+1:index+5])});index+=5
                    if code==0 or row['pools'][-1]['usedBytes']<0 or row['pools'][-1]['committedBytes']<0:partial=True
                count=values[index];index+=1;require(-1<=count<=4 and len(values)==index+max(count,0)*4,'BOUNDED_PROCESS_IDENTITY_REQUIRED')
                if count<0:partial=True
                for _ in range(max(count,0)):
                    code,objects,used,capacity=values[index:index+4];require(0<=code<=3,'BOUNDED_PROCESS_IDENTITY_REQUIRED');index+=4
                    row['buffers'].append({'kindCode':code,'count':objects,'memoryUsedBytes':used,'totalCapacityBytes':capacity})
                    if code==0 or min(objects,used,capacity)<0:partial=True
                candidates=[p for p in state.get('javaProcesses',[]) if p.get('namespacePid')==pid
                        and p.get('role')==('SUREFIRE' if role=='SUREFIRE' else 'DESCENDANT_JVM')
                        and p.get('jvmMemory',{}).get('profile')==('SUREFIRE_RUNTIME' if role=='SUREFIRE' else 'SCHEDULER_PROBE')]
                if len(candidates)==1:
                    proof=candidates[0]
                    row.update(binding='ACCEPTED_CGROUP_PROCESS',hostPid=proof['hostPid'],startTicks=proof['startTicks'])
                else:row['binding']='UNKNOWN_NO_UNIQUE_ACCEPTED_PROCESS';partial=True
                # JDK max/init=-1 can mean undefined; it is not zero or a missing used/committed reading.
                if uptime<0 or any(value<0 for value in (values[4],values[5],values[8],values[9])):partial=True
                key=(role,pid,nano,phase);require(key not in seen,'BOUNDED_PROCESS_IDENTITY_REQUIRED');seen.add(key);records.append(row)
        if not records:return unknown
        return {'state':'PARTIAL_OR_UNKNOWN' if partial else 'OBSERVED','records':records,
                'sourceSha':os.environ['SOURCE_SHA'],'runId':os.environ['GITHUB_RUN_ID'],'attempt':os.environ['GITHUB_RUN_ATTEMPT'],
                'executionCid':cid,'businessAccepted':False,'scope':'NUMERIC_JDK_MXBEANS_NOT_LIVE_HEAP_OR_SIMULTANEOUS_RSS'}
    except (OSError,ValueError,KeyError,TypeError,UnicodeError,IndexError):return unknown


def validate_app_inputs(obj,path,state):
    host = obj['HostConfig']; require(host['ReadonlyRootfs'] is True and not host.get('VolumesFrom')
            and not host.get('Devices') and obj['Config']['User'] == str(os.getuid())+':'+str(os.getgid()), 'PRIVATE_APP_IDENTITY_OR_ROOTFS_CHANGED')
    require(obj['Config']['Entrypoint'] == ['/bin/bash'] and obj['Config']['Cmd'] == fixed_maven_command(),'FIXED_OFFLINE_MAVEN_COMMAND_CHANGED')
    expected = {(str(ROOT),str(ROOT),False),(state['cache'],'/cache/repository',False),(str(path/'launch'),'/budget',False)}
    expected.update((str(ROOT/module/'target'),str(ROOT/module/'target'),True) for module in MODULES)
    content=ROOT/'chengyinhub-admin/target/controlled-content-security'
    expected.add((str(content),str(content),False))
    binds = {(m['Source'],m['Destination'],m['RW']) for m in obj['Mounts'] if m['Type']=='bind'}
    require(binds == expected and all(m['Type']=='bind' or (m['Type']=='tmpfs' and m['Destination']=='/tmp')
            for m in obj['Mounts']), 'PRIVATE_MOUNT_BOUNDARY_CHANGED')
    actual = dict(item.split('=',1) for item in obj['Config']['Env'])
    expected_env = {**state['jdkEnvironment'],**child_environment(os.environ)}
    require(actual == expected_env,'PRIVATE_ENV_BOUNDARY_CHANGED')
    return {'sourceReadOnly':True,'dependencyCacheReadOnly':True,'hostSettingsMounted':False,
            'privateLaunchOnly':True,'forwardedEnvironmentNames':sorted(child_environment(os.environ)),
            'writableTargets':list(MODULES),'forkCount':1,'offline':True}


def run(path):
    gate(os.environ); path,state = load_private(path); require(state['phase'] == 'PREPARED','PREPARED_OWNER_REQUIRED')
    try:return run_owned_app(path,state)
    except Exception as failure:
        # Persist this in-memory sample and its first exact predicate, not the previous loop snapshot.
        state.setdefault('firstFailure',execution_failure(failure,state['phase'],state.get('executionSubstage')))
        try:save(path,state)
        except Exception:print('Combined HTTP execution failure evidence: UNKNOWN',file=__import__('sys').stderr)
        raise


def run_owned_app(path,state):
    state['executionSubstage']='BACKING_IDENTITY'
    backing_mysql(os.environ);require(verified_cache(Path(state['cache']))==state['cacheProof'],'OFFLINE_CACHE_PROOF_CHANGED')
    state['executionSubstage']='SOURCE_MANIFEST'
    state['source']=source_manifest();save(path,state)
    state['executionSubstage']='MYSQL_ADDRESS'
    state['mysqlAddressProof'] = mysql_address_proof(state['mysql']['cid'])
    mounts = [(ROOT,ROOT,'readonly'),(Path(state['cache']),Path('/cache/repository'),'readonly'),(path/'launch',Path('/budget'),'readonly')]
    for module in MODULES:
        target = ROOT/module/'target'; require(target.is_dir() and not target.is_symlink(),'ORIGINAL_TARGET_REQUIRED')
        mounts.append((target,target,'readonly=false'))
    require(controlled_content_proof()==state['cacheProof']['controlledContentSecurity'],'CONTROLLED_CONTENT_SECURITY_PROOF_REQUIRED')
    content=ROOT/'chengyinhub-admin/target/controlled-content-security';mounts.append((content,content,'readonly'))
    private_run_log(create=True);state['runLogCaptured']=False
    state['phase']='APP_CREATE';save(path,state)
    state['executionSubstage']='APP_CREATE'
    cid = create_slot(path,state,'app',JDK,fixed_maven_command(),mounts,path/'private.env')
    state['executionSubstage']='APP_LIMITS'
    obj = inspect(cid); read_cgroup(obj['State']['Pid'],cid) # actual limits before releasing Maven/test.
    state['executionSubstage']='APP_INPUTS'
    state['launchProof'] = validate_app_inputs(obj,path,state)
    state['executionSubstage']='NETWORK_NAMESPACE'
    app_namespace = command(DOCKER+['exec',cid,'readlink','/proc/self/ns/net']).strip()
    mysql_namespace = command(DOCKER+['exec',state['mysql']['cid'],'readlink','/proc/self/ns/net']).strip()
    require(app_namespace == mysql_namespace and re.fullmatch(rb'net:\[[0-9]+\]',app_namespace),'NOT_SAME_OWNED_LOOPBACK_NAMESPACE')
    state['networkNamespaceSha256'] = hashlib.sha256(app_namespace).hexdigest();save(path,state)
    state['executionSubstage']='APP_RELEASE'
    (path/'launch/release').write_text('fixed-source-budget-verified\n')
    deadline = time.monotonic()+LIMIT_SECONDS; state['phase']='APP_EXECUTION';save(path,state)
    while True:
        state['executionSubstage']='APP_INSPECT'
        obj = inspect(cid)
        state['executionSubstage']='APP_OWNER'
        validate_container(obj,state['slots']['app']['name'],JDK,labels(os.environ,state['nonce']),MEMORY,'container:'+state['mysql']['cid'],cid)
        state['executionSubstage']='APP_LIVE'
        require(obj['State']['Running'],'APP_EXIT_BEFORE_MAVEN_STATUS')
        state['executionSubstage']='DEADLINE'
        require(time.monotonic()<deadline,'OWNED_EXECUTION_DEADLINE')
        state['executionSubstage']='JAVA_SAMPLE'
        sample_java(state,obj);save(path,state)
        state['executionSubstage']='CGROUP_OOM'
        require(int(state['cgroupMemoryEvents']['oom_kill']) == 0,'ACTUAL_CGROUP_CHILD_OOM_KILL')
        state['executionSubstage']='MAVEN_STATUS'
        status = command(DOCKER+['exec',cid,'/bin/bash','-c','if [ -f /tmp/cy-budget.exit ]; then cat /tmp/cy-budget.exit; fi']).decode().strip()
        if status:
            state['executionSubstage']='MAVEN_STATUS_VALUE'
            require(re.fullmatch('[0-9]{1,3}',status) and int(status)<256,'ACTUAL_MAVEN_EXIT_REQUIRED');break
        time.sleep(.5)
    state['mavenExit'] = int(status);state['phase']='MAVEN_EXITED_AWAIT_CLEANUP';save(path,state)
    state['executionSubstage']='PRIVATE_LOG'
    capture_app_log(path,state)
    state['executionSubstage']='POSTIMAGE'
    require(source_manifest() == state['source'],'COMPILED_POSTIMAGE_DRIFT')
    state['executionSubstage']='CACHE_RECHECK'
    require(verified_cache(Path(state['cache']))==state['cacheProof'],'OFFLINE_CACHE_PROOF_CHANGED')
    state['executionSubstage']='REQUIRED_JVM_ROLES'
    require(any(x['role']=='MAVEN' for x in state['javaProcesses']) and any(x['role']=='SUREFIRE' for x in state['javaProcesses']), 'REAL_MAVEN_AND_JUNIT_PID_PROOF_REQUIRED')
    state['executionSubstage']='APP_OOM'
    require(not obj['State']['OOMKilled'],'APP_OOM_OR_UNKNOWN_EXIT')
    state['executionSubstage']='FINAL_MYSQL_ADDRESS'
    require(mysql_address_proof(state['mysql']['cid']) == state['mysqlAddressProof'],'MYSQL_ADDRESS_OR_UUID_DRIFT')
    return state['mavenExit']


def mysql_address_proof(cid):
    # Password only on private stdin, never argv/env artifact. Both addresses query the SAME server.
    config = ('[client]\npassword='+os.environ['CHENGYIN_MYBATIS_IT_PASSWORD']+'\n').encode()
    args=mysql_client_arguments()
    responses=[]
    for argv in (args+['--port=33080'],namespace_mysql_arguments(cid,args)):
        result=subprocess.run(argv,input=config,capture_output=True,timeout=20,env=mysql_client_environment())
        require(result.returncode==0,'MYSQL_TCP_IDENTITY_MISMATCH')
        responses.append(result.stdout.decode().strip().split('\t'))
    host,namespace=responses
    require(host == namespace and len(host)==3 and re.fullmatch('[0-9a-f-]{36}',host[0])
            and host[1].split('-')[0]=='8.0.45' and host[2]=='3306','SAME_MYSQL_UUID_BOTH_ADDRESSES_REQUIRED')
    return {'cid':cid,'serverUuid':host[0],'version':host[1],'hostEndpoint':'127.0.0.1:33080','executionEndpoint':'127.0.0.1:3306'}


def cleanup_slot(path,state,role):
    slot = state['slots'].get(role)
    if slot is None: return {'role':role,'result':'NOT_ATTEMPTED'}
    cid = slot.get('cid')
    if cid is None:
        # Recover a lost create response only through its private cidfile and original unique labels.
        candidate = path/(role+'.cid')
        if candidate.is_file() and not candidate.is_symlink(): cid = candidate.read_text().strip()
        else:
            # A unique attempted name/private nonce can recover an actual object, never a foreign one.
            obj = inspect(slot['name']);validate_owner(obj,slot['name'],JDK if role == 'app' else REDIS,labels(os.environ,state['nonce']))
            cid = obj['Id']
        require(re.fullmatch('[0-9a-f]{64}',cid),'CREATE_IDENTITY_UNKNOWN')
    obj = inspect(cid); name = slot['name']; image = JDK if role == 'app' else REDIS
    # Budget rejection does not prevent removal of an independently proven OWN object.
    validate_owner(obj,name,image,labels(os.environ,state['nonce']),cid)
    require(inspect(name)['Id'] == cid,'CLEANUP_NAME_CID_CHANGED')
    slot['cid']=cid # Preserve the recovered actual ID in the public cleanup postimage.
    if obj['State']['Running']: command(DOCKER+['stop','--time','20',cid],timeout=30)
    obj = inspect(cid); require(not obj['State']['Running'] and obj['State']['Pid']==0,'OWNED_PROCESS_STOP_UNKNOWN')
    terminal = {k:obj['State'][k] for k in ('ExitCode','OOMKilled','Running','Pid')}
    command(DOCKER+['rm',cid]);absent('container',cid);absent('container',name)
    return {'role':role,'cid':cid,'result':'EXACT_CID_REMOVED_AND_ABSENT','terminal':terminal}


def namespace_port_proof(path,state):
    # The original JDK app is the existing execution tool; MySQL remains the namespace owner.
    # Maven exit is not a prerequisite for recording cleanup evidence, nor is this business acceptance.
    app=state['slots']['app']
    obj=inspect(app['cid']);validate_container(obj,app['name'],JDK,labels(os.environ,state['nonce']),
            MEMORY,'container:'+state['mysql']['cid'],app['cid'])
    require(obj['State']['Running'],'ORIGINAL_EXECUTION_NAMESPACE_NOT_LIVE')
    mysql,_=backing_mysql(os.environ)
    require(mysql['Id']==state['mysql']['cid'] and mysql['State']['Pid']==state['mysqlHostPid'],
            'MYSQL_PROCESS_REPLACED_BEFORE_PORT_PROOF')
    namespace=command(DOCKER+['exec',mysql['Id'],'readlink','/proc/self/ns/net']).strip()
    require(re.fullmatch(rb'net:\[[0-9]+\]',namespace)
            and hashlib.sha256(namespace).hexdigest()==state['networkNamespaceSha256']
            and command(DOCKER+['exec',app['cid'],'readlink','/proc/self/ns/net']).strip()==namespace,'PORT_PROBE_NAMESPACE_CHANGED')
    require(digest(path/'launch/CombinedHttpNamespacePortProbe.java')==state['namespaceProbeSourceSha256'],
            'NAMESPACE_PROBE_POSTIMAGE_CHANGED')
    result=command(DOCKER+['exec',app['cid'],'java','-Xmx32m','/budget/CombinedHttpNamespacePortProbe.java',str(state['redisPort'])])
    require(result==('NAMESPACE_REDIS_PORT_FREE '+str(state['redisPort'])+'\n').encode(),'ACTUAL_NAMESPACE_BIND_PROOF_REQUIRED')
    state['cgroupMemoryEvents']=read_cgroup(obj['State']['Pid'],app['cid'])['memoryEvents']
    return {'result':'ACTUAL_OWNER_NAMESPACE_BIND_FREE','executionCid':app['cid'],
            'mysqlCid':state['mysql']['cid'],'namespaceSha256':state['networkNamespaceSha256'],
            'probeSourceSha256':state['namespaceProbeSourceSha256'],'port':state['redisPort']}


def finalize(path, original_exit):
    gate(os.environ); path,state = load_private(path); failures=[]; cleaned=[]
    # Recover private original logs before removing the app, including timeout/create failures.
    app = state['slots'].get('app',{})
    if app.get('cid') and state.get('runLogCaptured') is not True:
        try:
            capture_app_log(path,state)
        except Exception as failure: failures.append({'phase':'PRIVATE_LOG','type':type(failure).__name__})
    memory_readback=mxbean_readback(state)
    # Keep the original namespace and JDK alive until the exact Redis removal + real bind attempt,
    # including sampling/identity failures where no Maven exit receipt has been observed.
    order = ('redis','app')
    port_proof = {'result':'UNKNOWN'}
    for role in order:
        if role == 'app':
            try:
                port_proof=namespace_port_proof(path,state)
            except Exception as failure:failures.append(execution_failure(failure,'NAMESPACE_PORT_PROOF','NAMESPACE_PORT_PROOF'))
        if role == 'app' and 'mavenExit' in state:
            try:
                (path/'launch/stop').write_text('original-maven-exit-only\n')
                for _ in range(50):
                    obj=inspect(app['cid'])
                    if not obj['State']['Running']:break
                    time.sleep(.1)
                require(not obj['State']['Running'],'BOUNDED_ORIGINAL_EXIT_NOT_OBSERVED')
                state['appExit']={key:obj['State'][key] for key in ('ExitCode','OOMKilled','Running','Pid')}
            except Exception as failure: failures.append({'phase':'ORIGINAL_APP_EXIT','type':type(failure).__name__})
        try: cleaned.append(cleanup_slot(path,state,role))
        except Exception as failure: failures.append({'phase':'CLEANUP_'+role.upper(),'type':type(failure).__name__})
    successful = accepted_outcome(state,original_exit,failures,cleaned,port_proof)
    report = {**state['identity'],'result':'FIXED_COMBINED_BUDGET_PROCESS_PROVEN' if successful else 'NOT_ACCEPTED',
              'originalExit':original_exit,'mavenExit':state.get('mavenExit'),'appExit':state.get('appExit'),'mysql':state['mysql'],
              'mysqlAddressProof':state.get('mysqlAddressProof'),'mysqlBootstrap':state['mysqlBootstrap'],'cacheProof':state['cacheProof'],
              'namespaceRedisPortFree':port_proof,'redisPort':state['redisPort'],'database':state['schema'],
              'source':state['source'],'slots':{role:{k:v for k,v in value.items() if k in ('name','cid','budget')} for role,value in state['slots'].items()},
              'javaProcesses':state['javaProcesses'],'cleanup':cleaned,'failures':failures,'firstFailure':state.get('firstFailure'),
              'rejectedJavaCandidate':state.get('rejectedJavaCandidate'),
              'networkNamespaceSha256':state.get('networkNamespaceSha256'),
              'launchProof':state.get('launchProof'),
              'cgroupMemoryEvents':state.get('cgroupMemoryEvents'),
              'cgroupMemoryUsage':state.get('cgroupMemoryUsage'),
              'jvmMxBeanReadback':memory_readback,
              'wholeSystemCapacityAccepted':False,'q4q8Complete':False}
    write(PUBLIC/'combined-http-budget.json',report)
    require(successful,'ORIGINAL_EXIT_OR_BUDGET_OR_CLEANUP_NOT_ACCEPTED')


def accepted_outcome(state,original_exit,failures,cleaned,port_proof):
    successful = original_exit == 0 and not failures and not state.get('firstFailure') and state.get('appExit') == {'ExitCode':0,'OOMKilled':False,'Running':False,'Pid':0}
    successful = successful and all(row['result']=='EXACT_CID_REMOVED_AND_ABSENT' for row in cleaned) and len(cleaned)==2
    successful = successful and {row.get('role') for row in cleaned}=={'app','redis'}
    successful = successful and {'MAVEN','SUREFIRE'} <= {x['role'] for x in state['javaProcesses']}
    successful = successful and bool(state.get('launchProof')) and bool(state.get('networkNamespaceSha256'))
    successful = successful and state.get('mavenExit')==0 and port_proof['result']=='ACTUAL_OWNER_NAMESPACE_BIND_FREE'
    successful = successful and int(state.get('cgroupMemoryEvents',{}).get('oom_kill','-1'))==0
    successful = successful and all(not row.get('terminal',{}).get('OOMKilled',True)
            and row['terminal']['ExitCode'] in (0,143) for row in cleaned)
    return successful


def check_subject(report, subject_identity, java_pid, jar_sha256):
    """F/root pass their already-sanitized original identity and javaPid; no JSON nesting guessed."""
    require({k:report.get(k) for k in ('sourceSha','runId','attempt','class')} == subject_identity,
            'SUBJECT_SOURCE_RUN_CLASS_MISMATCH')
    require(subject_identity.get('class')==CLASS and re.fullmatch('[0-9a-f]{40}',subject_identity.get('sourceSha',''))
            and re.fullmatch('[0-9a-f]{64}',jar_sha256),'FIXED_SUBJECT_ARTIFACT_REQUIRED')
    require(report.get('result') == 'FIXED_COMBINED_BUDGET_PROCESS_PROVEN'
            and isinstance(report.get('source'),dict) and report.get('originalExit')==report.get('mavenExit')==0
            and report.get('appExit')=={'ExitCode':0,'OOMKilled':False,'Running':False,'Pid':0}
            and report.get('source',{}).get('artifactJarSha256') == jar_sha256
            and type(java_pid) is int and java_pid > 0
            and any(p['role']=='SUREFIRE' and p['namespacePid']==java_pid for p in report.get('javaProcesses',[])),
            'ACTUAL_JUNIT_SUBJECT_PID_NOT_PROVEN')
    for role,image,memory in (('app',JDK,MEMORY),('redis',REDIS,67108864)):
        slot=report.get('slots',{}).get(role,{}); proof=slot.get('budget',{})
        require(re.fullmatch('[0-9a-f]{64}',slot.get('cid','')) and proof.get('cid')==slot['cid']
                and proof.get('image')==image and proof.get('memory')==proof.get('memorySwap')==memory
                and proof.get('nanoCpus')==1000000000 and proof.get('pids')==256,'SUBJECT_RESOURCE_PROOF_MISMATCH')
    for process in report['javaProcesses']:
        validate_cgroup_limits(process['cgroup']['limits'])
        if process['role'] in ('MAVEN','SUREFIRE'):
            options=RUNTIME_MAVEN_OPTIONS if process['role']=='MAVEN' else SUREFIRE_MEMORY_ARGUMENTS
            require(process.get('jvmMemory')==jvm_memory_binding(options.split(),process['role']),
                    'FIXED_JVM_MEMORY_ARGUMENTS_REQUIRED')
    cache_proof=report.get('cacheProof',{})
    content=cache_proof.get('controlledContentSecurity',{})
    require(content==report.get('source',{}).get('controlledContentSecurity')
            and set(content)=={'purpose','sourceSha','sourceSha256','classSha256','proofSha256'}
            and content['purpose']=='SAME_SOURCE_PRECOMPILED_CONTENT_SECURITY' and content['sourceSha']==subject_identity['sourceSha']
            and all(re.fullmatch('[0-9a-f]{64}',content.get(key,'')) for key in ('sourceSha256','classSha256','proofSha256')),
            'CONTROLLED_CONTENT_SECURITY_PROOF_REQUIRED')
    require(cache_proof.get('image')==JDK and cache_proof.get('mavenVersion')==CORE_VERSION
            and cache_proof.get('imageId')==report['slots']['app']['budget'].get('imageId')
            and cache_proof.get('settingsSha256')==hashlib.sha256(EMPTY_SETTINGS.encode()).hexdigest()
            and all(re.fullmatch('[0-9a-f]{64}',cache_proof.get(k,'')) for k in ('reportSha256','mavenCoreSha256'))
            and type(cache_proof.get('cache',{}).get('files')) is int and cache_proof['cache']['files']>0
            and re.fullmatch('[0-9a-f]{64}',cache_proof['cache'].get('sha256','')),'OFFLINE_CACHE_PREFLIGHT_REQUIRED')
    mysql=report.get('mysql',{})
    require(re.fullmatch('[0-9a-f]{64}',mysql.get('cid','')) and mysql.get('image')==MYSQL
            and mysql.get('memory')==mysql.get('memorySwap')==MEMORY and mysql.get('nanoCpus')==1000000000
            and mysql.get('pids')==256,'SUBJECT_BACKING_BUDGET_PROOF_MISMATCH')
    bootstrap=report.get('mysqlBootstrap',{})
    require({k:bootstrap.get(k) for k in ('sourceSha','runId','attempt','class')}==subject_identity
            and bootstrap.get('result')=='READY' and bootstrap.get('originalExit')==0
            and bootstrap.get('readiness',{}).get('result')=='BOTH_LOOPBACK_TCP_IDENTITIES_VERIFIED'
            and bootstrap['readiness'].get('cid')==mysql['cid']
            and re.fullmatch('[0-9a-f]{64}',bootstrap.get('reportSha256','')),'BOOTSTRAP_NOT_VERIFIED')
    require(bootstrap.get('hostPath',{}).get('result')=='ORIGINAL_OWNED_MYSQL_RELAY'
            and bootstrap['hostPath'].get('mysqlCid')==mysql['cid']
            and bootstrap['hostPath'].get('hostEndpoint')=='127.0.0.1:33080'
            and bootstrap['hostPath'].get('closedAfterReadiness') is True,'BOOTSTRAP_NOT_VERIFIED')
    require(all(report.get('mysqlAddressProof',{}).get(key)==bootstrap['readiness'].get(key) for key in
                ('cid','serverUuid','version','hostEndpoint','executionEndpoint'))
            and re.fullmatch('[0-9a-f-]{36}',bootstrap['readiness'].get('serverUuid','')),'BOOTSTRAP_NOT_VERIFIED')
    redis_cleanup_proven(report,subject_identity)


def check_storage_cleanup(report,storage):
    require({k:report.get(k) for k in ('sourceSha','runId','attempt','class')} ==
            {k:storage.get(k) for k in ('sourceSha','runId','attempt','class')}
            and storage.get('mysqlAbsent') is True and storage.get('networkAbsent') is True
            and storage.get('mysql')==report.get('mysql')
            and storage.get('beforeCleanup',{}).get('Running') is True
            and storage.get('beforeCleanup',{}).get('OOMKilled') is False,'EXACT_BACKING_STORAGE_CLEANUP_REQUIRED')


def redis_cleanup_proven(report, expected_identity):
    require({k:report.get(k) for k in ('sourceSha','runId','attempt','class')} == expected_identity,'CLEANUP_SOURCE_IDENTITY_MISMATCH')
    rows=[row for row in report.get('cleanup',[]) if row.get('role')=='redis']
    proof=report.get('namespaceRedisPortFree',{})
    require(len(rows)==1 and rows[0].get('result')=='EXACT_CID_REMOVED_AND_ABSENT'
            and rows[0].get('cid')==report.get('slots',{}).get('redis',{}).get('cid')
            and proof.get('result')=='ACTUAL_OWNER_NAMESPACE_BIND_FREE' and type(report.get('redisPort')) is int
            and 1<=report['redisPort']<=65535 and proof.get('port')==report['redisPort']
            and proof.get('executionCid')==report.get('slots',{}).get('app',{}).get('cid')
            and proof.get('mysqlCid')==report.get('mysql',{}).get('cid')
            and proof.get('namespaceSha256')==report.get('networkNamespaceSha256'), 'ACTUAL_INTERNAL_REDIS_CLEANUP_REQUIRED')


def read_redis_cleanup_proof():
    """Exact shell cleanup marker adapter, independently usable even when business result is red."""
    gate(os.environ)
    report=json.loads((PUBLIC/'combined-http-budget.json').read_text())
    require(report.get('redisPort')==int(os.environ['CHENGYIN_HTTP_E2E_REDIS_PORT']),'WRONG_ORIGINAL_REDIS_PORT_PROOF')
    redis_cleanup_proven(report, identity(os.environ))


def private_cleanup_proven(report,expected_identity):
    require({k:report.get(k) for k in ('sourceSha','runId','attempt','class')}==expected_identity,'PRIVATE_CLEANUP_IDENTITY_MISMATCH')
    rows=report.get('cleanup',[])
    require(len(rows)==2 and {row.get('role') for row in rows}=={'app','redis'}
            and all(row.get('result')=='EXACT_CID_REMOVED_AND_ABSENT'
                    and row.get('cid')==report.get('slots',{}).get(row['role'],{}).get('cid') for row in rows),
            'PRIVATE_STATE_MUST_BE_RETAINED')


def read_private_cleanup_proof():
    gate(os.environ)
    private_cleanup_proven(json.loads((PUBLIC/'combined-http-budget.json').read_text()),identity(os.environ))


def require_subject_budget(receipt_directory, subject_identity, java_pid, jar_sha256, database):
    """F entry finalize: after original XML/schema/Redis + bounded subject projection, before outer SQL removal."""
    path=Path(receipt_directory)/'combined-http-budget.json'
    require(path.is_file() and not path.is_symlink() and path.stat().st_size<8*1024*1024,'BOUNDED_BUDGET_PROOF_REQUIRED')
    report=json.loads(path.read_text())
    check_subject(report,subject_identity,java_pid,jar_sha256)
    require(report.get('database')==database and re.fullmatch(r'cy_e2e_w1_http_[0-9_]+',database),
            'ORIGINAL_HTTP_SCHEMA_BUDGET_BINDING_REQUIRED')
    return report


def require_final_budget(receipt_directory, subject_identity, java_pid, jar_sha256, database):
    """F/root summary ONLY: after the workflow's exact MySQL/network cleanup has published its proof."""
    report=require_subject_budget(receipt_directory,subject_identity,java_pid,jar_sha256,database)
    path=Path(receipt_directory)/'combined-http-storage-cleanup.json'
    require(path.is_file() and not path.is_symlink() and path.stat().st_size<8*1024*1024,'BOUNDED_STORAGE_PROOF_REQUIRED')
    check_storage_cleanup(report,json.loads(path.read_text()))


def cleanup_mysql():
    PUBLIC.mkdir(parents=True,exist_ok=True)
    report={**resource_envelope(os.environ),'result':'UNKNOWN','phase':'CLEANUP_IDENTITY',
            'mysqlAbsent':None,'networkAbsent':None,'failures':[],'nativeOperations':[],'originalExit':None}
    path=PUBLIC/'combined-http-storage-cleanup.json'
    def publish():
        try:write(path,report)
        except Exception as failure:
            report['failures'].append(resource_failure(failure,'CLEANUP_EVIDENCE_WRITE'))
            print('Combined MySQL cleanup evidence: UNKNOWN',file=__import__('sys').stderr)
    publish() # Evidence failure must not prevent native cleanup of independently proven own objects.
    try:identity_gate(os.environ)
    except Exception as failure:
        report['failures'].append(resource_failure(failure,'CLEANUP_IDENTITY'));publish();raise
    cleanup_env=dict(os.environ)
    try:
        original=read_bootstrap(required=False)
        if original:
            report['originalExit']=original.get('originalExit')
            if original.get('firstFailure'):report['firstFailure']=original['firstFailure']
            # Only source-bound, previously owner-verified IDs can fill a lost GITHUB_ENV write.
            # Never adopt by name alone, never replace a conflicting provided identity.
            recovered=[]
            for key,value in (('HTTP_MYSQL_CID',original.get('mysql',{}).get('cid')),
                              ('HTTP_BUDGET_NETWORK_ID',original.get('network',{}).get('cid') or original.get('mysql',{}).get('networkId'))):
                if not cleanup_env.get(key) and isinstance(value,str) and re.fullmatch('[0-9a-f]{64}',value):
                    cleanup_env[key]=value;recovered.append(key)
            if recovered:
                name,network=mysql_names(cleanup_env)
                cleanup_env.setdefault('HTTP_MYSQL_CONTAINER',name);cleanup_env.setdefault('HTTP_BUDGET_NETWORK',network)
                report['recoveredIdentityFields']=recovered
    except Exception as failure:
        report['bootstrapEvidenceFailure']=resource_failure(failure,'BOOTSTRAP_PROVENANCE')
    try:
        name,network=mysql_names(cleanup_env)
        if not cleanup_env.get('HTTP_MYSQL_CID'):
            absent('container',name);report['mysqlAbsent']=True
            report['mysqlIdentity']='NO_ACTUAL_CID_NAME_INDEPENDENTLY_ABSENT'
        else:
            obj=owned_mysql(cleanup_env)
            if obj is None:
                report['mysqlAbsent']=True;report['mysqlIdentity']='EXPECTED_CID_AND_NAME_INDEPENDENTLY_ABSENT'
            else:
                report['phase']='MYSQL_DIAGNOSTIC_BEFORE_REMOVE'
                report['mysql']=mysql_proof(obj,cleanup_env.get('HTTP_BUDGET_NETWORK_ID'))
                report['beforeCleanup']={key:obj['State'].get(key) for key in ('Running','OOMKilled','ExitCode')}
                report['diagnostic']={'state':safe_mysql_state(obj)};publish()
                try:report['diagnostic']=mysql_diagnostic(obj)
                except Exception as capture:report['diagnostic']={'result':'UNKNOWN','type':type(capture).__name__}
                publish()
                # Ownership recheck, not readiness/budget/live-IP validation. A bad OWN object still must exit.
                current=owned_mysql(cleanup_env)
                if current is not None:command(DOCKER+['rm','-f',current['Id']])
                absent('container',obj['Id']);absent('container',name);report['mysqlAbsent']=True
    except Exception as failure:report['failures'].append(resource_failure(failure,report['phase']))
    report['phase']='NETWORK_OWNER_READ';publish()
    try:
        if not cleanup_env.get('HTTP_BUDGET_NETWORK_ID'):
            report['phase']='NETWORK_ABSENCE_NAME';publish()
            _,name=mysql_names(cleanup_env);absent('network',name,report['nativeOperations'])
            report['networkIdentity']='NO_ACTUAL_ID_NAME_INDEPENDENTLY_ABSENT'
        else:
            net=owned_network(cleanup_env,report['nativeOperations'])
            if net is not None:
                require(not net.get('Containers'),'NETWORK_NOT_EXACT_OR_NOT_EMPTY')
                report['phase']='NETWORK_REMOVE';publish()
                # Recheck the original network immediately before deletion; no endpoint is adopted or detached.
                current=owned_network(cleanup_env,report['nativeOperations'])
                require(current is not None and not current.get('Containers'),'NETWORK_NOT_EXACT_OR_NOT_EMPTY')
                result=subprocess.run(DOCKER+['network','rm',net['Id']],capture_output=True,timeout=20)
                report['nativeOperations'].append({'operation':'NETWORK_REMOVE',**native_operation('network',net['Id'],result)})
                publish();require(result.returncode==0,'FIXED_COMMAND_FAILED')
                report['phase']='NETWORK_ABSENCE_CID';publish();absent('network',net['Id'],report['nativeOperations'])
                report['phase']='NETWORK_ABSENCE_NAME';publish();absent('network',net['Name'],report['nativeOperations'])
        report['networkAbsent']=True
    except Exception as failure:report['failures'].append(resource_failure(failure,report['phase']))
    complete=report['mysqlAbsent'] is True and report['networkAbsent'] is True and not report['failures']
    report['phase']='RESOURCE_CLEANUP_TERMINAL';report['result']='EXACT_OWN_STORAGE_REMOVED' if complete else 'UNKNOWN'
    publish()
    # This exit only certifies resource cleanup. Original bootstrap exit/cause remain in the receipt;
    # the original failed workflow step and missing application/XML/PID evidence cannot become success.
    require(complete and not report['failures'],'ABSENCE_NOT_INDEPENDENTLY_PROVEN')


def main():
    os.umask(0o077)
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase',choices=('prepare','run','finalize','cleanup-mysql','bootstrap-init','bootstrap-ready','bootstrap-failure','run-host-path','warm-cache'))
    parser.add_argument('private',nargs='?',type=Path);parser.add_argument('original_exit',nargs='?',type=int)
    args=parser.parse_args()
    if args.phase=='warm-cache':
        require(args.private is None and args.original_exit is None,'FIXED_BOOTSTRAP_ARGS')
        previous={number:signal.signal(number,cache_stop_signal) for number in (signal.SIGINT,signal.SIGTERM,signal.SIGHUP)}
        try:warm_cache();return 0
        finally:
            for number,handler in previous.items():signal.signal(number,handler)
    if args.phase=='run-host-path':
        require(args.private is None and args.original_exit is None,'FIXED_BOOTSTRAP_ARGS');return run_host_path()
    if args.phase in ('bootstrap-init','bootstrap-ready'):
        require(args.private is None and args.original_exit is None,'FIXED_BOOTSTRAP_ARGS')
        if args.phase=='bootstrap-init':bootstrap_init();return 0
        return bootstrap_ready()
    if args.phase=='bootstrap-failure':
        require(args.private is not None and args.original_exit is None and re.fullmatch('[0-9]{1,3}',str(args.private)), 'ORIGINAL_SHELL_EXIT_REQUIRED')
        bootstrap_failure(int(str(args.private)));return 0
    if args.phase=='cleanup-mysql': require(args.private is None and args.original_exit is None,'FIXED_CLEANUP_ARGS');cleanup_mysql();return 0
    require(args.private is not None,'PRIVATE_DIRECTORY_REQUIRED')
    if args.phase=='finalize': require(args.original_exit is not None,'ORIGINAL_EXIT_REQUIRED');finalize(args.private,args.original_exit);return 0
    require(args.original_exit is None,'FIXED_PHASE_ARGS')
    if args.phase=='prepare': prepare(args.private);return 0
    return run(args.private)


if __name__=='__main__':
    try: raise SystemExit(main())
    except Exception as failure:
        try:
            if len(__import__('sys').argv)>2 and __import__('sys').argv[1] in ('prepare','run'):
                path,state=load_private(Path(__import__('sys').argv[2]))
                state.setdefault('firstFailure',execution_failure(failure,state['phase'],state.get('executionSubstage')));save(path,state)
        except Exception:
            print('Combined HTTP budget failure evidence: UNKNOWN',file=__import__('sys').stderr)
        # Never publish exception text, inspect payload, argv, environment or raw SQL/logs.
        phase=__import__('sys').argv[1] if len(__import__('sys').argv)>1 and __import__('sys').argv[1] in (
                'prepare','run','finalize','cleanup-mysql','bootstrap-init','bootstrap-ready','bootstrap-failure','run-host-path','warm-cache') else 'FIXED_ARGUMENTS'
        print('Combined HTTP budget failed: '+json.dumps(resource_failure(failure,phase),sort_keys=True),file=__import__('sys').stderr)
        raise SystemExit(2)
