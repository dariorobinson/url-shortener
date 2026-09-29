package com.schwab.urlshortener.cucumber;

import io.cucumber.junit.platform.engine.Constants;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * JUnit Platform suite that runs the Cucumber features. Named {@code *IT} so Failsafe's include
 * (D25) picks it up alongside plain {@code *IT} classes.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = Constants.GLUE_PROPERTY_NAME, value = "com.schwab.urlshortener.cucumber")
public class CucumberIT {
}
