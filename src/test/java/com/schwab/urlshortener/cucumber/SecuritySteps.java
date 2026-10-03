package com.schwab.urlshortener.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Step definitions for {@code security.feature} (D32, AC5 of US-005). Uses the JDK HTTP client so
 * that HEAD and response headers are observed exactly as a real client sees them. Requests are
 * always anonymous: no credentials are ever sent or printed here.
 */
public class SecuritySteps {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpResponse<String> response;

    @When("an anonymous client sends {word} to {string}")
    public void anAnonymousClientSends(String method, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Then("the response status is {int}")
    public void theResponseStatusIs(int expected) {
        assertThat(response.statusCode()).isEqualTo(expected);
    }

    @Then("the response status is not {int}")
    public void theResponseStatusIsNot(int unexpected) {
        assertThat(response.statusCode()).isNotEqualTo(unexpected);
    }

    @Then("the response has no authentication challenge")
    public void theResponseHasNoAuthenticationChallenge() {
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Then("the response has a Basic challenge for realm {string}")
    public void theResponseHasABasicChallenge(String realm) {
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .contains("Basic realm=\"" + realm + "\"");
    }

    @Then("the response body has {string} equal to {string}")
    public void theResponseBodyHasFieldEqualTo(String field, String value) throws IOException {
        assertThat(objectMapper.readTree(response.body()).get(field).asText()).isEqualTo(value);
    }

    @Then("the response is an authentication-required problem")
    public void theResponseIsAnAuthenticationRequiredProblem() throws IOException {
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.path("errorCode").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(json.path("status").asInt()).isEqualTo(401);
    }

    @Then("the response is not an authentication error")
    public void theResponseIsNotAnAuthenticationError() {
        // HEAD has no body; for GET the body must not carry a security errorCode.
        assertThat(response.body()).doesNotContain("AUTHENTICATION_REQUIRED").doesNotContain("ACCESS_DENIED");
    }
}
