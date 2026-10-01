package com.schwab.urlshortener.security;

import com.schwab.urlshortener.controller.error.ErrorCode;
import com.schwab.urlshortener.controller.error.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers 403 with an {@code ACCESS_DENIED} problem body (D30, D31). The exception message is never
 * used, and no username is logged (D52).
 */
@Slf4j
@RequiredArgsConstructor
class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    static final String DETAIL = "You do not have permission to perform this action.";

    private final ProblemDetailResponseWriter writer;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        if (response.isCommitted()) {
            log.debug("Response already committed, cannot write 403 for {} {}", request.getMethod(),
                    request.getRequestURI());
            return;
        }
        log.info("Access denied: {} {}", request.getMethod(), request.getRequestURI());
        writer.write(response, ProblemDetails.of(ErrorCode.ACCESS_DENIED, DETAIL, request.getRequestURI()));
    }
}
