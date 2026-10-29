"""Signup producer source contracts only, not transaction/MySQL evidence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java'


def method(source, marker):
    start = source.index(marker)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


class SignupIntentProducerContract(unittest.TestCase):
    def setUp(self):
        self.source = SERVICE.read_text()
        self.payment = method(self.source, 'private String completePaymentSuccess(')
        self.producer = method(self.source, 'private void registerPaymentAfterCommit(')

    def test_original_cas_result_is_frozen_before_history_repair(self):
        capture = self.payment.index('final boolean freshPaymentClaimed = paymentTransitioned;')
        self.assertLess(self.payment.index('cmsRegistrationMapper.claimPaymentSuccess('), capture)
        self.assertLess(capture, self.payment.index('if (!paymentTransitioned)'))
        self.assertIn('registration.getActivityTitle(), freshPaymentClaimed)', self.payment)

    def test_business_transaction_is_required_before_intent_lookup(self):
        self.assertLess(self.producer.index('TransactionSynchronizationManager.isActualTransactionActive()'),
                        self.producer.index('systemNotificationIntents.findIntentId('))

    def test_original_first_identity_and_fresh_only_production(self):
        self.assertIn('registrationIds.get(0)', self.producer)
        self.assertIn('"registration-signup:" + originalRegistrationId', self.producer)
        self.assertIn('intentId == null && freshPaymentClaimed', self.producer)
        self.assertNotIn('.sort(', self.producer)
        self.assertNotIn('UUID', self.producer)

    def test_frozen_payload_is_enqueued_before_after_commit(self):
        enqueue = self.producer.index('systemNotificationIntents.enqueue(')
        callback = self.producer.index('Runnable effects =')
        self.assertLess(enqueue, callback)
        self.assertIn('memberId.equals(paid.getMemberId())', self.producer[:callback])
        self.assertIn('RegistrationCheckoutServiceImpl.signupNoticeExtra(', self.producer[:callback])

    def test_after_commit_only_wakes_original_intent_not_direct_send(self):
        self.assertNotIn('.sendSystem(', self.producer)
        self.assertIn('imService.deliverSystemNotification(signupIntentId)', self.producer)
        self.assertIn('public void afterCommit()', self.producer)

    def test_qr_and_wx_existing_effects_remain(self):
        self.assertIn('generateVerificationQrcode(registrationId)', self.producer)
        self.assertIn('notifyService.notifySignupSuccess(memberId, activityTitle)', self.producer)


if __name__ == '__main__':
    unittest.main()
