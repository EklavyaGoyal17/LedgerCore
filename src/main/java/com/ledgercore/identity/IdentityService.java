package com.ledgercore.identity;

import com.ledgercore.common.DomainException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {
  public record Registration(UUID id) {}

  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final String dummyHash;

  public IdentityService(UserRepository users, PasswordEncoder passwords) {
    this.users = users;
    this.passwords = passwords;
    this.dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  public Registration register(String email, String password) {
    try {
      return new Registration(
          users.saveAndFlush(new User(normalize(email), passwords.encode(password), "USER")).id());
    } catch (DataIntegrityViolationException ex) {
      throw new DomainException(409, "IDENTITY_CONFLICT", "Identity cannot be registered");
    }
  }

  public User authenticate(String email, String password) {
    User user = users.findByEmail(normalize(email)).orElse(null);
    boolean matches = passwords.matches(password, user == null ? dummyHash : user.passwordHash());
    if (!matches || user == null || !user.active())
      throw new DomainException(401, "INVALID_CREDENTIALS", "Invalid credentials");
    return user;
  }

  static String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }
}
