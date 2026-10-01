Feature: Production hardening
  As an operator
  I want every response to carry a request ID and the standard security headers
  So that failures can be traced and browsers are protected from content sniffing and framing

  # AC1: a request ID is generated when none is supplied
  Scenario: An authenticated request gets a generated request ID
    Given alice owns a short URL "Hard100" for "https://example.com/hard" with status "ACTIVE"
    When alice lists the details of "Hard100" for hardening
    Then the hardening response status is 200
    And the hardening response carries a generated request id

  # AC1: a plain client request ID is kept, so a trace can span services
  Scenario: A client-supplied request ID is echoed back
    Given alice owns a short URL "Hard101" for "https://example.com/hard" with status "ACTIVE"
    When alice lists the details of "Hard101" for hardening with the request id "trace-0001"
    Then the hardening response carries the request id "trace-0001"

  # AC2: security headers, on a success and on an error
  Scenario Outline: Responses carry the security headers
    Given alice owns a short URL "Hard102" for "https://example.com/hard" with status "ACTIVE"
    When <user> lists the details of "Hard102" for hardening
    Then the hardening response status is <status>
    And the hardening response has the headers "X-Content-Type-Options" = "nosniff"
    And the hardening response has the headers "X-Frame-Options" = "DENY"

    Examples:
      | user  | status |
      | alice | 200    |
      | bob   | 404    |
