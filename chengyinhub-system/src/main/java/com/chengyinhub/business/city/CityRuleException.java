package com.chengyinhub.business.city;

/** Stable internal reason code. An eventual API adapter must use the existing response envelope. */
public final class CityRuleException extends IllegalArgumentException {
    public CityRuleException(String code) { super(code); }
}
