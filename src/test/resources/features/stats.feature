Feature: Short URL statistics
  As the owner of a short URL, or an administrator
  I want to see the total clicks and a per-day breakdown in my own time zone
  So that I can understand how the link is being used

  # New York changed its clocks on 2026-03-08 (a 23-hour day) and on 2026-11-01 (a 25-hour day). The clicks sit at
  # the exact local-midnight edges: 04:59:59.999999Z is still 7 March in New York, 05:00:00Z is 8 March, and so on.
  Background:
    Given alice owns a short URL "Stats001" for "https://example.com/stats" with status "ACTIVE"

  # AC1 (D95): the 23-hour spring-forward day
  Scenario: The daily breakdown follows the New York calendar day across the spring DST change
    Given the short URL "Stats001" has clicks at "2026-03-08T04:59:59.999999Z, 2026-03-08T05:00:00Z, 2026-03-08T06:59:59.999999Z, 2026-03-08T07:00:00Z, 2026-03-09T03:59:59.999999Z, 2026-03-09T04:00:00Z"
    When alice requests the stats of "Stats001" with query "timezone=America/New_York&from=2026-03-07&to=2026-03-09"
    Then the stats response status is 200
    And the stats response has exactly the documented fields
    And the stats response echoes timezone "America/New_York" from "2026-03-07" and to "2026-03-09"
    And the stats response daily counts are "1, 4, 1"
    And the stats response shows 6 total clicks and 6 clicks in range
    And the stats response shows the last access at "2026-03-09T04:00:00Z"

  # AC1 (D95): the 25-hour fall-back day, with both 01:30 local times
  Scenario: The daily breakdown follows the New York calendar day across the autumn DST change
    Given the short URL "Stats001" has clicks at "2026-11-01T03:59:59.999999Z, 2026-11-01T04:00:00Z, 2026-11-01T05:30:00Z, 2026-11-01T06:30:00Z, 2026-11-02T04:30:00Z, 2026-11-02T05:00:00Z"
    When alice requests the stats of "Stats001" with query "timezone=America/New_York&from=2026-10-31&to=2026-11-02"
    Then the stats response status is 200
    And the stats response daily counts are "1, 4, 1"
    And the stats response shows 6 total clicks and 6 clicks in range

  # AC1 (D95): a southern-hemisphere zone, where DST starts in October (a 23-hour day)
  Scenario: The daily breakdown follows the Sydney calendar day across its spring DST change
    Given the short URL "Stats001" has clicks at "2026-10-03T13:59:59.999999Z, 2026-10-03T14:00:00Z, 2026-10-03T15:59:59.999999Z, 2026-10-03T16:00:00Z, 2026-10-04T12:59:59.999999Z, 2026-10-04T13:00:00Z"
    When alice requests the stats of "Stats001" with query "timezone=Australia/Sydney&from=2026-10-03&to=2026-10-05"
    Then the stats response status is 200
    And the stats response daily counts are "1, 4, 1"

  # AC1 (D95): a southern-hemisphere zone, where DST ends in April (a 25-hour day)
  Scenario: The daily breakdown follows the Sydney calendar day across its autumn DST change
    Given the short URL "Stats001" has clicks at "2026-04-04T12:59:59.999999Z, 2026-04-04T13:00:00Z, 2026-04-04T15:30:00Z, 2026-04-04T16:30:00Z, 2026-04-05T13:59:59.999999Z, 2026-04-05T14:00:00Z"
    When alice requests the stats of "Stats001" with query "timezone=Australia/Sydney&from=2026-04-04&to=2026-04-06"
    Then the stats response status is 200
    And the stats response daily counts are "1, 4, 1"

  # AC2 (D10, D96): without a timezone the days are UTC days, and the response says so
  Scenario: Without a timezone the clicks are bucketed by UTC day
    Given the short URL "Stats001" has clicks at "2026-03-08T04:59:59.999999Z, 2026-03-08T05:00:00Z, 2026-03-08T06:59:59.999999Z, 2026-03-08T07:00:00Z, 2026-03-09T03:59:59.999999Z, 2026-03-09T04:00:00Z"
    When alice requests the stats of "Stats001" with query "from=2026-03-07&to=2026-03-09"
    Then the stats response status is 200
    And the stats response echoes timezone "UTC" from "2026-03-07" and to "2026-03-09"
    And the stats response daily counts are "0, 4, 2"

  # AC3 (D96, D99, D56): every non-IANA form is refused by naming the field, never echoing the value
  Scenario Outline: An invalid timezone is refused with a validation error on the timezone field
    When alice requests the stats of "Stats001" with query "<query>"
    Then the stats response status is 400
    And the stats response has error code "VALIDATION_FAILED"
    And the stats response has a validation error on only "timezone"
    And the stats response does not echo "<value>"

    Examples:
      | query                      | value            |
      | timezone=%2B05:00          | +05:00           |
      | timezone=Z                 | Z                |
      | timezone=UTC%2B5           | UTC+5            |
      | timezone=GMT-3             | GMT-3            |
      | timezone=UT                | UT               |
      | timezone=PST               | PST              |
      | timezone=america/new_york  | america/new_york |
      | timezone=Mars/Olympus_Mons | Mars/Olympus     |

  # AC3 (D96, D99): an empty timezone has no value that could be echoed, so only the field is checked
  Scenario: An empty timezone is refused with a validation error on the timezone field
    When alice requests the stats of "Stats001" with query "timezone="
    Then the stats response status is 400
    And the stats response has error code "VALIDATION_FAILED"
    And the stats response has a validation error on only "timezone"

  # AC3 (D96): IANA IDs outside the region form are still accepted, and the sign of Etc/GMT+5 is the IANA one
  Scenario Outline: A valid IANA timezone is accepted and echoed as sent
    When alice requests the stats of "Stats001" with query "<query>"
    Then the stats response status is 200
    And the stats response timezone is "<timezone>"

    Examples:
      | query                   | timezone       |
      | timezone=UTC            | UTC            |
      | timezone=Asia/Calcutta  | Asia/Calcutta  |
      | timezone=Etc/GMT%2B5    | Etc/GMT+5      |
      | timezone=Asia/Kathmandu | Asia/Kathmandu |

  # AC4 (D4)
  Scenario: A user who does not own the link gets 404
    When bob requests the stats of "Stats001"
    Then the stats response status is 404
    And the stats response has error code "SHORT_URL_NOT_FOUND"
    And the stats response reveals nothing about the short URL

  # AC5
  Scenario: An administrator reads the stats of any link
    When admin requests the stats of "Stats001"
    Then the stats response status is 200
    And the stats response has exactly the documented fields

  # AC6
  Scenario Outline: An unknown or deleted code gets 404 for everyone
    Given alice owns a short URL "Gone0001" for "https://example.com/gone" with status "DELETED"
    When <caller> requests the stats of "<code>"
    Then the stats response status is 404
    And the stats response has error code "SHORT_URL_NOT_FOUND"

    Examples:
      | caller | code     |
      | alice  | Gone0001 |
      | admin  | Gone0001 |
      | alice  | Never001 |
      | admin  | Never001 |
      | alice  | ab       |

  # AC7 (D31)
  Scenario: A caller without credentials gets 401 with a Basic challenge
    When an anonymous caller requests the stats of "Stats001"
    Then the stats response status is 401
    And the stats response has error code "AUTHENTICATION_REQUIRED"
    And the stats response has a Basic challenge

  Scenario: A caller with a wrong password gets the same 401
    When alice requests the stats of "Stats001" with a wrong password
    Then the stats response status is 401
    And the stats response has error code "AUTHENTICATION_REQUIRED"

  # AC8 (D19): a day with no clicks is listed, not omitted
  Scenario: A day without clicks is listed with zero
    Given the short URL "Stats001" has clicks at "2026-03-01T12:00:00Z, 2026-03-03T12:00:00Z"
    When alice requests the stats of "Stats001" with query "from=2026-03-01&to=2026-03-03"
    Then the stats response status is 200
    And the stats response daily counts are "1, 0, 1"
    And the stats response shows 2 total clicks and 2 clicks in range

  # AC8 (D95): Samoa skipped 30 December 2011 entirely; the date exists in the response and counts zero
  Scenario: A local date skipped by a zone transition is listed with zero
    Given the short URL "Stats001" has clicks at "2011-12-29T10:00:00Z, 2011-12-30T09:59:59.999999Z, 2011-12-30T10:00:00Z, 2011-12-31T09:59:59.999999Z, 2011-12-31T10:00:00Z"
    When alice requests the stats of "Stats001" with query "timezone=Pacific/Apia&from=2011-12-29&to=2011-12-31"
    Then the stats response status is 200
    And the stats response daily counts are "2, 0, 2"
    And the stats response shows 5 total clicks and 4 clicks in range

  # AC8 (D95): Sao Paulo skipped the hour from 00:00 to 01:00 on 2018-11-04, so that local day is 23 hours long
  Scenario: A local day that starts after a midnight DST gap is counted from the end of the gap
    Given the short URL "Stats001" has clicks at "2018-11-04T02:59:59.999999Z, 2018-11-04T03:00:00Z, 2018-11-05T01:59:59.999999Z, 2018-11-05T02:00:00Z"
    When alice requests the stats of "Stats001" with query "timezone=America/Sao_Paulo&from=2018-11-03&to=2018-11-05"
    Then the stats response status is 200
    And the stats response daily counts are "1, 2, 1"

  # AC9 (D98, D101): "today" is taken in the resolved zone; 03:30Z on 10 March is still 9 March in New York
  Scenario: Without from and to the stats cover the last 30 days ending today in UTC
    Given the clock is fixed at "2026-03-10T03:30:00Z"
    When alice requests the stats of "Stats001"
    Then the stats response status is 200
    And the stats response echoes timezone "UTC" from "2026-02-09" and to "2026-03-10"
    And the stats response has 30 daily entries

  Scenario: Without from and to the stats cover the last 30 days ending today in the requested zone
    Given the clock is fixed at "2026-03-10T03:30:00Z"
    When alice requests the stats of "Stats001" with query "timezone=America/New_York"
    Then the stats response status is 200
    And the stats response echoes timezone "America/New_York" from "2026-02-08" and to "2026-03-09"
    And the stats response has 30 daily entries

  # AC9: each default applies on its own
  Scenario: A missing to defaults to today and a missing from defaults to 29 days before to
    Given the clock is fixed at "2026-03-10T03:30:00Z"
    When alice requests the stats of "Stats001" with query "from=2026-03-01"
    Then the stats response echoes timezone "UTC" from "2026-03-01" and to "2026-03-10"
    And the stats response has 10 daily entries
    When alice requests the stats of "Stats001" with query "to=2026-03-01"
    Then the stats response echoes timezone "UTC" from "2026-01-31" and to "2026-03-01"
    And the stats response has 30 daily entries

  # AC10 (D97)
  Scenario: A single day is one entry
    Given the short URL "Stats001" has clicks at "2026-03-01T12:00:00Z, 2026-03-01T23:59:59.999999Z, 2026-03-02T00:00:00Z"
    When alice requests the stats of "Stats001" with query "from=2026-03-01&to=2026-03-01"
    Then the stats response status is 200
    And the stats response has 1 daily entries
    And the stats response daily counts are "2"

  Scenario: A window in the future is accepted and counts zero
    Given the short URL "Stats001" has clicks at "2026-03-01T12:00:00Z"
    When alice requests the stats of "Stats001" with query "from=2099-01-01&to=2099-01-03"
    Then the stats response status is 200
    And the stats response daily counts are "0, 0, 0"
    And the stats response shows 1 total clicks and 0 clicks in range

  # AC11 (D98)
  Scenario: A window of exactly 366 days is accepted
    When alice requests the stats of "Stats001" with query "from=2028-01-01&to=2028-12-31"
    Then the stats response status is 200
    And the stats response has 366 daily entries

  Scenario: A window of 367 days is refused on from
    When alice requests the stats of "Stats001" with query "from=2027-12-31&to=2028-12-31"
    Then the stats response status is 400
    And the stats response has error code "VALIDATION_FAILED"
    And the stats response has a validation error on only "from"

  # AC12 (D98, D99)
  Scenario Outline: A malformed or out-of-range date is refused by naming the parameter
    When alice requests the stats of "Stats001" with query "<query>"
    Then the stats response status is 400
    And the stats response has error code "VALIDATION_FAILED"
    And the stats response has a validation error on only "<field>"
    And the stats response does not echo "<value>"

    Examples:
      | query                         | field | value       |
      | from=2026-02-30               | from  | 2026-02-30  |
      | from=2026-2-3                 | from  | 2026-2-3    |
      | from=20260203                 | from  | 20260203    |
      | from=2026-02-03T00:00         | from  | T00:00      |
      | from=1969-12-31&to=1970-01-02 | from  | 1969-12-31  |
      | from=0000-01-01&to=1970-01-02 | from  | 0000-01-01  |
      | to=2026-02-30                 | to    | 2026-02-30  |
      | from=2026-02-01&to=2026-2-3   | to    | 2026-2-3    |
      | to=10000-01-01                | to    | 10000-01-01 |

  # AC12 (D98, D99): an empty date has no value that could be echoed, so only the field is checked
  Scenario: An empty from is refused with a validation error on the from field
    When alice requests the stats of "Stats001" with query "from="
    Then the stats response status is 400
    And the stats response has error code "VALIDATION_FAILED"
    And the stats response has a validation error on only "from"

  # AC13 (D98)
  Scenario: From after to is refused on from
    When alice requests the stats of "Stats001" with query "from=2026-03-09&to=2026-03-07"
    Then the stats response status is 400
    And the stats response has error code "VALIDATION_FAILED"
    And the stats response has a validation error on only "from"

  # AC14 (D100)
  Scenario Outline: An unknown or repeated parameter is a malformed request
    When alice requests the stats of "Stats001" with query "<query>"
    Then the stats response status is 400
    And the stats response has error code "MALFORMED_REQUEST"
    And the stats response has no validation errors
    And the stats response does not echo "<value>"

    Examples:
      | query                            | value      |
      | timeZone=UTC                     | timeZone   |
      | zzunknown=UTC                    | zzunknown  |
      | timezone=UTC&timezone=Asia/Tokyo | Asia/Tokyo |
      | from=2026-03-01&from=2026-03-02  | 2026-03-02 |
      | timezone=UTC&cache=1             | cache      |

  # AC15 (D104, D74): parameters are validated first, and neither order reveals the link
  Scenario Outline: Bad parameters on a code the caller cannot see give 400, and valid parameters give the same 404
    Given alice owns a short URL "Gone0001" for "https://example.com/gone" with status "DELETED"
    When bob requests the stats of "<code>" with query "timezone=PST"
    Then the stats response status is 400
    And the stats response has error code "VALIDATION_FAILED"
    When bob requests the stats of "<code>" with query "from=2026-03-09&to=2026-03-07"
    Then the stats response status is 400
    When bob requests the stats of "<code>" with query "tz=UTC"
    Then the stats response status is 400
    And the stats response has error code "MALFORMED_REQUEST"
    When bob requests the stats of "<code>" with query "timezone=UTC"
    Then the stats response status is 404
    And the stats response has error code "SHORT_URL_NOT_FOUND"
    And the stats response equals the 404 for an unknown code apart from the instance

    Examples:
      | code     |
      | Stats001 |
      | Never001 |
      | Gone0001 |

  # AC16 (D105, D4, D13)
  Scenario Outline: A deactivated link still has stats for its owner and an administrator
    Given alice owns a short URL "Paused01" for "https://example.com/paused" with status "DEACTIVATED"
    And the short URL "Paused01" has clicks at "2026-03-01T12:00:00Z"
    When <caller> requests the stats of "Paused01" with query "from=2026-03-01&to=2026-03-01"
    Then the stats response status is 200
    And the stats response daily counts are "1"
    And the stats response shows 1 total clicks and 1 clicks in range

    Examples:
      | caller |
      | alice  |
      | admin  |

  Scenario: A deactivated link has no stats for another user
    Given alice owns a short URL "Paused01" for "https://example.com/paused" with status "DEACTIVATED"
    When bob requests the stats of "Paused01"
    Then the stats response status is 404
    And the stats response has error code "SHORT_URL_NOT_FOUND"

  # D101: totalClicks is the stored counter, not a count of the events
  Scenario: The total is the stored click count even when it differs from the events in the window
    Given the short URL "Stats001" has 41 clicks, the last at "2026-01-01T00:00:00Z"
    When alice requests the stats of "Stats001" with query "from=2026-03-01&to=2026-03-03"
    Then the stats response status is 200
    And the stats response shows 41 total clicks and 0 clicks in range
    And the stats response shows the last access at "2026-01-01T00:00:00Z"

  Scenario: A link that was never followed has no last access
    When alice requests the stats of "Stats001"
    Then the stats response status is 200
    And the stats response shows 0 total clicks and 0 clicks in range
    And the stats response shows no last access

  # AC1 end to end: clicks recorded by the redirect appear in the stats
  Scenario: Clicks recorded by following the short link appear in the stats
    Given the clock is fixed at "2026-03-08T04:59:59.999999Z"
    When an anonymous visitor follows the short link "Stats001"
    And the clock advances by 1 seconds
    And an anonymous visitor follows the short link "Stats001"
    And alice requests the stats of "Stats001" with query "timezone=America/New_York&from=2026-03-07&to=2026-03-08"
    Then the stats response status is 200
    And the stats response daily counts are "1, 1"
    And the stats response shows 2 total clicks and 2 clicks in range
    And the stats response shows the last access at "2026-03-08T05:00:00.999999Z"

  # D94: a clock that moves backwards never moves the last access back
  Scenario: A click made when the clock is earlier does not move the last access backwards
    Given the clock is fixed at "2026-03-08T12:00:00Z"
    When an anonymous visitor follows the short link "Stats001"
    And the clock is fixed at "2026-03-08T11:00:00Z"
    And an anonymous visitor follows the short link "Stats001"
    And alice requests the stats of "Stats001" with query "from=2026-03-08&to=2026-03-08"
    Then the stats response status is 200
    And the stats response shows 2 total clicks and 2 clicks in range
    And the stats response shows the last access at "2026-03-08T12:00:00Z"

  # D18: HEAD mirrors GET with the same status and representation headers and no body
  Scenario: HEAD returns the same status and headers as GET with an empty body
    When alice requests the stats of "Stats001"
    And the stats response is remembered
    And alice sends HEAD for the stats of "Stats001"
    Then the stats response status is 200
    And the stats response has no body
    And the stats response headers equal the remembered ones apart from the framing
    And the stats response must not be cached
