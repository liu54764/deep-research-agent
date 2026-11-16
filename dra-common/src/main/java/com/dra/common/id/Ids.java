package com.dra.common.id;

import com.dra.common.hash.Hashing;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * ULID: 48-bit millisecond timestamp + 80-bit randomness, Crockford Base32 (26 chars).
 * Time-ordered, URL-safe, non-enumerable — used for all externally visible IDs.
 */
public final class Ids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /** Stable 26-char id for idempotent at-least-once activities. */
    public static String stableId(String... parts) {
        return Hashing.sha256Hex(String.join("|", parts)).substring(0, 26).toUpperCase(Locale.ROOT);
    }

    public static String ulid() {
        return ulid(System.currentTimeMillis());
    }

    public static String ulid(long epochMillis) {
        byte[] randomness = new byte[10];
        RANDOM.nextBytes(randomness);

        char[] chars = new char[26];
        long ts = epochMillis;
        for (int i = 9; i >= 0; i--) {
            chars[i] = ALPHABET[(int) (ts & 31)];
            ts >>>= 5;
        }
        BigInteger value = new BigInteger(1, randomness);
        for (int i = 25; i >= 10; i--) {
            chars[i] = ALPHABET[value.intValue() & 31];
            value = value.shiftRight(5);
        }
        return new String(chars);
    }

    public static boolean isUlid(String value) {
        if (value == null || value.length() != 26) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean valid = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z' && c != 'I' && c != 'L' && c != 'O' && c != 'U');
            if (!valid) {
                return false;
            }
        }
        return true;
    }
}
