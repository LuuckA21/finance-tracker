-- Interface preferences saved per user, so they follow the account on every device.
ALTER TABLE app_user
    ADD COLUMN language VARCHAR(2) NOT NULL DEFAULT 'IT' CHECK (language IN ('IT', 'EN')),
    ADD COLUMN theme    VARCHAR(8) NOT NULL DEFAULT 'SYSTEM' CHECK (theme IN ('SYSTEM', 'LIGHT', 'DARK'));
