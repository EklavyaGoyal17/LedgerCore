-- Both successful and rejected financial outcomes retain their durable replay record.
-- Backfill uses the replayed transfer ID; failed outcomes have no journal to join.
ALTER TABLE transfers ADD COLUMN idempotency_reference uuid;
-- The migration owner alone may backfill the newly added evidence reference. All
-- accounting identities/amounts stay unchanged, and Flyway rolls this DDL and
-- trigger state back together if any subsequent statement fails.
ALTER TABLE transfers DISABLE TRIGGER immutable_transfers;
UPDATE transfers t SET idempotency_reference=i.id FROM idempotency_records i
WHERE (i.response_body->>'id'=t.id::text OR i.response_body->>'transferId'=t.id::text)
  AND i.principal_id=t.actor_id;
ALTER TABLE transfers ENABLE TRIGGER immutable_transfers;
ALTER TABLE transfers ALTER COLUMN idempotency_reference SET NOT NULL;
ALTER TABLE transfers ADD CONSTRAINT transfer_idempotency_unique UNIQUE(idempotency_reference);
ALTER TABLE transfers ADD CONSTRAINT transfer_idempotency_fk
  FOREIGN KEY(idempotency_reference) REFERENCES idempotency_records(id);

CREATE UNIQUE INDEX one_financial_outbox_event ON outbox_events(aggregate_id)
 WHERE event_type IN ('TransferCompleted','TransferFailed','SimulatedDepositCompleted','ReversalCompleted');
CREATE UNIQUE INDEX one_financial_audit_event ON audit_events(resource_id)
 WHERE action IN ('TransferCompleted','TransferFailed','SimulatedDepositCompleted','ReversalCompleted');

CREATE FUNCTION protect_finished_idempotency() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR
    (OLD.state='FINISHED' AND (NEW.state,NEW.response_status,NEW.response_body)
      IS DISTINCT FROM (OLD.state,OLD.response_status,OLD.response_body)) OR
    (NEW.id,NEW.principal_id,NEW.operation,NEW.key,NEW.fingerprint,NEW.created_at)
      IS DISTINCT FROM
    (OLD.id,OLD.principal_id,OLD.operation,OLD.key,OLD.fingerprint,OLD.created_at) THEN
   RAISE EXCEPTION 'durable idempotency records cannot be changed or removed' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_finished_idempotency BEFORE UPDATE OR DELETE ON idempotency_records
 FOR EACH ROW EXECUTE FUNCTION protect_finished_idempotency();

-- Publication bookkeeping may change; the committed event identity and content may not.
CREATE FUNCTION protect_outbox_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   IF EXISTS(SELECT 1 FROM transfers WHERE id=OLD.aggregate_id) THEN
     RAISE EXCEPTION 'financial event evidence cannot be removed' USING ERRCODE='23514';
   END IF;
   RETURN OLD;
 END IF;
 IF (NEW.id,NEW.aggregate_id,NEW.event_type,NEW.payload,NEW.traceparent,NEW.created_at)
    IS DISTINCT FROM
    (OLD.id,OLD.aggregate_id,OLD.event_type,OLD.payload,OLD.traceparent,OLD.created_at) THEN
   RAISE EXCEPTION 'outbox event identity and content are immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_outbox_content BEFORE UPDATE OR DELETE ON outbox_events
 FOR EACH ROW EXECUTE FUNCTION protect_outbox_evidence();

CREATE FUNCTION verify_outbox_envelope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.payload->>'eventId' IS DISTINCT FROM NEW.id::text OR
    NEW.payload->>'aggregateId' IS DISTINCT FROM NEW.aggregate_id::text OR
    NEW.payload->>'eventType' IS DISTINCT FROM NEW.event_type OR
    coalesce(length(NEW.payload->>'correlationId'),0)=0 OR
    NEW.payload->>'occurredAt' IS NULL THEN
   RAISE EXCEPTION 'outbox envelope disagrees with its durable identity' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER outbox_envelope BEFORE INSERT ON outbox_events
 FOR EACH ROW EXECUTE FUNCTION verify_outbox_envelope();

CREATE FUNCTION verify_financial_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE i idempotency_records; expected_operation text; expected_event text; original transfers;
BEGIN
 SELECT * INTO STRICT i FROM idempotency_records WHERE id=NEW.idempotency_reference;
 expected_operation:=CASE NEW.kind WHEN 'TRANSFER' THEN 'transfer' WHEN 'DEPOSIT' THEN 'simulated-deposit'
   ELSE 'reversal:'||NEW.original_transfer_id::text END;
 expected_event:=CASE WHEN NEW.status='FAILED' THEN 'TransferFailed' WHEN NEW.kind='TRANSFER' THEN 'TransferCompleted'
   WHEN NEW.kind='DEPOSIT' THEN 'SimulatedDepositCompleted' ELSE 'ReversalCompleted' END;
 IF i.principal_id<>NEW.actor_id OR i.operation<>expected_operation OR i.state<>'FINISHED' THEN
   RAISE EXCEPTION 'transfer and idempotency identity disagree' USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM financial_accounts WHERE id=NEW.destination_id AND currency=NEW.currency AND status='ACTIVE') OR
    (NEW.source_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM financial_accounts WHERE id=NEW.source_id AND currency=NEW.currency AND status='ACTIVE')) THEN
   RAISE EXCEPTION 'financial outcome account currency or status mismatch' USING ERRCODE='23514';
 END IF;
 IF NEW.kind='REVERSAL' THEN
   SELECT * INTO STRICT original FROM transfers WHERE id=NEW.original_transfer_id;
   IF original.kind<>'TRANSFER' OR original.status<>'COMPLETED' OR
      original.source_id IS DISTINCT FROM NEW.destination_id OR original.destination_id IS DISTINCT FROM NEW.source_id OR
      original.amount_minor<>NEW.amount_minor OR original.currency<>NEW.currency THEN
     RAISE EXCEPTION 'reversal outcome disagrees with original transfer' USING ERRCODE='23514';
   END IF;
 END IF;
 IF NEW.status='COMPLETED' THEN
   IF i.response_status<>201 OR i.response_body->>'id' IS DISTINCT FROM NEW.id::text OR
      i.response_body->>'journalId' IS DISTINCT FROM NEW.journal_id::text OR
      i.response_body->>'status' IS DISTINCT FROM NEW.status OR
      i.response_body->>'actorId' IS DISTINCT FROM NEW.actor_id::text OR
      i.response_body->>'sourceAccountId' IS DISTINCT FROM NEW.source_id::text OR
      i.response_body->>'destinationAccountId' IS DISTINCT FROM NEW.destination_id::text OR
      i.response_body->>'kind' IS DISTINCT FROM NEW.kind OR
      i.response_body->>'originalTransferId' IS DISTINCT FROM NEW.original_transfer_id::text OR
      i.response_body->>'currency' IS DISTINCT FROM NEW.currency OR
      i.response_body->>'amount' IS DISTINCT FROM to_char(NEW.amount_minor::numeric/100,'FM99999999999999999990.00') OR
      (SELECT idempotency_reference FROM journal_transactions WHERE id=NEW.journal_id) IS DISTINCT FROM i.id THEN
     RAISE EXCEPTION 'completed transfer and replay outcome disagree' USING ERRCODE='23514';
   END IF;
 ELSE
   IF i.response_status<>422 OR i.response_body->>'transferId' IS DISTINCT FROM NEW.id::text OR
      i.response_body->>'code' IS DISTINCT FROM NEW.failure_code THEN
     RAISE EXCEPTION 'failed transfer and replay outcome disagree' USING ERRCODE='23514';
   END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM audit_events WHERE resource_id=NEW.id AND actor_id=NEW.actor_id AND action=expected_event) OR
    NOT EXISTS(SELECT 1 FROM outbox_events WHERE aggregate_id=NEW.id AND event_type=expected_event) THEN
   RAISE EXCEPTION 'financial outcome requires matching audit and outbox evidence' USING ERRCODE='23514';
 END IF;
 IF NEW.kind='TRANSFER' THEN
   IF NOT EXISTS(SELECT 1 FROM financial_accounts WHERE id=NEW.source_id AND owner_id=NEW.actor_id) THEN
     RAISE EXCEPTION 'transfer actor does not own the source account' USING ERRCODE='23514';
   END IF;
 ELSIF NOT EXISTS(SELECT 1 FROM app_users WHERE id=NEW.actor_id AND role='ADMIN') THEN
   RAISE EXCEPTION 'simulated deposits and reversals require an administrator' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER transfer_financial_evidence AFTER INSERT ON transfers
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_financial_evidence();

CREATE FUNCTION verify_financial_event_reference() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE business_id uuid; action_name text; expected_actor uuid; t transfers;
BEGIN
 IF TG_TABLE_NAME='outbox_events' THEN
   business_id:=NEW.aggregate_id; action_name:=NEW.event_type;
 ELSE
   business_id:=NEW.resource_id; action_name:=NEW.action; expected_actor:=NEW.actor_id;
 END IF;
 IF action_name NOT IN ('TransferCompleted','TransferFailed','SimulatedDepositCompleted','ReversalCompleted') THEN
   RETURN NULL;
 END IF;
 SELECT * INTO t FROM transfers WHERE id=business_id;
 IF NOT FOUND OR action_name IS DISTINCT FROM
     (CASE WHEN t.status='FAILED' THEN 'TransferFailed' WHEN t.kind='TRANSFER' THEN 'TransferCompleted'
       WHEN t.kind='DEPOSIT' THEN 'SimulatedDepositCompleted' ELSE 'ReversalCompleted' END) OR
    (expected_actor IS NOT NULL AND expected_actor<>t.actor_id) THEN
   RAISE EXCEPTION 'financial event or audit does not reference its business outcome' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER outbox_financial_reference AFTER INSERT ON outbox_events
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_financial_event_reference();
CREATE CONSTRAINT TRIGGER audit_financial_reference AFTER INSERT ON audit_events
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_financial_event_reference();

-- A FINISHED financial request must point back to exactly one immutable business outcome.
CREATE FUNCTION verify_idempotency_outcome() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.operation IN ('transfer','simulated-deposit') OR NEW.operation LIKE 'reversal:%') AND
    NOT EXISTS(SELECT 1 FROM transfers WHERE idempotency_reference=NEW.id) THEN
   RAISE EXCEPTION 'financial idempotency record has no durable transfer outcome' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER idempotency_financial_outcome AFTER INSERT OR UPDATE ON idempotency_records
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_idempotency_outcome();
