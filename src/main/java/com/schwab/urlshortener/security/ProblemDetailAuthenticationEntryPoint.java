package com.schwab.urlshortener.security;

import com.schwab.urlshortener.api.error.ErrorCode;
import com.schwab.urlshortener.api.error.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers 401 with a Basic challenge and an {@code AUTHENTICATION_REQUIRED} problem body (D30, D31).
 * The body and header are identical whatever the cause (no header, malformed header, unknown user,
 * wrong password), so nothing reveals which usernames exist. The exception message is never used, and
 * no username or client address is logged (D52).
 */
@Slf4j
@RequiredArgsConstructor
class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String DETAIL = "Authentication is required to access this resource.";

    private final ProblemDetailResponseWriter writer;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        if (response.isCommitted()) {
            log.debug("Response already committed, cannot write 401 for {} {}", request.getMethod(),
                    request.getRequestURI());
            return;
        }
        if (authException instanceof InsufficientAuthenticationException) {
            log.debug("Anonymous request to a secured path: {} {} ({})", request.getMethod(),
                    request.getRequestURI(), authException.getClass().getSimpleName());
        } else {
            log.info("Authentication failed: {} {} ({})", request.getMethod(), request.getRequestURI(),
                    authException.getClass().getSimpleName());
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + SecurityConfig.REALM + "\"");
        writer.write(response, ProblemDetails.of(ErrorCode.AUTHENTICATION_REQUIRED, DETAIL,
                request.getRequestURI()));
    }
}
