package com.ledgercore.security;

import com.ledgercore.identity.User;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
public class Tokens {
  public record AccessToken(String accessToken, String tokenType, long expiresIn) {}

  private final JwtEncoder encoder;
  private final String issuer;
  private final String audience;
  private final Duration ttl;

  public Tokens(
      JwtEncoder encoder,
      @Value("${ledgercore.security.issuer}") String issuer,
      @Value("${ledgercore.security.audience}") String audience,
      @Value("${ledgercore.security.ttl}") Duration ttl) {
    this.encoder = encoder;
    this.issuer = issuer;
    this.audience = audience;
    this.ttl = ttl;
  }

  public AccessToken issue(User user) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(user.id().toString())
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .claim("role", user.role())
            .build();
    String token =
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    return new AccessToken(token, "Bearer", ttl.toSeconds());
  }
}
