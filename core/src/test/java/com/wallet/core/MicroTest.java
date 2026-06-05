package com.wallet.core;

import java.util.Objects;

/**
 * A tiny, dependency-free test harness so the core can be verified with just a
 * JDK — no JUnit, no Gradle, no Android. Mirrors the philosophy of the sibling
 * C++ projects' microtest. Run via {@link CoreCheck}'s {@code main}.
 */
public final class MicroTest {

    private static int passed = 0;
    private static int failed = 0;

    public interface Body {
        void run() throws Exception;
    }

    private MicroTest() {}

    public static void test(String name, Body body) {
        try {
            body.run();
            System.out.println("  ok    " + name);
            passed++;
        } catch (AssertionError e) {
            System.out.println("  FAIL  " + name + " : " + e.getMessage());
            failed++;
        } catch (Throwable t) {
            System.out.println("  FAIL  " + name + " threw " + t);
            failed++;
        }
    }

    public static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    public static void eq(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void eq(long actual, long expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    public static int summary() {
        System.out.println("\n" + passed + "/" + (passed + failed) + " tests passed, " + failed + " failed");
        return failed == 0 ? 0 : 1;
    }
}
