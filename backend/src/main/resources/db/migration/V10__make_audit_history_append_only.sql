CREATE FUNCTION reject_audit_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Audit history is append-only'
        USING ERRCODE = '23514';
    RETURN NULL;
END;
$$;

CREATE TRIGGER access_audits_append_only
    BEFORE UPDATE OR DELETE ON access_audits
    FOR EACH ROW EXECUTE FUNCTION reject_audit_history_mutation();

CREATE TRIGGER device_ownership_audits_append_only
    BEFORE UPDATE OR DELETE ON device_ownership_audits
    FOR EACH ROW EXECUTE FUNCTION reject_audit_history_mutation();

CREATE TRIGGER device_status_audits_append_only
    BEFORE UPDATE OR DELETE ON device_status_audits
    FOR EACH ROW EXECUTE FUNCTION reject_audit_history_mutation();

CREATE TRIGGER policy_change_audits_append_only
    BEFORE UPDATE OR DELETE ON policy_change_audits
    FOR EACH ROW EXECUTE FUNCTION reject_audit_history_mutation();
