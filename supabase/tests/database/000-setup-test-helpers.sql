-- Runs first (alphabetical). Installs pgTAP + vendored Basejump test helpers for RLS/auth simulation.
-- Source: https://github.com/usebasejump/supabase-test-helpers (v0.0.6), trimmed to required functions.

create extension if not exists pgtap with schema extensions;

create schema if not exists tests;
create schema if not exists test_overrides;

grant usage on schema tests to anon, authenticated, service_role;
alter default privileges in schema tests revoke execute on functions from public;
alter default privileges in schema tests grant execute on functions to anon, authenticated, service_role;

grant usage on schema test_overrides to anon, authenticated, service_role;
alter default privileges in schema test_overrides revoke execute on functions from public;
alter default privileges in schema test_overrides grant execute on functions to anon, authenticated, service_role;

create or replace function tests.create_supabase_user(
    identifier text,
    email text default null,
    phone text default null,
    metadata jsonb default null
)
returns uuid
security definer
set search_path = auth, pg_temp
as $$
declare
    user_id uuid;
begin
    user_id := extensions.uuid_generate_v4();
    insert into auth.users (id, email, phone, raw_user_meta_data, raw_app_meta_data, created_at, updated_at)
    values (
        user_id,
        coalesce(email, concat(user_id, '@test.com')),
        phone,
        jsonb_build_object('test_identifier', identifier) || coalesce(metadata, '{}'::jsonb),
        '{}'::jsonb,
        now(),
        now()
    );
    return user_id;
end;
$$ language plpgsql;

create or replace function tests.get_supabase_user(identifier text)
returns json
security definer
set search_path = auth, pg_temp
as $$
declare
    supabase_user json;
begin
    select json_build_object(
        'id', id,
        'email', email,
        'phone', phone,
        'raw_user_meta_data', raw_user_meta_data,
        'raw_app_meta_data', raw_app_meta_data
    )
    into supabase_user
    from auth.users
    where raw_user_meta_data ->> 'test_identifier' = identifier
    limit 1;

    if supabase_user is null or supabase_user -> 'id' is null then
        raise exception 'User with identifier % not found', identifier;
    end if;

    return supabase_user;
end;
$$ language plpgsql;

create or replace function tests.get_supabase_uid(identifier text)
returns uuid
security definer
set search_path = auth, pg_temp
as $$
declare
    supabase_user uuid;
begin
    select id
    into supabase_user
    from auth.users
    where raw_user_meta_data ->> 'test_identifier' = identifier
    limit 1;

    if supabase_user is null then
        raise exception 'User with identifier % not found', identifier;
    end if;

    return supabase_user;
end;
$$ language plpgsql;

create or replace function tests.authenticate_as(identifier text)
returns void
as $$
declare
    user_data json;
    original_auth_data text;
begin
    original_auth_data := current_setting('request.jwt.claims', true);
    user_data := tests.get_supabase_user(identifier);

    if user_data is null or user_data ->> 'id' is null then
        raise exception 'User with identifier % not found', identifier;
    end if;

    perform set_config('role', 'authenticated', true);
    perform set_config(
        'request.jwt.claims',
        json_build_object(
            'sub', user_data ->> 'id',
            'email', user_data ->> 'email',
            'phone', user_data ->> 'phone',
            'user_metadata', user_data -> 'raw_user_meta_data',
            'app_metadata', user_data -> 'raw_app_meta_data'
        )::text,
        true
    );
exception
    when others then
        set local role authenticated;
        set local "request.jwt.claims" to original_auth_data;
        raise;
end;
$$ language plpgsql;

create or replace function tests.clear_authentication()
returns void
as $$
begin
    perform set_config('role', 'anon', true);
    perform set_config('request.jwt.claims', null, true);
end;
$$ language plpgsql;

create or replace function tests.rls_enabled(testing_schema text)
returns text
as $$
    select is(
        (
            select count(pc.relname)::integer
            from pg_class pc
            join pg_namespace pn on pn.oid = pc.relnamespace and pn.nspname = rls_enabled.testing_schema
            join pg_type pt on pt.oid = pc.reltype
            where relrowsecurity = false
        ),
        0,
        'All tables in the ' || testing_schema || ' schema should have row level security enabled'
    );
$$ language sql;

create or replace function tests.rls_enabled(testing_schema text, testing_table text)
returns text
as $$
    select is(
        (
            select count(*)::integer
            from pg_class pc
            join pg_namespace pn
                on pn.oid = pc.relnamespace
                and pn.nspname = rls_enabled.testing_schema
                and pc.relname = rls_enabled.testing_table
            join pg_type pt on pt.oid = pc.reltype
            where relrowsecurity = true
        ),
        1,
        testing_table || ' in the ' || testing_schema || ' schema should have row level security enabled'
    );
$$ language sql;

begin;
select plan(1);
select ok(true, 'test helpers installed');
select * from finish();
rollback;
