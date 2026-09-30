Feature: Short URL lifecycle
  As the owner of a short URL, or an administrator
  I want to deactivate, reactivate and delete short URLs
  So that links can be managed over their lifetime without losing audit history

  # AC1 (D34)
  Scenario: The owner deactivates an active short URL
    Given alice owns a short URL "Life0001" for "https://example.com/life1" with status "ACTIVE"
    And the stored lifecycle state of "Life0001" is remembered
    When alice deactivates the short URL "Life0001"
    Then the lifecycle response status is 200
    And the lifecycle response is the short URL "Life0001" with status "DEACTIVATED"
    And the stored status of "Life0001" is "DEACTIVATED"
    And the stored version of "Life0001" is 1 higher than remembered
    When an anonymous visitor follows the short link "Life0001"
    Then the redirect response status is 404
    And the redirect response has error code "SHORT_URL_NOT_FOUND"

  # AC2 (D34)
  Scenario: The owner reactivates a deactivated short URL
    Given alice owns a short URL "Life0002" for "https://example.com/life2" with status "DEACTIVATED"
    And the stored lifecycle state of "Life0002" is remembered
    When alice reactivates the short URL "Life0002"
    Then the lifecycle response status is 200
    And the lifecycle response is the short URL "Life0002" with status "ACTIVE"
    And the stored status of "Life0002" is "ACTIVE"
    And the stored version of "Life0002" is 1 higher than remembered
    When an anonymous visitor follows the short link "Life0002"
    Then the redirect response status is 302
    And the redirect response location is exactly "https://example.com/life2"

  # AC3 (D4)
  Scenario: A user cannot deactivate someone else's short URL and learns nothing about it
    Given alice owns a short URL "Life0003" for "https://example.com/life3" with status "ACTIVE"
    And the stored lifecycle state of "Life0003" is remembered
    When bob deactivates the short URL "Life0003"
    Then the lifecycle response status is 404
    And the lifecycle response has error code "SHORT_URL_NOT_FOUND"
    And the stored lifecycle state of "Life0003" is unchanged
    When alice deactivates the short URL "Life0003"
    Then the lifecycle response status is 200
    And the stored version of "Life0003" is 1 higher than remembered

  # AC4
  Scenario Outline: An administrator changes any user's short URL
    Given <owner> owns a short URL "Life0004" for "https://example.com/life4" with status "<from>"
    When admin <action> the short URL "Life0004"
    Then the lifecycle response status is 200
    And the lifecycle response is the short URL "Life0004" with status "<to>"
    And the stored status of "Life0004" is "<to>"

    Examples:
      | owner | from        | action       | to          |
      | alice | ACTIVE      | deactivates  | DEACTIVATED |
      | bob   | DEACTIVATED | reactivates  | ACTIVE      |

  # AC5 (D1, D36, D51)
  Scenario Outline: An administrator deletes a short URL and the audit trail is kept
    Given alice owns a short URL "Life0005" for "https://example.com/life5" with status "<status>"
    And the stored lifecycle state of "Life0005" is remembered
    When admin deletes the short URL "Life0005"
    Then the lifecycle response status is 204
    And the lifecycle response has no body and no content type
    And the stored status of "Life0005" is "DELETED"
    And the stored short URL "Life0005" was deleted by "admin" at the moment it was updated
    And the stored version of "Life0005" is 1 higher than remembered
    And exactly 1 short URL exists with code "Life0005"

    Examples:
      | status      |
      | ACTIVE      |
      | DEACTIVATED |

  # AC6 (D3)
  Scenario Outline: A user can never delete, not even their own short URL
    Given alice owns a short URL "Life0006" for "https://example.com/life6" with status "ACTIVE"
    And the stored lifecycle state of "Life0006" is remembered
    When <user> deletes the short URL "Life0006"
    Then the lifecycle response status is 403
    And the lifecycle response has error code "ACCESS_DENIED"
    And the stored lifecycle state of "Life0006" is unchanged
    When admin deletes the short URL "Life0006"
    Then the lifecycle response status is 204
    And the stored version of "Life0006" is 1 higher than remembered

    Examples:
      | user  |
      | alice |
      | bob   |

  # AC7
  Scenario Outline: Changing a short URL that does not exist is a 404
    When <user> <action> the short URL "Missing1"
    Then the lifecycle response status is 404
    And the lifecycle response has error code "SHORT_URL_NOT_FOUND"

    Examples:
      | user  | action      |
      | alice | deactivates |
      | alice | reactivates |
      | admin | deactivates |
      | admin | deletes     |

  # AC8 (D13, D36, D46)
  Scenario Outline: A deleted short URL accepts no further lifecycle change, even from an administrator
    Given alice owns a short URL "Life0008" for "https://example.com/life8" with status "DELETED"
    And the stored lifecycle state of "Life0008" is remembered
    When <user> <action> the short URL "Life0008"
    Then the lifecycle response status is 404
    And the lifecycle response has error code "SHORT_URL_NOT_FOUND"
    And the stored lifecycle state of "Life0008" is unchanged

    Examples:
      | user  | action      |
      | alice | deactivates |
      | alice | reactivates |
      | admin | deactivates |
      | admin | reactivates |
      | admin | deletes     |

  # AC8: the role check comes before the lookup, so a user learns nothing from DELETE
  Scenario Outline: A user deleting a code in any state, or no code at all, always gets 403
    Given alice owns a short URL "Life0009" for "https://example.com/life9" with status "<status>"
    When alice deletes the short URL "<code>"
    Then the lifecycle response status is 403
    And the lifecycle response has error code "ACCESS_DENIED"

    Examples:
      | status      | code     |
      | ACTIVE      | Life0009 |
      | DEACTIVATED | Life0009 |
      | DELETED     | Life0009 |
      | ACTIVE      | Missing1 |

  # AC9 (D26)
  Scenario: Deactivating an already deactivated short URL is a conflict
    Given alice owns a short URL "Life0010" for "https://example.com/life10" with status "DEACTIVATED"
    And the stored lifecycle state of "Life0010" is remembered
    When alice deactivates the short URL "Life0010"
    Then the lifecycle response status is 409
    And the lifecycle response has error code "SHORT_URL_ALREADY_DEACTIVATED"
    And the stored lifecycle state of "Life0010" is unchanged
    When alice reactivates the short URL "Life0010"
    Then the lifecycle response status is 200
    And the stored version of "Life0010" is 1 higher than remembered

  # AC10 (D26)
  Scenario: Reactivating an already active short URL is a conflict
    Given alice owns a short URL "Life0011" for "https://example.com/life11" with status "ACTIVE"
    And the stored lifecycle state of "Life0011" is remembered
    When alice reactivates the short URL "Life0011"
    Then the lifecycle response status is 409
    And the lifecycle response has error code "SHORT_URL_ALREADY_ACTIVE"
    And the stored lifecycle state of "Life0011" is unchanged
    When alice deactivates the short URL "Life0011"
    Then the lifecycle response status is 200
    And the stored version of "Life0011" is 1 higher than remembered

  # AC11 (D35, D86)
  Scenario: Two simultaneous deactivations produce exactly one success and one conflict
    Given alice creates a short URL for "https://example.com/life12" with alias "Life0012"
    And the stored lifecycle state of "Life0012" is remembered
    When alice sends two simultaneous deactivation requests for the short URL "Life0012"
    Then exactly one simultaneous request gets 200 and the other gets 409 with a concurrency or already-deactivated error code
    And the stored status of "Life0012" is "DEACTIVATED"
    And the stored version of "Life0012" is 1 higher than remembered

  # AC12 (D31)
  Scenario Outline: Without credentials, lifecycle changes are refused with 401
    Given alice owns a short URL "Life0013" for "https://example.com/life13" with status "ACTIVE"
    And the stored lifecycle state of "Life0013" is remembered
    When an anonymous caller <action> the short URL "Life0013"
    Then the lifecycle response status is 401
    And the lifecycle response has error code "AUTHENTICATION_REQUIRED"
    And the lifecycle response has a Basic challenge
    And the stored lifecycle state of "Life0013" is unchanged
    When admin deletes the short URL "Life0013"
    Then the lifecycle response status is 204
    And the stored version of "Life0013" is 1 higher than remembered

    Examples:
      | action      |
      | deactivates |
      | deletes     |

  # D1: the code of a deleted link is never handed out again
  Scenario: A deleted short code cannot be reused
    Given alice creates a short URL for "https://example.com/life14" with alias "Life0014"
    When admin deletes the short URL "Life0014"
    Then the lifecycle response status is 204
    When alice requests the details of "Life0014"
    Then the details response status is 404
    Given alice creates a short URL for "https://example.com/reuse" with alias "Life0014" and gets 409
    And exactly 1 short URL exists with code "Life0014"
    And no short URL exists for original URL "https://example.com/reuse"

  # D70: an unacceptable Accept is refused before anything changes
  Scenario Outline: An unacceptable Accept header is refused with 406 and nothing changes
    Given alice owns a short URL "Life0015" for "https://example.com/life15" with status "ACTIVE"
    And the stored lifecycle state of "Life0015" is remembered
    When <user> <action> the short URL "Life0015" accepting "application/xml"
    Then the lifecycle response status is 406
    And the lifecycle response has error code "NOT_ACCEPTABLE"
    And the stored lifecycle state of "Life0015" is unchanged
    When <user> <action> the short URL "Life0015" accepting "application/json"
    Then the lifecycle response status is <success>
    And the stored version of "Life0015" is 1 higher than remembered

    Examples:
      | user  | action      | success |
      | alice | deactivates | 200     |
      | admin | deletes     | 204     |

  # D88
  Scenario: A merge-patch media type is refused with 415 and nothing changes
    Given alice owns a short URL "Life0016" for "https://example.com/life16" with status "ACTIVE"
    And the stored lifecycle state of "Life0016" is remembered
    When alice patches the short URL "Life0016" with content type "application/merge-patch+json" and body '{"active": false}'
    Then the lifecycle response status is 415
    And the lifecycle response has error code "UNSUPPORTED_MEDIA_TYPE"
    And the stored lifecycle state of "Life0016" is unchanged
    When alice patches the short URL "Life0016" with content type "application/json" and body '{"active": false}'
    Then the lifecycle response status is 200
    And the stored version of "Life0016" is 1 higher than remembered

  # D89
  Scenario Outline: Only a real JSON boolean is accepted for active
    Given alice owns a short URL "Life0017" for "https://example.com/life17" with status "ACTIVE"
    And the stored lifecycle state of "Life0017" is remembered
    When alice patches the short URL "Life0017" with content type "application/json" and body '<body>'
    Then the lifecycle response status is 400
    And the lifecycle response has error code "<errorCode>"
    And the stored lifecycle state of "Life0017" is unchanged
    When alice deactivates the short URL "Life0017"
    Then the lifecycle response status is 200
    And the stored version of "Life0017" is 1 higher than remembered

    Examples:
      | body                | errorCode         |
      | {"active": "false"} | MALFORMED_REQUEST |
      | {"active": 0}       | MALFORMED_REQUEST |
      | {"active": 1}       | MALFORMED_REQUEST |
      | {"active": null}    | VALIDATION_FAILED |
      | {}                  | VALIDATION_FAILED |

  # D27, D90
  Scenario: Changing the lifecycle never touches the click data
    Given alice owns a short URL "Life0018" for "https://example.com/life18" with status "ACTIVE"
    And the short URL "Life0018" has 7 clicks, the last at "2026-03-01T10:15:30Z"
    And the stored lifecycle state of "Life0018" is remembered
    When alice deactivates the short URL "Life0018"
    Then the lifecycle response status is 200
    And the lifecycle response shows 7 clicks
    And the stored click data of "Life0018" is 7 clicks, the last at "2026-03-01T10:15:30Z"
    And the stored version of "Life0018" is 1 higher than remembered
    When admin deletes the short URL "Life0018"
    Then the lifecycle response status is 204
    And the stored click data of "Life0018" is 7 clicks, the last at "2026-03-01T10:15:30Z"
    And the stored version of "Life0018" is 2 higher than remembered
