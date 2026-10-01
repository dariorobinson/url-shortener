package com.schwab.urlshortener.util.shortcode;

import static org.assertj.core.api.Assertions.assertThat;

import com.schwab.urlshortener.config.ShortCodeProperties;
import com.schwab.urlshortener.support.IntegrationTestBase;
import com.schwab.urlshortener.support.ScriptedShortCodeGenerator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Full-context wiring check for the short-code generator. Proves AC1 (default length and attempts),
 * that the application context exposes the generator bean, and that the codes it produces satisfy
 * the real PostgreSQL code-format constraint (ck_short_url_code_format). Configured length (AC2) is
 * not exercised here; unit tests build the generator by hand.
 */
class ShortCodeGeneratorWiringIT extends IntegrationTestBase {

    private static final String CODE_PATTERN = "^[A-Za-z0-9]{7}$";

    /** The production bean, selected by name so the {@code @Primary} test seam is not injected. */
    @Autowired
    @Qualifier("shortCodeGenerator")
    private ShortCodeGenerator generator;

    /** Whatever the context resolves for the interface: the scripted test seam. */
    @Autowired
    private ShortCodeGenerator primaryGenerator;

    @Autowired
    private ShortCodeProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> insertedCodes = new ArrayList<>();

    @AfterEach
    void cleanUpInsertedRows() {
        insertedCodes.forEach(code -> jdbc.update("DELETE FROM short_url WHERE short_code = ?", code));
    }

    @Test
    void shouldExposeGeneratorWithDefaultConfigurationInApplicationContext() {
        assertThat(properties.length()).isEqualTo(7);
        assertThat(properties.maxAttempts()).isEqualTo(5);
        assertThat(generator).isInstanceOf(SecureRandomShortCodeGenerator.class);
        assertThat(generator.generate()).matches(CODE_PATTERN);
    }

    @Test
    void shouldResolveTheScriptedSeamAsTheUnqualifiedGeneratorAndWrapTheProductionOne() {
        assertThat(primaryGenerator).isInstanceOf(ScriptedShortCodeGenerator.class).isNotSameAs(generator);
    }

    @Test
    void shouldGenerateCodesAcceptedByTheDatabaseCodeFormatConstraint() {
        for (int i = 0; i < 50; i++) {
            String code = generator.generate();
            int inserted = jdbc.update(
                    "INSERT INTO short_url (short_code, original_url, created_by) VALUES (?, ?, ?)",
                    code, "https://example.com/" + i, "qa-user");
            insertedCodes.add(code);

            assertThat(inserted).isEqualTo(1);
        }
        assertThat(insertedCodes).hasSize(50).allMatch(code -> code.matches(CODE_PATTERN));
    }
}
