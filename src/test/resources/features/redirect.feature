Feature: Follow a short link
  As any visitor
  I want to follow a short link
  So that I am redirected to the original URL, and never learn the state of a link that does not redirect

  # AC1 (D76)
  Scenario: A visitor is redirected to exactly the stored URL and the redirect is not cacheable
    Given alice owns a short URL "Active01" for "https://Example.COM/Path/a%2fb%2F?a=1&b=two+words#top" with status "ACTIVE"
    When an anonymous visitor follows the short link "Active01"
    Then the redirect response status is 302
    And the redirect response location is exactly "https://Example.COM/Path/a%2fb%2F?a=1&b=two+words#top"
    And the redirect response cache control is exactly "no-store"
    And the redirect response has no Pragma or Expires header
    And the redirect response has an empty body and no content type

  # AC1 (D75)
  Scenario Outline: A stored URL with non-ASCII characters is redirected with UTF-8 percent-encoding
    When alice shortens "<stored>" through the API
    And an anonymous visitor follows the created short link
    Then the redirect response status is 302
    And the redirect response location is exactly "<location>"

    Examples:
      | stored                     | location                       |
      | https://example.com/café   | https://example.com/caf%C3%A9  |
      | https://example.com/中     | https://example.com/%E4%B8%AD  |
      | https://example.com/a?q=ü  | https://example.com/a?q=%C3%BC |

  # AC2
  Scenario: An unknown code is not found
    When an anonymous visitor follows the short link "Nothing1"
    Then the redirect response status is 404
    And the redirect response has error code "SHORT_URL_NOT_FOUND"
    And the redirect response has no Location header

  # AC3, AC4 (D2)
  Scenario Outline: A deactivated or deleted link looks exactly like an unknown one
    Given alice owns a short URL "Gone1234" for "https://example.com/gone" with status "<status>"
    When an anonymous visitor follows the short link "Gone1234"
    Then the redirect response status is 404
    And the redirect response has error code "SHORT_URL_NOT_FOUND"
    And the redirect response has no Location header
    And the redirect response is the same problem as for an unknown code, except for its instance

    Examples:
      | status      |
      | DEACTIVATED |
      | DELETED     |

  # AC5 (D78)
  Scenario Outline: Infrastructure paths are served by their own handlers, never by the redirect
    When an anonymous visitor sends GET to "<path>"
    Then the redirect response status is <status>
    And the redirect response has no error code "SHORT_URL_NOT_FOUND"

    Examples:
      | path                        | status |
      | /actuator/health            | 200    |
      | /v3/api-docs                | 200    |
      | /v3/api-docs/swagger-config | 200    |
      | /swagger-ui/index.html      | 200    |
      | /swagger-ui.html            | 302    |
      | /api                        | 401    |
      | /actuator                   | 401    |

  # AC5 (D78)
  Scenario: An authenticated request for the bare reserved word api is a not-found, never a redirect
    When alice sends GET to "/api"
    Then the redirect response status is 404
    And the redirect response has error code "SHORT_URL_NOT_FOUND"
    And the redirect response has no Location header

  # AC5: the management API keeps working next to the redirect
  Scenario: The management API is not shadowed by the redirect
    Given alice owns a short URL "Mine1234" for "https://example.com/mine" with status "ACTIVE"
    When alice sends GET to "/api/v1/urls/Mine1234"
    Then the redirect response status is 200
    And the redirect response has no Location header

  # AC6 (D72, D77)
  Scenario Outline: A code with the wrong shape is a not-found identical to an unknown code
    When an anonymous visitor follows the short link "<code>"
    Then the redirect response status is 404
    And the redirect response has error code "SHORT_URL_NOT_FOUND"
    And the redirect response is the same problem as for an unknown code, except for its instance

    Examples:
      | code                                |
      | ab                                  |
      | a_b                                 |
      | abc.json                            |
      | favicon.ico                         |
      | aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa   |

  # AC7 (D18, D32)
  Scenario: HEAD gives the same redirect headers as GET, with no body
    Given alice owns a short URL "Active02" for "https://example.com/head?x=1#f" with status "ACTIVE"
    When an anonymous visitor follows the short link "Active02"
    And the redirect response is remembered
    And an anonymous visitor sends HEAD for the short link "Active02"
    Then the redirect response status is 302
    And the redirect response location is exactly "https://example.com/head?x=1#f"
    And the redirect response cache control is exactly "no-store"
    And the redirect response has an empty body and no content type
    And the redirect response has the same headers as the remembered one

  # AC7, and the 404 counterpart on the same path
  Scenario: HEAD on a link that no longer redirects is a not-found
    Given alice owns a short URL "Gone5678" for "https://example.com/gone" with status "ACTIVE"
    When an anonymous visitor sends HEAD for the short link "Gone5678"
    Then the redirect response status is 302
    And the redirect response location is exactly "https://example.com/gone"
    When the short URL "Gone5678" is set to status "DEACTIVATED"
    And an anonymous visitor sends HEAD for the short link "Gone5678"
    Then the redirect response status is 404
    And the redirect response has no Location header
    When an anonymous visitor follows the short link "Gone5678"
    Then the redirect response status is 404
    And the redirect response has error code "SHORT_URL_NOT_FOUND"

  # AC8 (D79)
  Scenario: The query string of the short link is not forwarded
    Given alice owns a short URL "Active03" for "https://example.com/target?keep=1" with status "ACTIVE"
    When an anonymous visitor follows the short link "Active03?x=1"
    Then the redirect response status is 302
    And the redirect response location is exactly "https://example.com/target?keep=1"

  # Content negotiation (D70, D81)
  Scenario Outline: The answer does not depend on what the client says it accepts
    Given alice owns a short URL "Active04" for "https://example.com/accept" with status "ACTIVE"
    When an anonymous visitor follows the short link "Active04" accepting "<accept>"
    Then the redirect response status is 302
    And the redirect response location is exactly "https://example.com/accept"
    When an anonymous visitor follows the short link "Nothing1" accepting "<accept>"
    Then the redirect response status is 404
    And the redirect response has error code "SHORT_URL_NOT_FOUND"

    Examples:
      | accept      |
      | text/html   |
      | image/png   |
      | */*         |
      | application/xml |

  # D55
  Scenario: Wrong credentials are refused even on a public short link
    Given alice owns a short URL "Active05" for "https://example.com/public" with status "ACTIVE"
    When alice follows the short link "Active05" with a wrong password
    Then the redirect response status is 401
    And the redirect response has error code "AUTHENTICATION_REQUIRED"
    And the redirect response has no Location header

  # Authenticated visitors get the same redirect
  Scenario: Another user follows a short link they do not own
    Given bob owns a short URL "Active06" for "https://example.com/bobs" with status "ACTIVE"
    When alice sends GET to "/Active06"
    Then the redirect response status is 302
    And the redirect response location is exactly "https://example.com/bobs"

  # HEAD writes nothing (D9); the same-path GET is counted (US-010), and never touches version or updated_at
  Scenario: Sending HEAD for a link changes nothing in the stored row, while a GET is counted
    Given alice owns a short URL "Active07" for "https://example.com/readonly" with status "ACTIVE"
    And the stored row of "Active07" is noted
    When an anonymous visitor sends HEAD for the short link "Active07"
    And an anonymous visitor sends HEAD for the short link "Active07"
    Then the stored row of "Active07" is unchanged after the redirects
    When an anonymous visitor follows the short link "Active07"
    Then the stored row of "Active07" has 1 click and an unchanged version and update time
