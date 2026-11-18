-- bank-core 0.2: transfers to other banks, which bank-integration carries as ISO 20022 messages.

-- The bank's own accounts. A transfer to another bank first moves the money to TRANSIT, where it waits for
-- that bank's answer. Accepted, it moves on to SETTLEMENT, which stands for the bank's account at the National
-- Bank; refused, it goes back to the customer. Customers never see these accounts or pay into them.
ALTER TABLE accounts
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER'
        CHECK (kind IN ('CUSTOMER', 'TRANSIT', 'SETTLEMENT'));

-- One account of each internal kind.
CREATE UNIQUE INDEX accounts_one_per_internal_kind ON accounts (kind) WHERE kind <> 'CUSTOMER';

INSERT INTO accounts (iban, holder_name, currency, balance, kind, created_at)
VALUES ('UA693999990000000000000009001', 'Outgoing interbank payments in transit', 'UAH', 0.00, 'TRANSIT', now()),
       ('UA423999990000000000000009002', 'Settlement account at the National Bank', 'UAH', 0.00, 'SETTLEMENT', now());

-- The payee of a transfer to another bank holds no account here.
ALTER TABLE transfers DROP CONSTRAINT transfers_to_iban_fkey;

ALTER TABLE transfers
    -- The payee's name, which the payment message to the other bank must carry.
    ADD COLUMN to_name VARCHAR(140),
    -- The other bank's status report (the MsgId of its pacs.002) that settled or rejected the transfer.
    ADD COLUMN status_report_id VARCHAR(35),
    ADD CONSTRAINT transfers_status_known CHECK (status IN ('PENDING', 'COMPLETED', 'REJECTED'));

-- Spring Modulith's event publication registry, which is the outbox: an event is written here in the same
-- transaction as its transfer and deleted once Kafka has it, so the table holds only what is still to be sent.
-- The schema is Modulith's own for PostgreSQL (version 2 of its layout), created here so Flyway owns every table.
CREATE TABLE event_publication
(
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
