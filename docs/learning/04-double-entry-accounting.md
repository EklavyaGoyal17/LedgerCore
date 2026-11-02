# Double-entry accounting and exact money

## Concept and purpose

Every posted movement has equal debit and credit totals. Debit and credit are
accounting directions; their effect depends on the account's classification.
Customer balances are credit-normal liabilities because the simulated platform
owes those balances to customers. The settlement offset is a debit-normal asset.
This representation explains where every balance came from and permits an
independent reconstruction after a projection is damaged.

## Follow a concrete sequence

Start with empty INR accounts A and B. Fund A with 1000 and transfer 300 to B.

| Journal | Account | Debit | Credit | Customer effect |
|---|---|---:|---:|---|
| Simulated deposit | INR settlement asset | 1000 | 0 | offsetting asset increases |
| Simulated deposit | A liability | 0 | 1000 | A becomes 1000 |
| Transfer | A liability | 300 | 0 | A becomes 700 |
| Transfer | B liability | 0 | 300 | B becomes 300 |

A's authoritative balance is the sum of posted credits minus debits on its mapped
liability ledger. An internal transfer preserves total customer liabilities;
a deposit increases them with a matching settlement-asset debit. Do not sum
INR and USD amounts into one economic total without an exchange-rate model.
LedgerCore deliberately provides no foreign exchange operation.

## Implementation and defenses

Read `Money`, `Journal`, `JournalPosting`, `AccountService.balance`, and both
financial migrations. `Money.parse` uses `BigDecimal` only for exact conversion,
requires a two-decimal representation without rounding away fractions, and stores
integer minor units. It rejects unsupported currencies, zero/negative values,
fractional minor units, and values above the operation cap. `Journal` uses a
`BigInteger` accumulator so validating many large entries cannot wrap a `long`.

The database requires one positive side per entry and a balanced journal with at
least two entries. The transfer check additionally requires exactly the expected
two postings, amount, currencies, participants, and original reference for a
compensation. A valid balanced journal can still represent the wrong payment;
these checks protect economic intent in addition to arithmetic.

Customer projections are updated in the same transaction as entries and checked
against the authoritative sum by deferred triggers. The destination overflow
check rejects a credit that would exceed `Long.MAX_VALUE`, recording
`BALANCE_LIMIT_EXCEEDED` without posting. Redis never supplies a spendable balance.

## Corrections and immutable history

A completed ordinary transfer can be compensated by an administrator through a
new reversal transfer and journal. The original stays unchanged. The recipient
must still have enough available funds; a reversal cannot manufacture an
unauthorized overdraft. A unique constraint permits one successful compensation
per original, while failed attempts remain auditable.

`created_xid` prevents appending an entry to an already committed journal.
Triggers reject updates/deletes of journals and entries. V2 connects the terminal
outcome to its replay record, audit, and immutable financial event. The runtime
role cannot bypass these rules by disabling triggers or truncating history.

## Evidence, failures, and questions

`FinancialPropertiesTest` generates 1000 examples for each domain property:
amount formatting/parsing round trips, exact split counterpart journals, and
rejection of fractional minor units. jqwik can shrink a falsifying example to a
smaller case and report a seed for reproduction. Those pure properties complement
`FinancialIntegrityIT`'s seeded PostgreSQL operation sequences; they do not
exercise locks or database commit behavior by themselves. See the [jqwik guide](https://jqwik.net/docs/1.9.3/user-guide.html).

- **Can balanced entries prove authorization?** No. Authentication, source
  ownership, administrative privileges, and accounting consistency are distinct.
- **Why not overwrite the original with a corrected amount?** It removes the
  explanation of earlier statements and events. A new compensation preserves it.
- **Does a failed transfer need entries?** No. It needs a durable rejected outcome,
  replay metadata, audit, and event; creating money entries would misstate it.

Common mistakes include interpreting every debit as an increase, rounding `0.001`
into an accepted request, using floating-point amounts, balancing unlike
currencies, and treating a projection as an independent mutable balance.

## Practical exercise

Run `mvn -Dtest=FinancialPropertiesTest test`, then the financial integration suite.
Compute A and B's balances from entries after the sequence above and after its
reversal. Show that each journal balances and total customer liabilities remain
1000. Change one counterpart by one minor unit in a local domain test and inspect
the minimized failure rather than editing persisted journal history.
