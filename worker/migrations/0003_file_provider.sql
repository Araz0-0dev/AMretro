-- Preserve existing rows and constraints; this column distinguishes direct links from Cloudflare uploads.
ALTER TABLE games ADD COLUMN file_provider TEXT NOT NULL DEFAULT 'external' CHECK(file_provider IN ('external','cloudflare'));
ALTER TABLE bios_files ADD COLUMN file_provider TEXT NOT NULL DEFAULT 'external' CHECK(file_provider IN ('external','cloudflare'));
