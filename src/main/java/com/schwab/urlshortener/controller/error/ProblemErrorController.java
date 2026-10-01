package com.schwab.urlshortener.controller.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring Boot's {@code BasicErrorController} at {@code /error} (D82, US-014 H9). When the container forwards
 * an error (for example a request the firewall rejected), it answers with the catalogue code for that status, as a
 * problem body built by {@link ProblemDetails}; the original exception and message never reach the client. When
 * {@code /error} is requested directly there is nothing to report, so it answers {@code 404 RESOURCE_NOT_FOUND}
 * instead of the misleading 500 Boot gives.
 */
@Slf4j
@RestController
public class ProblemErrorController implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        if (!(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status)) {
            return respond(ErrorCode.RESOURCE_NOT_FOUND, request.getRequestURI());
        }
        String path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String original
                ? original : request.getRequestURI();
        ErrorCode code = GlobalExceptionHandler.codeFor(HttpStatusCode.valueOf(status));
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.error("Error dispatch: status={} path={}", status, path);
        }
        return respond(code, path);
    }

    private static ResponseEntity<ProblemDetail> respond(ErrorCode code, String path) {
        return ResponseEntity.status(code.status())
                .body(ProblemDetails.of(code, GlobalExceptionHandler.detailFor(code), path));
    }
}
