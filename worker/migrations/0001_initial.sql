PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS consoles (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  slug TEXT NOT NULL UNIQUE,
  manufacturer TEXT NOT NULL DEFAULT '',
  core TEXT NOT NULL,
  extensions TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE IF NOT EXISTS games (
  id TEXT PRIMARY KEY,
  console_id TEXT NOT NULL REFERENCES consoles(id) ON DELETE RESTRICT,
  title TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  cover_url TEXT NOT NULL DEFAULT '',
  source TEXT NOT NULL DEFAULT 'external' CHECK(source IN ('external','supabase')),
  external_url TEXT NOT NULL DEFAULT '',
  storage_key TEXT NOT NULL DEFAULT '',
  file_name TEXT NOT NULL DEFAULT '',
  size_bytes INTEGER NOT NULL DEFAULT 0 CHECK(size_bytes >= 0),
  sha256 TEXT NOT NULL DEFAULT '',
  published INTEGER NOT NULL DEFAULT 1 CHECK(published IN (0,1)),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS idx_games_console ON games(console_id, title);
CREATE INDEX IF NOT EXISTS idx_games_published ON games(published, title);

CREATE TABLE IF NOT EXISTS bios_files (
  id TEXT PRIMARY KEY,
  console_id TEXT NOT NULL REFERENCES consoles(id) ON DELETE RESTRICT,
  name TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  source TEXT NOT NULL DEFAULT 'external' CHECK(source IN ('external','supabase')),
  external_url TEXT NOT NULL DEFAULT '',
  storage_key TEXT NOT NULL DEFAULT '',
  file_name TEXT NOT NULL DEFAULT '',
  system_path TEXT NOT NULL DEFAULT '',
  size_bytes INTEGER NOT NULL DEFAULT 0 CHECK(size_bytes >= 0),
  sha256 TEXT NOT NULL DEFAULT '',
  published INTEGER NOT NULL DEFAULT 1 CHECK(published IN (0,1)),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS idx_bios_console ON bios_files(console_id, name);

CREATE TABLE IF NOT EXISTS cheats (
  id TEXT PRIMARY KEY,
  game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  code TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  published INTEGER NOT NULL DEFAULT 1 CHECK(published IN (0,1)),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS idx_cheats_game ON cheats(game_id, name);

INSERT OR IGNORE INTO consoles (id,name,slug,manufacturer,core,extensions) VALUES
('nes','Nintendo Entertainment System','nes','Nintendo','nestopia','nes,zip,7z'),
('snes','Super Nintendo','snes','Nintendo','snes9x','sfc,smc,zip,7z'),
('gb','Game Boy','gb','Nintendo','gambatte','gb,zip,7z'),
('gbc','Game Boy Color','gbc','Nintendo','gambatte','gbc,zip,7z'),
('gba','Game Boy Advance','gba','Nintendo','mgba','gba,zip,7z'),
('n64','Nintendo 64','n64','Nintendo','mupen64plus_next','n64,z64,v64,zip,7z'),
('genesis','Mega Drive / Genesis','genesis','Sega','genesis_plus_gx','md,gen,bin,smd,zip,7z'),
('ps1','PlayStation','ps1','Sony','pcsx_rearmed','cue,chd,pbp,m3u,zip,7z'),
('psp','PlayStation Portable','psp','Sony','ppsspp','iso,cso,chd,zip,7z'),
('arcade','Arcade','arcade','Arcade','fbneo','zip,7z');
