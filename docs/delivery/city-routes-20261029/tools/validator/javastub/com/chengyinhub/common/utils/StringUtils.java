package com.chengyinhub.common.utils;
/** Minimal stub for harness compile only. */
public class StringUtils {
    public static boolean isBlank(CharSequence s) { return s == null || s.toString().trim().isEmpty(); }
    public static boolean isNotBlank(CharSequence s) { return !isBlank(s); }
    public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }
    public static boolean isNotEmpty(CharSequence s) { return !isEmpty(s); }
}
