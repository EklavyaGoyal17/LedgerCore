package com.ledgercore.events;

import com.ledgercore.common.Actor;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/events/quarantine")
@SecurityRequirement(name = "bearerAuth")
public class EventOperationsController {
  public record Replay(
      @NotBlank @Size(max = 500) String reason, @Size(max = 65536) String payload) {}

  private final EventOperations operations;

  public EventOperationsController(EventOperations operations) {
    this.operations = operations;
  }

  @GetMapping
  public List<EventOperations.FailedEvent> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return operations.list(Actor.from(jwt), limit, offset);
  }

  @GetMapping("/{id}")
  public EventOperations.FailedEvent get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return operations.get(Actor.from(jwt), id);
  }

  @PostMapping("/{id}/replays")
  public EventOperations.FailedEvent replay(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Replay request) {
    return operations.replay(Actor.from(jwt), id, request.payload(), request.reason());
  }
}
