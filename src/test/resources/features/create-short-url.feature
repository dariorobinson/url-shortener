Feature: Create a short URL
  As an authenticated user
  I want to submit a long URL, optionally with a custom alias
  So that I receive a short code I can share

  # AC1
  Scenario: Creating a short URL without an alias
    Given "alice" is signed in
    When the caller creates a short URL for "https://example.com/page"
    Then the service answers with status 201
    And the Location header is the management resource of the new short code
    And the created resource has exactly the documented fields
    And the created resource has status "ACTIVE" and customAlias false and clickCount 0 and no lastAccessedAt
    And the created resource points at "https://example.com/page"
    And the short link is built from the configured base URL

  # AC2
  Scenario: Creating a short URL with a custom alias
    Given "alice" is signed in
    When the caller creates a short URL for "https://example.com/page" with alias "promo2026"
    Then the service answers with status 201
    And the created short code is "promo2026"
    And the created resource has customAlias true

  # AC3
  Scenario Outline: A custom alias that already exists is refused, whatever its status
    Given "alice" is signed in
    And a <status> short URL with code "taken01" already exists
    When the caller creates a short URL for "https://example.com/page" with alias "taken01"
    Then the service answers with status 409
    And the problem has error code "ALIAS_ALREADY_EXISTS"
    And the problem body reveals no internals
    And exactly 1 short URL exists with code "taken01"

    Examples:
      | status      |
      | ACTIVE      |
      | DEACTIVATED |
      | DELETED     |

  # AC4
  Scenario Outline: An invalid alias is refused
    Given "alice" is signed in
    When the caller creates a short URL for "https://example.com/page" with alias "<alias>"
    Then the service answers with status 400
    And the problem has error code "INVALID_ALIAS"
    And the problem names the field "alias"
    And no short URL exists for original URL "https://example.com/page"

    Examples:
      | alias                              | reason           |
      | ab                                 | too short        |
      | aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  | too long         |
      | promo_1                            | bad charset      |
      | api                                | reserved word    |
      | API                                | reserved, cased  |
      | (empty)                            | empty            |
      | (three spaces)                     | blank            |

  # AC5
  Scenario Outline: An unacceptable original URL is refused
    Given "alice" is signed in
    When the caller creates a short URL for "<url>"
    Then the service answers with status 400
    And the problem has error code "INVALID_URL"
    And the problem names the field "originalUrl"
    And no short URL exists for original URL "<url>"

    Examples:
      | url                        | reason                 |
      | ftp://example.com/file     | wrong scheme           |
      | https://u:p@example.com/x  | embedded credentials   |
      | https://short.example/x    | this service's own host|
      | https://bücher.example/x   | non-ASCII host (D49)   |

  Scenario: An original URL that is too long is refused
    Given "alice" is signed in
    When the caller creates a short URL of 2049 characters
    Then the service answers with status 400
    And the problem has error code "INVALID_URL"

  # D84
  Scenario Outline: An original URL at exactly 2048 encoded bytes is accepted
    Given "alice" is signed in
    When the caller creates a short URL of <bytes> encoded bytes made of <kind> characters
    Then the service answers with status 201
    And exactly one short URL exists for the submitted original URL

    Examples:
      | kind  | bytes |
      | CJK   | 2048  |
      | emoji | 2048  |

  # D84
  Scenario Outline: An original URL over 2048 encoded bytes is refused even within 2048 characters
    Given "alice" is signed in
    When the caller creates a short URL of <bytes> encoded bytes made of <kind> characters
    Then the service answers with status 400
    And the problem has error code "INVALID_URL"
    And the problem names the field "originalUrl"
    And no short URL exists for the submitted original URL

    Examples:
      | kind  | bytes |
      | CJK   | 2049  |
      | emoji | 2049  |

  # AC6
  Scenario: A generated code that collides is retried in a fresh transaction
    Given "alice" is signed in
    And a short URL with code "Coll1de" already exists
    And the next generated short codes are "Coll1de"
    When the caller creates a short URL for "https://example.com/ac6"
    Then the service answers with status 201
    And the created short code is not "Coll1de"
    And the generator was asked for 2 codes
    And exactly 1 short URL exists with code "Coll1de"
    And exactly 1 short URL exists for original URL "https://example.com/ac6"

  # AC7
  Scenario: When every attempt collides the request fails and nothing is committed
    Given "alice" is signed in
    And short URLs already exist for every allowed attempt and the generator will offer exactly those codes
    When the caller creates a short URL for "https://example.com/ac7"
    Then the service answers with status 503
    And the problem has error code "SHORT_CODE_UNAVAILABLE"
    And the generator was asked for the maximum number of attempts
    And no short URL exists for original URL "https://example.com/ac7"

  # AC8
  Scenario: Creating without credentials is refused
    Given the caller is not signed in
    When the caller creates a short URL for "https://example.com/page"
    Then the service answers with status 401
    And the problem has error code "AUTHENTICATION_REQUIRED"
    And no short URL exists for original URL "https://example.com/page"

  # AC9
  Scenario: Two callers racing for the same alias produce exactly one short URL
    When "alice" and "bob" create a short URL with alias "race0001" at the same time
    Then exactly one of them gets 201 and the other gets 409 with error code "ALIAS_ALREADY_EXISTS"
    And exactly 1 short URL exists with code "race0001"
    And the short URL "race0001" is owned by the caller who got 201

  # AC10
  Scenario Outline: The creator is recorded but never returned
    Given "<login>" is signed in
    When the caller creates a short URL for "https://example.com/owner"
    Then the service answers with status 201
    And the created short URL is recorded as created by "<owner>"
    And the created resource does not expose its owner

    Examples:
      | login | owner |
      | alice | alice |
      | ALICE | alice |
      | admin | admin |

  # AC11
  Scenario: The same URL submitted twice creates two different short codes
    Given "alice" is signed in
    When the caller creates a short URL for "https://example.com/same"
    And the caller creates a short URL for "https://example.com/same" again
    Then both creations answered 201 with different short codes
    And exactly 2 short URLs exist for original URL "https://example.com/same"

  # AC12
  Scenario Outline: A missing or blank original URL is refused
    Given "alice" is signed in
    When the caller posts the raw JSON body '<body>'
    Then the service answers with status 400
    And the problem has error code "VALIDATION_FAILED"
    And the problem names the field "originalUrl"
    And the problem body reveals no internals

    Examples:
      | body                        |
      | {}                          |
      | {"originalUrl":null}        |
      | {"originalUrl":""}          |
      | {"originalUrl":"   "}       |
      | {"alias":"promo2026"}       |

  # AC13
  Scenario: The API documentation describes the create operation
    When anyone requests the API documentation
    Then the documentation describes POST "/api/v1/urls" with Basic authentication
    And the documentation lists 201, 400, 401, 406, 409, 415 and 503 for the operation
    And the documentation says internationalised hosts must be submitted as punycode

  # AC14
  Scenario Outline: A malformed body is refused without leaking parser details
    Given "alice" is signed in
    When the caller posts the raw JSON body '<body>'
    Then the service answers with status 400
    And the problem has error code "MALFORMED_REQUEST"
    And the problem body reveals no internals

    Examples:
      | body                                                   | reason             |
      | {"originalUrl":                                        | unterminated       |
      |                                                        | empty body         |
      | []                                                     | array              |
      | {"originalUrl":"https://example.com/a","alais":"x"}    | unknown field      |
      | {"originalUrl":"https://example.com/a","alias":"abc","alias":"abd"} | duplicate key |

  # AC15
  Scenario Outline: A generated code that is a reserved word is retried
    Given "alice" is signed in
    And the next generated short codes are "<generated>"
    When the caller creates a short URL for "https://example.com/ac15"
    Then the service answers with status 201
    And the created short code is not a reserved word
    And the generator was asked for 2 codes
    And no short URL exists with code "<generated>"

    Examples:
      | generated |
      | Health    |
      | LOGIN     |
      | v3        |

  # AC16
  Scenario: A forged Host header does not change the short link
    Given "alice" is signed in
    When the caller creates a short URL for "https://example.com/ac16" pretending to be host "attacker.example"
    Then the service answers with status 201
    And the short link is built from the configured base URL
    And nothing in the response mentions "attacker.example"

  # AC17
  Scenario Outline: An unacceptable Accept header is refused and creates nothing
    Given "alice" is signed in
    When the caller creates a short URL for "https://example.com/ac17/<slug>" accepting "<accept>"
    Then the service answers with status 406
    And the problem has error code "NOT_ACCEPTABLE"
    And the response has no Location header
    And no short URL exists for original URL "https://example.com/ac17/<slug>"

    Examples:
      | accept                   | slug    |
      | application/xml          | xml     |
      | text/plain               | text    |
      | application/problem+json | problem |

  Scenario: An alias is still free after a refused Accept
    Given "alice" is signed in
    When the caller creates a short URL for "https://example.com/ac17" with alias "reuse001" accepting "application/xml"
    Then the service answers with status 406
    When the caller creates a short URL for "https://example.com/ac17" with alias "reuse001" accepting "application/json"
    Then the service answers with status 201
    And the created short code is "reuse001"
