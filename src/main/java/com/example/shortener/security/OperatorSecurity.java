package com.example.shortener.security;

import com.example.shortener.common.ErrorCodes;

import com.example.shortener.common.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class OperatorSecurity {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemResponses problems,
                                    @Value("${app.security.operator-token:}") String token) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/workflow-runs", "/api/v1/workflow-runs/**", "/actuator/**")
                        .hasRole("OPERATOR")
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                problems.write(request, response, 401, ErrorCodes.AUTHENTICATION_REQUIRED, "An operator bearer token is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                problems.write(request, response, 403, ErrorCodes.ACCESS_DENIED, "Operator permission is required")))
                .addFilterBefore(new BearerFilter(token, problems), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static final class BearerFilter extends OncePerRequestFilter {
        private final byte[] expected;
        private final ProblemResponses problems;

        private BearerFilter(String token, ProblemResponses problems) {
            this.expected = token.getBytes(StandardCharsets.UTF_8);
            this.problems = problems;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                var supplied = header.substring(7).getBytes(StandardCharsets.UTF_8);
                if (expected.length == 0 || !MessageDigest.isEqual(expected, supplied)) {
                    problems.write(request, response, 401, ErrorCodes.INVALID_OPERATOR_TOKEN, "Operator authentication failed");
                    return;
                }
                var authentication = new UsernamePasswordAuthenticationToken("operator", null,
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
            chain.doFilter(request, response);
        }
    }
}
