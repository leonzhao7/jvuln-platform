package com.jvuln.tracer;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Static callback layer for ByteBuddy {@code @Advice}-inlined bytecode.
 * <p>
 * {@code @Advice} classes cannot call instance methods on the advice class
 * itself (they are inlined into the target), so every callback here is
 * {@code public static} and delegates to the shared {@link TracerEventWriter}.
 * <p>
 * Thread-safety: depth is tracked per-thread via {@link ThreadLocal};
 * the sequence counter is a global {@link AtomicInteger}. The writer
 * reference is volatile so that a late {@code setWriter} is visible
 * to all threads immediately.
 */
final class TracerInterceptor {

    private static final AtomicInteger seqGen = new AtomicInteger(0);
    private static final ThreadLocal<Integer> depth = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    private static volatile TracerEventWriter writer;

    static void setWriter(TracerEventWriter w) {
        writer = w;
    }

    /**
     * Reset mutable state so tests run in isolation.
     * Package-private -- only called from tests.
     */
    static void resetForTest() {
        seqGen.set(0);
        depth.remove();
    }

    /**
     * Called by {@code MethodEntryAdvice} on every instrumented method entry.
     * Records current depth, then increments it for nested calls.
     */
    public static void onEnter(String className, String methodName, Object[] args) {
        try {
            int d = depth.get();
            depth.set(d + 1);

            TracerEventWriter w = writer;
            if (w == null) return;

            String[] argStrings = new String[args == null ? 0 : args.length];
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    argStrings[i] = stringify(args[i]);
                }
            }

            w.writeEvent(seqGen.incrementAndGet(), d, className, methodName, argStrings, null);
        } catch (Exception ignored) {
            // Agent must never alter control flow of the demo application
        }
    }

    /**
     * Called by {@code MethodExitAdvice} on normal method return.
     * Records the return value and decrements depth.
     */
    public static void onExit(String className, String methodName, Object ret) {
        try {
            int d = depth.get();
            if (d > 0) depth.set(d - 1);

            TracerEventWriter w = writer;
            if (w == null) return;

            w.writeEvent(seqGen.incrementAndGet(), d, className, methodName, null, stringify(ret));
        } catch (Exception ignored) {
            // Agent must never alter control flow of the demo application
        }
    }

    /**
     * Called by {@code MethodExitAdvice} when a method exits via exception.
     * Records the exception info and decrements depth.
     * The exception is never swallowed -- it is rethrown by ByteBuddy automatically.
     */
    public static void onThrow(String className, String methodName, Throwable t) {
        try {
            int d = depth.get();
            if (d > 0) depth.set(d - 1);

            TracerEventWriter w = writer;
            if (w == null) return;

            String throwStr = "throw:" + (t == null ? "null" : t.getClass().getName() + ": " + t.getMessage());
            w.writeEvent(seqGen.incrementAndGet(), d, className, methodName, null, throwStr);
        } catch (Exception ignored) {
            // Agent must never alter control flow of the demo application
        }
    }

    private static String stringify(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return (String) o;
        try {
            return o.toString();
        } catch (Exception e) {
            return o.getClass().getName() + "@?";
        }
    }
}
