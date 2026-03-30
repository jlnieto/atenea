package com.atenea.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class OperatorAuthenticationFilter extends OncePerRequestFilter {

    private final OperatorAuthenticationService operatorAuthenticationService;
    private final OperatorAuthenticationEntryPoint authenticationEntryPoint;

    public OperatorAuthenticationFilter(
            OperatorAuthenticationService operatorAuthenticationService,
            OperatorAuthenticationEntryPoint authenticationEntryPoint
    ) {
        this.operatorAuthenticationService = operatorAuthenticationService;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null || path.isBlank()) {
            path = request.getServletPath();
        }
        if (!path.startsWith("/api/mobile/") || isPublicAuthPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AuthenticatedOperator operator = operatorAuthenticationService.authenticateAccessToken(
                    authorization.substring("Bearer ".length()).trim()
            );
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    operator,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (OperatorAuthenticationException exception) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(request, response, exception);
        }
    }

    private boolean isPublicAuthPath(String path) {
        return "/api/mobile/auth/login".equals(path)
                || "/api/mobile/auth/refresh".equals(path)
                || "/api/mobile/auth/logout".equals(path);
    }
}
