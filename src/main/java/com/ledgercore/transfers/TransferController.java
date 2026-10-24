package com.ledgercore.transfers;

import com.ledgercore.common.Actor;
import com.ledgercore.ledger.domain.Money;
import com.ledgercore.transfers.application.Idempotency;
import com.ledgercore.transfers.application.TransferEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class TransferController {
  public record TransferRequest(
      @NotNull UUID sourceAccountId,
      @NotNull UUID destinationAccountId,
      @NotBlank
          @Pattern(regexp = "[0-9]{1,11}(\\.[0-9]{1,2})?")
          @Schema(example = "500.00", type = "string")
          String amount,
      @NotNull @Pattern(regexp = "INR|USD") String currency) {}

  public record DepositRequest(
      @NotNull UUID accountId,
      @NotBlank
          @Pattern(regexp = "[0-9]{1,11}(\\.[0-9]{1,2})?")
          @Schema(example = "1000.00", type = "string")
          String amount,
      @NotNull @Pattern(regexp = "INR|USD") String currency) {}

  private final TransferEngine engine;
  private final TransferStore transfers;

  public TransferController(TransferEngine engine, TransferStore transfers) {
    this.engine = engine;
    this.transfers = transfers;
  }

  @PostMapping("/transfers")
  @Operation(
      summary = "Atomically transfer simulated funds; replay using the original Idempotency-Key")
  public ResponseEntity<String> transfer(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody TransferRequest request) {
    return response(
        engine.transfer(
            Actor.from(jwt),
            key,
            request.sourceAccountId(),
            request.destinationAccountId(),
            Money.parse(request.amount(), request.currency())));
  }

  @GetMapping("/transfers/{id}")
  public TransferStore.Transfer status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return transfers.owned(Actor.from(jwt), id);
  }

  @PostMapping("/transfers/{id}/reversals")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<String> reverse(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key) {
    return response(engine.reverse(Actor.from(jwt), key, id));
  }

  @PostMapping("/admin/simulated-deposits")
  public ResponseEntity<String> deposit(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody DepositRequest request) {
    return response(
        engine.deposit(
            Actor.from(jwt),
            key,
            request.accountId(),
            Money.parse(request.amount(), request.currency())));
  }

  private static ResponseEntity<String> response(Idempotency.Result result) {
    return ResponseEntity.status(result.status())
        .contentType(
            result.status() >= 400
                ? MediaType.APPLICATION_PROBLEM_JSON
                : MediaType.APPLICATION_JSON)
        .body(result.body());
  }
}
