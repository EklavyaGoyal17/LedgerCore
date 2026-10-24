package com.ledgercore.observability;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApiDocumentation {
  @Bean
  org.springdoc.core.customizers.OpenApiCustomizer postingResponseSchemas() {
    return api -> {
      io.swagger.v3.core.converter.ModelConverters.getInstance()
          .read(com.ledgercore.transfers.TransferStore.Transfer.class)
          .forEach((name, schema) -> api.getComponents().addSchemas(name, schema));
      for (String path :
          java.util.List.of(
              "/api/v1/transfers",
              "/api/v1/transfers/{id}/reversals",
              "/api/v1/admin/simulated-deposits")) {
        var responses = api.getPaths().get(path).getPost().getResponses();
        responses.remove("200");
        responses.addApiResponse(
            "201",
            new io.swagger.v3.oas.models.responses.ApiResponse()
                .description(
                    "Committed simulated movement; idempotent replay returns the same status and JSON values")
                .content(
                    new io.swagger.v3.oas.models.media.Content()
                        .addMediaType(
                            "application/json",
                            new io.swagger.v3.oas.models.media.MediaType()
                                .schema(
                                    new io.swagger.v3.oas.models.media.Schema<>()
                                        .$ref("#/components/schemas/Transfer")))));
        responses.addApiResponse(
            "422",
            new io.swagger.v3.oas.models.responses.ApiResponse()
                .description(
                    "Insufficient funds: durable Problem Details including transferId. Currency or inactive-account validation also uses 422."));
        responses.addApiResponse(
            "409",
            new io.swagger.v3.oas.models.responses.ApiResponse()
                .description("Idempotency payload conflict or original transfer already reversed"));
        responses.addApiResponse(
            "503",
            new io.swagger.v3.oas.models.responses.ApiResponse()
                .description("Outcome unconfirmed: retry using the original key"));
      }
    };
  }

  @Bean
  OpenAPI api() {
    return new OpenAPI()
        .info(
            new Info()
                .title("LedgerCore — SIMULATED financial infrastructure")
                .version("1.0.0")
                .description(
                    "Educational double-entry ledger. No real money or payment rails. Amounts are decimal strings in INR or USD. Monetary POSTs require Idempotency-Key; replay preserves status and JSON values."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
  }
}
