Feature: Click recording on redirect
  As the system
  I want every successful GET redirect on an active link to be recorded
  So that accurate click analytics exist without ever slowing down or breaking the redirect

  # AC1 (D45): the counter, the last access and one event, all at the same microsecond-truncated clock instant
  Scenario: Following an active link records one click at the current time
    Given the clock is fixed at "2026-03-01T10:15:30.123456789Z"
    And alice owns a short URL "Click001" for "https://example.com/clicked" with status "ACTIVE"
    And the stored row of "Click001" is remembered for click recording
    When an anonymous visitor follows the short link "Click001"
    And alice requests the details of "Click001"
    Then the redirect response status is 302
    And the redirect response location is exactly "https://example.com/clicked"
    And the redirect response cache control is exactly "no-store"
    And the details response shows 1 clicks and a last access at "2026-03-01T10:15:30.123456Z"
    And the stored click events of "Click001" are exactly at "2026-03-01T10:15:30.123456Z"
    And the stored version and update time of "Click001" are unchanged for click recording

  # AC1: increments, rather than sets
  Scenario: A second click at a later time is counted as well
    Given the clock is fixed at "2026-03-01T10:15:30.123456789Z"
    And alice owns a short URL "Click002" for "https://example.com/twice" with status "ACTIVE"
    When an anonymous visitor follows the short link "Click002"
    And the clock advances by 5 seconds
    And an anonymous visitor follows the short link "Click002"
    And alice requests the details of "Click002"
    Then the details response shows 2 clicks and a last access at "2026-03-01T10:15:35.123456Z"
    And the stored click events of "Click002" are exactly at "2026-03-01T10:15:30.123456Z, 2026-03-01T10:15:35.123456Z"

  # AC2 (D9, D18): HEAD is never counted; the GET control on the same path is
  Scenario: Sending HEAD for an active link records nothing, while a GET on the same path does
    Given alice owns a short URL "Click003" for "https://example.com/head" with status "ACTIVE"
    When an anonymous visitor sends HEAD for the short link "Click003"
    And an anonymous visitor sends HEAD for the short link "Click003"
    And alice requests the details of "Click003"
    Then the redirect response status is 302
    And the details response has no clicks yet
    And the stored click events of "Click003" number 0
    When an anonymous visitor follows the short link "Click003"
    And alice requests the details of "Click003"
    Then the details response shows 1 clicks
    And the stored click events of "Click003" number 1

  # AC1 precondition (D2, D91): only an ACTIVE link is counted
  Scenario Outline: A link that does not redirect records nothing
    Given alice owns a short URL "Click004" for "https://example.com/inactive" with status "<status>"
    When an anonymous visitor follows the short link "Click004"
    Then the redirect response status is 404
    And the redirect response has error code "SHORT_URL_NOT_FOUND"
    And the stored click count of "Click004" is 0
    And the stored click events of "Click004" number 0

    Examples:
      | status      |
      | DEACTIVATED |
      | DELETED     |

  Scenario: A link deactivated through the API stops being counted
    Given alice owns a short URL "Click005" for "https://example.com/stops" with status "ACTIVE"
    When an anonymous visitor follows the short link "Click005"
    And alice deactivates the short URL "Click005"
    And an anonymous visitor follows the short link "Click005"
    Then the redirect response status is 404
    And the stored click count of "Click005" is 1
    And the stored click events of "Click005" number 1

  # AC3 (D12): fail open, with a real database failure in the click_event INSERT
  Scenario: A failing click event insert never breaks the redirect and leaves no partial click
    Given alice owns a short URL "Click006" for "https://example.com/insert-fails" with status "ACTIVE"
    And the database rejects every click event insert
    When an anonymous visitor follows the short link "Click006"
    Then the redirect response status is 302
    And the redirect response location is exactly "https://example.com/insert-fails"
    And the redirect response cache control is exactly "no-store"
    And the redirect response has an empty body and no content type
    And the stored click count of "Click006" is 0
    And the stored click events of "Click006" number 0
    When the injected click failure is removed
    And an anonymous visitor follows the short link "Click006"
    Then the stored click count of "Click006" is 1
    And the stored click events of "Click006" number 1

  # AC3 (D12): fail open, with a real database failure in the counter UPDATE
  Scenario: A failing click counter update never breaks the redirect
    Given alice owns a short URL "Click007" for "https://example.com/update-fails" with status "ACTIVE"
    And the database rejects every click counter update
    When an anonymous visitor follows the short link "Click007"
    Then the redirect response status is 302
    And the redirect response location is exactly "https://example.com/update-fails"
    And the stored click count of "Click007" is 0
    And the stored click events of "Click007" number 0
    When the injected click failure is removed
    And an anonymous visitor follows the short link "Click007"
    Then the stored click count of "Click007" is 1
    And the stored click events of "Click007" number 1

  # AC4: no lost updates
  Scenario: Fifty simultaneous visitors are all counted exactly once
    Given alice owns a short URL "Click008" for "https://example.com/busy" with status "ACTIVE"
    When 50 anonymous visitors follow the short link "Click008" at the same time
    And alice requests the details of "Click008"
    Then all simultaneous visitors were redirected to "https://example.com/busy"
    And the details response shows 50 clicks
    And the stored click events of "Click008" number 50
