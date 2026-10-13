"""The explicit registration IT must run against this job's empty MySQL schema."""

import pathlib
import unittest


WORKFLOW = pathlib.Path(__file__).resolve().parents[1] / '.github/workflows/ci.yml'


def assert_registration_target(source):
    step = source.split('      - name: Build and test backend\n', 1)[1].split('      - name:', 1)[0]
    required = (
        'python3 -m unittest ci/test_wechat_registration_ci_contract.py',
        'CHENGYIN_WECHAT_IDENTITY_MYSQL_IT: "true"',
        'CHENGYIN_TEST_MYSQL_HOST: 127.0.0.1',
        'CHENGYIN_TEST_MYSQL_PORT: ${{ env.IT_PORT }}',
        'CHENGYIN_TEST_MYSQL_USER: root',
        'CHENGYIN_TEST_MYSQL_PASSWORD: root',
        'CHENGYIN_TEST_MYSQL_DATABASE_BASE: chengyin_ci',
        'CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX: _${{ github.run_id }}_${{ github.run_attempt }}',
        'IT_NAME: chengyin-mybatis-it-${{ github.run_id }}-${{ github.run_attempt }}',
        'docker exec "$IT_NAME" mysql -uroot -proot',
        'SELECT COUNT(*) FROM information_schema.schemata',
        'CREATE DATABASE ${CHENGYIN_TEST_MYSQL_DATABASE_BASE}_wechat_identity${CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX}',
    )
    for value in required:
        if value not in step:
            raise AssertionError('Missing registration IT isolation contract: ' + value)
    if 'IF NOT EXISTS' in step or 'DROP DATABASE' in step:
        raise AssertionError('An existing schema must fail rather than be reused or destroyed')
    if step.index('CREATE DATABASE') > step.index('bash ci/backend-test.sh'):
        raise AssertionError('Create the empty schema before the explicit IT loop')


class WechatRegistrationCiContractTest(unittest.TestCase):
    def test_workflow_enables_real_registration_it_with_isolated_target(self):
        assert_registration_target(WORKFLOW.read_text())

    def test_missing_switch_wrong_port_and_reused_schema_are_rejected(self):
        source = WORKFLOW.read_text()
        for before, after in (
            ('CHENGYIN_WECHAT_IDENTITY_MYSQL_IT: "true"', 'CHENGYIN_WECHAT_IDENTITY_MYSQL_IT: "false"'),
            ('CHENGYIN_TEST_MYSQL_PORT: ${{ env.IT_PORT }}', 'CHENGYIN_TEST_MYSQL_PORT: 3306'),
            ('CREATE DATABASE ', 'CREATE DATABASE IF NOT EXISTS '),
            ('CREATE DATABASE ', 'REMOVED_CREATE '),
            ('SELECT COUNT(*) FROM information_schema.schemata', 'REMOVED_EMPTY_SCHEMA_CHECK'),
            ('CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX: _${{ github.run_id }}_${{ github.run_attempt }}', 'CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX: _shared'),
        ):
            with self.subTest(mutation=before):
                self.assertIn(before, source)
                with self.assertRaises(AssertionError):
                    assert_registration_target(source.replace(before, after, 1))


if __name__ == '__main__':
    unittest.main()
