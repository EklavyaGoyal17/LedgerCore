package com.ledgercore.reconciliation;

import com.ledgercore.common.Actor;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/reconciliation-runs")
@SecurityRequirement(name = "bearerAuth")
public class ReconciliationController {
  private final ReconciliationService service;

  public ReconciliationController(ReconciliationService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ReconciliationService.Report run(@AuthenticationPrincipal Jwt jwt) {
    return service.run(Actor.from(jwt));
  }

  @GetMapping("/{id}")
  public ReconciliationService.Report get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.get(Actor.from(jwt), id);
  }
}
