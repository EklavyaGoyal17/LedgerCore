# Java and Spring fundamentals through one transfer

## Concept and purpose

Spring assembles collaborating objects and applies infrastructure behavior around
method calls. Java records carry values; ordinary classes coordinate behavior;
a JPA entity maps a persistent identity. These mechanisms do different jobs. A
backend engineer needs to know which code validates an input, which code starts a
transaction, and which value can safely cross the HTTP boundary.

## Follow the implemented path

Open these files under `src/main/java/com/ledgercore`:

1. `transfers/TransferController.java` validates a request DTO and obtains an
   `Actor` from the authenticated JWT. It delegates the money movement.
2. `ledger/domain/Money.java` converts decimal strings to exact minor units.
   `Money.parse("500.0", "INR")` and `Money.parse("500.00", "INR")` produce equal
   records containing `50000` and `Currency.INR`.
3. `transfers/application/TransferEngine.java` orchestrates a complete use case
   using constructor-injected collaborators. `TransactionTemplate` encloses each
   attempt, making the retry boundary visible in the code.
4. `ledger/domain/Journal.java` validates accounting values without depending on
   Spring or JPA. Its constructor copies the entry list so the caller cannot
   subsequently mutate a journal's contents through its original list reference.
5. `accounts/AccountStore.java` and `ledger/JournalPosting.java` execute explicit
   SQL. `common/Json.java` serializes DTOs and retained response metadata.

Records are shallowly immutable: a final list reference would still expose mutable
contents unless copied. `Journal` explicitly addresses that issue. `identity/User`
is instead a JPA entity with a protected no-argument constructor and controlled
accessors. A controller must never return this entity because it includes the
password hash and persistence concerns.

`security/AuthenticationController.java` now composes `identity/IdentityService`
and `security/Tokens`. Keeping token issuance in the security module avoids a
circular identity-to-security-to-identity dependency. `ArchitectureTest` checks
module cycles, framework-free accounting values, controller entity exposure, and
financial modules' independence from Redis/Kafka clients.

## Transaction proxies versus explicit transactions

`AccountService.create` uses `@Transactional`. A call through its Spring-managed
proxy starts the transaction before the method inserts its ledger account,
customer account, audit, and outbox event. A method calling another annotated
method on `this` can bypass that proxy interception. `TransferEngine` uses an
explicit template so a retry can create a fresh transaction around the complete
operation. Moving the retry loop inside one annotated transaction would keep a
failed database transaction alive across attempts.

This proxy behavior is documented in [Spring's transaction annotation guide](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html).

## Failures and common mistakes

A DTO's `@Valid` checks do not replace `Money` validation or PostgreSQL constraints.
A test can call a service directly, another application component can bypass a
controller, and direct SQL can bypass Java entirely. Each layer protects a
different boundary. Avoid field injection, exposing entities, catching an SQL
exception and returning success, and wrapping mutable lists in records without
copying them. Do not place a Kafka call in the controller or posting transaction.

## Questions to answer with the editor open

- **Why use a record for `Money`?** Its value equality and compact constructor make
  the currency/amount pair explicit and validated at construction.
- **Why not make every class a record?** Services have behavior and dependencies;
  JPA entities have persistence lifecycle requirements. Those are different roles.
- **Where is the financial transaction started?** In `TransferEngine.execute`,
  outside the callback that performs reservation and posting.
- **What proves the domain has no framework coupling?** The corresponding
  `ArchitectureTest` rule checks compiled dependencies, alongside code review.

## Practical exercise

Run `mvn '-Dtest=FinancialDomainTest,FinancialPropertiesTest,ArchitectureTest' test` and inspect
which tests need Docker: these selected domain/architecture tests do not. Add an
invalid half-paise input to a local domain test, then explain why `0.001` is
rejected while `1.000` can be represented exactly. Finally draw the call chain
above and mark the first and last instruction inside the database transaction.
