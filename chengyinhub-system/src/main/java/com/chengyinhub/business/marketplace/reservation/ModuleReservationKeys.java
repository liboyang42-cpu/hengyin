package com.chengyinhub.business.marketplace.reservation;
import com.chengyinhub.business.marketplace.contract.BuyerPrincipal;
/** Server coordination facade over the EXISTING canonical identity algorithm. Keys are neither
 * authorization nor a public wire contract. Repositories must use request-before-license ordering. */
public final class ModuleReservationKeys {
    private ModuleReservationKeys() { }
    public static String request(BuyerPrincipal buyer,String requestId){return ReservationIdentity.key(buyer,requestId);}
    public static String license(String licenseId){return ReservationIdentity.license(licenseId);}
}
