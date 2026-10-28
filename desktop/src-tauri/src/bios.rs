//! BIOS installation is deliberately separate from the game download.  A manifest
//! records the *extracted* files, rather than mistaking an archive's SHA-256 for
//! the hash of its contents. Existing, different files are never overwritten.
use crate::{db, install::{self, ArchiveKind}, models::{AppSettings,Bios,Console}};
use cap_std::{ambient_authority,fs::{Dir,OpenOptions}};
use serde::{Deserialize,Serialize};
use sha2::{Digest,Sha256};
use std::{collections::HashSet,fs::{self,File},io::Read,path::{Path,PathBuf},sync::{Arc,Mutex}};
use tauri::AppHandle;
use walkdir::WalkDir;

#[derive(Clone,Debug,Serialize,Deserialize)]
pub(crate) struct BiosFile { path:String, sha256:String }
#[derive(Debug,Serialize,Deserialize)]
struct Manifest { fingerprint:String, files:Vec<BiosFile> }

fn fingerprint(bios:&Bios)->Result<String,String>{
    serde_json::to_string(&[&bios.file_name,&bios.system_path,&bios.sha256,&bios.download_url]).map_err(|e|e.to_string())
}
fn check_sha(value:&str)->Result<(),String>{
    if !value.is_empty() && (value.len()!=64 || !value.bytes().all(|b|b.is_ascii_hexdigit())) {return Err("SHA-256 مربوط به BIOS در پنل نامعتبر است.".into())}
    Ok(())
}
fn digest(mut reader:impl Read)->Result<String,String>{
    let mut hash=Sha256::new();let mut buffer=[0u8;65536];
    loop{let n=reader.read(&mut buffer).map_err(|e|e.to_string())?;if n==0{break}hash.update(&buffer[..n]);}
    Ok(format!("{:x}",hash.finalize()))
}
fn filename(bios:&Bios)->String{
    if !bios.file_name.trim().is_empty(){return install::safe_name(&bios.file_name)}
    let name=reqwest::Url::parse(&bios.download_url).ok().and_then(|u|u.path_segments().and_then(|mut v|v.next_back()).map(str::to_owned)).unwrap_or_else(||"bios.bin".into());install::safe_name(&name)
}
fn archive_name(name:&str)->bool{
    let n=name.to_ascii_lowercase();[".zip",".7z",".rar",".tar",".tar.gz",".tgz",".tar.xz",".tar.zst",".tar.lz4"].iter().any(|x|n.ends_with(x))
}
fn requested_path(bios:&Bios)->Result<PathBuf,String>{
    if bios.system_path.is_empty(){Ok(PathBuf::new())}else{install::relative_path(&bios.system_path)}
}
fn raw_target(bios:&Bios)->Result<PathBuf,String>{
    let requested=requested_path(bios)?;
    if requested.as_os_str().is_empty(){Ok(PathBuf::from(filename(bios)))}else if requested.extension().is_some(){Ok(requested)}else{Ok(requested.join(filename(bios)))}
}
// A symlink or an unexpected file must not be treated as an installed BIOS.
fn existing_hash(root:&Dir,rel:&Path)->Result<Option<String>,String>{
    let metadata=match root.symlink_metadata(rel){Ok(m)=>m,Err(e) if e.kind()==std::io::ErrorKind::NotFound=>return Ok(None),Err(e)=>return Err(e.to_string())};
    if metadata.file_type().is_symlink() || !metadata.is_file(){return Err(format!("مسیر BIOS فایل معمولی نیست: {}",rel.display()))}
    if metadata.len()==0{return Err(format!("فایل BIOS خالی است و جایگزین نمی‌شود: {}",rel.display()))}
    let file=root.open(rel).map_err(|e|e.to_string())?;digest(file).map(Some)
}
fn verify_manifest(root:&Dir,manifest:&Manifest)->Result<bool,String>{
    if manifest.files.is_empty(){return Ok(false)}
    let mut all=true;
    for f in &manifest.files {
        let rel=install::relative_path(&f.path)?;
        match existing_hash(root,&rel)? {
            None=>all=false,
            Some(hash) if hash.eq_ignore_ascii_case(&f.sha256)=>{},
            Some(_)=>return Err(format!("فایل BIOS موجود تغییر کرده است و برای حفظ اطلاعات بازنویسی نمی‌شود: {}",rel.display())),
        }
    }
    Ok(all)
}
fn save_manifest(db_path:&Path,system:&Path,bios:&Bios,files:Vec<BiosFile>)->Result<(),String>{
    let manifest=Manifest{fingerprint:fingerprint(bios)?,files};let json=serde_json::to_string(&manifest).map_err(|e|e.to_string())?;
    db::save_bios_manifest(db_path,&bios.id,&system.to_string_lossy(),&json)
}
/// Checks records and already-present, uncompressed files. No network access.
fn already_installed(db_path:&Path,system:&Path,bios:&Bios)->Result<bool,String>{
    install::validate_id(&bios.id)?;check_sha(&bios.sha256)?;let _=requested_path(bios)?;
    if !system.exists(){return Ok(false)}
    let root=Dir::open_ambient_dir(system,ambient_authority()).map_err(|e|e.to_string())?;
    if let Some(json)=db::bios_manifest(db_path,&bios.id,&system.to_string_lossy())? {
        if let Ok(manifest)=serde_json::from_str::<Manifest>(&json){
            if manifest.fingerprint==fingerprint(bios)?{return verify_manifest(&root,&manifest)}
        }
    }
    // For a raw single-file download, the artifact hash is also the BIOS hash.
    // For archives this shortcut is unsafe: download/extract and compare instead.
    if !archive_name(&filename(bios)) {
        let rel=raw_target(bios)?;
        if let Some(hash)=existing_hash(&root,&rel)? {
            if !bios.sha256.is_empty() && !hash.eq_ignore_ascii_case(&bios.sha256){return Err(format!("BIOS موجود با SHA-256 ثبت‌شده مطابقت ندارد و بازنویسی نمی‌شود: {}",rel.display()))}
            save_manifest(db_path,system,bios,vec![BiosFile{path:rel.to_string_lossy().replace('\\',"/"),sha256:hash}])?;
            return Ok(true)
        }
    }
    Ok(false)
}
/// Extract/copy safely. An identical file may be reused; conflicting files are
/// never changed. On failure only files created by this attempt are removed.
pub(crate) fn copy_files(download:&Path,system:&Path,bios:&Bios,staging:&Path)->Result<Vec<BiosFile>,String>{
    if staging.exists(){fs::remove_dir_all(staging).map_err(|e|e.to_string())?}
    fs::create_dir_all(staging).map_err(|e|e.to_string())?;
    let mut created:Vec<PathBuf>=Vec::new();
    let result=(||{
        let kind=install::archive_kind(download)?;
        if kind==ArchiveKind::Unsupported{return Err("RAR پشتیبانی نمی‌شود؛ ZIP یا 7z لازم است.".into())}
        if kind==ArchiveKind::None {fs::copy(download,staging.join(filename(bios))).map_err(|e|e.to_string())?;}else{install::extract_safe(download,staging)?;}
        let mut files:Vec<PathBuf>=WalkDir::new(staging).follow_links(false).into_iter().filter_map(Result::ok).filter(|e|e.file_type().is_file()).map(|e|e.path().to_path_buf()).collect();
        files.sort();if files.is_empty(){return Err("فایل BIOS در بسته پیدا نشد.".into())}
        let requested=requested_path(bios)?;
        if files.len()>1 && requested.extension().is_some(){return Err("بسته چند فایل BIOS دارد؛ مسیر system باید پوشه باشد.".into())}
        fs::create_dir_all(system).map_err(|e|e.to_string())?;
        let root=Dir::open_ambient_dir(system,ambient_authority()).map_err(|e|e.to_string())?;
        let mut result=Vec::new();let mut destinations=HashSet::new();
        for source in files {
            let relative=source.strip_prefix(staging).map_err(|e|e.to_string())?;
            let dest=if requested.as_os_str().is_empty(){relative.to_path_buf()}else if requested.extension().is_some(){requested.clone()}else{requested.join(relative)};
            let dest=install::relative_path(&dest.to_string_lossy())?;
            if !destinations.insert(dest.clone()){return Err("دو فایل BIOS مسیر نصب یکسان دارند.".into())}
            let sha=digest(File::open(&source).map_err(|e|e.to_string())?)?;
            if let Some(existing)=existing_hash(&root,&dest)? {
                if existing!=sha{return Err(format!("فایل BIOS موجود متفاوت است و برای حفظ اطلاعات بازنویسی نمی‌شود: {}",dest.display()))}
            }else{
                if let Some(parent)=dest.parent(){if !parent.as_os_str().is_empty(){root.create_dir_all(parent).map_err(|e|e.to_string())?}}
                let mut opts=OpenOptions::new();opts.write(true).create_new(true);
                let mut output=root.open_with(&dest,&opts).map_err(|e|format!("ایجاد BIOS ممکن نیست: {e}"))?;created.push(dest.clone());
                let mut input=File::open(source).map_err(|e|e.to_string())?;
                std::io::copy(&mut input,&mut output).map_err(|e|e.to_string())?;
            }
            result.push(BiosFile{path:dest.to_string_lossy().replace('\\',"/"),sha256:sha});
        }
        Ok(result)
    })();
    if result.is_err(){if let Ok(root)=Dir::open_ambient_dir(system,ambient_authority()){for p in &created{let _=root.remove_file(p);}}}
    let _=fs::remove_dir_all(staging);result
}
async fn ensure_one(app:&AppHandle,db_path:&Path,data_root:&Path,settings:&AppSettings,bios:&Bios)->Result<bool,String>{
    let system=PathBuf::from(&settings.system_dir);
    let d=db_path.to_path_buf();let sys=system.clone();let b=bios.clone();
    if tokio::task::spawn_blocking(move||already_installed(&d,&sys,&b)).await.map_err(|e|e.to_string())??{
        install::progress(app,&bios.id,"bios","installed","BIOS از قبل موجود است؛ دوباره دانلود نشد.");return Ok(false)
    }
    let dir=data_root.join("downloads");fs::create_dir_all(&dir).map_err(|e|e.to_string())?;
    let temp=dir.join(format!("bios-{}-{}",bios.id,filename(bios)));
    install::progress(app,&bios.id,"bios","starting",if bios.sha256.is_empty(){"دانلود BIOS آغاز شد؛ SHA-256 در پنل ثبت نشده است."}else{"دانلود BIOS با بررسی SHA-256 آغاز شد."});
    install::download(app.clone(),bios.id.clone(),"bios".into(),bios.download_url.clone(),temp.clone(),bios.sha256.clone()).await?;
    install::progress(app,&bios.id,"bios","installing","در حال بررسی و نصب BIOS…");
    let stage=data_root.join("bios-staging").join(&bios.id);let b=bios.clone();let sys=system.clone();let temp2=temp.clone();
    let files=tokio::task::spawn_blocking(move||copy_files(&temp2,&sys,&b,&stage)).await.map_err(|e|e.to_string())??;
    save_manifest(db_path,&system,bios,files)?;let _=fs::remove_file(temp);
    install::progress(app,&bios.id,"bios","installed","BIOS نصب شد.");Ok(true)
}
fn known_ps1_bios(system:&Path)->bool{
    // Only for the configured PCSX-ReARMed core. A plausible name is not an
    // authenticity check; without a published hash we can establish presence only.
    const NAMES:[&str;8]=["scph5500.bin","scph5501.bin","scph5502.bin","scph1001.bin","scph7001.bin","scph101.bin","PSXONPSP660.bin","scph7003.bin"];
    fs::read_dir(system).ok().into_iter().flatten().filter_map(Result::ok).any(|e|{
        let name=e.file_name().to_string_lossy().to_string();
        #[cfg(target_os="windows")] let recognized=NAMES.iter().any(|n|name.eq_ignore_ascii_case(n));
        #[cfg(not(target_os="windows"))] let recognized=NAMES.contains(&name.as_str());
        recognized && e.file_type().map(|x|x.is_file()).unwrap_or(false) && e.metadata().map(|x|x.len()>0).unwrap_or(false)
    })
}
pub async fn ensure_for_game(app:&AppHandle,db_path:&Path,data_root:&Path,settings:&AppSettings,console:&Console,items:&[Bios],lock:&tokio::sync::Mutex<()>)->Result<(),String>{
    let _guard=lock.lock().await;
    let items:Vec<&Bios>=items.iter().filter(|b|b.console_id==console.id).collect();
    if items.is_empty() && console.id=="ps1" && !(console.core=="pcsx_rearmed" && known_ps1_bios(Path::new(&settings.system_dir))) {
        return Err("BIOS پلی‌استیشن ۱ پیدا نشد. ابتدا فایل مجاز BIOS را در پنل برای این کنسول منتشر کن یا آن را در پوشه system قرار بده.".into())
    }
    for bios in items {
        if let Err(e)=ensure_one(app,db_path,data_root,settings,bios).await {
            install::progress(app,&bios.id,"bios","failed",&e);return Err(format!("BIOS «{}»: {e}",bios.name))
        }
    }
    Ok(())
}
pub fn begin_manual(app:AppHandle,db_path:PathBuf,data_root:PathBuf,settings:AppSettings,bios:Bios,active:Arc<Mutex<HashSet<String>>>,lock:Arc<tokio::sync::Mutex<()>>)->Result<(),String>{
    install::validate_id(&bios.id)?;let lockid=format!("bios-{}",bios.id);
    {let mut a=active.lock().map_err(|_|"خطای وضعیت دانلود".to_string())?;if !a.insert(lockid.clone()){return Err("این BIOS در حال دانلود است.".into())}}
    tauri::async_runtime::spawn(async move{
        let _guard=lock.lock().await;let id=bios.id.clone();
        if let Err(e)=ensure_one(&app,&db_path,&data_root,&settings,&bios).await{install::progress(&app,&id,"bios","failed",&e)}
        if let Ok(mut a)=active.lock(){a.remove(&lockid);}
    });Ok(())
}

#[cfg(test)] mod tests{
    use super::*;use std::io::Write;
    fn setup()->(tempfile::TempDir,PathBuf){let t=tempfile::tempdir().unwrap();let db=t.path().join("db");crate::db::initialize(&db).unwrap();(t,db)}
    #[test] fn detects_existing_raw_and_rejects_changed_file(){
        let (t,db)=setup();let system=t.path().join("system");fs::create_dir(&system).unwrap();fs::write(system.join("scph.bin"),b"hello world").unwrap();
        let bios=Bios{id:"b1".into(),file_name:"scph.bin".into(),system_path:"scph.bin".into(),sha256:"b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9".into(),..Default::default()};
        assert!(already_installed(&db,&system,&bios).unwrap());fs::write(system.join("scph.bin"),b"wrong").unwrap();assert!(already_installed(&db,&system,&bios).is_err());
    }
    #[test] fn archive_manifest_checks_extracted_files_and_never_overwrites(){
        let (t,db)=setup();let zip=t.path().join("bios.zip");let mut w=zip::ZipWriter::new(File::create(&zip).unwrap());
        w.start_file("a.bin",zip::write::SimpleFileOptions::default()).unwrap();w.write_all(b"alpha").unwrap();w.start_file("b.bin",zip::write::SimpleFileOptions::default()).unwrap();w.write_all(b"beta").unwrap();w.finish().unwrap();
        let bios=Bios{id:"archive".into(),file_name:"bios.zip".into(),system_path:"ps1".into(),sha256:digest(File::open(&zip).unwrap()).unwrap(),..Default::default()};
        let system=t.path().join("system");let stage=t.path().join("stage");assert!(!already_installed(&db,&system,&bios).unwrap());
        let files=copy_files(&zip,&system,&bios,&stage).unwrap();save_manifest(&db,&system,&bios,files).unwrap();assert!(already_installed(&db,&system,&bios).unwrap());
        fs::remove_file(system.join("ps1/a.bin")).unwrap();assert!(!already_installed(&db,&system,&bios).unwrap());
        let files=copy_files(&zip,&system,&bios,&stage).unwrap();save_manifest(&db,&system,&bios,files).unwrap();assert!(already_installed(&db,&system,&bios).unwrap());
        fs::write(system.join("ps1/b.bin"),b"different").unwrap();assert!(already_installed(&db,&system,&bios).is_err());assert!(copy_files(&zip,&system,&bios,&stage).is_err());assert_eq!(fs::read(system.join("ps1/b.bin")).unwrap(),b"different");
    }
    #[test] fn missing_ps1_and_real_file_detection(){let t=tempfile::tempdir().unwrap();assert!(!known_ps1_bios(t.path()));fs::write(t.path().join("scph5501.bin"),b"test fixture").unwrap();assert!(known_ps1_bios(t.path()));}
}
