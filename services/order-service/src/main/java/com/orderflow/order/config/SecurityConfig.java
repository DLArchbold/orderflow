package com.orderflow.order.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** PLAN step 18b: JWT resource server. Keycloak puts roles in realm_access.roles, mapped to ROLE_customer / ROLE_admin. */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.cors(Customizer.withDefaults())   // uses the CorsConfigurationSource bean below
            .csrf(csrf -> csrf.disable())   // stateless bearer-token API: no cookies, so no CSRF to defend against
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()   // CORS preflight carries no token
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/actuator/health/**").permitAll()
                // scraped by Prometheus inside the private network; restrict it by network (step 23/24), not by token
                .requestMatchers("/actuator/prometheus").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * PLAN step 18d: for a deployment where the UI and the API have different origins. In development the Vite
     * proxy keeps one origin, so the browser sends no cross-origin requests at all.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @org.springframework.beans.factory.annotation.Value("${orderflow.cors.allowed-origin:http://localhost:5173}") String origin) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origin));
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(realmRoles());
        return converter;
    }

    private static Converter<Jwt, Collection<GrantedAuthority>> realmRoles() {
        return jwt -> {
            Object realmAccess = jwt.getClaim("realm_access");
            if (!(realmAccess instanceof Map<?, ?> access) || !(access.get("roles") instanceof List<?> roles)) {
                return List.of();
            }
            return roles.stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        };
    }
}
