create table voice_note_send_intent (
    id bigserial primary key,
    operator_id bigint not null references operator_account(id) on delete cascade,
    status varchar(32) not null,
    destination_type varchar(40) not null,
    project_id bigint references project(id) on delete set null,
    project_name varchar(160),
    work_session_id bigint references work_session(id) on delete set null,
    work_session_title varchar(220),
    note_ids_json text not null,
    instruction varchar(500),
    confirmation_token varchar(80) not null,
    confirmation_prompt text not null,
    agent_run_id bigint references agent_run(id) on delete set null,
    error_message text,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    confirmed_at timestamptz,
    sent_at timestamptz,
    cancelled_at timestamptz,
    updated_at timestamptz not null,
    constraint ck_voice_note_send_intent_status
        check (status in ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'FAILED', 'SENT')),
    constraint ck_voice_note_send_intent_destination_type
        check (destination_type in ('WORK_SESSION'))
);

create index idx_voice_note_send_intent_operator_status_created
    on voice_note_send_intent(operator_id, status, created_at desc);
