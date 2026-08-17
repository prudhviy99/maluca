package com.maluca.mcp.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.maluca.mcp.config.MalucaMcpProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Maps fixed service tokens to narrow agent/human authorities without logging credentials. */
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final MalucaMcpProperties properties;

    public BearerTokenFilter(MalucaMcpProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(PREFIX)) {
            String candidate = authorization.substring(PREFIX.length());
            UsernamePasswordAuthenticationToken authentication = authenticate(candidate);
            if (authentication != null) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UsernamePasswordAuthenticationToken authenticate(String candidate) {
        MalucaMcpProperties.Security security = properties.security();
        if (properties.applyEnabled() && matches(candidate, security.approvalBearerToken())) {
            return UsernamePasswordAuthenticationToken.authenticated(
                    "mcp-human", null,
                    List.of(new SimpleGrantedAuthority("ROLE_AGENT"),
                            new SimpleGrantedAuthority("ROLE_OPERATOR")));
        }
        if (matches(candidate, security.bearerToken())) {
            return UsernamePasswordAuthenticationToken.authenticated(
                    "mcp-agent", null, List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
        }
        return null;
    }

    private static boolean matches(String candidate, String expected) {
        if (candidate == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
