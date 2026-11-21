package com.chengyinhub.business.modcommerce.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * R6c workshop-iap. Offer catalogue, the app's StoreKit order state, and the Apple notification inbox.
 * Payment and license facts stay in {@code workshop_order} (provider APPLE_IAP), so creator earnings and the
 * existing install flow keep reading one table.
 */
public interface ModCommerceMapper {

    // ------------------------------------------------------------------ offers

    String OFFER_COLUMNS = "o.id, o.offer_id, o.version_id, o.market, o.currency, o.price_minor, o.product_id, o.enabled,"
            + " o.create_by, o.create_time, o.update_by, o.update_time,"
            + " v.seller_id, v.module_id, v.content_hash, v.content_json, v.terms_hash, v.terms_json, v.review_state";

    /** On sale: enabled offer of an approved release, newest first; {@code before} is an offer row id. */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select " + OFFER_COLUMNS + " from mod_commerce_offer o join workshop_version v on v.version_id = o.version_id"
            + " where o.enabled = 1 and v.review_state = 'APPROVED' and o.market = #{market} and o.id < #{before}"
            + " order by o.id desc limit #{limit}")
    List<Map<String, Object>> onSale(@Param("market") String market, @Param("before") long before, @Param("limit") int limit);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select " + OFFER_COLUMNS + " from mod_commerce_offer o join workshop_version v on v.version_id = o.version_id"
            + " where o.offer_id = #{offerId}")
    Map<String, Object> offer(@Param("offerId") String offerId);

    /** MERCHANT-9: every App Store product id of this market, on sale or not, so earlier purchases stay restorable. */
    @Select("select distinct o.product_id from mod_commerce_offer o where o.market = #{market} order by o.product_id")
    List<String> productIds(@Param("market") String market);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select " + OFFER_COLUMNS + " from mod_commerce_offer o join workshop_version v on v.version_id = o.version_id"
            + " where o.product_id = #{productId}")
    Map<String, Object> offerByProduct(@Param("productId") String productId);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select " + OFFER_COLUMNS + " from mod_commerce_offer o join workshop_version v on v.version_id = o.version_id"
            + " order by o.id desc limit 500")
    List<Map<String, Object>> adminOffers();

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select version_id, seller_id, module_id, content_hash, content_json, terms_hash, terms_json, review_state"
            + " from workshop_version where version_id = #{versionId}")
    Map<String, Object> version(@Param("versionId") String versionId);

    @Insert("insert into mod_commerce_offer(offer_id, version_id, market, currency, price_minor, product_id, enabled,"
            + " create_by, create_time, update_by, update_time)"
            + " values(#{offerId}, #{versionId}, #{market}, #{currency}, #{priceMinor}, #{productId}, 0,"
            + " #{operator}, current_timestamp(3), #{operator}, current_timestamp(3))")
    int insertOffer(Map<String, Object> row);

    @Update("update mod_commerce_offer set enabled = #{enabled}, update_by = #{operator}, update_time = current_timestamp(3)"
            + " where offer_id = #{offerId}")
    int setOfferEnabled(@Param("offerId") String offerId, @Param("enabled") int enabled, @Param("operator") String operator);

    // ------------------------------------------------------------------ orders

    @Insert("insert into workshop_order(order_no, buyer_id, request_id, request_hash, version_id, amount_fen, provider, state,"
            + " license_status, version_review_hash, currency, amount_milliunits, app_account_token, apple_bundle_id,"
            + " apple_environment, apple_product_id)"
            + " values(#{orderNo}, #{owner}, #{requestId}, #{requestHash}, #{versionId}, null, 'APPLE_IAP', 'PENDING',"
            + " 'UNPAID', null, #{currency}, #{amountMilliunits}, #{accountToken}, #{bundleId}, #{environment}, #{productId})")
    int insertWorkshopOrder(Map<String, Object> row);

    @Insert("insert into mod_commerce_order(order_no, member_id, request_id, offer_id, offer_json, client_state,"
            + " cancellation_requested, create_time, update_time)"
            + " values(#{orderNo}, #{owner}, #{requestId}, #{offerId}, #{offerJson}, 'CREATED', 0,"
            + " current_timestamp(3), current_timestamp(3))")
    int insertCommerceOrder(Map<String, Object> row);

    /** The payment / license row. Locked first, always, so every writer serialises on the same row. */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select * from workshop_order where order_no = #{orderNo} for update")
    Map<String, Object> lockWorkshopOrder(@Param("orderNo") String orderNo);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select * from mod_commerce_order where order_no = #{orderNo}")
    Map<String, Object> commerceOrder(@Param("orderNo") String orderNo);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select * from mod_commerce_order where member_id = #{member} and request_id = #{requestId}")
    Map<String, Object> commerceOrderByRequest(@Param("member") long member, @Param("requestId") String requestId);

    /** A buyer's earlier, still meaningful order for the same release (a non-consumable is bought once). */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select w.order_no from workshop_order w join mod_commerce_order c on c.order_no = w.order_no"
            + " where w.buyer_id = #{member} and w.version_id = #{versionId} and w.provider = 'APPLE_IAP'"
            + " and (w.state = 'PAID' or (w.state = 'PENDING' and c.client_state <> 'CANCELLED'))"
            + " order by case when w.state = 'PAID' then 0 when c.client_state = 'SUBMITTING' then 1 else 2 end, w.id"
            + " limit 1")
    String earlierOrder(@Param("member") long member, @Param("versionId") String versionId);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select w.order_no from workshop_order w join mod_commerce_order c on c.order_no = w.order_no"
            + " where w.provider = 'APPLE_IAP' and w.app_account_token = #{token}")
    String orderByToken(@Param("token") String token);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select w.order_no from workshop_order w join mod_commerce_order c on c.order_no = w.order_no"
            + " where w.provider = 'APPLE_IAP' and w.apple_original_transaction_id = #{originalId}")
    String orderByOriginal(@Param("originalId") String originalId);

    @Update("update mod_commerce_order set client_state = #{to}, update_time = current_timestamp(3)"
            + " where order_no = #{orderNo} and client_state = #{from}")
    int moveClientState(@Param("orderNo") String orderNo, @Param("from") String from, @Param("to") String to);

    /** The single-use StoreKit gate: only one caller ever moves NATIVE_READY to SUBMITTING. */
    @Update("update mod_commerce_order set client_state = 'SUBMITTING', dispatched_at = current_timestamp(3),"
            + " update_time = current_timestamp(3)"
            + " where order_no = #{orderNo} and client_state = 'NATIVE_READY' and cancellation_requested = 0")
    int dispatch(@Param("orderNo") String orderNo);

    @Update("update mod_commerce_order set cancellation_requested = 1, update_time = current_timestamp(3)"
            + " where order_no = #{orderNo}")
    int requestCancellation(@Param("orderNo") String orderNo);

    @Update("update mod_commerce_order set client_state = 'CANCELLED', cancellation_requested = 1,"
            + " update_time = current_timestamp(3)"
            + " where order_no = #{orderNo} and client_state in ('CREATED', 'NATIVE_READY')")
    int cancelCommerceOrder(@Param("orderNo") String orderNo);

    @Update("update workshop_order set state = 'CANCELLED' where order_no = #{orderNo} and state = 'PENDING'"
            + " and paid_at is null and apple_transaction_id is null")
    int cancelWorkshopOrder(@Param("orderNo") String orderNo);

    /** Grant exactly once: a pending (or cancelled-before-dispatch but charged) order with no bound transaction. */
    @Update("update workshop_order set state = 'PAID', license_status = 'ACTIVE', paid_at = #{paidAt},"
            + " apple_transaction_id = #{transactionId}, apple_original_transaction_id = #{originalId}, apple_fact_hash = #{factHash}"
            + " where order_no = #{orderNo} and provider = 'APPLE_IAP' and state in ('PENDING', 'CANCELLED')"
            + " and apple_transaction_id is null and paid_at is null")
    int grant(Map<String, Object> row);

    /** Refund or revocation. Binds the transaction if it arrived first, so a later grant cannot happen. */
    @Update("update workshop_order set state = 'REFUNDED', license_status = 'REVOKED',"
            + " apple_transaction_id = coalesce(apple_transaction_id, #{transactionId}),"
            + " apple_original_transaction_id = coalesce(apple_original_transaction_id, #{originalId}),"
            + " apple_fact_hash = coalesce(apple_fact_hash, #{factHash}), apple_revocation_hash = #{factHash},"
            + " paid_at = coalesce(paid_at, #{paidAt}), refunded_at = coalesce(refunded_at, #{revokedAt}),"
            + " revoked_at = coalesce(revoked_at, #{revokedAt}), revoke_reason = #{reason}"
            + " where order_no = #{orderNo} and provider = 'APPLE_IAP' and state <> 'REFUNDED'")
    int revoke(Map<String, Object> row);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select w.* from workshop_order w join mod_commerce_order c on c.order_no = w.order_no"
            + " where w.buyer_id = #{member} and w.paid_at is not null and w.id < #{before} order by w.id desc limit #{limit}")
    List<Map<String, Object>> licenses(@Param("member") long member, @Param("before") long before, @Param("limit") int limit);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select id from workshop_order where order_no = #{orderNo} and buyer_id = #{member}")
    Long orderRowId(@Param("orderNo") String orderNo, @Param("member") long member);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select id from mod_commerce_offer where offer_id = #{offerId}")
    Long offerRowId(@Param("offerId") String offerId);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select count(*) from workshop_install where order_no = #{orderNo} and draft_id is not null")
    long installations(@Param("orderNo") String orderNo);

    // ------------------------------------------------------------------ notifications

    @Insert("insert into mod_commerce_apple_notification(notification_uuid, notification_type, subtype, transaction_id,"
            + " payload_hash, state, create_time, update_time)"
            + " select #{uuid}, #{type}, #{subtype}, #{transactionId}, #{hash}, 'RECEIVED', current_timestamp(3), current_timestamp(3)"
            + " from (select 1 as one) seed"
            + " where not exists (select 1 from mod_commerce_apple_notification where notification_uuid = #{uuid})")
    int insertNotification(@Param("uuid") String uuid, @Param("type") String type, @Param("subtype") String subtype,
                           @Param("transactionId") String transactionId, @Param("hash") String hash);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("select * from mod_commerce_apple_notification where notification_uuid = #{uuid} for update")
    Map<String, Object> lockNotification(@Param("uuid") String uuid);

    @Update("update mod_commerce_apple_notification set state = #{state}, reason_code = #{reason},"
            + " update_time = current_timestamp(3) where notification_uuid = #{uuid}")
    int notificationState(@Param("uuid") String uuid, @Param("state") String state, @Param("reason") String reason);
}
