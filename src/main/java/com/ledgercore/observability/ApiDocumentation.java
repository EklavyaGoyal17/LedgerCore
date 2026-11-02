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
      var problemSchema =
          new io.swagger.v3.oas.models.media.ObjectSchema()
              .addProperty(
                  "type",
                  new io.swagger.v3.oas.models.media.StringSchema()
                      .example("urn:ledgercore:problem:insufficient_funds"))
              .addProperty(
                  "title",
                  new io.swagger.v3.oas.models.media.StringSchema().example("INSUFFICIENT_FUNDS"))
              .addProperty(
                  "status", new io.swagger.v3.oas.models.media.IntegerSchema().example(422))
              .addProperty(
                  "detail",
                  new io.swagger.v3.oas.models.media.StringSchema()
                      .example("Available funds are insufficient"))
              .addProperty(
                  "code",
                  new io.swagger.v3.oas.models.media.StringSchema().example("INSUFFICIENT_FUNDS"))
              .addProperty("correlationId", new io.swagger.v3.oas.models.media.StringSchema())
              .addProperty("transferId", new io.swagger.v3.oas.models.media.UUIDSchema());
      api.getComponents().addSchemas("LedgerProblem", problemSchema);
      api.getPaths()
          .forEach(
              (path, item) -> {
                if (!path.startsWith("/api/")) return;
                item.readOperations()
                    .forEach(
                        operation -> {
                          for (int status : new int[] {400, 401, 403, 404, 429, 500, 503}) {
                            String key = Integer.toString(status);
                            if (!operation.getResponses().containsKey(key)) {
                              operation
                                  .getResponses()
                                  .addApiResponse(
                                      key,
                                      problemResponse(
                                          "Problem Details; consult code and correlationId"));
                            }
                          }
                        });
              });
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
                    "Committed simulated movement; idempotent replay returns the same status and"
                        + " JSON values")
                .content(
                    new io.swagger.v3.oas.models.media.Content()
                        .addMediaType(
                            "application/json",
                            new io.swagger.v3.oas.models.media.MediaType()
                                .schema(
                                    new io.swagger.v3.oas.models.media.Schema<>()
                                        .$ref("#/components/schemas/Transfer"))
                                .example(postingExample(path)))));
        responses.addApiResponse(
            "422",
            problemResponse(
                "Insufficient funds: durable Problem Details including transferId. Currency or"
                    + " inactive-account validation also uses 422."));
        responses.addApiResponse(
            "409",
            problemResponse("Idempotency payload conflict or original transfer already reversed"));
        responses.addApiResponse(
            "503", problemResponse("Outcome unconfirmed: retry using the original key"));
      }
    };
  }

  private static java.util.Map<String, Object> postingExample(String path) {
    var example = new java.util.LinkedHashMap<String, Object>();
    example.put("id", "e9851468-07fd-4ab5-aa88-52d1a6bf2ffd");
    example.put("actorId", "07483891-f744-49b9-bda5-d7d410191131");
    example.put("sourceAccountId", "426ad2c7-167c-42eb-ac3b-715c306cfa09");
    example.put("destinationAccountId", "720b6f75-0438-4f93-8717-4f796177c03c");
    example.put("amount", "500.00");
    example.put("currency", "INR");
    example.put(
        "kind",
        path.endsWith("reversals")
            ? "REVERSAL"
            : path.endsWith("simulated-deposits") ? "DEPOSIT" : "TRANSFER");
    example.put("status", "COMPLETED");
    example.put("failureCode", null);
    example.put("journalId", "33f1baa0-824b-432b-87cb-ea128e15db83");
    example.put(
        "originalTransferId",
        path.endsWith("reversals") ? "6627bfdc-26c5-4a32-8a99-feb5ffb9a042" : null);
    example.put("createdAt", "2026-11-02T12:00:00Z");
    return example;
  }

  private static io.swagger.v3.oas.models.responses.ApiResponse problemResponse(
      String description) {
    return new io.swagger.v3.oas.models.responses.ApiResponse()
        .description(description)
        .content(
            new io.swagger.v3.oas.models.media.Content()
                .addMediaType(
                    "application/problem+json",
                    new io.swagger.v3.oas.models.media.MediaType()
                        .schema(
                            new io.swagger.v3.oas.models.media.Schema<>()
                                .$ref("#/components/schemas/LedgerProblem"))));
  }

  @Bean
  OpenAPI api() {
    return new OpenAPI()
        .info(
            new Info()
                .title("LedgerCore — SIMULATED financial infrastructure")
                .version("1.0.0")
                .description(
                    "Educational double-entry ledger. No real money or payment rails. Amounts are"
                        + " decimal strings in INR or USD. Monetary POSTs require Idempotency-Key;"
                        + " replay preserves status and JSON values."))
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
