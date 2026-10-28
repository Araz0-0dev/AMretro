//! Named, independently switchable keyboard and logical controller profiles.
//! RetroArch still chooses its physical controller using its own device index.
use crate::{controls::{self,ControlProfile},db};
use rusqlite::{params,Connection,OptionalExtension,Transaction,TransactionBehavior};
use serde::{Deserialize,Serialize};
use std::{collections::BTreeMap,path::Path};

const MAX_PROFILES:i64=32;
#[derive(Clone,Debug,Serialize,Deserialize,PartialEq,Eq)]
pub struct Preset {pub id:String,pub kind:String,pub name:String,pub device_guid:String,pub active:bool}
type Mapping=BTreeMap<String,String>;
fn kind(k:&str)->Result<&str,String>{if k=="keyboard"||k=="controller"{Ok(k)}else{Err("نوع پروفایل معتبر نیست.".into())}}
fn valid_name(name:&str)->Result<String,String>{
    let n=name.trim();if n.is_empty()||n.chars().count()>40||n.chars().any(char::is_control){return Err("نام پروفایل باید بین ۱ تا ۴۰ نویسه و بدون نویسهٔ کنترلی باشد.".into())}Ok(n.into())
}
fn valid_guid(g:&str)->bool{g.is_empty()||(g.len()==36&&g.chars().all(|c|c.is_ascii_hexdigit()||c=='-'))}
fn mapping(p:&ControlProfile,k:&str)->Mapping{if k=="keyboard"{p.keyboard.clone()}else{p.gamepad.clone()}}
fn set_mapping(p:&mut ControlProfile,k:&str,m:Mapping){if k=="keyboard"{p.keyboard=m}else{p.gamepad=m}}
fn global(c:&Connection)->Result<ControlProfile,String>{
    let raw:Option<String>=c.query_row("SELECT json FROM control_profiles WHERE scope='global'",[],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
    let p=match raw {Some(s)=>serde_json::from_str(&s).map_err(|_|"تنظیم دکمه‌های عمومی نامعتبر است.".to_string())?,None=>controls::default_profile()};
    controls::validate(&p)?;Ok(p)
}
fn put_global(tx:&Transaction<'_>,p:&ControlProfile)->Result<(),String>{
    controls::validate(p)?;let json=serde_json::to_string(p).map_err(|e|e.to_string())?;
    tx.execute("INSERT INTO control_profiles(scope,json) VALUES('global',?1) ON CONFLICT(scope) DO UPDATE SET json=excluded.json",[json]).map_err(|e|e.to_string())?;Ok(())
}
fn ensure(path:&Path)->Result<(),String>{
    let mut c=db::open(path)?;let tx=c.transaction_with_behavior(TransactionBehavior::Immediate).map_err(|e|e.to_string())?;
    let p=global(&tx)?;
    for (k,id,name) in [("keyboard","default-keyboard","کیبورد ۱"),("controller","default-controller","دستهٔ پیش‌فرض")] {
        let count:i64=tx.query_row("SELECT COUNT(*) FROM input_presets WHERE kind=?1",[k],|r|r.get(0)).map_err(|e|e.to_string())?;
        if count==0 {let json=serde_json::to_string(&mapping(&p,k)).map_err(|e|e.to_string())?;
            tx.execute("INSERT INTO input_presets(id,kind,name,device_guid,json) VALUES(?1,?2,?3,'',?4)",params![id,k,name,json]).map_err(|e|e.to_string())?;}
        let active:Option<String>=tx.query_row("SELECT a.profile_id FROM active_input_presets a JOIN input_presets p ON p.id=a.profile_id AND p.kind=a.kind WHERE a.kind=?1",[k],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
        if active.is_none(){let first:String=tx.query_row("SELECT id FROM input_presets WHERE kind=?1 ORDER BY rowid LIMIT 1",[k],|r|r.get(0)).map_err(|e|e.to_string())?;
            tx.execute("INSERT INTO active_input_presets(kind,profile_id) VALUES(?1,?2) ON CONFLICT(kind) DO UPDATE SET profile_id=excluded.profile_id",params![k,first]).map_err(|e|e.to_string())?;}
    }
    tx.commit().map_err(|e|e.to_string())
}
fn set_active(tx:&Transaction<'_>,k:&str,id:&str)->Result<(),String>{
    tx.execute("INSERT INTO active_input_presets(kind,profile_id) VALUES(?1,?2) ON CONFLICT(kind) DO UPDATE SET profile_id=excluded.profile_id",params![k,id]).map_err(|e|e.to_string())?;Ok(())
}
fn active(tx:&Transaction<'_>,k:&str)->Result<String,String>{tx.query_row("SELECT profile_id FROM active_input_presets WHERE kind=?1",[k],|r|r.get(0)).map_err(|e|e.to_string())}
fn read_mapping(tx:&Transaction<'_>,k:&str,id:&str)->Result<Mapping,String>{
    let raw:Option<String>=tx.query_row("SELECT json FROM input_presets WHERE id=?1 AND kind=?2",params![id,k],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
    let raw=raw.ok_or("پروفایل پیدا نشد.")?;serde_json::from_str(&raw).map_err(|_|"تنظیم پروفایل نامعتبر است.".into())
}
fn write_active_maps(tx:&Transaction<'_>,p:&ControlProfile)->Result<(),String>{
    put_global(tx,p)?;
    for k in ["keyboard","controller"] {
        let id=active(tx,k)?;let raw=serde_json::to_string(&mapping(p,k)).map_err(|e|e.to_string())?;
        let changed=tx.execute("UPDATE input_presets SET json=?1 WHERE id=?2 AND kind=?3",params![raw,id,k]).map_err(|e|e.to_string())?;
        if changed!=1{return Err("پروفایل فعال پیدا نشد.".into())}
    }Ok(())
}
/// Save global controls and both active profiles as one transaction.
pub fn save_global(path:&Path,p:&ControlProfile)->Result<(),String>{
    controls::validate(p)?;ensure(path)?;let mut c=db::open(path)?;let tx=c.transaction_with_behavior(TransactionBehavior::Immediate).map_err(|e|e.to_string())?;
    write_active_maps(&tx,p)?;tx.commit().map_err(|e|e.to_string())
}
pub fn list(path:&Path)->Result<Vec<Preset>,String>{
    ensure(path)?;let c=db::open(path)?;
    let mut st=c.prepare("SELECT p.id,p.kind,p.name,p.device_guid,CASE WHEN a.profile_id=p.id THEN 1 ELSE 0 END FROM input_presets p LEFT JOIN active_input_presets a ON a.kind=p.kind ORDER BY p.kind,p.rowid").map_err(|e|e.to_string())?;
    let rows=st.query_map([],|r|Ok(Preset{id:r.get(0)?,kind:r.get(1)?,name:r.get(2)?,device_guid:r.get(3)?,active:r.get::<_,i64>(4)?!=0})).map_err(|e|e.to_string())?;
    rows.collect::<Result<Vec<_>,_>>().map_err(|e|e.to_string())
}
/// Create and activate a profile, preserving both current editable mappings.
pub fn create(path:&Path,k:&str,name:&str,template:&str,device_guid:&str,current:&ControlProfile)->Result<String,String>{
    let k=kind(k)?;let name=valid_name(name)?;
    if !valid_guid(device_guid)||(k=="keyboard"&&!device_guid.is_empty()){return Err("شناسهٔ دسته معتبر نیست.".into())}
    if template!="current" && !(k=="keyboard"&&template=="numpad"){return Err("الگوی پروفایل معتبر نیست.".into())}
    controls::validate(current)?;ensure(path)?;let mut c=db::open(path)?;let tx=c.transaction_with_behavior(TransactionBehavior::Immediate).map_err(|e|e.to_string())?;
    let count:i64=tx.query_row("SELECT COUNT(*) FROM input_presets WHERE kind=?1",[k],|r|r.get(0)).map_err(|e|e.to_string())?;
    if count>=MAX_PROFILES{return Err("حداکثر ۳۲ پروفایل از هر نوع مجاز است.".into())}
    {let mut st=tx.prepare("SELECT name FROM input_presets WHERE kind=?1").map_err(|e|e.to_string())?;
     let names=st.query_map([k],|r|r.get::<_,String>(0)).map_err(|e|e.to_string())?;
     for n in names {if n.map_err(|e|e.to_string())?.to_lowercase()==name.to_lowercase(){return Err("این نام از قبل وجود دارد.".into())}}}
    write_active_maps(&tx,current)?;
    let mut next=current.clone();
    if template=="numpad"{next.keyboard=controls::default_profile().keyboard;for (button,key) in [("up","keypad8"),("down","keypad2"),("left","keypad4"),("right","keypad6")] {next.keyboard.insert(button.into(),key.into());}}
    controls::validate(&next)?;let json=serde_json::to_string(&mapping(&next,k)).map_err(|e|e.to_string())?;
    let id=uuid::Uuid::new_v4().to_string();
    tx.execute("INSERT INTO input_presets(id,kind,name,device_guid,json) VALUES(?1,?2,?3,?4,?5)",params![id,k,name,device_guid,json]).map_err(|e|e.to_string())?;
    set_active(&tx,k,&id)?;put_global(&tx,&next)?;tx.commit().map_err(|e|e.to_string())?;Ok(id)
}
pub fn activate(path:&Path,k:&str,id:&str)->Result<(),String>{
    let k=kind(k)?;ensure(path)?;let mut c=db::open(path)?;let tx=c.transaction_with_behavior(TransactionBehavior::Immediate).map_err(|e|e.to_string())?;
    let value=read_mapping(&tx,k,id)?;let mut p=global(&tx)?;set_mapping(&mut p,k,value);controls::validate(&p)?;set_active(&tx,k,id)?;put_global(&tx,&p)?;tx.commit().map_err(|e|e.to_string())
}
pub fn rename(path:&Path,id:&str,name:&str)->Result<(),String>{
    let name=valid_name(name)?;ensure(path)?;let mut c=db::open(path)?;let tx=c.transaction_with_behavior(TransactionBehavior::Immediate).map_err(|e|e.to_string())?;
    let k:Option<String>=tx.query_row("SELECT kind FROM input_presets WHERE id=?1",[id],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
    let k=k.ok_or("پروفایل پیدا نشد.")?;
    {let mut st=tx.prepare("SELECT id,name FROM input_presets WHERE kind=?1").map_err(|e|e.to_string())?;
     let rows=st.query_map([&k],|r|Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?))).map_err(|e|e.to_string())?;
     for row in rows {let (other,n)=row.map_err(|e|e.to_string())?;if other!=id&&n.to_lowercase()==name.to_lowercase(){return Err("این نام از قبل وجود دارد.".into())}}}
    tx.execute("UPDATE input_presets SET name=?1 WHERE id=?2",params![name,id]).map_err(|e|e.to_string())?;tx.commit().map_err(|e|e.to_string())
}
pub fn delete(path:&Path,k:&str,id:&str)->Result<(),String>{
    let k=kind(k)?;ensure(path)?;let mut c=db::open(path)?;let tx=c.transaction_with_behavior(TransactionBehavior::Immediate).map_err(|e|e.to_string())?;
    let _=read_mapping(&tx,k,id)?;let count:i64=tx.query_row("SELECT COUNT(*) FROM input_presets WHERE kind=?1",[k],|r|r.get(0)).map_err(|e|e.to_string())?;
    if count<=1{return Err("حداقل یک پروفایل از هر نوع باید باقی بماند.".into())}
    if active(&tx,k)?==id {
        let fallback:String=tx.query_row("SELECT id FROM input_presets WHERE kind=?1 AND id<>?2 ORDER BY rowid LIMIT 1",params![k,id],|r|r.get(0)).map_err(|e|e.to_string())?;
        let value=read_mapping(&tx,k,&fallback)?;let mut p=global(&tx)?;set_mapping(&mut p,k,value);controls::validate(&p)?;set_active(&tx,k,&fallback)?;put_global(&tx,&p)?;
    }
    tx.execute("DELETE FROM input_presets WHERE id=?1 AND kind=?2",params![id,k]).map_err(|e|e.to_string())?;tx.commit().map_err(|e|e.to_string())
}

#[cfg(test)]mod tests{
 use super::*;
 #[test]fn keyboards_switch_persist_and_numpad_does_not_change_controller(){
    let t=tempfile::tempdir().unwrap();let path=t.path().join("db");db::initialize(&path).unwrap();
    let mut legacy=controls::default_profile();legacy.keyboard.insert("up".into(),"f".into());db::save_control_profile(&path,"global",&serde_json::to_string(&legacy).unwrap()).unwrap();
    let first=list(&path).unwrap().into_iter().find(|p|p.kind=="keyboard"&&p.active).unwrap();
    let second=create(&path,"keyboard","کیبورد ۲","numpad","",&legacy).unwrap();let now=controls::effective(&path,"game-1").unwrap();
    assert_eq!(now.keyboard["up"],"keypad8");assert_eq!(now.keyboard["down"],"keypad2");assert_eq!(now.gamepad,legacy.gamepad);
    activate(&path,"keyboard",&first.id).unwrap();assert_eq!(controls::effective(&path,"game-1").unwrap().keyboard["up"],"f");
    activate(&path,"keyboard",&second).unwrap();let mut p=controls::effective(&path,"game-1").unwrap();p.keyboard.insert("a".into(),"p".into());save_global(&path,&p).unwrap();
    activate(&path,"keyboard",&first.id).unwrap();activate(&path,"keyboard",&second).unwrap();assert_eq!(controls::effective(&path,"game-1").unwrap().keyboard["a"],"p");
    delete(&path,"keyboard",&second).unwrap();assert_eq!(controls::effective(&path,"game-1").unwrap().keyboard["up"],"f");assert!(delete(&path,"keyboard",&first.id).is_err());
 }
 #[test]fn independent_controller_presets_names_and_game_overrides(){
    let t=tempfile::tempdir().unwrap();let path=t.path().join("db");db::initialize(&path).unwrap();let p=controls::default_profile();
    let old=list(&path).unwrap().into_iter().find(|p|p.kind=="controller"&&p.active).unwrap();
    let guid="00112233-4455-6677-8899-aabbccddeeff";
    let next=create(&path,"controller","دستهٔ آبی","current",guid,&p).unwrap();let mut changed=p.clone();changed.gamepad.insert("a".into(),"b".into());changed.gamepad.insert("b".into(),"a".into());save_global(&path,&changed).unwrap();
    activate(&path,"controller",&old.id).unwrap();assert_eq!(controls::effective(&path,"g").unwrap().gamepad["a"],"a");
    activate(&path,"controller",&next).unwrap();assert_eq!(controls::effective(&path,"g").unwrap().gamepad["a"],"b");
    controls::save(&path,Some("g"),&p).unwrap();activate(&path,"controller",&old.id).unwrap();assert_eq!(controls::effective(&path,"g").unwrap(),p);
    assert!(create(&path,"keyboard","\n","current","",&p).is_err());assert!(create(&path,"controller","bad","current","../../",&p).is_err());
    rename(&path,&next,"دستهٔ تازه").unwrap();assert!(list(&path).unwrap().iter().any(|x|x.name=="دستهٔ تازه"&&x.device_guid==guid));
 }
}
