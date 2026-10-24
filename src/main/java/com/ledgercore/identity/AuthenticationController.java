package com.ledgercore.identity;

import com.ledgercore.security.Tokens;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {
  public record Credentials(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank
          @Size(min = 12, max = 72)
          @Pattern(
              regexp = "[\\x20-\\x7E]+",
              message = "Use printable ASCII to respect BCrypt's 72 byte limit")
          String password) {}

  private final IdentityService identities;
  private final Tokens tokens;

  public AuthenticationController(IdentityService identities, Tokens tokens) {
    this.identities = identities;
    this.tokens = tokens;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Register a user for this simulated ledger")
  public IdentityService.Registration register(@Valid @RequestBody Credentials request) {
    return identities.register(request.email(), request.password());
  }

  @PostMapping("/login")
  public Tokens.AccessToken login(@Valid @RequestBody Credentials request) {
    return tokens.issue(identities.authenticate(request.email(), request.password()));
  }
}
