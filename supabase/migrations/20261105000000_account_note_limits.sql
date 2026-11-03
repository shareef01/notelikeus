-- Audit of 2026-11-02, AUD-004: an account's notes are bounded, as its attachments already were.
--
-- `validate_note_payload` caps each note (100 000 characters of content, 500 checklist items of 2 000,
-- 100 labels), but nothing capped how many notes an account holds, so one account could grow the
-- database without limit at about a megabyte a row. Attachments have had a 200 MB quota since the
-- start; notes now have two limits of their own:
--
--   * 10 000 notes, and
--   * 100 MiB of note text: title, content, labels and checklist, measured as stored text.
--
-- Enforcement is one trigger on `notes`, so every writer is covered (apply_note_change in all its
-- arities, restore_note), including any added later. It keeps a per-account running total in
-- `account_note_usage` from each row's own change, so a write costs one row's size, never a scan of
-- the account. The writers already hold the per-owner `note_sync_write:` lock, which serialises the
-- total's updates.
--
-- A write is refused (NL005) only when it would take the account past a limit *and* grows it. An
-- account already over a limit, from before this migration, can still shrink notes and delete them,
-- so nobody is stuck: they just cannot add more until they are back under.
--
-- Rollback: drop the trigger, the functions and the table. No note changes either way.

CREATE TABLE public.account_note_usage (
    owner_id UUID PRIMARY KEY REFERENCES auth.users (id) ON DELETE CASCADE,
    note_count INTEGER NOT NULL DEFAULT 0,
    note_bytes BIGINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE public.account_note_usage IS
    'Running note count and stored note text per account, kept by notes_account_usage (AUD-004). '
    'Written only by that trigger; no role reads or writes it directly.';

-- RLS on, no policies, no grants: nothing outside the trigger's definer reaches it.
ALTER TABLE public.account_note_usage ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.account_note_usage FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION notelikeus_internal.note_limit_count()
RETURNS integer LANGUAGE sql IMMUTABLE AS $$ SELECT 10000 $$;

CREATE OR REPLACE FUNCTION notelikeus_internal.note_limit_bytes()
RETURNS bigint LANGUAGE sql IMMUTABLE AS $$ SELECT 104857600::bigint $$;

/*
 * The text a note row stores, in bytes: what the byte limit counts. `octet_length` of a text column
 * reads its stored length without decompressing it; the two JSON columns are serialised, which is
 * bounded by validate_note_payload's per-note limits.
 */
CREATE OR REPLACE FUNCTION notelikeus_internal.note_row_bytes(p_note public.notes)
RETURNS bigint
LANGUAGE sql
STABLE
SET search_path = public
AS $$
    SELECT coalesce(octet_length(p_note.title), 0)::bigint
         + coalesce(octet_length(p_note.content), 0)::bigint
         + coalesce(octet_length(p_note.labels::text), 0)::bigint
         + coalesce(octet_length(p_note.checklist::text), 0)::bigint;
$$;

/*
 * Keeps an account's running total, and refuses the write that would grow it past a limit.
 *
 * SECURITY DEFINER because the total must not be writable by the account it limits; the function only
 * ever touches the row of the note's own owner. An AFTER ROW trigger: raising here aborts the statement
 * and, with it, the transaction's other changes, so a refused write leaves the note and the total as
 * they were.
 */
CREATE OR REPLACE FUNCTION notelikeus_internal.notes_account_usage()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_owner UUID := CASE WHEN TG_OP = 'DELETE' THEN OLD.owner_id ELSE NEW.owner_id END;
    v_count_delta INTEGER := 0;
    v_bytes_delta BIGINT := 0;
    v_usage public.account_note_usage%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        v_count_delta := 1;
        v_bytes_delta := notelikeus_internal.note_row_bytes(NEW);
    ELSIF TG_OP = 'UPDATE' THEN
        v_bytes_delta := notelikeus_internal.note_row_bytes(NEW) - notelikeus_internal.note_row_bytes(OLD);
    ELSE
        v_count_delta := -1;
        v_bytes_delta := -notelikeus_internal.note_row_bytes(OLD);
    END IF;

    INSERT INTO public.account_note_usage AS u (owner_id, note_count, note_bytes)
    VALUES (v_owner, GREATEST(v_count_delta, 0), GREATEST(v_bytes_delta, 0))
    ON CONFLICT (owner_id) DO UPDATE
        SET note_count = GREATEST(u.note_count + v_count_delta, 0),
            note_bytes = GREATEST(u.note_bytes + v_bytes_delta, 0)
    RETURNING * INTO v_usage;

    IF v_count_delta > 0 AND v_usage.note_count > notelikeus_internal.note_limit_count() THEN
        RAISE EXCEPTION
            'account_note_limit: this account has reached its limit of % notes. Delete notes you no '
            'longer need to sync new ones.', notelikeus_internal.note_limit_count()
            USING ERRCODE = 'NL005';
    END IF;
    IF v_bytes_delta > 0 AND v_usage.note_bytes > notelikeus_internal.note_limit_bytes() THEN
        RAISE EXCEPTION
            'account_note_limit: this account has reached its limit of 100 MB of note text. Shorten '
            'or delete notes you no longer need to sync this change.'
            USING ERRCODE = 'NL005';
    END IF;

    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION notelikeus_internal.note_limit_count() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION notelikeus_internal.note_limit_bytes() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION notelikeus_internal.note_row_bytes(public.notes) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION notelikeus_internal.notes_account_usage() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION notelikeus_internal.note_limit_count() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION notelikeus_internal.note_limit_bytes() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION notelikeus_internal.note_row_bytes(public.notes) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION notelikeus_internal.notes_account_usage() TO authenticated, service_role;

CREATE TRIGGER notes_account_usage
    AFTER INSERT OR UPDATE OR DELETE ON public.notes
    FOR EACH ROW
    EXECUTE FUNCTION notelikeus_internal.notes_account_usage();

-- Existing accounts start from what they hold now. Over-limit accounts are recorded as they are;
-- the trigger lets them shrink, not grow.
INSERT INTO public.account_note_usage (owner_id, note_count, note_bytes)
SELECT n.owner_id, count(*)::integer, sum(notelikeus_internal.note_row_bytes(n))::bigint
FROM public.notes n
GROUP BY n.owner_id
ON CONFLICT (owner_id) DO UPDATE
    SET note_count = EXCLUDED.note_count,
        note_bytes = EXCLUDED.note_bytes;
