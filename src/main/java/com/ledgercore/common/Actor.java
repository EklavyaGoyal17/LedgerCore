package com.ledgercore.common;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

public record Actor(UUID id, boolean admin) {
  public static Actor from(Jwt jwt) {
    return new Actor(
        UUID.fromString(jwt.getSubject()), "ADMIN".equals(jwt.getClaimAsString("role")));
  }
}
