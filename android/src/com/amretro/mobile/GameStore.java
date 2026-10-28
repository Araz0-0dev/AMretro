package com.amretro.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

final class GameStore extends SQLiteOpenHelper {
    GameStore(Context context) { super(context, "amretro-mobile.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE games (id TEXT PRIMARY KEY, title TEXT NOT NULL, console_id TEXT NOT NULL, core TEXT NOT NULL, rom_path TEXT NOT NULL, cover_url TEXT NOT NULL DEFAULT '', ra_id INTEGER NOT NULL DEFAULT 0, play_ms INTEGER NOT NULL DEFAULT 0, sessions INTEGER NOT NULL DEFAULT 0)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
    synchronized void add(String id, String title, String console, String core, String rom, String cover) {
        ContentValues v = new ContentValues();
        v.put("id", id); v.put("title", title); v.put("console_id", console); v.put("core", core);
        v.put("rom_path", rom); v.put("cover_url", cover == null ? "" : cover);
        getWritableDatabase().insertOrThrow("games", null, v);
    }
    synchronized JSONObject get(String id) {
        try (Cursor c = getReadableDatabase().query("games", null, "id=?", new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? item(c) : null;
        }
    }
    synchronized JSONArray all() {
        JSONArray items = new JSONArray();
        try (Cursor c = getReadableDatabase().query("games", null, null, null, null, null, "title COLLATE NOCASE ASC")) {
            while (c.moveToNext()) items.put(item(c));
        }
        return items;
    }
    private JSONObject item(Cursor c) {
        JSONObject o = new JSONObject();
        try {
            for (String name : new String[]{"id", "title", "console_id", "core", "rom_path", "cover_url"}) o.put(name, c.getString(c.getColumnIndexOrThrow(name)));
            o.put("ra_id", c.getInt(c.getColumnIndexOrThrow("ra_id")));
            o.put("play_ms", c.getLong(c.getColumnIndexOrThrow("play_ms")));
            o.put("sessions", c.getInt(c.getColumnIndexOrThrow("sessions")));
        } catch (Exception ignored) { }
        return o;
    }
    synchronized void setRaId(String id, int raId) {
        ContentValues v = new ContentValues(); v.put("ra_id", Math.max(0, raId));
        getWritableDatabase().update("games", v, "id=?", new String[]{id});
    }
    synchronized void record(String id, long millis) {
        if (millis < 0) return;
        getWritableDatabase().execSQL("UPDATE games SET play_ms=play_ms+?, sessions=sessions+1 WHERE id=?", new Object[]{millis, id});
    }
    synchronized void remove(String id) { getWritableDatabase().delete("games", "id=?", new String[]{id}); }
}
