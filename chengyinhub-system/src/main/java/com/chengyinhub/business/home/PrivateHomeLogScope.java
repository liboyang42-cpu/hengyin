package com.chengyinhub.business.home;

/** Synchronous private-home boundary. Never stores an owner, coordinate, body or evidence handle. */
public final class PrivateHomeLogScope implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();
    private final int previous;
    private PrivateHomeLogScope() { previous = depth(); DEPTH.set(previous + 1); }
    public static PrivateHomeLogScope open() { return new PrivateHomeLogScope(); }
    public static boolean active() { return depth() > 0; }
    private static int depth() { Integer value = DEPTH.get(); return value == null ? 0 : value; }
    @Override public void close() { if (previous == 0) DEPTH.remove(); else DEPTH.set(previous); }
}
