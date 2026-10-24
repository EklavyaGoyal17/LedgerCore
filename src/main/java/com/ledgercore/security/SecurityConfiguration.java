package com.ledgercore.security;

import com.ledgercore.common.Json;
import com.ledgercore.common.ProblemAdvice;
import com.ledgercore.identity.User;
import com.ledgercore.identity.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SecretKey signingKey(@Value("${ledgercore.security.jwt-secret}") String secret) {
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
      throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
    return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  JwtDecoder jwtDecoder(
      SecretKey key,
      @Value("${ledgercore.security.issuer}") String issuer,
      @Value("${ledgercore.security.audience}") String audience,
      UserRepository users) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    OAuth2TokenValidator<Jwt> claims =
        jwt -> {
          try {
            User u = users.findById(UUID.fromString(jwt.getSubject())).orElse(null);
            if (jwt.getExpiresAt() != null
                && jwt.getAudience().contains(audience)
                && u != null
                && u.active()
                && u.role().equals(jwt.getClaimAsString("role")))
              return OAuth2TokenValidatorResult.success();
          } catch (IllegalArgumentException ex) {
            /* Untrusted subject is rejected. */
          }
          return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), claims));
    return decoder;
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, RateLimitFilter rateLimit, Json json)
      throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/v1/auth/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/actuator/prometheus")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(
                        j ->
                            j.jwtAuthenticationConverter(
                                jwt ->
                                    new JwtAuthenticationToken(
                                        jwt,
                                        List.of(
                                            new SimpleGrantedAuthority(
                                                "ROLE_" + jwt.getClaimAsString("role"))))))
                    .authenticationEntryPoint(
                        (req, res, ex) -> {
                          res.setStatus(401);
                          res.setContentType("application/problem+json");
                          res.getWriter()
                              .write(
                                  json.write(
                                      ProblemAdvice.body(
                                          401,
                                          "UNAUTHENTICATED",
                                          "A valid access token is required")));
                        }))
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(
                    (req, res, ex) -> {
                      res.setStatus(403);
                      res.setContentType("application/problem+json");
                      res.getWriter()
                          .write(
                              json.write(
                                  ProblemAdvice.body(403, "FORBIDDEN", "Access is forbidden")));
                    }))
        .addFilterBefore(rateLimit, AnonymousAuthenticationFilter.class)
        .build();
  }

  @Bean
  ApplicationRunner bootstrapAdmin(
      UserRepository users,
      PasswordEncoder passwords,
      @Value("${ledgercore.security.admin-email}") String email,
      @Value("${ledgercore.security.admin-password}") String password) {
    return args -> {
      if (!email.isBlank()) {
        if (password.length() < 16 || password.length() > 72 || !password.matches("[\\x20-\\x7E]+"))
          throw new IllegalArgumentException(
              "ADMIN_PASSWORD must be 16–72 printable ASCII characters");
        String normalized = email.strip().toLowerCase(java.util.Locale.ROOT);
        if (users.findByEmail(normalized).isEmpty())
          users.saveAndFlush(new User(normalized, passwords.encode(password), "ADMIN"));
      }
    };
  }
}
