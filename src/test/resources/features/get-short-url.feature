Feature: Get short URL details
  As the owner of a short URL, or an administrator
  I want to retrieve the details of a short URL
  So that I can confirm what was created and see its current status

  # AC1
  Scenario: The owner retrieves an active short URL with exactly the documented fields
    Given alice owns a short URL "Alice001" for "https://example.com/mine" with status "ACTIVE"
    When alice requests the details of "Alice001"
    Then the details response status is 200
    And the details response has exactly the documented fields
    And the details response has status "ACTIVE" and points at "https://example.com/mine"
    And the details response has no clicks yet
    And the details response does not expose the owner, the id, the update time or the version

  # AC1
  Scenario: The owner retrieves a deactivated short URL and sees its status
    Given alice owns a short URL "Alice002" for "https://example.com/paused" with status "DEACTIVATED"
    When alice requests the details of "Alice002"
    Then the details response status is 200
    And the details response has exactly the documented fields
    And the details response has status "DEACTIVATED" and points at "https://example.com/paused"

  # AC1
  Scenario: The details show the live click data
    Given alice owns a short URL "Alice003" for "https://example.com/popular" with status "ACTIVE"
    And the short URL "Alice003" has 5 clicks, the last at "2026-03-01T10:15:30Z"
    When alice requests the details of "Alice003"
    Then the details response status is 200
    And the details response shows 5 clicks and a last access at "2026-03-01T10:15:30Z"

  # AC1 (D58): the shape is the create response
  Scenario: The details of a freshly created short URL equal the create response
    Given alice creates a short URL for "https://example.com/fresh"
    When alice requests the details of the created short URL
    Then the details response status is 200
    And the details response equals the create response

  # AC1 (D58)
  Scenario: The details of a short URL created with an alias equal the create response
    Given alice creates a short URL for "https://example.com/branded" with alias "Branded1"
    When alice requests the details of "Branded1"
    Then the details response status is 200
    And the details response equals the create response

  # AC2
  Scenario Outline: An administrator retrieves any owner's short URL
    Given <owner> owns a short URL "Shared01" for "https://example.com/shared" with status "<status>"
    When admin requests the details of "Shared01"
    Then the details response status is 200
    And the details response has exactly the documented fields
    And the details response has status "<status>" and points at "https://example.com/shared"

    Examples:
      | owner | status      |
      | alice | ACTIVE      |
      | alice | DEACTIVATED |
      | bob   | ACTIVE      |
      | bob   | DEACTIVATED |
      | admin | ACTIVE      |

  # AC3
  Scenario Outline: A user who does not own the short URL gets 404, never 403
    Given alice owns a short URL "Secret01" for "https://example.com/private-page" with status "<status>"
    When bob requests the details of "Secret01"
    Then the details response status is 404
    And the details response has error code "SHORT_URL_NOT_FOUND"
    And the details response reveals nothing about the short URL

    Examples:
      | status      |
      | ACTIVE      |
      | DEACTIVATED |

  # AC3
  Scenario: The owner does not see other users' short URLs either
    Given bob owns a short URL "BobOnly1" for "https://example.com/bobs" with status "ACTIVE"
    When alice requests the details of "BobOnly1"
    Then the details response status is 404
    And the details response has error code "SHORT_URL_NOT_FOUND"

  # AC4
  Scenario: A short code that does not exist gets 404
    When alice requests the details of "Nothing1"
    Then the details response status is 404
    And the details response has error code "SHORT_URL_NOT_FOUND"

  # AC4 (D72)
  Scenario Outline: A value that can never be a short code gets the same 404
    When alice requests the details of "<code>"
    Then the details response status is 404
    And the details response has error code "SHORT_URL_NOT_FOUND"

    Examples:
      | code                                | reason                      |
      | ab                                  | too short                   |
      | a-b                                 | hyphen                      |
      | a_b                                 | underscore                  |
      | aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa   | 33 characters               |
      | ab%20c                              | encoded space               |
      | abc%C3%A9                           | non-ASCII letter            |

  # AC4 (D74)
  Scenario: An unknown code and a foreign code look the same to a caller
    When bob requests the details of "Same1234"
    And the details response is remembered
    And alice owns a short URL "Same1234" for "https://example.com/hidden" with status "ACTIVE"
    And bob requests the details of "Same1234"
    Then the details response is identical to the remembered one

  # AC5
  Scenario Outline: A deleted short URL is invisible to everyone, administrators included
    Given alice owns a short URL "Gone0001" for "https://example.com/gone" with status "DELETED"
    When <caller> requests the details of "Gone0001"
    Then the details response status is 404
    And the details response has error code "SHORT_URL_NOT_FOUND"

    Examples:
      | caller |
      | alice  |
      | bob    |
      | admin  |

  # AC6
  Scenario: No credentials gets 401
    Given alice owns a short URL "Alice004" for "https://example.com/mine" with status "ACTIVE"
    When an anonymous caller requests the details of "Alice004"
    Then the details response status is 401
    And the details response has error code "AUTHENTICATION_REQUIRED"
    And the details response has a Basic challenge

  # AC6 (D30)
  Scenario: Wrong credentials get the same 401
    Given alice owns a short URL "Alice005" for "https://example.com/mine" with status "ACTIVE"
    When alice requests the details of "Alice005" with a wrong password
    Then the details response status is 401
    And the details response has error code "AUTHENTICATION_REQUIRED"

  # D6
  Scenario: Short codes are case-sensitive
    Given alice owns a short URL "Mixed1" for "https://example.com/alice-mixed" with status "ACTIVE"
    And bob owns a short URL "mixed1" for "https://example.com/bob-mixed" with status "ACTIVE"
    When alice requests the details of "mixed1"
    Then the details response status is 404
    And the details response has error code "SHORT_URL_NOT_FOUND"
    When alice requests the details of "Mixed1"
    Then the details response status is 200
    And the details response has status "ACTIVE" and points at "https://example.com/alice-mixed"

  # D71
  Scenario: After an unexpected 409 the owner can confirm the earlier create succeeded
    Given alice creates a short URL for "https://example.com/first" with alias "Lost201x"
    And alice creates a short URL for "https://example.com/retry" with alias "Lost201x" and gets 409
    When alice requests the details of "Lost201x"
    Then the details response status is 200
    And the details response has status "ACTIVE" and points at "https://example.com/first"

  # D71
  Scenario: After a 409 another user learns the alias is not theirs
    Given alice creates a short URL for "https://example.com/first" with alias "Lost202x"
    And bob creates a short URL for "https://example.com/retry" with alias "Lost202x" and gets 409
    When bob requests the details of "Lost202x"
    Then the details response status is 404
    And the details response has error code "SHORT_URL_NOT_FOUND"

  # D70
  Scenario Outline: An unacceptable Accept header gets 406
    Given alice owns a short URL "Alice006" for "https://example.com/mine" with status "ACTIVE"
    When alice requests the details of "Alice006" accepting "<accept>"
    Then the details response status is 406
    And the details response has error code "NOT_ACCEPTABLE"

    Examples:
      | accept                   |
      | application/xml          |
      | text/plain               |
      | application/problem+json |

  # D70
  Scenario: An acceptable Accept header still gets the details
    Given alice owns a short URL "Alice007" for "https://example.com/mine" with status "ACTIVE"
    When alice requests the details of "Alice007" accepting "application/json"
    Then the details response status is 200

  # D73
  Scenario: Details are never cached
    Given alice owns a short URL "Alice008" for "https://example.com/mine" with status "ACTIVE"
    When alice requests the details of "Alice008"
    Then the details response status is 200
    And the details response must not be cached

  # Reading is side-effect free
  Scenario: Reading the details changes nothing
    Given alice owns a short URL "Alice009" for "https://example.com/mine" with status "ACTIVE"
    And the short URL "Alice009" has 5 clicks, the last at "2026-03-01T10:15:30Z"
    When the stored state of "Alice009" is remembered
    And alice requests the details of "Alice009"
    And admin requests the details of "Alice009"
    Then the details response status is 200
    And the stored state of "Alice009" is unchanged

  # HEAD (management API): recorded behaviour, same rules as GET.
  # A HEAD 404 has no body, so errorCode cannot be asserted; the 200 on the same path in this scenario
  # proves the mapping was reached, so the 404 is not the unmapped RESOURCE_NOT_FOUND.
  Scenario: HEAD follows the same visibility rules as GET but sends no body
    Given alice owns a short URL "Alice010" for "https://example.com/mine" with status "ACTIVE"
    When alice sends HEAD for the details of "Alice010"
    Then the details response status is 200
    And the details response has no body
    When bob sends HEAD for the details of "Alice010"
    Then the details response status is 404
    And the details response has no body
