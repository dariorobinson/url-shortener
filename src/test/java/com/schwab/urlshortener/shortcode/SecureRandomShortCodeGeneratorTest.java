package com.schwab.urlshortener.shortcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecureRandomShortCodeGeneratorTest {

    private static SecureRandomShortCodeGenerator generator(RandomGenerator rng, int length) {
        return new SecureRandomShortCodeGenerator(rng, length);
    }

    @Test
    void shouldGenerateSevenCharBase62CodeWithDefaultLength() {
        var gen = new SecureRandomShortCodeGenerator(new SecureRandom(), 7);

        assertThat(gen.generate()).matches("^[A-Za-z0-9]{7}$");
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 16, 31, 32})
    void shouldGenerateCodeOfExactlyConfiguredLength(int length) {
        var gen = generator(new SecureRandom(), length);

        for (int i = 0; i < 200; i++) {
            assertThat(gen.generate()).matches("^[A-Za-z0-9]{" + length + "}$");
        }
    }

    @Test
    void shouldGenerateOnlyValidUniqueCodesAcrossTenThousandSamples() {
        // Uniqueness is statistical: P(any clash in 10,000 codes of 62^7) is about 1.4e-5,
        // so this can fail in theory (roughly once per 70,000 runs). The database unique
        // constraint is the real guarantee. The real SecureRandom is used here on purpose.
        var gen = generator(new SecureRandom(), 7);
        Pattern pattern = Pattern.compile("^[A-Za-z0-9]{7}$");
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            String code = gen.generate();
            assertThat(pattern.matcher(code).matches()).as(code).isTrue();
            seen.add(code);
        }

        assertThat(seen).hasSize(10_000);
    }

    @Test
    void shouldUseEveryOneOfTheSixtyTwoCharactersOverManySamples() {
        // Seeded Random keeps this deterministic: it checks the alphabet mapping, not
        // SecureRandom's quality. 5,000 codes x 7 chars = 35,000 draws over 62 symbols.
        var gen = generator(new Random(42L), 7);
        Set<Character> seen = new HashSet<>();

        for (int i = 0; i < 5_000; i++) {
            for (char c : gen.generate().toCharArray()) {
                seen.add(c);
            }
        }

        assertThat(seen).hasSize(62);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(
                SecureRandomShortCodeGenerator.ALPHABET.chars().mapToObj(c -> (char) c).toList());
    }

    @Test
    void shouldMapRandomValuesToAlphabetPositionsDeterministically() {
        var counter = new int[] {0};
        var rng = new Random() {
            @Override
            public int nextInt(int bound) {
                return counter[0]++ % bound;
            }
        };

        assertThat(generator(rng, 3).generate()).isEqualTo("ABC");
        assertThat(generator(rng, 3).generate()).isEqualTo("DEF");
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 33, 0, -1})
    void shouldRejectLengthOutsideThreeToThirtyTwo(int length) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SecureRandomShortCodeGenerator(new SecureRandom(), length))
                .withMessageContaining(String.valueOf(length));
    }

    @Test
    void shouldRejectNullRandomSource() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SecureRandomShortCodeGenerator(null, 7));
    }
}
