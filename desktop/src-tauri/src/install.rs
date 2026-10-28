use crate::{db,models::{AppSettings,Bios,Console,Game,ProgressEvent}};
use bytehaul::{Checksum,DownloadSpec,Downloader};
use cap_std::{ambient_authority,fs::Dir};
use libarchive_oxide::{ArchiveReader,Extractor,SeekArchiveReader};
use std::{collections::HashSet,fs::{self,File},io::Read,path::{Component,Path,PathBuf},sync::{Arc,Mutex}};
use tauri::{AppHandle,Emitter};
use walkdir::WalkDir;

pub fn validate_id(id:&str)->Result<(),String>{
    if id.is_empty()||id.len()>160||!id.bytes().all(|c|c.is_ascii_alphanumeric()||c==b'-'||c==b'_') {return Err("شناسه نامعتبر است.".into())} Ok(())
}
pub fn safe_name(input:&str)->String{
    let name=input.rsplit(['/', '\\']).next().unwrap_or("download.bin");
    let mut out:String=name.chars().take(180).map(|c|if c.is_ascii_alphanumeric()||"._-".contains(c){c}else{'_'}).collect();
    while out.starts_with('.') {out.remove(0);} if out.is_empty(){"download.bin".into()}else{out}
}
pub fn relative_path(input:&str)->Result<PathBuf,String>{
    let normalized=input.replace('\\',"/");let p=Path::new(&normalized);
    if normalized.contains(':')||normalized.starts_with('/')||p.components().any(|x|!matches!(x,Component::Normal(_))){return Err("مسیر نصب نامعتبر است.".into())}
    Ok(p.to_path_buf())
}
fn filename(game:&Game)->String{
    if !game.file_name.trim().is_empty(){return safe_name(&game.file_name)}
    let value=reqwest::Url::parse(&game.download_url).ok().and_then(|u|u.path_segments().and_then(|mut a|a.next_back()).map(str::to_owned)).unwrap_or_else(||"download.bin".into());safe_name(&value)
}
fn validate_link(url:&str)->Result<(),String>{
    let u=reqwest::Url::parse(url).map_err(|_|"لینک دانلود نامعتبر است.".to_string())?;
    if !matches!(u.scheme(),"http"|"https"){return Err("لینک دانلود باید HTTP یا HTTPS باشد.".into())}Ok(())
}
pub(crate) fn progress(app:&AppHandle,id:&str,kind:&str,state:&str,message:&str){let _=app.emit("download-progress",ProgressEvent{id:id.into(),kind:kind.into(),state:state.into(),message:message.into(),downloaded:0,total:None,speed:0.0,eta:None});}
pub async fn download(app:AppHandle,id:String,kind:String,url:String,path:PathBuf,sha:String)->Result<(),String>{
    download_with_progress(url,path,sha,move |snap|{
        let state=format!("{:?}",snap.state).to_lowercase();
        let _=app.emit("download-progress",ProgressEvent{id:id.clone(),kind:kind.clone(),state,message:"در حال دانلود…".into(),downloaded:snap.downloaded,total:snap.total_size,speed:snap.speed_bytes_per_sec,eta:snap.eta_secs});
    }).await
}
async fn download_with_progress<F>(url:String,path:PathBuf,sha:String,on_progress:F)->Result<(),String>
where F:Fn(bytehaul::ProgressSnapshot)+Send+'static {
    validate_link(&url)?;if !sha.is_empty()&&(sha.len()!=64||!sha.bytes().all(|x|x.is_ascii_hexdigit())){return Err("SHA-256 ثبت‌شده نامعتبر است.".into())}
    let downloader=Downloader::builder().build().map_err(|e|format!("دانلودکننده آماده نشد: {e}"))?;
    let mut spec=DownloadSpec::new(url).output_path(path).resume(!sha.is_empty()).max_connections(4).max_retries(2);
    if !sha.is_empty(){spec=spec.checksum(Checksum::Sha256(sha));}
    let handle=downloader.download(spec);let mut rx=handle.subscribe_progress();
    let watcher=tauri::async_runtime::spawn(async move {while rx.changed().await.is_ok(){let snap=rx.borrow().clone();let terminal=!matches!(snap.state,bytehaul::DownloadState::Pending|bytehaul::DownloadState::Downloading);on_progress(snap);if terminal {break;}}});
    let result=handle.wait().await.map_err(|e|format!("دانلود ناموفق بود: {e}"));let _=watcher.await;result
}


// Download mirrors independently. Never mix a partial primary file with mirror
// bytes; validate an optional SHA-256 against both. A plain HTML error page also
// counts as a failed link and triggers the backup.
async fn game_download_with_backup<F,G>(primary:String,backup:String,primary_path:PathBuf,backup_path:PathBuf,sha:String,on_progress:F,on_backup:G)->Result<PathBuf,String>
where F:Fn(bytehaul::ProgressSnapshot,bool)+Send+Sync+'static, G:Fn()+Send+Sync {
    let has_backup=!backup.trim().is_empty() && backup!=primary;
    let cb=Arc::new(on_progress);let first_cb=cb.clone();
    let first=download_with_progress(primary,primary_path.clone(),sha.clone(),move |s|first_cb(s,has_backup)).await
        .and_then(|_|archive_kind(&primary_path).map(|_|()));
    match first {
        Ok(())=>Ok(primary_path),
        Err(first_error)=>{
            if !has_backup{return Err(first_error)}
            on_backup();let second_cb=cb.clone();
            match download_with_progress(backup,backup_path.clone(),sha,move |s|second_cb(s,false)).await
                .and_then(|_|archive_kind(&backup_path).map(|_|())) {
                Ok(())=>Ok(backup_path),
                Err(_)=>Err("دانلود از هر دو آدرس ناموفق بود؛ لینک‌ها و SHA-256 را بررسی کن.".into()),
            }
        }
    }
}
fn clear_partial(path:&Path){
    let _=fs::remove_file(path);let mut sidecar=path.as_os_str().to_os_string();sidecar.push(".bytehaul");let _=fs::remove_file(PathBuf::from(sidecar));
}

#[derive(Debug,PartialEq)]
pub(crate) enum ArchiveKind { Seek, Stream, Unsupported, None }
pub(crate) fn archive_kind(path:&Path)->Result<ArchiveKind,String>{
    let mut f=File::open(path).map_err(|e|e.to_string())?;let mut head=[0u8;16];let n=f.read(&mut head).map_err(|e|e.to_string())?;let h=&head[..n];
    let text=String::from_utf8_lossy(h).trim_start().to_ascii_lowercase();if text.starts_with("<!doctype")||text.starts_with("<html"){return Err("لینک به صفحه وب اشاره می‌کند؛ لینک دانلود مستقیم لازم است.".into())}
    if h.starts_with(b"PK\x03\x04")||h.starts_with(b"PK\x05\x06")||h.starts_with(&[0x37,0x7a,0xbc,0xaf,0x27,0x1c]){return Ok(ArchiveKind::Seek)}
    if h.starts_with(b"Rar!"){return Ok(ArchiveKind::Unsupported)}
    let name=path.file_name().and_then(|v|v.to_str()).unwrap_or("").to_ascii_lowercase();
    if [".tar",".tar.gz",".tgz",".tar.xz",".tar.zst",".tar.lz4"].iter().any(|e|name.ends_with(e)){return Ok(ArchiveKind::Stream)}
    if name.ends_with(".zip")||name.ends_with(".7z"){return Err("فایل دانلودشده آرشیو معتبر نیست؛ لینک مستقیم را بررسی کن.".into())}
    Ok(ArchiveKind::None)
}
pub fn extract_safe(archive:&Path,destination:&Path)->Result<(),String>{
    fs::create_dir_all(destination).map_err(|e|e.to_string())?;
    let root=Dir::open_ambient_dir(destination,ambient_authority()).map_err(|e|e.to_string())?;
    let limits=libarchive_oxide::libarchive_oxide_core::Limits::safe().with_entries(Some(50_000)).with_path_bytes(Some(4096));
    let mut extractor=Extractor::with_limits(root,limits);let file=File::open(archive).map_err(|e|e.to_string())?;
    let report=match archive_kind(archive)? {
        ArchiveKind::Seek=>{let mut reader=SeekArchiveReader::with_limits(file,limits).map_err(|e|format!("آرشیو باز نشد: {e}"))?;extractor.extract_seek_matching(&mut reader,|_|true).map_err(|e|format!("استخراج ناموفق بود: {e}"))?},
        ArchiveKind::Stream=>{let mut reader=ArchiveReader::with_limits(file,limits);extractor.extract(&mut reader).map_err(|e|format!("استخراج ناموفق بود: {e}"))?},
        ArchiveKind::Unsupported=>return Err("آرشیو RAR پشتیبانی نمی‌شود؛ از ZIP یا 7z استفاده کن.".into()),
        ArchiveKind::None=>return Err("فایل، آرشیو شناخته‌شده‌ای نیست.".into()),
    };
    if report.has_rejections(){return Err("آرشیو مسیر یا فایل ناامن دارد و نصب متوقف شد.".into())}Ok(())
}
fn choose_playable(dir:&Path,extensions:&str)->Result<PathBuf,String>{
    let mut allowed:Vec<String>=extensions.split(',').map(|s|s.trim().trim_start_matches('.').to_lowercase()).filter(|s|!s.is_empty()&&!matches!(s.as_str(),"7z"|"zip")).collect();
    if allowed.is_empty(){allowed=vec!["m3u","cue","chd","iso","cso","pbp","nes","sfc","smc","gb","gbc","gba","n64","z64","v64","md","gen"].into_iter().map(str::to_string).collect()}
    let priority=["m3u","cue","chd","pbp","iso","cso"];
    let mut files:Vec<PathBuf>=WalkDir::new(dir).follow_links(false).into_iter().filter_map(Result::ok).filter(|e|e.file_type().is_file()).map(|e|e.path().to_path_buf()).filter(|p|p.extension().and_then(|s|s.to_str()).map(|s|allowed.contains(&s.to_lowercase())).unwrap_or(false)).collect();
    files.sort_by_key(|p|{let ext=p.extension().and_then(|s|s.to_str()).unwrap_or("").to_lowercase();(priority.iter().position(|x|*x==ext).unwrap_or(99),p.to_string_lossy().to_lowercase())});
    files.into_iter().next().ok_or_else(||"فایل قابل اجرای کنسول در بسته پیدا نشد؛ پسوندهای کنسول را در پنل بررسی کن.".into())
}
fn install_files(download:&Path,games_dir:&Path,game:&Game,console:&Console)->Result<PathBuf,String>{
    validate_id(&game.id)?;fs::create_dir_all(games_dir).map_err(|e|e.to_string())?;
    let target=games_dir.join(&game.id);if target.exists(){return Err("پوشه این بازی از قبل وجود دارد؛ برای جلوگیری از حذف اطلاعات نصب متوقف شد.".into())}
    let stage=games_dir.join(format!(".install-{}",game.id));if stage.exists(){fs::remove_dir_all(&stage).map_err(|e|e.to_string())?}fs::create_dir(&stage).map_err(|e|e.to_string())?;
    let result=(||{
        let kind=archive_kind(download)?;
        let playable=if matches!(game.console_id.as_str(),"arcade"|"neogeo"|"naomi"|"dos") && kind==ArchiveKind::Seek && download.extension().and_then(|x|x.to_str()).map(|x|x.eq_ignore_ascii_case("zip")).unwrap_or(false){
            let p=stage.join(filename(game));fs::copy(download,&p).map_err(|e|e.to_string())?;p
        } else if kind==ArchiveKind::Unsupported {return Err("RAR پشتیبانی نمی‌شود؛ ZIP یا 7z لازم است.".into())}
        else if kind!=ArchiveKind::None {extract_safe(download,&stage)?;choose_playable(&stage,&console.extensions)?}
        else {let p=stage.join(filename(game));fs::copy(download,&p).map_err(|e|e.to_string())?;choose_playable(&stage,&console.extensions)?};
        let relative=playable.strip_prefix(&stage).map_err(|e|e.to_string())?.to_path_buf();fs::rename(&stage,&target).map_err(|e|e.to_string())?;Ok(target.join(relative))
    })();
    if result.is_err(){let _=fs::remove_dir_all(&stage);} result
}
pub async fn install_game(app:AppHandle,db_path:PathBuf,data_root:PathBuf,settings:AppSettings,game:Game,console:Console,bios:Vec<Bios>,bios_lock:Arc<tokio::sync::Mutex<()>>)->Result<(),String>{
    validate_id(&game.id)?;
    progress(&app,&game.id,"game","starting","در حال بررسی BIOS کنسول پیش از نصب بازی…");
    crate::bios::ensure_for_game(&app,&db_path,&data_root,&settings,&console,&bios,&bios_lock).await?;
    let dir=data_root.join("downloads");fs::create_dir_all(&dir).map_err(|e|e.to_string())?;
    let primary=dir.join(format!("{}-{}",game.id,filename(&game)));
    let backup=dir.join(format!("mirror-{}-{}",game.id,filename(&game)));
    progress(&app,&game.id,"game","starting",if game.sha256.is_empty(){"دانلود آغاز شد؛ هش SHA-256 ثبت نشده است."}else{"دانلود با بررسی SHA-256 آغاز شد."});
    let a=app.clone();let id=game.id.clone();let backup_app=app.clone();let backup_id=game.id.clone();
    let temp=game_download_with_backup(game.download_url.clone(),game.backup_url.clone(),primary.clone(),backup.clone(),game.sha256.clone(),
        move |snap,may_retry|{
            let failed=matches!(snap.state,bytehaul::DownloadState::Failed);
            let state=if failed&&may_retry{"starting".into()}else{format!("{:?}",snap.state).to_lowercase()};
            let message=if failed&&may_retry{"در حال بررسی لینک پشتیبان…"}else{"در حال دانلود…"};
            let _=a.emit("download-progress",ProgressEvent{id:id.clone(),kind:"game".into(),state,message:message.into(),downloaded:snap.downloaded,total:snap.total_size,speed:snap.speed_bytes_per_sec,eta:snap.eta_secs});
        },move ||progress(&backup_app,&backup_id,"game","starting","لینک اصلی کار نکرد؛ در حال امتحان لینک پشتیبان…")).await?;
    progress(&app,&game.id,"game","installing","در حال بررسی و نصب بازی…");
    let g=game.clone();let c=console.clone();let game_dir=PathBuf::from(&settings.games_dir);let temp2=temp.clone();
    let installed=tokio::task::spawn_blocking(move||install_files(&temp2,&game_dir,&g,&c)).await.map_err(|e|e.to_string())??;
    if let Err(e)=db::record_install(&db_path,&game,&installed){
        // This folder was created by this installation attempt; don't leave an unusable partial install.
        let _=fs::remove_dir_all(PathBuf::from(&settings.games_dir).join(&game.id));return Err(e)
    }
    clear_partial(&primary);clear_partial(&backup);
    progress(&app,&game.id,"game","installed","بازی نصب شد.");Ok(())
}
pub fn begin_game(app:AppHandle,db_path:PathBuf,data_root:PathBuf,settings:AppSettings,game:Game,console:Console,bios:Vec<Bios>,active:Arc<Mutex<HashSet<String>>>,bios_lock:Arc<tokio::sync::Mutex<()>>)->Result<(),String>{
    if console.core=="unsupported" {return Err("این کنسول در نسخهٔ فعلی AMretro پشتیبانی نمی‌شود.".into())}
    {let mut a=active.lock().map_err(|_|"خطای وضعیت دانلود".to_string())?;if !a.insert(game.id.clone()){return Err("این بازی در حال دانلود است.".into())}}
    tauri::async_runtime::spawn(async move{let id=game.id.clone();if let Err(e)=install_game(app.clone(),db_path,data_root,settings,game,console,bios,bios_lock).await{progress(&app,&id,"game","failed",&e)}if let Ok(mut a)=active.lock(){a.remove(&id);}});Ok(())
}
#[cfg(test)]
mod tests {
 use super::*;use std::io::Write;
 #[test]fn paths_are_safe(){assert!(validate_id("abc-123").is_ok());assert!(validate_id("../bad").is_err());assert!(relative_path("ps1/scph.bin").is_ok());assert!(relative_path("../outside").is_err());assert!(relative_path("C:\\evil").is_err());assert_eq!(safe_name("../../evil.zip"),"evil.zip");}
 #[test]fn extracts_zip_and_selects_game(){let temp=tempfile::tempdir().unwrap();let zip=temp.path().join("game.zip");let f=File::create(&zip).unwrap();let mut w=zip::ZipWriter::new(f);w.start_file("nested/game.nes",zip::write::SimpleFileOptions::default()).unwrap();w.write_all(b"rom").unwrap();w.finish().unwrap();let dest=temp.path().join("out");extract_safe(&zip,&dest).unwrap();let found=choose_playable(&dest,"nes,zip,7z").unwrap();assert_eq!(fs::read(found).unwrap(),b"rom");}
 #[test]fn rejects_zip_traversal(){let temp=tempfile::tempdir().unwrap();let zip=temp.path().join("evil.zip");let f=File::create(&zip).unwrap();let mut w=zip::ZipWriter::new(f);w.start_file("../escape.nes",zip::write::SimpleFileOptions::default()).unwrap();w.write_all(b"bad").unwrap();w.finish().unwrap();assert!(extract_safe(&zip,&temp.path().join("out")).is_err());assert!(!temp.path().join("escape.nes").exists());}
 #[test]fn installs_zip_without_overwriting(){
   let t=tempfile::tempdir().unwrap();let zip=t.path().join("game.zip");let mut w=zip::ZipWriter::new(File::create(&zip).unwrap());w.start_file("inside/game.nes",zip::write::SimpleFileOptions::default()).unwrap();w.write_all(b"test-rom").unwrap();w.finish().unwrap();
   let g=Game{id:"game-1".into(),file_name:"game.zip".into(),..Default::default()};let c=Console{extensions:"nes,zip,7z".into(),..Default::default()};
   let path=install_files(&zip,&t.path().join("games"),&g,&c).unwrap();assert_eq!(fs::read(&path).unwrap(),b"test-rom");assert!(install_files(&zip,&t.path().join("games"),&g,&c).is_err());assert_eq!(fs::read(path).unwrap(),b"test-rom");
 }
 #[test]fn extracts_real_7z_fixture(){
   let t=tempfile::tempdir().unwrap();let archive=t.path().join("demo.7z");fs::write(&archive,include_bytes!("../../tests/fixtures/demo.7z")).unwrap();let dest=t.path().join("out");extract_safe(&archive,&dest).unwrap();let playable=choose_playable(&dest,"nes,7z").unwrap();assert_eq!(fs::read(playable).unwrap(),b"synthetic-rom-for-7z-test");
 }
 #[test]fn rejects_symbolic_links(){
   let t=tempfile::tempdir().unwrap();let zip=t.path().join("link.zip");let mut w=zip::ZipWriter::new(File::create(&zip).unwrap());w.add_symlink("outside","../evil",zip::write::SimpleFileOptions::default()).unwrap();w.finish().unwrap();
   assert!(extract_safe(&zip,&t.path().join("out")).is_err());assert!(!t.path().join("evil").exists());
 }
 #[test]fn installs_bios_without_overwriting_or_escape(){
   let t=tempfile::tempdir().unwrap();let src=t.path().join("bios.bin");fs::write(&src,b"pretend-bios").unwrap();
   let mut bios=crate::models::Bios{id:"bios-1".into(),file_name:"original.bin".into(),system_path:"scph5501.bin".into(),..Default::default()};let system=t.path().join("system");let stage=t.path().join("stage");
   crate::bios::copy_files(&src,&system,&bios,&stage).unwrap();assert_eq!(fs::read(system.join("scph5501.bin")).unwrap(),b"pretend-bios");crate::bios::copy_files(&src,&system,&bios,&stage).unwrap();fs::write(system.join("scph5501.bin"),b"different").unwrap();assert!(crate::bios::copy_files(&src,&system,&bios,&stage).is_err());bios.system_path="../escaped.bin".into();assert!(crate::bios::copy_files(&src,&system,&bios,&stage).is_err());assert!(!t.path().join("escaped.bin").exists());
 }
 #[tokio::test(flavor="multi_thread",worker_threads=2)]async fn bytehaul_downloads_and_checks_sha(){
   use std::{net::TcpListener,io::{Read as _,Write as _},sync::atomic::{AtomicBool,Ordering},time::Duration};
   let listener=TcpListener::bind("127.0.0.1:0").unwrap();listener.set_nonblocking(true).unwrap();let url=format!("http://{}/test.bin",listener.local_addr().unwrap());let done=Arc::new(AtomicBool::new(false));let stop=done.clone();
   let server=std::thread::spawn(move||{while !stop.load(Ordering::Relaxed){if let Ok((mut stream,_))=listener.accept(){let _=stream.set_read_timeout(Some(Duration::from_secs(2)));let mut buf=[0;4096];let n=stream.read(&mut buf).unwrap_or(0);let req=String::from_utf8_lossy(&buf[..n]);let head=req.starts_with("HEAD ");if req.contains(" /missing.bin "){let _=stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");continue}let bytes=b"hello world";let mut start=0usize;let mut end=bytes.len()-1;let mut partial=false;
    for line in req.lines(){if let Some(v)=line.to_ascii_lowercase().strip_prefix("range: bytes="){let mut parts=v.trim().split('-');if let Some(a)=parts.next(){start=a.parse().unwrap_or(0)}if let Some(b)=parts.next(){if !b.is_empty(){end=b.parse::<usize>().unwrap_or(end).min(end)}}partial=true;}}
    if start>=bytes.len(){let _=stream.write_all(b"HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */11\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");continue}
    let range=if partial{format!("Content-Range: bytes {start}-{end}/{}\r\n",bytes.len())}else{String::new()};let status=if partial{"206 Partial Content"}else{"200 OK"};let h=format!("HTTP/1.1 {status}\r\nContent-Length: {}\r\nAccept-Ranges: bytes\r\n{range}Connection: close\r\n\r\n",end-start+1);let _=stream.write_all(h.as_bytes());if !head{let _=stream.write_all(&bytes[start..=end]);}
   }else{std::thread::sleep(Duration::from_millis(2))}}});
   let t=tempfile::tempdir().unwrap();let path=t.path().join("ok.bin");let sha="b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";
   let result=download_with_progress(url.clone(),path.clone(),sha.into(),|_|{}).await;assert!(result.is_ok(),"{result:?}");assert_eq!(fs::read(path).unwrap(),b"hello world");
   let bad=download_with_progress(url.clone(),t.path().join("bad.bin"),"0".repeat(64),|_|{}).await;assert!(bad.is_err());
   let primary=t.path().join("primary.bin");let mirror=t.path().join("mirror.bin");fs::write(&primary,b"stale partial").unwrap();
   let switched=Arc::new(AtomicBool::new(false));let changed=switched.clone();
   let result=game_download_with_backup(url.replace("/test.bin","/missing.bin"),url.clone(),primary.clone(),mirror.clone(),sha.into(),|_,_|{},move||{changed.store(true,Ordering::Relaxed);}).await.unwrap();
   assert_eq!(result,mirror);assert!(switched.load(Ordering::Relaxed));assert_eq!(fs::read(result).unwrap(),b"hello world");
   let failed=game_download_with_backup(url.replace("/test.bin","/missing.bin"),String::new(),t.path().join("missing.bin"),t.path().join("unused.bin"),sha.into(),|_,_|{},||{}).await;assert!(failed.is_err());
   done.store(true,Ordering::Relaxed);server.join().unwrap();
 }

 #[tokio::test(flavor="multi_thread",worker_threads=2)]#[ignore="uses a temporary test file published on the configured Cloudflare Worker"]
 async fn live_cloudflare_bios_download_and_install(){
   let url=std::env::var("RETRO_TEST_URL").expect("RETRO_TEST_URL");let sha=std::env::var("RETRO_TEST_SHA").expect("RETRO_TEST_SHA");let original=std::env::var("RETRO_TEST_ORIGINAL").expect("RETRO_TEST_ORIGINAL");
   let t=tempfile::tempdir().unwrap();let download=t.path().join("bios.bin");download_with_progress(url,download.clone(),sha,|_|{}).await.unwrap();assert_eq!(fs::read(&download).unwrap(),fs::read(original).unwrap());
   let bios=crate::models::Bios{id:"live-test".into(),file_name:"bios.bin".into(),system_path:"scph5501.bin".into(),..Default::default()};
   crate::bios::copy_files(&download,&t.path().join("system"),&bios,&t.path().join("stage")).unwrap();assert_eq!(fs::read(t.path().join("system/scph5501.bin")).unwrap(),fs::read(download).unwrap());
 }

}

