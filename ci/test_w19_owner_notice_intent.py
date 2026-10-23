"""W19 producer source checks; not Java execution or MySQL transaction evidence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/MerchantOfferServiceImpl.java'


def method(source, marker):
    start = source.index(marker)
    opening = source.index('{', start)
    end, depth = opening + 1, 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


class W19OwnerNoticeProducerContract(unittest.TestCase):
    def setUp(self):
        self.source = SOURCE.read_text()
        self.withdraw = method(self.source, 'public boolean withdrawOwnChapterOffer(')

    def producer(self):
        return method(self.source, 'private void registerOwnerWithdrawalNotice(')

    def test_only_original_exit_winner_registers_owner_intent(self):
        registration = self.withdraw.index('registerOwnerWithdrawalNotice(')
        self.assertLess(self.withdraw.index('offerMapper.withdrawOwned('), registration)
        self.assertLess(self.withdraw.index('return required &&'), registration)
        self.assertNotIn('businessNotice.sendAfterCommit(', self.withdraw)

    def test_original_transaction_is_required_before_intent_lookup(self):
        producer = self.producer()
        self.assertLess(producer.index('TransactionSynchronizationManager.isActualTransactionActive()'),
                        producer.index('systemNotificationIntents.findIntentId('))
        self.assertIn('TransactionSynchronizationManager.isSynchronizationActive()', producer)

    def test_permanent_event_and_original_payload_are_enqueued_before_callback(self):
        producer = self.producer()
        self.assertIn('"chapter-supply-withdraw-owner:" + offerId', producer)
        self.assertLess(producer.index('systemNotificationIntents.enqueue('),
                        producer.index('TransactionSynchronizationManager.registerSynchronization('))
        self.assertIn('ImMessage.MSG_TEXT', producer)
        self.assertIn('title + "\\n" + body', producer)

    def test_existing_identity_is_not_rebuilt_or_sent_again_to_wx(self):
        producer = self.producer()
        self.assertIn('if (intentId == null)', producer)
        self.assertLess(producer.index('systemNotificationIntents.findIntentId('),
                        producer.index('systemNotificationIntents.enqueue('))
        self.assertIn('originalWxMessage != null', producer)

    def test_callback_only_wakes_original_im_id_without_old_fanout(self):
        producer = self.producer()
        self.assertIn('imService.deliverSystemNotification(originalIntentId)', producer)
        self.assertNotIn('.sendSystem(', producer)
        self.assertNotIn('notifyService.notifyOfficialBroadcast(', producer)
        self.assertIn('public void afterCommit()', producer)

    def test_wx_has_one_existing_executor_attempt_and_no_recovery_enqueue(self):
        wx = method(self.source, 'private void scheduleOwnerWithdrawalWx(')
        self.assertIn('ownerNoticeExecutor.schedule(', wx)
        self.assertIn('ownerNoticeWxChannel.send(originalWxMessage)', wx)
        self.assertIn('TransactionSynchronizationManager.isActualTransactionActive()', wx)
        self.assertNotIn('dispatchInNewTransaction', wx)
        self.assertNotIn('enqueue(', wx)

    def test_player_notification_and_original_business_cas_still_exist(self):
        self.assertIn('businessNotice.executeAfterCommit("chapter-supply-withdraw-players"', self.withdraw)
        self.assertIn('registrationEntitlementMapper.selectAffectedPaidMembers(', self.withdraw)
        self.assertIn('offerMapper.withdrawOwned(offerId, merchantId, why, required) != 1', self.withdraw)


if __name__ == '__main__':
    unittest.main()
