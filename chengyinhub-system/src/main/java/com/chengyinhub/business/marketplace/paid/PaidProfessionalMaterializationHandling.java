package com.chengyinhub.business.marketplace.paid;
import java.util.Map;
/** Separate NEW confirmation/history port. No existing owned/body read grant satisfies this port. */
public interface PaidProfessionalMaterializationHandling {
 Map<String,Object> materialize(long authenticatedActor,PaidProfessionalMaterializationCommand intent);
 Map<String,Object> status(long authenticatedActor,String requestId);
}
