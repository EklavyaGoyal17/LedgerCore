# Java and Spring fundamentals

## Concept in plain English

A record carries immutable values; dependency injection supplies collaborators; a proxy intercepts a method to apply transaction behavior.

## Why backend engineers need it

Correct boundaries depend on understanding which code actually runs inside a database transaction.

## LedgerCore implementation

`Money` and DTOs are records. `User` is a JPA entity with a protected constructor. Controllers use constructor injection. `AccountService.create` uses @Transactional; TransferEngine uses TransactionTemplate explicitly.

## Relevant files and classes

`ledger/domain/Money.java`, `identity/User.java`, `accounts/AccountService.java`, `transfers/application/TransferEngine.java` under `src/main/java/com/ledgercore`.

## Concrete example

Money.parse("500.0","INR") and Money.parse("500.00","INR") both yield 50000 minor units. The wire request remains a validated DTO rather than exposing a JPA entity.

## Common mistakes

Calling a transactional method through self-invocation can bypass Spring proxy advice. Mutable DTOs or exposing User could leak password hashes.

## Failure scenario

An exception inside AccountService.create rolls back account, ledger mapping and outbox. Catching and ignoring that exception would misrepresent success.

## Interview question and answer

Why use TransactionTemplate here? It makes retry scope explicit: each retry encloses and recreates the entire financial transaction.

## Further learning exercise

Trace one HTTP transfer through DTO validation, Actor conversion, engine and JSON serialization. Add a domain test for Money upper bounds.
