package com.schwab.urlshortener.support;

import com.schwab.urlshortener.util.validation.LocationEncoder;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Builds URLs whose D75-encoded form (D84) is an exact number of bytes, by repeating a non-ASCII unit and padding
 * with ASCII. The result is asserted with {@link LocationEncoder#encode} by the callers, never assumed.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EncodedUrls {

    public static final String PREFIX = "https://example.com/";
    public static final String CJK = "中";
    public static final String EMOJI = "😀";

    /** Unit "kind" is "CJK" or "emoji"; others are rejected so a typo cannot silently build ASCII. */
    public static String unitFor(String kind) {
        return switch (kind) {
            case "CJK" -> CJK;
            case "emoji" -> EMOJI;
            default -> throw new IllegalArgumentException("unknown unit kind: " + kind);
        };
    }

    /** {@code PREFIX + unit*n + ASCII padding} whose encoded form is exactly {@code encodedBytes} long. */
    public static String withEncodedLength(String unit, int encodedBytes) {
        int unitBytes = LocationEncoder.encode(unit).length();
        int room = encodedBytes - PREFIX.length();
        return PREFIX + unit.repeat(room / unitBytes) + "a".repeat(room % unitBytes);
    }
}
