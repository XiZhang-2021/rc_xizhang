package xizhang.rc_xizhang_coding.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import xizhang.rc_xizhang_coding.config.NotificationProperties;
import xizhang.rc_xizhang_coding.validation.RequestPolicy;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    private static final Set<String> CALLBACK_PATHS = Set.of(
            "/api/advertising/callback", "/api/crm/callback", "/api/inventory/callback");
    private final NotificationProperties properties;
    public ApiKeyFilter(NotificationProperties properties) { this.properties = properties; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Only the supplier callback is exempt from the internal caller key. It verifies its own key.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean callback = "POST".equals(request.getMethod()) && CALLBACK_PATHS.contains(path);
        if (!callback
                && !RequestPolicy.sameSecret(properties.apiKey(), request.getHeader("X-Api-Key"))) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid API key\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
