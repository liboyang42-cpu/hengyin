package com.chengyinhub.business.marketplace.paid;

import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.paid.store.*;
import com.chengyinhub.business.service.impl.VerifiedWorkshopPayment;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** NEW, unregistered adapter. The payment mutation and unique purchased-version grant commit together.
 * This is not a checkout/payment request, subscription extension, settlement or refund executor.
 * All future snapshot/order/refund writers must honor the OMS order lock before activation. */
public final class PersistedPaidLicenseService implements WorkshopPaidPaymentHandler {
    public enum Policy { DISABLED, VERIFIED_WECHAT_PURCHASED_VERSION_V1 }
    public static final String BIZ_TYPE = "workshop_module_v1";
    private final PaidLicenseStore store;
    private final PaidPersistenceBoundary persistence;
    private final Policy policy;
    private final Clock clock;
    public PersistedPaidLicenseService(SqlSessionTemplate session, DataSourceTransactionManager manager, Clock clock) {
        this(session,manager,clock,Policy.DISABLED);
    }
    public PersistedPaidLicenseService(SqlSessionTemplate session, DataSourceTransactionManager manager, Clock clock, Policy policy) {
        this(new PaidPersistenceBoundary(session,manager),clock,policy);
    }
    PersistedPaidLicenseService(PaidPersistenceBoundary persistence,Clock clock,Policy policy){
        this.persistence=persistence;store=persistence.mapper(PaidLicenseStore.class);
        this.clock=Objects.requireNonNull(clock);this.policy=Objects.requireNonNull(policy);
    }
    /** Configuration check only, never a payment, owner or entitlement approval. */
    public void requireCurrentPersistence(){persistence.verifyConfiguration();}
    /** Owner index reads use the same sealed pair; external-session queries cannot escape its boundary. */
    public List<com.chengyinhub.business.marketplace.paid.read.PurchasedLicenseIndexStore.Row> listOwnedIndex(long actor,Long before){
        require(policy==Policy.VERIFIED_WECHAT_PURCHASED_VERSION_V1 && actor>0 && (before==null||before>0));
        return persistence.inTransaction(()->{
            require(store.activeBuyer(actor)!=null);
            return persistence.mapper(com.chengyinhub.business.marketplace.paid.read.PurchasedLicenseIndexStore.class).listOwned(actor,before);
        });
    }
    @Override public boolean accept(VerifiedWorkshopPayment event) {
        // Existing OMS payment_time is DATETIME(0). Refuse unsupported paid-time precision
        // BEFORE a transaction or any write. Do not silently truncate accepted payment evidence.
        if(policy != Policy.VERIFIED_WECHAT_PURCHASED_VERSION_V1 || event == null || !wholeSecond(event.getPaidAt()))return false;
        try { return Boolean.TRUE.equals(persistence.inTransaction(() -> acceptLocked(event))); }
        catch(IllegalArgumentException invalid){return false;}
    }
    private boolean acceptLocked(VerifiedWorkshopPayment event) {
        persistence.requireTransaction();
        PaidOrderRow order=store.lockOrder(event.getOrderNumber());require(order!=null);
        Frozen frozen=frozen(order);PaidSnapshotRow row=frozen.row;PaidPurchaseSnapshot snapshot=frozen.snapshot;
        require(event.getAppId().equals(row.getProviderAppId()) && event.getMerchantId().equals(row.getProviderMerchantId())
                && event.getCurrency().equals(row.getCurrency()) && event.getAmountMinor()==row.getAmountMinor()
                && !event.getPaidAt().isBefore(row.getFrozenAt().toInstant()) && !event.getPaidAt().isAfter(clock.instant().plusSeconds(300)));
        PaidLicenseRow old=store.lockLicense(frozen.line.getId());
        if(old!=null){
            // Replay never creates a new grant and never reactivates a revoked/suspended one.
            verifyReceipt(order,frozen,old);
            require(event.getTransactionId().equals(old.getProviderTransactionId())
                    && event.getPaidAt().equals(old.getPaidAt().toInstant()));
            return true;
        }
        require(Long.valueOf(0).equals(order.getStatus()) && Long.valueOf(0).equals(order.getPayType())
                && order.getTransactionId()==null && order.getPaymentTime()==null && cleanAfterSale(order,frozen.line)
                && "FROZEN".equals(row.getState()) && store.activeBuyer(order.getMemberId())!=null);
        PaidLicenseRow next=new PaidLicenseRow();next.setLicenseId("w18-paid-"+frozen.line.getId());next.setOrderLineId(frozen.line.getId());
        next.setOrderId(order.getId());next.setBuyerMemberId(order.getMemberId());next.setProviderTransactionId(event.getTransactionId());
        next.setSourceEventId("wechat-success:"+event.getTransactionId());next.setSnapshotHash(row.getSnapshotHash());next.setState("ACTIVE");
        next.setPaidAt(Timestamp.from(event.getPaidAt()));next.setIssuedAt(Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)));
        // Construct the actual existing grant before writes; duration has no finite expiry or subscription dependency.
        grant(snapshot,next,ModuleLicenseGrant.Status.ACTIVE);
        persistence.requireTransaction();
        require(store.markPaid(order.getId(),event.getTransactionId(),next.getPaidAt())==1);
        persistence.requireTransaction();
        require(store.insertLicense(next)==1);
        persistence.requireTransaction();
        // Verify physical database values, not just update counts/in-memory grant construction.
        // A storage precision/trigger/default mismatch rolls back OMS and license together.
        PaidOrderRow writtenOrder=store.lockOrder(event.getOrderNumber());
        PaidLicenseRow writtenReceipt=store.lockLicense(frozen.line.getId());
        require(writtenOrder!=null && writtenReceipt!=null);
        verifyReceipt(writtenOrder,frozen,writtenReceipt);
        require(Long.valueOf(1).equals(writtenOrder.getStatus()) && cleanAfterSale(writtenOrder,frozen.line)
                && next.getState().equals(writtenReceipt.getState())
                && event.getPaidAt().equals(writtenReceipt.getPaidAt().toInstant())
                && next.getIssuedAt().toInstant().equals(writtenReceipt.getIssuedAt().toInstant()));
        persistence.requireTransaction();
        return true;
    }
    /** Owner-only read over the durable receipt. It never promotes status-only old orders to paid grants. */
    public ModuleLicenseGrant readOwned(long actor,String licenseId) {
        require(policy==Policy.VERIFIED_WECHAT_PURCHASED_VERSION_V1 && actor>0 && licenseId!=null && licenseId.matches("w18-paid-[1-9][0-9]{0,18}"));
        return persistence.inTransaction(()->captureOwnedInCurrentTransaction(actor,licenseId).grant);
    }
    /** Package-private: reserve/consume use this ONLY inside the same fixed transaction, never a detached preview. */
    LockedPurchase captureOwnedInCurrentTransaction(long actor,String licenseId){
        require(policy==Policy.VERIFIED_WECHAT_PURCHASED_VERSION_V1 && actor>0 && licenseId!=null && licenseId.matches("w18-paid-[1-9][0-9]{0,18}"));
        persistence.fenceCurrentTransaction();
        String number=store.ownedOrderNumber(actor,licenseId);require(number!=null);
        PaidOrderRow order=store.lockOrder(number);require(order!=null && Long.valueOf(actor).equals(order.getMemberId()));
        Frozen frozen=frozen(order);PaidLicenseRow receipt=store.lockLicense(frozen.line.getId());require(receipt!=null && licenseId.equals(receipt.getLicenseId()));
        require(store.activeBuyer(actor)!=null);verifyReceipt(order,frozen,receipt);
        ModuleLicenseGrant.Status effective=ModuleLicenseGrant.Status.valueOf(receipt.getState());
        if("REVOKED".equals(frozen.row.getState()))effective=ModuleLicenseGrant.Status.REVOKED;
        else if(!"FROZEN".equals(frozen.row.getState()) || !cleanAfterSale(order,frozen.line) || !retainsPurchasedUse(order.getStatus()))
            effective=effective==ModuleLicenseGrant.Status.REVOKED ? effective:ModuleLicenseGrant.Status.SUSPENDED;
        return new LockedPurchase(grant(frozen.snapshot,receipt,effective),frozen.snapshot);
    }
    static final class LockedPurchase {
        final ModuleLicenseGrant grant;final PaidPurchaseSnapshot snapshot;
        LockedPurchase(ModuleLicenseGrant grant,PaidPurchaseSnapshot snapshot){this.grant=grant;this.snapshot=snapshot;}
    }
    private Frozen frozen(PaidOrderRow order) {
        require(order.getId()!=null && order.getMemberId()!=null && order.getMemberId()>0 && BIZ_TYPE.equals(order.getBizType())
                && Integer.valueOf(0).equals(order.getDelFlag()) && order.getMerchantId()==null
                && order.getEntitlementDays()==null && order.getEntitlementMaxUsage()==null && Long.valueOf(1).equals(order.getProductQuantity()));
        List<PaidLineRow> lines=store.lockLines(order.getId());require(lines!=null && lines.size()==1);PaidLineRow line=lines.get(0);
        require(line.getId()!=null && line.getProductId()!=null && line.getProductId()>0 && Long.valueOf(1).equals(line.getQuantity())
                && order.getId().equals(line.getOrderId()));
        PaidSnapshotRow row=store.lockSnapshot(line.getId());require(row!=null && order.getId().equals(row.getOrderId())
                && order.getMemberId().equals(row.getBuyerMemberId()) && line.getProductId().equals(row.getProductId())
                && Objects.equals(line.getSkuId(),row.getSkuId()) && row.getFrozenAt()!=null
                && Arrays.asList("FROZEN","SUSPENDED","REVOKED").contains(row.getState()));
        require(row.getSnapshotJson()!=null && PaidPurchaseSnapshot.sha(row.getSnapshotJson()).equals(row.getSnapshotHash()));
        PaidPurchaseSnapshot snapshot=PaidPurchaseSnapshot.decode(utf8(row.getSnapshotJson()),row.getSourceContent(),row.getTermsDocument());
        long amount=snapshot.getOffer().getPriceMinor();require(Long.valueOf(amount).equals(row.getAmountMinor()) && snapshot.getOffer().getCurrency().equals(row.getCurrency())
                && cents(line.getPrice())==amount && cents(order.getPayAmount())==amount && cents(order.getOrderTotalAmount())==amount
                && cents(order.getProductTotalAmount())==amount && cents(order.getDeliveryFee())==0 && cents(order.getTaxFee())==0
                && cents(order.getPointsAmount())==0 && cents(order.getCouponAmount())==0);
        return new Frozen(row,line,snapshot);
    }
    private static void verifyReceipt(PaidOrderRow order,Frozen frozen,PaidLicenseRow receipt) {
        require(order.getId().equals(receipt.getOrderId()) && frozen.line.getId().equals(receipt.getOrderLineId())
                && order.getMemberId().equals(receipt.getBuyerMemberId()) && frozen.row.getSnapshotHash().equals(receipt.getSnapshotHash())
                && receipt.getLicenseId().equals("w18-paid-"+frozen.line.getId()) && receipt.getProviderTransactionId()!=null
                && receipt.getSourceEventId().equals("wechat-success:"+receipt.getProviderTransactionId())
                && receipt.getProviderTransactionId().equals(order.getTransactionId()) && Long.valueOf(1).equals(order.getPayType())
                // Historic fractional or mismatched rows remain unavailable. No repair, reissue,
                // truncation or activation is inferred from status/payment_time alone.
                && order.getPaymentTime()!=null && receipt.getPaidAt()!=null
                && wholeSecond(order.getPaymentTime().toInstant()) && wholeSecond(receipt.getPaidAt().toInstant())
                && order.getPaymentTime().toInstant().equals(receipt.getPaidAt().toInstant())
                && receipt.getIssuedAt()!=null && Arrays.asList("ACTIVE","SUSPENDED","REVOKED").contains(receipt.getState()));
    }
    private static ModuleLicenseGrant grant(PaidPurchaseSnapshot snapshot,PaidLicenseRow receipt,ModuleLicenseGrant.Status state) {
        ModuleVersionSnapshot version=snapshot.getVersion();
        return new ModuleLicenseGrant(receipt.getLicenseId(),new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,receipt.getBuyerMemberId()),snapshot.getOffer(),
                version.getVersionId(),Collections.singletonMap(version.getVersionId(),version.getContentHash()),state,
                ModuleLicenseGrant.EvidenceKind.VERIFIED_PAID_ORDER_LINE,"oms_order_item:"+receipt.getOrderLineId(),receipt.getSourceEventId(),receipt.getPaidAt().toInstant(),null);
    }
    /** Existing OMS 4 means completed, independently of its aftersale/refund fields. Completion
     * cannot expire an already verified purchased-version receipt. This is evaluated ONLY after
     * frozen source, actual payment receipt, current buyer and nondeleted order checks above.
     * Unregistered intermediate/closed/invalid states remain unavailable; no status issues a grant. */
    private static boolean retainsPurchasedUse(Long orderStatus){
        return Long.valueOf(1).equals(orderStatus) || Long.valueOf(4).equals(orderStatus);
    }
    private static boolean cleanAfterSale(PaidOrderRow order,PaidLineRow line){
        return Long.valueOf(1).equals(order.getAftersaleStatus()) && Long.valueOf(0).equals(line.getAftersaleStatus()) && line.getAftersaleOrderId()==null;
    }
    private static String utf8(byte[] bytes) {
        require(bytes!=null && bytes.length>0 && bytes.length<=65536);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("invalid frozen snapshot encoding");}
    }
    private static long cents(BigDecimal value){require(value!=null);return value.movePointRight(2).longValueExact();}
    private static boolean wholeSecond(Instant value){return value!=null && value.getNano()==0;}
    private static void require(boolean value){if(!value)throw new IllegalArgumentException("Workshop paid source unavailable or inconsistent");}
    private static final class Frozen {
        final PaidSnapshotRow row;final PaidLineRow line;final PaidPurchaseSnapshot snapshot;
        Frozen(PaidSnapshotRow row,PaidLineRow line,PaidPurchaseSnapshot snapshot){this.row=row;this.line=line;this.snapshot=snapshot;}
    }
}
