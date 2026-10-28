use crate::models::{AppSettings, Catalog, Game, InstalledGame};
use rusqlite::{params, Connection, OptionalExtension};
use std::path::Path;

const API: &str = "https://retro-hub-68aa04.am0u0dev.workers.dev";
fn open(path:&Path)->Result<Connection,String>{let c=Connection::open(path).map_err(|e|format!("خطای پایگاه داده: {e}"))?;c.busy_timeout(std::time::Duration::from_secs(5)).map_err(|e|e.to_string())?;Ok(c)}
pub fn initialize(path:&Path)->Result<(),String>{
    let c=open(path)?;
    c.execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
      CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS installed_games(id TEXT PRIMARY KEY,title TEXT NOT NULL,console_id TEXT NOT NULL,console_name TEXT NOT NULL,core TEXT NOT NULL,cover_url TEXT NOT NULL,game_path TEXT NOT NULL,installed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP);
      CREATE TABLE IF NOT EXISTS game_preferences(game_id TEXT PRIMARY KEY,preset TEXT NOT NULL DEFAULT 'balanced' CHECK(preset IN ('performance','balanced','quality')));
      CREATE TABLE IF NOT EXISTS catalog_cache(id INTEGER PRIMARY KEY CHECK (id=1), json TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS bios_installs(bios_id TEXT NOT NULL, system_dir TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(bios_id,system_dir));
      CREATE TABLE IF NOT EXISTS control_profiles(scope TEXT PRIMARY KEY, json TEXT NOT NULL);")
      .map_err(|e|e.to_string())?;
    // Upgrade existing local libraries without losing installations.
    let mut columns=c.prepare("PRAGMA table_info(installed_games)").map_err(|e|e.to_string())?;
    let names=columns.query_map([],|r|r.get::<_,String>(1)).map_err(|e|e.to_string())?.collect::<Result<Vec<_>,_>>().map_err(|e|e.to_string())?;
    drop(columns);
    if !names.iter().any(|v|v=="icon_url") { c.execute("ALTER TABLE installed_games ADD COLUMN icon_url TEXT NOT NULL DEFAULT ''",[]).map_err(|e|e.to_string())?; }
    Ok(())
}
fn get(c:&Connection,key:&str,default:String)->Result<String,String>{c.query_row("SELECT value FROM settings WHERE key=?1",[key],|r|r.get(0)).optional().map_err(|e|e.to_string()).map(|v|v.unwrap_or(default))}
pub fn load_settings(path:&Path, data:&Path)->Result<AppSettings,String>{
    let c=open(path)?; let home=dirs::home_dir().unwrap_or_else(||data.to_path_buf());
    #[cfg(target_os="windows")]
    let retro=dirs::config_dir().unwrap_or_else(||home.clone()).join("RetroArch");
    #[cfg(not(target_os="windows"))]
    let retro=dirs::config_dir().unwrap_or_else(||home.join(".config")).join("retroarch");
    #[cfg(target_os="windows")] let exe="retroarch.exe";
    #[cfg(not(target_os="windows"))] let exe="retroarch";
    Ok(AppSettings{
        api_url:get(&c,"api_url",API.into())?,
        games_dir:get(&c,"games_dir",data.join("games").to_string_lossy().into_owned())?,
        retroarch_path:get(&c,"retroarch_path",exe.into())?,
        cores_dir:get(&c,"cores_dir",retro.join("cores").to_string_lossy().into_owned())?,
        system_dir:get(&c,"system_dir",retro.join("system").to_string_lossy().into_owned())?,
    })
}
pub fn save_settings(path:&Path,s:&AppSettings)->Result<(),String>{
    let url=reqwest::Url::parse(&s.api_url).map_err(|_|"آدرس API نامعتبر است.".to_string())?;
    if !matches!(url.scheme(),"https"|"http") {return Err("آدرس API باید HTTP یا HTTPS باشد.".into());}
    for p in [&s.games_dir,&s.cores_dir,&s.system_dir] {if !Path::new(p).is_absolute(){return Err("مسیر پوشه‌ها باید مطلق باشد.".into());}}
    if s.retroarch_path.trim().is_empty(){return Err("مسیر RetroArch خالی است.".into());}
    let mut c=open(path)?;let tx=c.transaction().map_err(|e|e.to_string())?;
    for (k,v) in [("api_url",s.api_url.trim_end_matches('/')),("games_dir",s.games_dir.as_str()),("retroarch_path",s.retroarch_path.as_str()),("cores_dir",s.cores_dir.as_str()),("system_dir",s.system_dir.as_str())]{tx.execute("INSERT INTO settings(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",params![k,v]).map_err(|e|e.to_string())?;}
    tx.commit().map_err(|e|e.to_string())
}
pub fn record_install(path:&Path,game:&Game,game_path:&Path)->Result<(),String>{
    let c=open(path)?; c.execute("INSERT INTO installed_games(id,title,console_id,console_name,core,cover_url,game_path,icon_url) VALUES(?1,?2,?3,?4,?5,?6,?7,?8) ON CONFLICT(id) DO UPDATE SET title=excluded.title,console_id=excluded.console_id,console_name=excluded.console_name,core=excluded.core,cover_url=excluded.cover_url,game_path=excluded.game_path,icon_url=excluded.icon_url,installed_at=CURRENT_TIMESTAMP",params![game.id,game.title,game.console_id,game.console_name,game.console_core,game.cover_url,game_path.to_string_lossy().as_ref(),game.icon_url]).map_err(|e|e.to_string())?;Ok(())
}
pub fn installed(path:&Path)->Result<Vec<InstalledGame>,String>{
    let c=open(path)?; let mut st=c.prepare("SELECT g.id,g.title,g.console_id,g.console_name,g.core,g.cover_url,g.game_path,COALESCE(p.preset,'balanced'),g.icon_url FROM installed_games g LEFT JOIN game_preferences p ON p.game_id=g.id ORDER BY g.title COLLATE NOCASE").map_err(|e|e.to_string())?;
    let rows=st.query_map([],|r|Ok(InstalledGame{id:r.get(0)?,title:r.get(1)?,console_id:r.get(2)?,console_name:r.get(3)?,core:r.get(4)?,cover_url:r.get(5)?,game_path:r.get(6)?,preset:r.get(7)?,icon_url:r.get(8)?})).map_err(|e|e.to_string())?;
    rows.collect::<Result<Vec<_>,_>>().map_err(|e|e.to_string())
}
pub fn set_preset(path:&Path,id:&str,preset:&str)->Result<(),String>{
    if !matches!(preset,"performance"|"balanced"|"quality"){return Err("حالت عملکرد نامعتبر است.".into())}
    open(path)?.execute("INSERT INTO game_preferences(game_id,preset) VALUES(?1,?2) ON CONFLICT(game_id) DO UPDATE SET preset=excluded.preset",params![id,preset]).map_err(|e|e.to_string())?;Ok(())
}

pub fn save_catalog(path:&Path,catalog:&Catalog)->Result<(),String>{
    let json=serde_json::to_string(catalog).map_err(|e|e.to_string())?;
    let mut db=open(path)?;let tx=db.transaction().map_err(|e|e.to_string())?;
    tx.execute("INSERT INTO catalog_cache(id,json) VALUES(1,?1) ON CONFLICT(id) DO UPDATE SET json=excluded.json",[json]).map_err(|e|e.to_string())?;
    for g in &catalog.games {
        tx.execute("UPDATE installed_games SET title=?1,console_name=?2,core=?3,cover_url=?4,icon_url=?5 WHERE id=?6",params![g.title,g.console_name,g.console_core,g.cover_url,g.icon_url,g.id]).map_err(|e|e.to_string())?;
    }
    tx.commit().map_err(|e|e.to_string())
}
pub fn cached_catalog(path:&Path)->Result<Option<Catalog>,String>{
    let text:Option<String>=open(path)?.query_row("SELECT json FROM catalog_cache WHERE id=1",[],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
    text.map(|v|serde_json::from_str(&v).map_err(|e|e.to_string())).transpose()
}

pub fn all_preferences(path:&Path)->Result<Vec<(String,String)>,String>{
    let c=open(path)?;let mut st=c.prepare("SELECT game_id,preset FROM game_preferences").map_err(|e|e.to_string())?;
    let rows=st.query_map([],|r|Ok((r.get(0)?,r.get(1)?))).map_err(|e|e.to_string())?;
    rows.collect::<Result<Vec<_>,_>>().map_err(|e|e.to_string())
}

pub fn bios_manifest(path:&Path,id:&str,system:&str)->Result<Option<String>,String>{
    open(path)?.query_row("SELECT json FROM bios_installs WHERE bios_id=?1 AND system_dir=?2",params![id,system],|r|r.get(0)).optional().map_err(|e|e.to_string())
}
pub fn save_bios_manifest(path:&Path,id:&str,system:&str,json:&str)->Result<(),String>{
    open(path)?.execute("INSERT INTO bios_installs(bios_id,system_dir,json) VALUES(?1,?2,?3) ON CONFLICT(bios_id,system_dir) DO UPDATE SET json=excluded.json",params![id,system,json]).map_err(|e|e.to_string())?;Ok(())
}
pub fn control_profile(path:&Path,scope:&str)->Result<Option<String>,String>{
    open(path)?.query_row("SELECT json FROM control_profiles WHERE scope=?1",[scope],|r|r.get(0)).optional().map_err(|e|e.to_string())
}
pub fn save_control_profile(path:&Path,scope:&str,json:&str)->Result<(),String>{
    open(path)?.execute("INSERT INTO control_profiles(scope,json) VALUES(?1,?2) ON CONFLICT(scope) DO UPDATE SET json=excluded.json",params![scope,json]).map_err(|e|e.to_string())?;Ok(())
}
pub fn delete_control_profile(path:&Path,scope:&str)->Result<(),String>{
    open(path)?.execute("DELETE FROM control_profiles WHERE scope=?1",[scope]).map_err(|e|e.to_string())?;Ok(())
}

#[cfg(test)]mod tests{
 use super::*;use crate::models::Console;
 #[test]fn caches_catalog_and_keeps_local_preferences(){
   let t=tempfile::tempdir().unwrap();let db=t.path().join("library.db");initialize(&db).unwrap();assert!(cached_catalog(&db).unwrap().is_none());
   let game=Game{id:"one".into(),title:"Demo".into(),console_id:"nes".into(),console_name:"NES".into(),console_core:"nestopia".into(),cover_url:"https://example.com/cover.png".into(),icon_url:"https://example.com/icon.png".into(),..Default::default()};
   record_install(&db,&game,&t.path().join("one.nes")).unwrap();set_preset(&db,"one","quality").unwrap();assert!(set_preset(&db,"one","bad").is_err());
   save_catalog(&db,&Catalog{consoles:vec![Console{id:"nes".into(),..Default::default()}],games:vec![game]}).unwrap();let result=cached_catalog(&db).unwrap().unwrap();assert_eq!(result.games[0].title,"Demo");let local=installed(&db).unwrap();assert_eq!(local[0].preset,"quality");assert_eq!(local[0].icon_url,"https://example.com/icon.png");
   initialize(&db).unwrap();assert_eq!(installed(&db).unwrap().len(),1);
 }
}
