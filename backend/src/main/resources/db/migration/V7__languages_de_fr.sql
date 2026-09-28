-- German and French join Italian and English as interface languages.
ALTER TABLE app_user DROP CONSTRAINT app_user_language_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_language_check CHECK (language IN ('IT', 'EN', 'DE', 'FR'));
