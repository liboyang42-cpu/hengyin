package com.chengyinhub.business.service.notify;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.service.impl.TransportResponse;
import com.chengyinhub.business.service.impl.WebhookTransport;

/**
 * C09 隔离夹具：内存原回执 / 投递日志 / 设备登记，以及不触网的 WebhookTransport。
 */
final class InMemoryNotifyFixtures {

    static InetAddress publicTestNet() {
        return ipv4(203, 0, 113, 10);
    }

    static InetAddress loopback() {
        return ipv4(127, 0, 0, 1);
    }

    static InetAddress rfc1918() {
        return ipv4(10, 0, 0, 1);
    }

    static InetAddress metadata() {
        return ipv4(169, 254, 169, 254);
    }

    static InetAddress ipv4(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class MemoryReceiptStore implements ImmutableReceiptProducer.OriginalReceiptStore {
        private final Map<String, ImmutableReceiptProducer.OriginalReceipt> rows =
                new LinkedHashMap<String, ImmutableReceiptProducer.OriginalReceipt>();

        @Override
        public ImmutableReceiptProducer.OriginalReceipt find(String requestId) {
            return rows.get(requestId);
        }

        @Override
        public void insert(ImmutableReceiptProducer.OriginalReceipt receipt) {
            rows.put(receipt.requestId, receipt);
        }

        int size() {
            return rows.size();
        }
    }

    static final class MemoryReviewStore implements AttachmentReviewProducer.ReviewReceiptStore {
        private final Map<String, AttachmentReviewProducer.ReviewReceipt> rows =
                new LinkedHashMap<String, AttachmentReviewProducer.ReviewReceipt>();

        @Override
        public AttachmentReviewProducer.ReviewReceipt find(String requestId) {
            return rows.get(requestId);
        }

        @Override
        public void insert(AttachmentReviewProducer.ReviewReceipt receipt) {
            rows.put(receipt.requestId, receipt);
        }

        @Override
        public void replaceSameIdentity(AttachmentReviewProducer.ReviewReceipt receipt) {
            rows.put(receipt.requestId, receipt);
        }

        int size() {
            return rows.size();
        }
    }

    static final class MemoryDeviceStore implements DevicePushRegistrationLifecycle.RegistrationStore {
        private final Map<String, DevicePushRegistrationLifecycle.Registration> rows =
                new LinkedHashMap<String, DevicePushRegistrationLifecycle.Registration>();

        @Override
        public DevicePushRegistrationLifecycle.Registration find(String deviceId) {
            return rows.get(deviceId);
        }

        @Override
        public void insert(DevicePushRegistrationLifecycle.Registration registration) {
            rows.put(registration.deviceId, registration);
        }

        @Override
        public void replaceSameIdentity(DevicePushRegistrationLifecycle.Registration registration) {
            rows.put(registration.deviceId, registration);
        }

        int size() {
            return rows.size();
        }
    }

    static final class MemoryDeliveryStore implements WebhookDeliveryLogProducer.DeliveryLogStore {
        private final Map<String, WebhookDeliveryLogProducer.DeliveryLog> rows =
                new LinkedHashMap<String, WebhookDeliveryLogProducer.DeliveryLog>();

        @Override
        public WebhookDeliveryLogProducer.DeliveryLog findByEventId(String eventId) {
            return rows.get(eventId);
        }

        @Override
        public void insert(WebhookDeliveryLogProducer.DeliveryLog log) {
            rows.put(log.eventId, log);
        }

        @Override
        public void replaceSameIdentity(WebhookDeliveryLogProducer.DeliveryLog log) {
            rows.put(log.eventId, log);
        }

        int size() {
            return rows.size();
        }
    }

    static final class FixedNonceSource implements WebhookDeliveryLogProducer.NonceSource {
        private final String nonce;

        FixedNonceSource(String nonce) {
            this.nonce = nonce;
        }

        @Override
        public String nextNonce(String eventId) {
            return nonce;
        }
    }

    /** 只记录调用，绝不打开套接字 / 解析 DNS。 */
    static final class RecordingTransport implements WebhookTransport {
        final List<String> urls = new ArrayList<String>();
        final List<String> bodies = new ArrayList<String>();
        private TransportResponse response;

        RecordingTransport(int statusCode, String body) {
            this.response = new TransportResponse(statusCode, body);
        }

        void setResponse(int statusCode, String body) {
            this.response = new TransportResponse(statusCode, body);
        }

        @Override
        public TransportResponse post(String url, String jsonBody) {
            if (url != null && (url.startsWith("http://127.") || url.contains("169.254.169.254"))) {
                throw new AssertionError("隔离夹具禁止向内网发 HTTP: " + url);
            }
            urls.add(url);
            bodies.add(jsonBody);
            return response;
        }
    }

    static WebhookDeliveryLogProducer.WebhookConfig fixtureConfig(boolean enabled, boolean revoked) {
        return new WebhookDeliveryLogProducer.WebhookConfig(
                "wh-c09-fixture",
                "https://hooks.example.test/c09-e",
                "fixture-hmac-c09-not-production",
                enabled,
                revoked);
    }

    static WebhookSsrfHopGuard.Hop publicHop() {
        return WebhookSsrfHopGuard.Hop.of("https://hooks.example.test/c09-e", publicTestNet());
    }
}
