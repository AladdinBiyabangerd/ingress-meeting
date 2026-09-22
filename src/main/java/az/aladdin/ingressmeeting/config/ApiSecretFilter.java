package az.aladdin.ingressmeeting.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiSecretFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Api-Key";

    private final String apiSecret;

    public ApiSecretFilter(@Value("${app.api-secret:}") String apiSecret) {
        this.apiSecret = apiSecret != null ? apiSecret : "";
        if (this.apiSecret.isBlank()) {
            log.warn("API_SECRET_KEY / app.api-secret is not set — /api/** will return 503");
        } else {
            log.info("API secret filter enabled for /api/**");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Strip context path if present
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path == null || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        if (apiSecret.isBlank()) {
            log.warn("Rejected {} {} — API secret not configured", request.getMethod(), request.getRequestURI());
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "API secret not configured");
            return;
        }

        String provided = request.getHeader(HEADER_NAME);
        byte[] expected = apiSecret.getBytes(StandardCharsets.UTF_8);
        byte[] actual = provided != null
                ? provided.getBytes(StandardCharsets.UTF_8)
                : new byte[0];

        if (!MessageDigest.isEqual(expected, actual)) {
            log.warn("Rejected {} {} — invalid or missing {}", request.getMethod(), request.getRequestURI(), HEADER_NAME);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing API key");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
