//! Persistent player-one RetroPad/keyboard bindings. Physical pads continue to
//! use RetroArch's device-specific autoconfig; remaps operate on logical buttons.
use crate::{db,install};
use serde::{Deserialize,Serialize};
use std::{collections::{BTreeMap,HashSet},ffi::{c_char,CStr},fs,path::{Component,Path}};

pub const BUTTONS:[&str;16]=["b","y","select","start","up","down","left","right","a","x","l","r","l2","r2","l3","r3"];
#[derive(Clone,Debug,Serialize,Deserialize,PartialEq,Eq)]
pub struct ControlProfile { pub keyboard:BTreeMap<String,String>, pub gamepad:BTreeMap<String,String> }
#[derive(Clone,Debug,Serialize)]
pub struct ProfileView { pub profile:ControlProfile, pub customized:bool, pub inherited:bool }

pub fn default_profile()->ControlProfile{
    // Shared semantic directions across consoles: W/A/S/D. The same four
    // keys also feed the left analog axes in the generated RetroArch config.
    let keys=["j","u","rshift","enter","w","s","a","d","k","i","q","e","c","v","num1","num2"];
    let mut keyboard=BTreeMap::new();let mut gamepad=BTreeMap::new();
    for (i,button) in BUTTONS.iter().enumerate(){keyboard.insert((*button).into(),keys[i].into());gamepad.insert((*button).into(),(*button).into());}
    ControlProfile{keyboard,gamepad}
}
fn scope(game_id:Option<&str>)->Result<String,String>{
    if let Some(id)=game_id {install::validate_id(id)?;Ok(format!("game:{id}"))}else{Ok("global".into())}
}
fn valid_key(v:&str)->bool{
    if v.len()==1 && v.as_bytes()[0].is_ascii_lowercase(){return true}
    if let Some(n)=v.strip_prefix("num"){if n.len()==1 && n.as_bytes()[0].is_ascii_digit(){return true}}
    if let Some(n)=v.strip_prefix("keypad"){if n.len()==1 && n.as_bytes()[0].is_ascii_digit(){return true}}
    matches!(v,"nul"|"up"|"down"|"left"|"right"|"enter"|"kp_enter"|"space"|"rshift"|"shift"|"ctrl"|"rctrl"|"alt"|"ralt"|"tab"|"backspace"|"insert"|"del"|"end"|"home"|"pageup"|"pagedown"|"period"|"quote"|"comma"|"minus"|"slash"|"semicolon"|"equals"|"leftbracket"|"backslash"|"rightbracket"|"backquote"|"kp_period"|"kp_plus"|"kp_minus"|"multiply"|"divide")
}
pub fn validate(p:&ControlProfile)->Result<(),String>{
    if p.keyboard.len()!=BUTTONS.len() || p.gamepad.len()!=BUTTONS.len(){return Err("فهرست دکمه‌ها کامل نیست.".into())}
    let mut used=HashSet::new();
    for button in BUTTONS {
        let key=p.keyboard.get(button).ok_or("دکمه صفحه‌کلید پیدا نشد.")?;
        if !valid_key(key){return Err(format!("کلید صفحه‌کلید پشتیبانی نمی‌شود: {key}"))}
        let mapped=p.gamepad.get(button).ok_or("دکمه دسته پیدا نشد.")?;
        if !BUTTONS.contains(&mapped.as_str()) || !used.insert(mapped){return Err("دکمه‌های دسته باید معتبر و بدون تکرار باشند.".into())}
    }Ok(())
}
fn read(db_path:&Path,key:&str)->Result<Option<ControlProfile>,String>{
    db::control_profile(db_path,key)?.map(|s|{
        let p:ControlProfile=serde_json::from_str(&s).map_err(|_|"تنظیم ذخیره‌شده دکمه‌ها نامعتبر است.".to_string())?;validate(&p)?;Ok(p)
    }).transpose()
}
pub fn view(db_path:&Path,game_id:Option<&str>)->Result<ProfileView,String>{
    let key=scope(game_id)?;
    if let Some(p)=read(db_path,&key)?{return Ok(ProfileView{profile:p,customized:true,inherited:false})}
    if game_id.is_some(){if let Some(p)=read(db_path,"global")?{return Ok(ProfileView{profile:p,customized:false,inherited:true})}}
    Ok(ProfileView{profile:default_profile(),customized:false,inherited:game_id.is_some()})
}
pub fn save(db_path:&Path,game_id:Option<&str>,profile:&ControlProfile)->Result<(),String>{
    validate(profile)?;
    if game_id.is_none(){return crate::input_presets::save_global(db_path,profile)}
    let key=scope(game_id)?;db::save_control_profile(db_path,&key,&serde_json::to_string(profile).map_err(|e|e.to_string())?)
}
pub fn reset(db_path:&Path,game_id:Option<&str>)->Result<(),String>{
    if game_id.is_none(){crate::input_presets::save_global(db_path,&default_profile())}else{db::delete_control_profile(db_path,&scope(game_id)?)}
}
pub fn effective(db_path:&Path,game_id:&str)->Result<ControlProfile,String>{
    let key=scope(Some(game_id))?;
    if let Some(p)=read(db_path,&key)? {return Ok(p)}
    Ok(read(db_path,"global")?.unwrap_or_else(default_profile))
}

#[repr(C)]
struct RetroSystemInfo { library_name:*const c_char,library_version:*const c_char,valid_extensions:*const c_char,need_fullpath:bool,block_extract:bool }
/// A core supplies its own library name, which is the folder RetroArch uses for
/// remaps. Loading the same trusted core that will be run by RetroArch avoids
/// guessing names from filenames and avoids mixing different cores' remaps.
fn core_library_name(core:&Path)->Result<String,String>{
    unsafe {
        let lib=libloading::Library::new(core).map_err(|e|format!("خواندن نام هسته برای تنظیم دکمه‌ها ممکن نشد: {e}"))?;
        let get:libloading::Symbol<unsafe extern "C" fn(*mut RetroSystemInfo)>=lib.get(b"retro_get_system_info\0").map_err(|e|format!("هسته RetroArch معتبر نیست: {e}"))?;
        let mut info=RetroSystemInfo{library_name:std::ptr::null(),library_version:std::ptr::null(),valid_extensions:std::ptr::null(),need_fullpath:false,block_extract:false};
        get(&mut info);if info.library_name.is_null(){return Err("هسته نامی برای تنظیم دکمه‌ها اعلام نکرد.".into())}
        let name=CStr::from_ptr(info.library_name).to_str().map_err(|_|"نام هسته معتبر نیست.")?.to_string();
        if name.is_empty() || name.len()>160 || name.contains(['/', '\\', ':']) || name.chars().any(char::is_control) || name=="." || name==".." {return Err("نام هسته برای مسیر تنظیم دکمه‌ها امن نیست.".into())}
        Ok(name)
    }
}
fn quote(s:&str)->String{s.replace('\\',"\\\\").replace('"',"\\\"")}
fn safe_stem(path:&Path)->Result<String,String>{
    let s=path.file_stem().and_then(|x|x.to_str()).ok_or("نام فایل بازی معتبر نیست.")?;
    if s.is_empty()||s=="."||s==".."||s.contains(['/', '\\', ':'])||s.chars().any(char::is_control)||!matches!(Path::new(s).components().next(),Some(Component::Normal(_))){return Err("نام فایل بازی برای ذخیره دکمه‌ها مناسب نیست.".into())}
    Ok(s.to_string())
}
/// Render the RetroPad mapping and keyboard binds from the same profile.
fn mapping_lines(p:&ControlProfile)->(String,String){
    let mut remap=String::new();let mut keys=String::new();
    for (target_id,target) in BUTTONS.iter().enumerate(){
        let source=&p.gamepad[*target];let key=&p.keyboard[*target];
        remap.push_str(&format!("input_player1_btn_{source} = \"{target_id}\"\n"));
        keys.push_str(&format!("input_player1_{source} = \"{key}\"\n"));
    }
    // Also route the semantic movement keys to the left analog stick. Cores
    // that use a D-pad ignore these; N64/PSP-style cores may read the stick.
    // This is digital keyboard input, not a claim of full analog support.
    for (axis,direction) in [("l_y_minus","up"),("l_y_plus","down"),("l_x_minus","left"),("l_x_plus","right")] {
        keys.push_str(&format!("input_player1_{axis} = \"{}\"\n",p.keyboard[direction]));
    }
    (remap,keys)
}
/// Produce a game-private remap file plus the config fragment passed to
/// RetroArch. The reverse map is important: .rmp maps *source* RetroPad button
/// to the *target* game button. Keyboard binds are attached to those sources too.
pub fn generate(data:&Path,game_id:&str,game_path:&Path,core:&Path,p:&ControlProfile)->Result<String,String>{
    validate(p)?;install::validate_id(game_id)?;
    let name=core_library_name(core)?;let stem=safe_stem(game_path)?;
    let dir=data.join("input-remaps").join(game_id);let sub=dir.join(name);
    fs::create_dir_all(&sub).map_err(|e|e.to_string())?;
    let (remap,keys)=mapping_lines(p);
    fs::write(sub.join(format!("{stem}.rmp")),remap).map_err(|e|e.to_string())?;
    Ok(format!("input_remapping_directory = \"{}\"\ninput_remap_binds_enable = \"true\"\ninput_remap_sort_by_controller_enable = \"false\"\nconfig_save_on_exit = \"false\"\n{keys}",quote(&dir.to_string_lossy())))
}

#[cfg(test)] mod tests{
    use super::*;
    #[test]fn global_and_per_game_are_saved_separately(){
        let t=tempfile::tempdir().unwrap();let path=t.path().join("db");db::initialize(&path).unwrap();
        assert!(!view(&path,None).unwrap().customized);let mut global=default_profile();global.keyboard.insert("a".into(),"p".into());save(&path,None,&global).unwrap();
        let game=view(&path,Some("game-1")).unwrap();assert!(game.inherited);assert_eq!(game.profile.keyboard["a"],"p");
        let mut profile=game.profile;profile.keyboard.insert("a".into(),"q".into());save(&path,Some("game-1"),&profile).unwrap();assert_eq!(effective(&path,"game-1").unwrap().keyboard["a"],"q");
        reset(&path,Some("game-1")).unwrap();assert_eq!(effective(&path,"game-1").unwrap().keyboard["a"],"p");reset(&path,None).unwrap();assert_eq!(effective(&path,"game-1").unwrap(),default_profile());
    }
    #[test]fn wasd_and_analog_use_the_same_customizable_directions(){
        let mut p=default_profile();let (_,keys)=mapping_lines(&p);
        for (key,value) in [("up","w"),("down","s"),("left","a"),("right","d"),("l_y_minus","w"),("l_y_plus","s"),("l_x_minus","a"),("l_x_plus","d")] {
            assert!(keys.contains(&format!("input_player1_{key} = \"{value}\"\n")));
        }
        p.keyboard.insert("up".into(),"f".into());let (_,keys)=mapping_lines(&p);
        assert!(keys.contains("input_player1_up = \"f\"\n"));assert!(keys.contains("input_player1_l_y_minus = \"f\"\n"));
    }
    #[test]fn rejects_unsafe_and_duplicate_bindings(){
        let mut p=default_profile();p.keyboard.insert("a".into(),"x\"\nmalicious = \"true".into());assert!(validate(&p).is_err());
        p=default_profile();p.gamepad.insert("a".into(),"b".into());assert!(validate(&p).is_err());
        assert!(scope(Some("../bad")).is_err());assert!(safe_stem(Path::new("game.nes")).is_ok());
    }
}
