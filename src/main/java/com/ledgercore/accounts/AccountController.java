package com.ledgercore.accounts;

import com.ledgercore.common.Actor;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/accounts")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {
  public record CreateAccount(@NotNull @Pattern(regexp = "INR|USD") String currency) {}

  private final AccountService service;

  public AccountController(AccountService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AccountService.View create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAccount request) {
    return service.create(Actor.from(jwt), request.currency());
  }

  @GetMapping
  public AccountService.Page<AccountService.View> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
    return service.list(Actor.from(jwt), offset, limit);
  }

  @GetMapping("/{id}")
  public AccountService.View get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.get(Actor.from(jwt), id);
  }

  @GetMapping("/{id}/balance")
  public AccountService.Balance balance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.balance(Actor.from(jwt), id);
  }

  @GetMapping("/{id}/statement")
  public AccountService.Page<AccountService.StatementEntry> statement(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
    return service.statement(Actor.from(jwt), id, offset, limit);
  }
}
