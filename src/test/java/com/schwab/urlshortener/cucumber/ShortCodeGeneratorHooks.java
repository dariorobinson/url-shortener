package com.schwab.urlshortener.cucumber;

import com.schwab.urlshortener.support.ScriptedShortCodeGenerator;
import com.schwab.urlshortener.support.ShortUrlTestData;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs for every scenario of every feature: clears the scripted generator's queue and call count
 * before and after (so no feature can inherit a queue, even if an earlier scenario failed midway),
 * and truncates {@code short_url} before each scenario. HTTP requests run on server threads
 * outside any test transaction, so table truncation, not rollback, isolates scenarios.
 */
public class ShortCodeGeneratorHooks {

    @Autowired
    private ScriptedShortCodeGenerator generator;

    @Autowired
    private JdbcTemplate jdbc;

    @Before(order = 0)
    public void resetBefore() {
        generator.reset();
        new ShortUrlTestData(jdbc).truncate();
    }

    @After(order = 0)
    public void resetAfter() {
        generator.reset();
    }
}
