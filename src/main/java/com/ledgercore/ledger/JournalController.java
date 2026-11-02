package com.ledgercore.ledger;

import com.ledgercore.common.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/journals")
@SecurityRequirement(name = "bearerAuth")
public class JournalController {
  private final JournalQueries queries;

  public JournalController(JournalQueries queries) {
    this.queries = queries;
  }

  @GetMapping("/{id}")
  @Operation(
      summary =
          "Inspect the immutable balanced journal for an account participant or administrator")
  public JournalQueries.View get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return queries.get(Actor.from(jwt), id);
  }
}
