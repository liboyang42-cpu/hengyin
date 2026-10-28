"""SOURCE-only projection controls. Synthetic receipts are not actual Java/JAR/isolation evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('combination_capacity', Path(__file__).with_name('capacity_mixed_probe.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ControlledCombinationControls(unittest.TestCase):
    def fixture(self):
        def curve(wave, concurrency, write):
            records = []
            for index in range(24):
                kind = 'command' if write else ('replay', 'receipt', 'foreign')[index % 3]
                records.append({'kind': kind, 'intentSha256': format(wave * 24 + index + 1, '064x'),
                                'category': 'LEGAL_AUTH_REJECTION' if kind == 'foreign' else 'SUCCESS',
                                'elapsedSeconds': (index + 1) / 1000})
            return {'configuredConcurrency': concurrency, 'requestsRecorded': 24, 'durationSeconds': 1,
                    'records': records}
        sample = {key: 0 for key in ('monotonicNanos', 'heapUsedBytes', 'heapMaxBytes', 'applicationActiveConnections',
                  'applicationIdleConnections', 'fixtureActiveConnections', 'fixtureIdleConnections', 'httpActive',
                  'httpQueued', 'cleanupActive', 'cleanupQueued', 'physicalConnectionsInTwoSchemas')}
        report = {'format': 'CONTROLLED_COMBINATION_V1', 'result': 'OBSERVED_REPRESENTATIVE_COMBINATION',
                  'sourceSha': 'a' * 40, 'runId': '123', 'runAttempt': '2', 'jarSha256': 'b' * 64,
                  'schema': 'cy_e2e_w1_http_123_2', 'economicSchema': 'cy_wp14_' + 'c' * 32,
                  'serverUuid': '11111111-1111-1111-1111-111111111111', 'coreHttpExpected': 240,
                  'economicAssertionsVerified': True, 'innerOwnedCleanupVerified': True,
                  'wholeSystemCapacityAccepted': False, 'q4q8Complete': False,
                  'coreWaves': [{'concurrency': concurrency, 'newIntents': 24, 'factsVerified': True,
                                 'write': curve(index, concurrency, True),
                                 'readReplayForeign': curve(index, concurrency, False)}
                                for index, concurrency in enumerate((1, 3, 6))],
                  'additionalReadWindows': [{'phase': phase, 'factsVerified': True, 'curve': curve(0, 6, False)}
                                           for phase in ('MAP_HTTP_HELD', 'DISCONNECT', 'BAD_RESPONSE', 'RECOVERED')],
                  'samples': [{**sample, 'phase': phase, 'processCpuNanos': 'UNKNOWN'}
                              for phase in ('BEFORE', 'HELD', 'RECOVERED')],
                  'observations': [{'phase': 'HELD_COMBINATION', 'coreHttpRequests': 144, 'callbackResult': 'SUCCESS',
                                    'wrongAmountZeroEffects': True, 'singleGrant': True, 'schedulerEffects': 1,
                                    'schedulerAttempts': 3, 'aiPhysicalHandlersHeld': 4, 'mapPhysicalSlotsHeld': 4,
                                    'notificationHandlerHeld': True}]}
        report['sourceTree'] = 'e' * 40
        report['javaPid'] = 1234
        report['runtimeObservations'] = {'javaMaxHeapBytes': 1024 ** 3, 'cgroupMemory': 'UNKNOWN', 'cgroupCpu': 'UNKNOWN'}
        report['poolCaps'] = {'application': 3, 'economicFixture': 3, 'domainPoolMaximumSum': 6}
        report['retainedUnknownIds'] = [1, 2, 3, 4, 5, 6]
        report['economicFactEvidence'] = {'order': 'TPLTM_T19_ORIGINAL', 'orderId': 8, 'totalFen': 1990,
            'currency': 'CNY', 'verifiedFactId': 1, 'verifiedFactHash': '1' * 64, 'stableEffectSha256': '2' * 64,
            'proofSource': 'CONTROLLED_ORIGINAL_FIXTURE_NOT_PROVIDER_SIGNATURE'}
        names = {'com.chengyinhub.RuoYiApplication', 'com.chengyinhub.web.controller.api.ApiClubLeadController',
                 'com.chengyinhub.quartz.util.CrossDomainSchedulerStage',
                 'com.chengyinhub.business.service.impl.CrossDomainSubscriptionProvider'}
        names |= {'com.chengyinhub.business.it.' + name for name in ('CrossDomainCombinedLoadMySqlIT',
                  'CombinedCoreHttpWorkload', 'CombinedControlledProviders', 'GameplayProcessRecoveryChild',
                  'CrossDomainStorageFixture', 'CrossDomainTransportStage')}
        names |= {'com.chengyinhub.business.it.' + name for name in ('CombinedControlledProviders$1',
                  'CombinedControlledProviders$Mode', 'CombinedCoreHttpWorkload$HttpStatusFailure',
                  'CrossDomainCombinedLoadMySqlIT$Initializer')}
        report['actualClassSha256'] = {name: 'f' * 64 for name in names}
        for index, phase in enumerate(('DISCONNECT', 'BAD_RESPONSE', 'RECOVERED')):
            report['observations'].append({'phase': phase, 'providerState': 'RECEIVED' if index == 2 else 'UNKNOWN',
                'intentId': index + 40, 'noticeId': index + 50, 'providerCounters': {
                    'aiPosts': index + 1, 'mapGets': index + 5, 'noticePosts': index + 1, 'activeHandlers': 0,
                    'peakHandlers': 4, 'workerLimit': 4, 'queueLimit': 4}})
        receipt = {'sourceSha': 'a' * 40, 'classes': ['CrossDomainCombinedLoadMySqlIT'], 'state': 'PASS',
                   'profile': 'github-hosted', 'database': report['schema'], 'javaExit': 0, 'tests': 1,
                   'xmlVerified': True, 'fixtureReportVerified': True,
                   'cleanup': {'mysqlAbsent': True, 'redisStoppedAndPortFree': True}}
        return report, receipt

    def project(self, report, receipt):
        return probe.controlled_combination_projection(report, receipt, 'a' * 40, '123', '2', 'b' * 64)

    def test_exact_measured_denominators_keep_original_category_contract(self):
        report, receipt = self.fixture()
        safe = self.project(report, receipt)
        result = probe.controlled_combination_summary(safe, receipt, 'a' * 40, '123', '2', 'b' * 64)
        self.assertEqual(240, result['total']['requestsRecorded'])
        self.assertEqual(184, result['total']['counts']['SUCCESS'])
        self.assertEqual(56, result['total']['counts']['LEGAL_AUTH_REJECTION'])
        self.assertEqual(.024, result['total']['p99Seconds'])
        self.assertFalse(result['wholeSystemCapacityAccepted']); self.assertFalse(result['q4q8Complete'])
        self.assertEqual('UNKNOWN_REQUIRES_ORIGINAL_OWNER_CAPS_AND_NETWORK_READBACK', result['physicalProfile'])

    def test_unknown_unapproved_category_and_nonfinite_latency_cannot_pass(self):
        for field, value in (('category', 'UNKNOWN'), ('category', 'accepted'), ('elapsedSeconds', float('nan')),
                             ('elapsedSeconds', -1), ('kind', 'foreign')):
            report, receipt = self.fixture(); report['coreWaves'][0]['write']['records'][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(RuntimeError): self.project(report, receipt)

    def test_wrong_source_jar_run_attempt_or_self_promotion_fails(self):
        for field, value in (('sourceSha', 'd' * 40), ('jarSha256', 'd' * 64), ('runId', '124'),
                             ('runAttempt', '3'), ('wholeSystemCapacityAccepted', True), ('q4q8Complete', True),
                             ('innerOwnedCleanupVerified', False), ('result', 'PASS')):
            report, receipt = self.fixture(); report[field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError): self.project(report, receipt)

    def test_missing_xml_foreign_suite_and_unknown_cleanup_are_not_metadata_success(self):
        for field, value in (('sourceSha', 'd' * 40), ('xmlVerified', False), ('fixtureReportVerified', False),
                             ('javaExit', 98), ('tests', 2), ('classes', ['GenericGameplayHttpE2eIT']),
                             ('cleanup', {'mysqlAbsent': True, 'redisStoppedAndPortFree': 'UNKNOWN'})):
            report, receipt = self.fixture(); receipt[field] = value
            with self.subTest(field=field), self.assertRaises(RuntimeError): self.project(report, receipt)

    def test_duplicate_intent_replaced_readback_and_missing_phase_fail(self):
        report, receipt = self.fixture()
        report['coreWaves'][1]['write'] = copy.deepcopy(report['coreWaves'][0]['write'])
        report['coreWaves'][1]['write']['configuredConcurrency'] = 3
        with self.assertRaises(RuntimeError): self.project(report, receipt)
        report, receipt = self.fixture(); report['additionalReadWindows'][1]['phase'] = 'MAP_HTTP_HELD'
        with self.assertRaises(RuntimeError): self.project(report, receipt)
        report, receipt = self.fixture(); report['additionalReadWindows'][0]['curve']['records'].pop()
        with self.assertRaises(RuntimeError): self.project(report, receipt)

    def test_original_economic_and_held_physical_observations_are_required(self):
        for key, value in (('wrongAmountZeroEffects', False), ('schedulerEffects', 2), ('aiPhysicalHandlersHeld', 0)):
            report, receipt = self.fixture(); report['observations'][0][key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError): self.project(report, receipt)
        report, receipt = self.fixture(); report['observations'][1]['providerCounters']['peakHandlers'] = 5
        with self.assertRaises(RuntimeError): self.project(report, receipt)

    def test_canary_extra_payload_is_not_copied_and_record_payload_is_rejected(self):
        marker = 'CONTROLLED_PRIVATE_BODY_OR_TOKEN_CANARY'
        report, receipt = self.fixture(); report['rawBody'] = marker; report['samples'][0]['source'] = marker
        self.assertNotIn(marker, json.dumps(self.project(report, receipt)))
        report['coreWaves'][0]['write']['records'][0]['rawBody'] = marker
        with self.assertRaises(RuntimeError): self.project(report, receipt)

    def test_missing_and_string_measurements_do_not_become_inferred_cpu_or_connections(self):
        for pid in (None, True, 0, -1, '1234'):
            report, receipt = self.fixture(); report['javaPid'] = pid
            with self.subTest(pid=pid), self.assertRaises(RuntimeError): self.project(report, receipt)
        report, receipt = self.fixture(); report['samples'][0]['httpActive'] = '0'
        with self.assertRaises(RuntimeError): self.project(report, receipt)
        report, receipt = self.fixture(); report['samples'] = []
        with self.assertRaises(RuntimeError): self.project(report, receipt)


if __name__ == '__main__':
    unittest.main()
