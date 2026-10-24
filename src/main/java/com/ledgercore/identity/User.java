package com.ledgercore.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_users")
public class User {
  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 254)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Column(nullable = false, length = 10)
  private String role;

  @Column(nullable = false, length = 10)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected User() {}

  public User(String email, String hash, String role) {
    this.id = UUID.randomUUID();
    this.email = email;
    this.passwordHash = hash;
    this.role = role;
    this.status = "ACTIVE";
    this.createdAt = Instant.now();
  }

  public UUID id() {
    return id;
  }

  public String passwordHash() {
    return passwordHash;
  }

  public String role() {
    return role;
  }

  public boolean active() {
    return "ACTIVE".equals(status);
  }
}
