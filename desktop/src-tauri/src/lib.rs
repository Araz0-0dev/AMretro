mod api;mod auth;mod bios;mod controls;mod db;mod install;mod models;mod retroarch;
use models::{AppSettings,Bios,Catalog,Cheat,Game,InstalledGame};
use std::{collections::HashSet,path::PathBuf,sync::{Arc,Mutex},time::Duration};
use tauri::{AppHandle,Emitter,Manager,State};

struct AppState { db_path:PathBuf,data_root:PathBuf,active:Arc<Mutex<HashSet<String>>>,bios_lock:Arc<tokio::sync::Mutex<()>>,session:Arc<Mutex<Option<auth::Session>>> }
#[tauri::command] fn get_settings(s:State<'_,AppState>)->Result<AppSettings,String>{db::load_settings(&s.db_path,&s.data_root)}
#[tauri::command] fn save_settings(s:State<'_,AppState>,settings:AppSettings)->Result<(),String>{db::save_settings(&s.db_path,&settings)}
#[tauri::command] async fn sync_catalog(s:State<'_,AppState>)->Result<Catalog,String>{let settings=db::load_settings(&s.db_path,&s.data_root)?;let catalog=api::catalog(&settings.api_url).await?;db::save_catalog(&s.db_path,&catalog)?;Ok(catalog)}
#[tauri::command] fn get_cached_catalog(s:State<'_,AppState>)->Result<Option<Catalog>,String>{db::cached_catalog(&s.db_path)}
#[tauri::command] async fn game_details(s:State<'_,AppState>,game_id:String)->Result<Game,String>{let settings=db::load_settings(&s.db_path,&s.data_root)?;api::game(&settings.api_url,&game_id).await}
#[tauri::command] async fn list_bios(s:State<'_,AppState>)->Result<Vec<Bios>,String>{let settings=db::load_settings(&s.db_path,&s.data_root)?;api::bios(&settings.api_url).await}
#[tauri::command] async fn list_cheats(s:State<'_,AppState>,game_id:String)->Result<Vec<Cheat>,String>{let settings=db::load_settings(&s.db_path,&s.data_root)?;api::cheats(&settings.api_url,&game_id).await}
#[tauri::command] fn list_library(s:State<'_,AppState>)->Result<Vec<InstalledGame>,String>{db::installed(&s.db_path)}
async fn active_session(store:&Arc<Mutex<Option<auth::Session>>>)->Result<Option<auth::Session>,String>{
    let current=store.lock().map_err(|_|"خطای نشست حساب".to_string())?.clone();
    match current {None=>Ok(None),Some(s)=>{let fresh=auth::refresh_if_needed(s).await?;*store.lock().map_err(|_|"خطای نشست حساب".to_string())?=Some(fresh.clone());Ok(Some(fresh))}}
}
#[tauri::command] async fn set_game_preset(s:State<'_,AppState>,game_id:String,preset:String)->Result<(),String>{
    install::validate_id(&game_id)?;db::set_preset(&s.db_path,&game_id,&preset)?;
    if let Some(session)=active_session(&s.session).await? {auth::save_preset(&session,&game_id,&preset).await.map_err(|e|format!("تنظیم روی دستگاه ذخیره شد؛ {e}"))?;}Ok(())
}
#[tauri::command] fn account_status(s:State<'_,AppState>)->Result<Option<auth::Account>,String>{Ok(s.session.lock().map_err(|_|"خطای نشست حساب".to_string())?.as_ref().map(auth::account))}
#[tauri::command] async fn account_sign_in(s:State<'_,AppState>,email:String,password:String)->Result<auth::Account,String>{
    let session=auth::sign_in(&email,&password).await?;let account=auth::account(&session);*s.session.lock().map_err(|_|"خطای نشست حساب".to_string())?=Some(session);Ok(account)
}
#[tauri::command] async fn account_sign_up(email:String,password:String)->Result<String,String>{auth::sign_up(&email,&password).await}
#[tauri::command] async fn account_sign_out(s:State<'_,AppState>)->Result<(),String>{
    let session=s.session.lock().map_err(|_|"خطای نشست حساب".to_string())?.take();if let Some(session)=session{auth::sign_out(&session).await}Ok(())
}
#[tauri::command] async fn sync_account(s:State<'_,AppState>)->Result<usize,String>{
    let session=active_session(&s.session).await?.ok_or("ابتدا وارد حساب شو.")?;let local=db::all_preferences(&s.db_path)?;let remote=auth::preferences(&session).await?;let mut remote_ids=HashSet::new();
    for p in &remote{install::validate_id(&p.game_id)?;db::set_preset(&s.db_path,&p.game_id,&p.preset)?;remote_ids.insert(p.game_id.clone());}
    for (id,preset) in local{if !remote_ids.contains(&id){auth::save_preset(&session,&id,&preset).await?;}}
    Ok(remote.len())
}
#[tauri::command] fn launch_game(s:State<'_,AppState>,game_id:String)->Result<(),String>{let settings=db::load_settings(&s.db_path,&s.data_root)?;let game=db::installed(&s.db_path)?.into_iter().find(|x|x.id==game_id).ok_or("بازی نصب نشده است.")?;retroarch::launch(&settings,&game,&s.data_root,&s.db_path)}
#[tauri::command] fn get_controls(s:State<'_,AppState>,game_id:Option<String>)->Result<controls::ProfileView,String>{controls::view(&s.db_path,game_id.as_deref())}
#[tauri::command] fn save_controls(s:State<'_,AppState>,game_id:Option<String>,profile:controls::ControlProfile)->Result<(),String>{controls::save(&s.db_path,game_id.as_deref(),&profile)}
#[tauri::command] fn reset_controls(s:State<'_,AppState>,game_id:Option<String>)->Result<(),String>{controls::reset(&s.db_path,game_id.as_deref())}
#[tauri::command] async fn start_install(app:AppHandle,s:State<'_,AppState>,game_id:String)->Result<(),String>{
    let settings=db::load_settings(&s.db_path,&s.data_root)?;let game=api::game(&settings.api_url,&game_id).await?;
    let catalog=api::catalog(&settings.api_url).await?;let console=catalog.consoles.into_iter().find(|c|c.id==game.console_id).ok_or("کنسول بازی پیدا نشد.")?;
    let bios=api::bios(&settings.api_url).await?;
    install::begin_game(app,s.db_path.clone(),s.data_root.clone(),settings,game,console,bios,s.active.clone(),s.bios_lock.clone())
}
#[tauri::command] async fn start_bios_install(app:AppHandle,s:State<'_,AppState>,bios_id:String)->Result<(),String>{
    let settings=db::load_settings(&s.db_path,&s.data_root)?;let bios=api::bios(&settings.api_url).await?.into_iter().find(|b|b.id==bios_id).ok_or("BIOS پیدا نشد.")?;
    bios::begin_manual(app,s.db_path.clone(),s.data_root.clone(),settings,bios,s.active.clone(),s.bios_lock.clone())
}
fn gamepad(app:AppHandle){std::thread::spawn(move||{let Ok(mut gilrs)=gilrs::Gilrs::new() else{return};loop{while let Some(event)=gilrs.next_event(){if let gilrs::EventType::ButtonPressed(button,_)=event.event{
    let bind=match button{gilrs::Button::South=>"b",gilrs::Button::East=>"a",gilrs::Button::West=>"y",gilrs::Button::North=>"x",gilrs::Button::Select=>"select",gilrs::Button::Start=>"start",gilrs::Button::DPadUp=>"up",gilrs::Button::DPadDown=>"down",gilrs::Button::DPadLeft=>"left",gilrs::Button::DPadRight=>"right",gilrs::Button::LeftTrigger=>"l",gilrs::Button::RightTrigger=>"r",gilrs::Button::LeftTrigger2=>"l2",gilrs::Button::RightTrigger2=>"r2",gilrs::Button::LeftThumb=>"l3",gilrs::Button::RightThumb=>"r3",_=>""};
    if !bind.is_empty(){let _=app.emit("gamepad-bind",bind);}
    let action=match button{gilrs::Button::DPadUp=>"up",gilrs::Button::DPadDown=>"down",gilrs::Button::DPadLeft=>"left",gilrs::Button::DPadRight=>"right",gilrs::Button::South=>"activate",gilrs::Button::East=>"back",_=>""};if !action.is_empty(){let _=app.emit("gamepad-input",action);}
}}std::thread::sleep(Duration::from_millis(25));}});}

pub fn run(){tauri::Builder::default().setup(|app|{let data_root=app.path().app_data_dir()?;std::fs::create_dir_all(&data_root)?;let db_path=data_root.join("retro-hub.sqlite3");db::initialize(&db_path).map_err(std::io::Error::other)?;app.manage(AppState{db_path,data_root,active:Arc::new(Mutex::new(HashSet::new())),bios_lock:Arc::new(tokio::sync::Mutex::new(())),session:Arc::new(Mutex::new(None))});gamepad(app.handle().clone());Ok(())}).invoke_handler(tauri::generate_handler![get_settings,save_settings,sync_catalog,get_cached_catalog,account_status,account_sign_in,account_sign_up,account_sign_out,sync_account,game_details,list_bios,list_cheats,list_library,set_game_preset,get_controls,save_controls,reset_controls,launch_game,start_install,start_bios_install]).run(tauri::generate_context!()).expect("error running AMretro")}
