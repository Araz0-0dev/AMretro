use crate::models::{AppSettings,InstalledGame};
use std::{fs,path::Path,process::Command};
fn quote(s:&str)->String{s.replace('\\',"\\\\").replace('"',"\\\"")}
pub fn preset_config(preset:&str,system:&str)->Result<String,String>{
    let (vsync,smooth,threaded)=match preset{"performance"=>("false","false","true"),"balanced"=>("true","false","true"),"quality"=>("true","true","false"),_=>return Err("حالت عملکرد نامعتبر است.".into())};
    Ok(format!("video_vsync = \"{vsync}\"\nvideo_smooth = \"{smooth}\"\nvideo_threaded = \"{threaded}\"\nrewind_enable = \"false\"\nsystem_directory = \"{}\"\n",quote(system)))
}
pub fn launch(settings:&AppSettings,game:&InstalledGame,data:&Path,db_path:&Path)->Result<(),String>{
    if !Path::new(&game.game_path).is_file(){return Err("فایل بازی نصب‌شده پیدا نشد.".into())}
    if game.core.is_empty()||!game.core.bytes().all(|c|c.is_ascii_alphanumeric()||c==b'_'||c==b'-'){return Err("نام هسته RetroArch نامعتبر است.".into())}
    #[cfg(target_os="windows")] let ext="dll";
    #[cfg(target_os="macos")] let ext="dylib";
    #[cfg(all(not(target_os="windows"),not(target_os="macos")))] let ext="so";
    let stem=if game.core.ends_with("_libretro"){game.core.clone()}else{format!("{}_libretro",game.core)};
    let core=Path::new(&settings.cores_dir).join(format!("{stem}.{ext}"));
    if !core.is_file(){return Err(format!("هسته RetroArch پیدا نشد: {}",core.display()))}
    crate::install::validate_id(&game.id)?;let dir=data.join("game-configs");fs::create_dir_all(&dir).map_err(|e|e.to_string())?;let config=dir.join(format!("{}.cfg",game.id));let mut contents=preset_config(&game.preset,&settings.system_dir)?;
    let profile=crate::controls::effective(db_path,&game.id)?;
    contents.push_str(&crate::controls::generate(data,&game.id,Path::new(&game.game_path),&core,&profile)?);
    fs::write(&config,contents).map_err(|e|e.to_string())?;
    Command::new(&settings.retroarch_path).arg("-L").arg(core).arg(&game.game_path).arg("--appendconfig").arg(config).spawn().map_err(|e|format!("RetroArch اجرا نشد؛ مسیر آن را بررسی کن: {e}"))?;Ok(())
}
#[cfg(test)]mod tests{use super::*;#[test]fn modes_are_distinct(){assert_ne!(preset_config("performance","/tmp").unwrap(),preset_config("quality","/tmp").unwrap());assert!(preset_config("bad","/tmp").is_err())}}
