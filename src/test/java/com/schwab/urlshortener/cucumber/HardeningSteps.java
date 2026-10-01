package com.schwab.urlshortener.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.support.ApiClient;
import com.schwab.urlshortener.support.TestUsers;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Steps for {@code hardening.feature} (US-014 AC1, AC2). Real HTTP; users resolved through {@link TestUsers}. */
public class HardeningSteps {

    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpResponse<String> response;

    private ApiClient client() {
        return new ApiClient(port, objectMapper);
    }

    @When("{word} lists the details of {string} for hardening")
    public void listsDetails(String user, String code) throws Exception {
        response = client().send("GET", "/api/v1/urls/" + code, TestUsers.require(user), null, null);
    }

    @When("{word} lists the details of {string} for hardening with the request id {string}")
    public void listsDetailsWithRequestId(String user, String code, String requestId) throws Exception {
        response = client().send("GET", "/api/v1/urls/" + code, TestUsers.require(user), null, null,
                "X-Request-Id", requestId);
    }

    @Then("the hardening response carries a generated request id")
    public void carriesAGeneratedRequestId() {
        assertThat(response.headers().firstValue("X-Request-Id"))
                .hasValueSatisfying(id -> assertThat(id).matches(UUID_PATTERN));
    }

    @Then("the hardening response carries the request id {string}")
    public void carriesTheRequestId(String requestId) {
        assertThat(response.headers().allValues("X-Request-Id")).containsExactly(requestId);
    }

    @Then("the hardening response status is {int}")
    public void theStatusIs(int status) {
        assertThat(response.statusCode()).isEqualTo(status);
    }

    @Then("the hardening response has the headers {string} = {string}")
    public void hasTheHeader(String name, String value) {
        assertThat(response.headers().allValues(name)).containsExactly(value);
    }
}
