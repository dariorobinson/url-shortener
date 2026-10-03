Feature: Application smoke checks
  As an engineer building on this skeleton
  I want a small set of smoke checks
  So that I know the build, the database, and the HTTP layer are wired together correctly

  Scenario: The application is healthy
    When I check the application's health
    Then the health check responds with status 200
    And the health status is exactly "UP"

  Scenario: The test database container is shared with other integration tests
    When I record the identity of the test database container from a Cucumber step
    Then it is the same test database container that the JUnit integration tests use
