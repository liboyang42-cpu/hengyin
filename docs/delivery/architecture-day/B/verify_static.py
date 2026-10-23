"""Read-only candidate scope / cached SDK symbol checks. Does not run Java or tests."""
from pathlib import Path
import struct
import subprocess
import sys
import hashlib
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[4]
BASE = "2ec68899099a1e250df0949a8383bf85b5d3ffe0"
ALLOWED = {
    *[f"chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/{name}.java" for name in (
        "WechatPayServiceImpl", "MerchantCommerceService", "ClubMembershipServiceImpl", "RegistrationCheckoutServiceImpl")],
    "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCoopController.java",
    *[f"chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/{name}.java" for name in (
        "WechatPayTransferClassificationTest", "WechatPayCallbackFactsTest", "WechatPayCallbackForgedSignatureTest", "WechatPayOrderQueryTest",
        "WechatPaymentIntentLockTest", "WechatPaymentIntentTestSupport", "WechatPaymentIntentMySqlIT", "WechatPayAppChannelTest",
        "WechatPayNotifyUrlTest", "WechatPayRefundNotifyUrlTest", "WechatPayReadinessTest", "RefundVerifiedFactsTest")],
    *[f"chengyinhub-admin/src/test/java/com/chengyinhub/business/service/{name}.java" for name in (
        "MerchantCommerceServiceTest", "ClubMembershipServiceImplTest", "RegistrationFundsMybatisIntegrationTest")],
    "chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiCoopDepositStatusTest.java",
}



def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()


def methods(data):
    """Read method name/descriptors without loading bytecode into a JVM."""
    pos = 8

    def u2():
        nonlocal pos
        value = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        return value

    pool = [None] * u2()
    i = 1
    while i < len(pool):
        tag = data[pos]
        pos += 1
        if tag == 1:
            size = u2()
            pool[i] = data[pos:pos + size].decode("utf-8", errors="replace")
            pos += size
        else:
            pos += {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4,
                    11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}[tag]
            if tag in (5, 6):
                i += 1
        i += 1
    pos += 6
    interface_count = u2()
    pos += 2 * interface_count

    def members():
        nonlocal pos
        found = set()
        count = u2()
        for _ in range(count):
            u2()
            name, descriptor = pool[u2()], pool[u2()]
            found.add((name, descriptor))
            attributes = u2()
            for _ in range(attributes):
                u2()
                size = struct.unpack_from(">I", data, pos)[0]
                pos += 4 + size
        return found

    members()  # fields
    return members()


print("Baseline:", BASE)
print("Historical HEAD (new candidate is uncommitted):", git("rev-parse", "HEAD"))
print("Branch:", git("branch", "--show-current"))
changed = set(git("diff", "--name-only", BASE).splitlines())
changed.update(git("ls-files", "--others", "--exclude-standard").splitlines())
assert all(path in ALLOWED or path.startswith("docs/delivery/architecture-day/B/") for path in changed), changed
print("Scope check: PASS")
assert not git("diff", "--check", BASE)
print("git diff --check baseline: PASS (no output)")
for path in sorted(ALLOWED):
    print("Working file SHA256:", hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), path)
for name in ("WechatPayTransferClassificationTest", "WechatPayCallbackFactsTest", "WechatPayCallbackForgedSignatureTest", "WechatPayOrderQueryTest", "WechatPaymentIntentLockTest", "WechatPaymentIntentMySqlIT"):
    path = ROOT / f"chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/{name}.java"
    print(name, "source @Test methods:", path.read_text().count("@Test"), "executed: 0")

if len(sys.argv) != 2:
    raise SystemExit("Pass the approved existing wechatpay-java 0.2.15 JAR path; do not download credentials.")
checks = {
    "payments/jsapi/JsapiService": {("queryOrderByOutTradeNo", "(Lcom/wechat/pay/java/service/payments/jsapi/model/QueryOrderByOutTradeNoRequest;)Lcom/wechat/pay/java/service/payments/model/Transaction;")},
    "transferbatch/model/InitiateBatchTransferResponse": {("getOutBatchNo", "()Ljava/lang/String;"), ("getBatchId", "()Ljava/lang/String;")},
    "transferbatch/model/TransferBatchGet": {("getTotalNum", "()Ljava/lang/Integer;"), ("getTotalAmount", "()Ljava/lang/Long;"), ("getMchid", "()Ljava/lang/String;")},
    "transferbatch/model/TransferDetailEntity": {("getTransferAmount", "()Ljava/lang/Long;"), ("getOutDetailNo", "()Ljava/lang/String;")},
    "payments/model/Transaction": {("getAppid", "()Ljava/lang/String;"), ("getMchid", "()Ljava/lang/String;"),
                                    ("getTradeType", "()Lcom/wechat/pay/java/service/payments/model/Transaction$TradeTypeEnum;")},
    "payments/model/TransactionAmount": {("getTotal", "()Ljava/lang/Integer;"), ("getCurrency", "()Ljava/lang/String;")},
}
with ZipFile(sys.argv[1]) as sdk:
    for name, expected in checks.items():
        available = methods(sdk.read(f"com/wechat/pay/java/service/{name}.class"))
        assert expected <= available, (name, expected - available)
        print("SDK symbols: PASS", name)
print("Java compile/JUnit/MySQL/Redis/real payment: NOT RUN")
