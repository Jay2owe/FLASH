package flash.pipeline.runrecord;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Java 8 compatible monotonic ULID generator.
 *
 * <p>A ULID is a 128-bit value rendered as 26 Crockford base-32 characters:
 * a 48-bit millisecond timestamp (first 10 characters) followed by 80 bits of
 * randomness (last 16 characters). Because the timestamp occupies the high
 * order bits and the alphabet is in ASCII order, ULIDs sort lexicographically
 * in time order. Within a single millisecond the randomness component is
 * incremented so identifiers stay strictly increasing and collision resistant.
 *
 * <p>The full 26-character value is persisted everywhere; UI tables may show a
 * short prefix but identifiers on disk stay complete.
 */
public final class Ulid {

    /** Crockford base-32 alphabet (no I, L, O, U). Ascending in ASCII order. */
    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    public static final int LENGTH = 26;
    private static final int RANDOM_BYTES = 10; // 80 bits
    private static final long TIMESTAMP_MASK = 0xFFFFFFFFFFFFL; // 48 bits

    private static final Ulid DEFAULT = new Ulid();

    private final Random random;
    private long lastTimestamp = -1L;
    private final byte[] lastRandomness = new byte[RANDOM_BYTES];

    /** Production generator backed by {@link SecureRandom}. */
    public Ulid() {
        this(new SecureRandom());
    }

    /** Package-visible seam so tests can supply a deterministic {@link Random}. */
    Ulid(Random random) {
        this.random = random == null ? new SecureRandom() : random;
    }

    /** Generate the next monotonic ULID for the current wall clock. */
    public static String next() {
        return DEFAULT.generate(System.currentTimeMillis());
    }

    /**
     * Generate the next monotonic ULID for the supplied epoch-millis timestamp.
     * Thread-safe; monotonic even if the clock repeats or moves backwards.
     */
    public synchronized String generate(long timestamp) {
        timestamp &= TIMESTAMP_MASK;
        if (timestamp > lastTimestamp) {
            lastTimestamp = timestamp;
            random.nextBytes(lastRandomness);
        } else {
            // Same millisecond, or the clock moved backwards: stay on the last
            // timestamp and bump the randomness so ordering is preserved.
            incrementRandomness();
        }
        return encode(lastTimestamp, lastRandomness);
    }

    private void incrementRandomness() {
        for (int i = RANDOM_BYTES - 1; i >= 0; i--) {
            int value = (lastRandomness[i] & 0xFF) + 1;
            lastRandomness[i] = (byte) value;
            if (value <= 0xFF) {
                return; // no carry past this byte
            }
        }
        // 80-bit overflow within one millisecond: advance to the next timestamp
        // and reseed so the identifier keeps climbing.
        lastTimestamp = (lastTimestamp + 1) & TIMESTAMP_MASK;
        random.nextBytes(lastRandomness);
    }

    private static String encode(long timestamp, byte[] randomness) {
        char[] out = new char[LENGTH];
        out[0] = ENCODING[(int) ((timestamp >>> 45) & 0x1F)];
        out[1] = ENCODING[(int) ((timestamp >>> 40) & 0x1F)];
        out[2] = ENCODING[(int) ((timestamp >>> 35) & 0x1F)];
        out[3] = ENCODING[(int) ((timestamp >>> 30) & 0x1F)];
        out[4] = ENCODING[(int) ((timestamp >>> 25) & 0x1F)];
        out[5] = ENCODING[(int) ((timestamp >>> 20) & 0x1F)];
        out[6] = ENCODING[(int) ((timestamp >>> 15) & 0x1F)];
        out[7] = ENCODING[(int) ((timestamp >>> 10) & 0x1F)];
        out[8] = ENCODING[(int) ((timestamp >>> 5) & 0x1F)];
        out[9] = ENCODING[(int) (timestamp & 0x1F)];
        for (int i = 0; i < 16; i++) {
            out[10 + i] = ENCODING[readBits(randomness, i * 5)];
        }
        return new String(out);
    }

    /** Read five bits from {@code data}, MSB-first, starting at {@code bitPos}. */
    private static int readBits(byte[] data, int bitPos) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = bitPos + i;
            int bit = (data[b >>> 3] >> (7 - (b & 7))) & 1;
            value = (value << 1) | bit;
        }
        return value;
    }
}
