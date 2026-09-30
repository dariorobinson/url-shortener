package com.schwab.urlshortener.cucumber;

import com.schwab.urlshortener.support.TestClock;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import org.springframework.beans.factory.annotation.Autowired;

/** Resets the controllable test clock to real time before and after every scenario. */
public class TestClockHooks {

    @Autowired
    private TestClock clock;

    @Before(order = 0)
    public void resetBefore() {
        clock.reset();
    }

    @After(order = 0)
    public void resetAfter() {
        clock.reset();
    }
}
