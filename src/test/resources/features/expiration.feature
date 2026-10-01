Feature: URL expiration
  As a link owner
  I want to give a short URL an optional expiry that I can change or remove
  So that a link stops redirecting when it is no longer valid, without losing its code or its analytics

  Background:
    Given the clock is fixed at "2026-09-30T12:00:00Z"

  # AC1 (D106, D107): an absolute expiry with an explicit offset, stored and returned in UTC
  Scenario: Creating a short URL with an expiry
    When alice creates a short URL "Exp1001" with the expiry value "\"2026-09-30T15:00:00+02:00\""
    Then the expiry response status is 201
    And the expiry response shows the expiry "2026-09-30T13:00:00Z" and expired false
    And the stored expiry of "Exp1001" is "2026-09-30T13:00:00Z"

  # AC1 (D106): no expiry means the link never expires
  Scenario: Creating a short URL without an expiry
    When alice creates a short URL "Exp1002" without an expiry
    Then the expiry response status is 201
    And the expiry response shows no expiry and expired false

  # AC2 (D124): not in the future, or beyond the horizon, is a validation failure that creates nothing
  Scenario Outline: Creating a short URL with an unacceptable expiry
    When alice creates a short URL "Exp1003" with the expiry value "<value>"
    Then the expiry response status is 400
    And the expiry response has error code "VALIDATION_FAILED"
    And the expiry response names only the field "expiresAt"
    And no short URL was stored with code "Exp1003"

    Examples:
      | value                        |
      | \"2026-09-30T11:59:59Z\"     |
      | \"2026-09-30T12:00:00Z\"     |
      | \"2036-09-30T12:00:00.000001Z\" |

  # AC2 (D123): a number or a date-time without an offset cannot be read
  Scenario Outline: Creating a short URL with an expiry that is not a strict timestamp
    When alice creates a short URL "Exp1004" with the expiry value "<value>"
    Then the expiry response status is 400
    And the expiry response has error code "MALFORMED_REQUEST"
    And no short URL was stored with code "Exp1004"

    Examples:
      | value                     |
      | 1790000000                |
      | \"2026-10-01T00:00:00\"   |

  # AC3 (D109-D111, D117, D119): gone from the expiry instant, never cached, never counted
  Scenario: Following a short link before and after its expiry
    Given alice has a short URL "Exp1005" expiring at "2026-09-30T13:00:00Z"
    And the clock is fixed at "2026-09-30T12:59:59.999999Z"
    When an anonymous visitor follows the short link "Exp1005"
    Then the redirect response status is 302
    Given the clock is fixed at "2026-09-30T13:00:00Z"
    When an anonymous visitor follows the short link "Exp1005"
    Then the redirect response status is 410
    And the redirect response has error code "SHORT_URL_EXPIRED"
    And the redirect response cache control is exactly "no-store"
    And the redirect response has no Location header
    And the stored click count of "Exp1005" is 1

  # AC3 (D119): HEAD on an expired link
  Scenario: Checking an expired short link with HEAD
    Given alice has a short URL "Exp1006" expiring at "2026-09-30T13:00:00Z"
    And the clock is fixed at "2026-09-30T14:00:00Z"
    When an anonymous visitor sends HEAD for the short link "Exp1006"
    Then the redirect response status is 410
    And the redirect response cache control is exactly "no-store"
    And the stored click count of "Exp1006" is 0

  # AC4 (D113): deactivated wins over expired
  Scenario: A deactivated link that has also expired is not found
    Given alice has a short URL "Exp1007" expiring at "2026-09-30T13:00:00Z"
    And alice deactivates the short URL "Exp1007"
    And the clock is fixed at "2026-09-30T14:00:00Z"
    When an anonymous visitor follows the short link "Exp1007"
    Then the redirect response status is 404
    And the redirect response has error code "SHORT_URL_NOT_FOUND"

  # AC5 (D114): extend, then clear
  Scenario: Changing and clearing the expiry
    Given alice has a short URL "Exp1008" expiring at "2026-09-30T13:00:00Z"
    When alice changes the expiry of "Exp1008" with the body "{\"expiresAt\":\"2026-10-31T00:00:00Z\"}"
    Then the expiry response status is 200
    And the stored expiry of "Exp1008" is "2026-10-31T00:00:00Z"
    When alice changes the expiry of "Exp1008" with the body "{\"expiresAt\":null}"
    Then the expiry response status is 200
    And the expiry response shows no expiry and expired false
    And the stored expiry of "Exp1008" is cleared

  # AC5 (D126): a redundant active fails the whole request and nothing is applied
  Scenario: A combined change with a redundant active changes nothing
    Given alice has a short URL "Exp1009" expiring at "2026-09-30T13:00:00Z"
    When alice changes the expiry of "Exp1009" with the body "{\"active\":true,\"expiresAt\":\"2026-10-31T00:00:00Z\"}"
    Then the expiry response status is 409
    And the expiry response has error code "SHORT_URL_ALREADY_ACTIVE"
    And the stored expiry of "Exp1009" is "2026-09-30T13:00:00Z"

  # AC5 (D127, D4): a past expiry is rejected; another user's link is not found
  Scenario: A past expiry and another user's link are both refused
    Given alice has a short URL "Exp1010" expiring at "2026-09-30T13:00:00Z"
    When alice changes the expiry of "Exp1010" with the body "{\"expiresAt\":\"2026-09-30T11:00:00Z\"}"
    Then the expiry response status is 400
    And the expiry response names only the field "expiresAt"
    When bob changes the expiry of "Exp1010" with the body "{\"expiresAt\":null}"
    Then the expiry response status is 404
    And the expiry response has error code "SHORT_URL_NOT_FOUND"
    And the stored expiry of "Exp1010" is "2026-09-30T13:00:00Z"

  # AC6 (D115, D116): revival, and an expired code is never reused
  Scenario: Reviving an expired link and trying to reuse its code
    Given alice has a short URL "Exp1011" expiring at "2026-09-30T13:00:00Z"
    And the clock is fixed at "2026-09-30T14:00:00Z"
    When alice creates a short URL "Exp1011" without an expiry
    Then the expiry response status is 409
    And the expiry response has error code "ALIAS_ALREADY_EXISTS"
    When alice changes the expiry of "Exp1011" with the body "{\"expiresAt\":\"2026-10-31T00:00:00Z\"}"
    And an anonymous visitor follows the short link "Exp1011"
    Then the redirect response status is 302

  # AC7 (D117, D118): details and stats survive expiry
  Scenario: The owner still sees the details and the stats of an expired link
    Given alice has a short URL "Exp1012" expiring at "2026-09-30T13:00:00Z"
    And the clock is fixed at "2026-09-30T14:00:00Z"
    When alice reads the details of "Exp1012" for expiry
    Then the expiry response status is 200
    And the expiry response shows the expiry "2026-09-30T13:00:00Z" and expired true
    When alice reads the stats of "Exp1012" for expiry
    Then the expiry response status is 200
    And the expiry response shows the expiry "2026-09-30T13:00:00Z" and expired true
