-- The two category levels, checked by the database too (the service checks them first, with a
-- clearer message): a detail sits under a macro of the same user and kind, and has no details of its
-- own; a macro with details keeps its kind.
CREATE FUNCTION category_levels_check() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    parent category%ROWTYPE;
BEGIN
    IF NEW.parent_id IS NOT NULL THEN
        SELECT * INTO parent FROM category WHERE id = NEW.parent_id;
        IF parent.id = NEW.id OR parent.user_id <> NEW.user_id OR parent.kind <> NEW.kind
                OR parent.parent_id IS NOT NULL THEN
            RAISE EXCEPTION 'category %: the parent must be a macro category of the same user and kind', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.parent_id IS NOT NULL OR NEW.kind <> OLD.kind OR NEW.user_id <> OLD.user_id)
            AND EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.id) THEN
        RAISE EXCEPTION 'category %: a category with details stays a macro of its kind', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER category_levels
    BEFORE INSERT OR UPDATE OF parent_id, kind, user_id ON category
    FOR EACH ROW EXECUTE FUNCTION category_levels_check();
