package com.maluca.triage.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain triageSecurity(HttpSecurity http, TokenAuthenticationFilter tokenFilter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/internal/**").hasRole("INTERNAL")
                        // The proxy/MCP service credential can ingest, read, and propose,
                        // but it can never approve or apply. Apply requires the separate
                        // operator bearer credential even when invoked through MCP.
                        .requestMatchers(
                                "/api/v1/incidents/*/apply",
                                "/api/v1/incidents/*/dismiss",
                                "/api/v1/incidents/*/reconcile-policy")
                            .hasRole("OPERATOR")
                        .requestMatchers("/api/**").hasAnyRole("OPERATOR", "INTERNAL")
                        .requestMatchers("/actuator/**").hasRole("OPERATOR")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"error\":\"authentication_required\"}");
                }))
                .build();
    }

    @Component
    static final class TokenAuthenticationFilter extends OncePerRequestFilter {

        private static final String INTERNAL_HEADER = "X-Maluca-Internal-Token";
        private final byte[] apiToken;
        private final byte[] internalToken;

        TokenAuthenticationFilter(TriageProperties properties) {
            this.apiToken = bytes(properties.security().apiToken());
            this.internalToken = bytes(properties.security().internalToken());
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String internal = request.getHeader(INTERNAL_HEADER);
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (constantTimeEquals(internalToken, internal)) {
                authenticate("maluca-service", "ROLE_INTERNAL");
            } else if (authorization != null && authorization.startsWith("Bearer ")
                    && constantTimeEquals(apiToken, authorization.substring(7))) {
                authenticate("maluca-operator", "ROLE_OPERATOR");
            }
            chain.doFilter(request, response);
        }

        private static void authenticate(String principal, String authority) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority(authority)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        private static boolean constantTimeEquals(byte[] expected, String actual) {
            return actual != null && expected.length > 0
                    && MessageDigest.isEqual(expected, actual.getBytes(StandardCharsets.UTF_8));
        }

        private static byte[] bytes(String value) {
            return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        }
    }
}
