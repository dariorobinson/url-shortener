package com.schwab.urlshortener.web;

import com.schwab.urlshortener.api.error.ProblemDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an ID (US-014 AC1): the caller's {@code X-Request-Id} if it is a short, plain token, otherwise a
 * new UUID. The allow-list keeps arbitrary client text (CR/LF, very long values) out of logs and response headers.
 * The ID is in the MDC as {@code requestId} for the whole request, including the container's error dispatch, is
 * returned in the {@code X-Request-Id} response header, and is added to 500 problem bodies by {@code ProblemDetails}.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = ProblemDetails.REQUEST_ID;

    private static final Pattern ACCEPTED = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = (String) request.getAttribute(ATTRIBUTE);   // set on the first dispatch; reused on ERROR
        if (id == null) {
            String supplied = request.getHeader(HEADER);
            id = supplied != null && ACCEPTED.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, id);
        }
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    /** Also runs on the container's error dispatch, so its log lines and problem body carry the same ID. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
