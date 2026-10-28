-- Optional icon artwork, independently from game cover and gallery images.
ALTER TABLE games ADD COLUMN icon_url TEXT NOT NULL DEFAULT '';
ALTER TABLE games ADD COLUMN icon_key TEXT NOT NULL DEFAULT '';
ALTER TABLE consoles ADD COLUMN icon_url TEXT NOT NULL DEFAULT '';
ALTER TABLE consoles ADD COLUMN icon_key TEXT NOT NULL DEFAULT '';
