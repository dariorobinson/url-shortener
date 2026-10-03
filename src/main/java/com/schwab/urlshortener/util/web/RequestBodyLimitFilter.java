package com.schwab.urlshortener.util.web;

import com.schwab.urlshortener.controller.error.ErrorCode;
import com.schwab.urlshortener.controller.error.PayloadTooLargeException;
import com.schwab.urlshortener.controller.error.ProblemDetails;
import com.schwab.urlshortener.security.ProblemDetailResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * D68, D130: refuses request bodies larger than the configured limit before anything parses them, and before
 * authentication, so a huge anonymous body costs almost nothing. A declared {@code Content-Length} over the limit is
 * answered at once with {@code 413 PAYLOAD_TOO_LARGE}. A body without a declared length (chunked) is wrapped, and
 * reading past the limit throws {@link PayloadTooLargeException}, which the advice maps to the same 413.
 */
@Slf4j
@RequiredArgsConstructor
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    static final String DETAIL = "The request body is too large.";

    private final long maxBytes;
    private final ProblemDetailResponseWriter writer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            log.info("Request body refused: method={} path={} declaredBytes={} maxBytes={}", request.getMethod(),
                    request.getRequestURI(), declared, maxBytes);
            writer.write(response, ProblemDetails.of(ErrorCode.PAYLOAD_TOO_LARGE, DETAIL, request.getRequestURI()));
            return;
        }
        chain.doFilter(declared >= 0 ? request : new LimitedRequest(request, maxBytes), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8.name() : encoding));
        }
    }

    @RequiredArgsConstructor
    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws PayloadTooLargeException {
            read += n;
            if (read > maxBytes) {
                throw new PayloadTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
