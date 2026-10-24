# API security

## Concept in plain English

Authentication establishes identity; authorization decides whether that identity can perform this action on this object.

## Why backend engineers need it

A valid JWT must not allow a user to read another customer statement by guessing a UUID.

## LedgerCore implementation

BCrypt hashes passwords; signed short-lived JWTs validate issuer/audience/expiry and current DB role/status. AccountStore and TransferStore enforce object-level visibility; administrative funding/reversal is restricted.

## Relevant files and classes

`security/SecurityConfiguration.java`, `Tokens.java`, `identity/IdentityService.java`, `AccountStore.owned`, `TransferStore.owned`.

## Concrete example

Alice requests Bob's statement and gets 404 without private data. A USER token calling reconciliation receives 403. A modified token signature receives 401.

## Common mistakes

Route-only authorization, accepting an unsigned role, exposing password hashes as entities, logging tokens, or using multibyte passwords beyond BCrypt limits can introduce security defects.

## Failure scenario

A stolen token remains useful until expiration unless its identity is disabled or role changes. Missing Redis blocks authentication traffic according to the chosen policy.

## Interview question and answer

Why validate audience as well as signature? A trusted issuer can issue tokens for multiple services; a correctly signed token for another audience should not authorize this API.

## Further learning exercise

Run realBcryptLoginAndJwtValidation, invalidJwtClaimsAreRejected and httpOwnershipRolesValidationAndProblemDetails. Add a missing-expiration case and evaluate asymmetric key rotation.
