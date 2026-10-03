Feature: Public and protected paths
  As an operator and as a visitor
  I want infrastructure and short-link paths to be reachable without credentials
  And every management path to demand authentication
  So that links can be followed by anyone while the API stays protected

  Scenario: Health is reachable without credentials
    When an anonymous client sends GET to "/actuator/health"
    Then the response status is 200
    And the response has no authentication challenge
    And the response body has "status" equal to "UP"

  Scenario Outline: A short-code-shaped path is not blocked by security
    When an anonymous client sends <method> to "<path>"
    Then the response status is not 401
    And the response status is not 403
    And the response has no authentication challenge
    And the response is not an authentication error

    Examples:
      | method | path     |
      | GET    | /abc1234 |
      | HEAD   | /abc1234 |
      | GET    | /ab      |
      | HEAD   | /ab      |

  Scenario Outline: A management path demands authentication
    When an anonymous client sends GET to "<path>"
    Then the response status is 401
    And the response has a Basic challenge for realm "url-shortener"
    And the response is an authentication-required problem

    Examples:
      | path              |
      | /api/v1/urls      |
      | /api              |
      | /actuator         |
      | /actuator/env     |
