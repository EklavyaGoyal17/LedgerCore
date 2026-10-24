CREATE TABLE app_users (
  id uuid PRIMARY KEY,
  email varchar(254) NOT NULL UNIQUE,
  password_hash varchar(100) NOT NULL,
  role varchar(10) NOT NULL CHECK (role IN ('USER','ADMIN')),
  status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE ledger_accounts (
  id uuid PRIMARY KEY,
  identifier varchar(100) NOT NULL UNIQUE,
  classification varchar(20) NOT NULL CHECK (classification IN ('LIABILITY','ASSET')),
  currency varchar(3) NOT NULL CHECK (currency IN ('INR','USD')),
  normal_direction varchar(6) NOT NULL CHECK (normal_direction IN ('DEBIT','CREDIT')),
  status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status = 'ACTIVE'),
  CHECK ((classification='LIABILITY' AND normal_direction='CREDIT') OR
         (classification='ASSET' AND normal_direction='DEBIT'))
);

INSERT INTO ledger_accounts(id,identifier,classification,currency,normal_direction) VALUES
 ('00000000-0000-0000-0000-000000000001','SIMULATED-SETTLEMENT-INR','ASSET','INR','DEBIT'),
 ('00000000-0000-0000-0000-000000000002','SIMULATED-SETTLEMENT-USD','ASSET','USD','DEBIT');

CREATE TABLE financial_accounts (
  id uuid PRIMARY KEY,
  owner_id uuid NOT NULL REFERENCES app_users(id),
  ledger_account_id uuid NOT NULL UNIQUE REFERENCES ledger_accounts(id),
  account_type varchar(10) NOT NULL DEFAULT 'CUSTOMER' CHECK (account_type='CUSTOMER'),
  currency varchar(3) NOT NULL CHECK (currency IN ('INR','USD')),
  status varchar(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN')),
  balance_minor bigint NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX financial_accounts_owner ON financial_accounts(owner_id,created_at,id);

CREATE TABLE journal_transactions (
  id uuid PRIMARY KEY,
  reference varchar(50) NOT NULL UNIQUE,
  kind varchar(10) NOT NULL CHECK (kind IN ('TRANSFER','DEPOSIT','REVERSAL')),
  currency varchar(3) NOT NULL CHECK (currency IN ('INR','USD')),
  status varchar(10) NOT NULL DEFAULT 'POSTED' CHECK (status='POSTED'),
  original_journal_id uuid UNIQUE REFERENCES journal_transactions(id),
  idempotency_reference uuid NOT NULL UNIQUE,
  created_xid bigint NOT NULL DEFAULT txid_current(),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  posted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK ((kind='REVERSAL') = (original_journal_id IS NOT NULL))
);
CREATE TABLE journal_entries (
  id uuid PRIMARY KEY,
  journal_id uuid NOT NULL REFERENCES journal_transactions(id),
  ledger_account_id uuid NOT NULL REFERENCES ledger_accounts(id),
  debit_minor bigint NOT NULL DEFAULT 0 CHECK (debit_minor >= 0),
  credit_minor bigint NOT NULL DEFAULT 0 CHECK (credit_minor >= 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK ((debit_minor > 0 AND credit_minor = 0) OR (credit_minor > 0 AND debit_minor = 0))
);
CREATE INDEX journal_entries_journal ON journal_entries(journal_id);
CREATE INDEX journal_entries_statement ON journal_entries(ledger_account_id,created_at,id);

CREATE TABLE transfers (
  id uuid PRIMARY KEY,
  actor_id uuid NOT NULL REFERENCES app_users(id),
  source_id uuid REFERENCES financial_accounts(id),
  destination_id uuid NOT NULL REFERENCES financial_accounts(id),
  amount_minor bigint NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 1000000000000),
  currency varchar(3) NOT NULL CHECK (currency IN ('INR','USD')),
  kind varchar(10) NOT NULL CHECK (kind IN ('TRANSFER','DEPOSIT','REVERSAL')),
  status varchar(10) NOT NULL CHECK (status IN ('COMPLETED','FAILED')),
  failure_code varchar(50),
  journal_id uuid UNIQUE REFERENCES journal_transactions(id),
  original_transfer_id uuid REFERENCES transfers(id),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK (source_id IS DISTINCT FROM destination_id),
  CHECK ((kind='DEPOSIT') = (source_id IS NULL)),
  CHECK ((kind='REVERSAL') = (original_transfer_id IS NOT NULL)),
  CHECK ((status='COMPLETED' AND journal_id IS NOT NULL AND failure_code IS NULL) OR
         (status='FAILED' AND journal_id IS NULL AND failure_code IS NOT NULL))
);
CREATE UNIQUE INDEX one_completed_reversal ON transfers(original_transfer_id) WHERE status='COMPLETED';
CREATE INDEX transfers_source ON transfers(source_id,created_at);
CREATE INDEX transfers_destination ON transfers(destination_id,created_at);

CREATE TABLE idempotency_records (
  id uuid PRIMARY KEY,
  principal_id uuid NOT NULL REFERENCES app_users(id),
  operation varchar(150) NOT NULL,
  key varchar(128) NOT NULL,
  fingerprint varchar(64) NOT NULL,
  state varchar(10) NOT NULL CHECK (state IN ('PROCESSING','FINISHED')),
  response_status integer,
  response_body jsonb,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at timestamptz NOT NULL DEFAULT (clock_timestamp()+interval '7 days'),
  UNIQUE(principal_id,operation,key),
  CHECK ((state='PROCESSING' AND response_status IS NULL AND response_body IS NULL) OR
         (state='FINISHED' AND response_status IS NOT NULL AND response_body IS NOT NULL))
);
ALTER TABLE journal_transactions ADD CONSTRAINT journal_idempotency_fk FOREIGN KEY(idempotency_reference) REFERENCES idempotency_records(id);

CREATE TABLE audit_events (
  id uuid PRIMARY KEY,
  actor_id uuid NOT NULL REFERENCES app_users(id),
  action varchar(50) NOT NULL,
  resource_id uuid NOT NULL,
  correlation_id varchar(64) NOT NULL,
  metadata jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX audit_events_resource ON audit_events(resource_id,created_at);

CREATE TABLE outbox_events (
  id uuid PRIMARY KEY,
  aggregate_id uuid NOT NULL,
  event_type varchar(50) NOT NULL,
  payload jsonb NOT NULL,
  traceparent varchar(100),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  published_at timestamptz,
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX outbox_pending ON outbox_events(next_attempt_at,created_at) WHERE published_at IS NULL;
CREATE TABLE processed_events (
  event_id uuid PRIMARY KEY,
  event_type varchar(50) NOT NULL,
  aggregate_id uuid NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE event_audit_projection (
  event_id uuid PRIMARY KEY REFERENCES processed_events(event_id),
  event_type varchar(50) NOT NULL,
  aggregate_id uuid NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE reconciliation_runs (
  id uuid PRIMARY KEY,
  started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  finished_at timestamptz NOT NULL,
  records_checked bigint NOT NULL,
  discrepancies jsonb NOT NULL,
  status varchar(20) NOT NULL CHECK (status IN ('CLEAN','DISCREPANCIES'))
);

-- Immutability also prevents changing an account's accounting identity after posting.
CREATE FUNCTION reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'immutable financial or audit record' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_journals BEFORE UPDATE OR DELETE ON journal_transactions FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER immutable_entries BEFORE UPDATE OR DELETE ON journal_entries FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER immutable_transfers BEFORE UPDATE OR DELETE ON transfers FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER immutable_ledger_accounts BEFORE UPDATE OR DELETE ON ledger_accounts FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER immutable_reconciliation BEFORE UPDATE OR DELETE ON reconciliation_runs FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE FUNCTION protect_entry_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE j journal_transactions;
BEGIN
 SELECT * INTO STRICT j FROM journal_transactions WHERE id=NEW.journal_id;
 IF j.created_xid <> txid_current() THEN
   RAISE EXCEPTION 'cannot append to a committed journal' USING ERRCODE='23514';
 END IF;
 IF j.currency <> (SELECT currency FROM ledger_accounts WHERE id=NEW.ledger_account_id) THEN
   RAISE EXCEPTION 'journal currency mismatch' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER entry_insert_guard BEFORE INSERT ON journal_entries FOR EACH ROW EXECUTE FUNCTION protect_entry_insert();

CREATE FUNCTION verify_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE jid uuid; d numeric; c numeric; n integer;
BEGIN
 IF TG_TABLE_NAME='journal_transactions' THEN jid:=NEW.id; ELSE jid:=NEW.journal_id; END IF;
 SELECT coalesce(sum(debit_minor),0),coalesce(sum(credit_minor),0),count(*) INTO d,c,n FROM journal_entries WHERE journal_id=jid;
 IF n < 2 OR d<>c THEN RAISE EXCEPTION 'unbalanced journal %',jid USING ERRCODE='23514'; END IF;
 IF NOT EXISTS (SELECT 1 FROM transfers WHERE journal_id=jid AND status='COMPLETED') THEN
   RAISE EXCEPTION 'orphan journal %',jid USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER balanced_journal AFTER INSERT ON journal_transactions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_journal();
CREATE CONSTRAINT TRIGGER balanced_entries AFTER INSERT ON journal_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_journal();

CREATE FUNCTION verify_projection() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE aid uuid; a financial_accounts; derived numeric;
BEGIN
 IF TG_TABLE_NAME='financial_accounts' THEN aid:=NEW.id;
 ELSE SELECT id INTO aid FROM financial_accounts WHERE ledger_account_id=NEW.ledger_account_id; END IF;
 IF aid IS NULL THEN RETURN NULL; END IF;
 SELECT * INTO STRICT a FROM financial_accounts WHERE id=aid;
 IF NOT EXISTS (SELECT 1 FROM ledger_accounts WHERE id=a.ledger_account_id AND currency=a.currency AND classification='LIABILITY') THEN
   RAISE EXCEPTION 'invalid customer ledger mapping' USING ERRCODE='23514'; END IF;
 SELECT coalesce(sum(credit_minor-debit_minor),0) INTO derived FROM journal_entries WHERE ledger_account_id=a.ledger_account_id;
 IF a.balance_minor<>derived OR derived<0 THEN
   RAISE EXCEPTION 'balance projection mismatch for %',aid USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER account_projection AFTER INSERT OR UPDATE ON financial_accounts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_projection();
CREATE CONSTRAINT TRIGGER entry_projection AFTER INSERT ON journal_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_projection();

CREATE FUNCTION protect_account_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.id,NEW.owner_id,NEW.ledger_account_id,NEW.currency,NEW.account_type,NEW.created_at)
 IS DISTINCT FROM (OLD.id,OLD.owner_id,OLD.ledger_account_id,OLD.currency,OLD.account_type,OLD.created_at) THEN
   RAISE EXCEPTION 'immutable account identity' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER account_identity BEFORE UPDATE ON financial_accounts FOR EACH ROW EXECUTE FUNCTION protect_account_identity();

CREATE FUNCTION verify_transfer() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE j journal_transactions; src uuid; dst uuid; orig transfers;
BEGIN
 IF NEW.status='FAILED' THEN RETURN NULL; END IF;
 SELECT * INTO STRICT j FROM journal_transactions WHERE id=NEW.journal_id;
 SELECT ledger_account_id INTO dst FROM financial_accounts WHERE id=NEW.destination_id AND currency=NEW.currency;
 IF NEW.kind='DEPOSIT' THEN
   SELECT id INTO src FROM ledger_accounts WHERE identifier='SIMULATED-SETTLEMENT-'||NEW.currency;
 ELSE SELECT ledger_account_id INTO src FROM financial_accounts WHERE id=NEW.source_id AND currency=NEW.currency; END IF;
 IF j.currency<>NEW.currency OR j.kind<>NEW.kind OR src IS NULL OR dst IS NULL OR
    (SELECT count(*) FROM journal_entries WHERE journal_id=j.id)<>2 OR
    NOT EXISTS(SELECT 1 FROM journal_entries WHERE journal_id=j.id AND ledger_account_id=src AND debit_minor=NEW.amount_minor AND credit_minor=0) OR
    NOT EXISTS(SELECT 1 FROM journal_entries WHERE journal_id=j.id AND ledger_account_id=dst AND credit_minor=NEW.amount_minor AND debit_minor=0) THEN
   RAISE EXCEPTION 'transfer and journal disagree' USING ERRCODE='23514'; END IF;
 IF NEW.kind='REVERSAL' THEN
   SELECT * INTO STRICT orig FROM transfers WHERE id=NEW.original_transfer_id;
   IF orig.kind<>'TRANSFER' OR orig.status<>'COMPLETED' OR orig.source_id<>NEW.destination_id OR orig.destination_id<>NEW.source_id OR orig.amount_minor<>NEW.amount_minor OR orig.currency<>NEW.currency OR j.original_journal_id<>orig.journal_id THEN
     RAISE EXCEPTION 'invalid reversal' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER transfer_journal_consistency AFTER INSERT ON transfers DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_transfer();

CREATE FUNCTION verify_idempotency_finished() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM idempotency_records WHERE id=NEW.id AND state<>'FINISHED') THEN
   RAISE EXCEPTION 'cannot commit an unfinished operation' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER finished_operation AFTER INSERT OR UPDATE ON idempotency_records DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_idempotency_finished();
