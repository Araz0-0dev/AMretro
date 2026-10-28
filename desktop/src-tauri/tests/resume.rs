//! Verify the same bytehaul resume configuration used by the desktop application.
use bytehaul::{DownloadSpec, Downloader};
use std::{io::{Read, Write}, net::TcpListener, sync::{Arc, Mutex, atomic::{AtomicBool, Ordering}}, time::Duration};

#[tokio::test(flavor="multi_thread", worker_threads=2)]
async fn interrupted_download_resumes_from_saved_ranges() {
    let payload:Arc<Vec<u8>>=Arc::new((0..12*1024*1024).map(|i|((i*31)%251) as u8).collect());
    let listener=TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let url=format!("http://{}/file.bin",listener.local_addr().unwrap());
    let done=Arc::new(AtomicBool::new(false));let stop=done.clone();
    let slow=Arc::new(AtomicBool::new(true));let slow_server=slow.clone();
    let ranges:Arc<Mutex<Vec<usize>>>=Arc::new(Mutex::new(Vec::new()));let recorded=ranges.clone();let data=payload.clone();
    let server=std::thread::spawn(move || {
        while !stop.load(Ordering::Relaxed) {
            if let Ok((mut stream,_))=listener.accept() {
                let data=data.clone();let slow=slow_server.clone();let ranges=recorded.clone();
                std::thread::spawn(move||{
                    let _=stream.set_read_timeout(Some(Duration::from_secs(3)));let mut req=Vec::new();let mut buf=[0u8;1024];
                    while !req.windows(4).any(|v|v==b"\r\n\r\n") && req.len()<16384 {match stream.read(&mut buf){Ok(0)|Err(_)=>return,Ok(n)=>req.extend_from_slice(&buf[..n])}}
                    let text=String::from_utf8_lossy(&req);let head=text.starts_with("HEAD ");let mut start=0usize;let mut end=data.len()-1;let mut partial=false;
                    for line in text.lines(){if let Some(v)=line.to_ascii_lowercase().strip_prefix("range: bytes="){let mut parts=v.trim().split('-');start=parts.next().and_then(|x|x.parse().ok()).unwrap_or(0);if let Some(n)=parts.next().and_then(|x|x.parse::<usize>().ok()){end=n.min(end)}partial=true;}}
                    if start>=data.len() || end<start {let _=write!(stream,"HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");return}
                    if partial {ranges.lock().unwrap().push(start)}
                    let status=if partial{"206 Partial Content"}else{"200 OK"};let range=if partial{format!("Content-Range: bytes {start}-{end}/{}\r\n",data.len())}else{String::new()};
                    let header=format!("HTTP/1.1 {status}\r\nContent-Length: {}\r\nAccept-Ranges: bytes\r\nETag: \"retro-resume-fixture\"\r\n{range}Connection: close\r\n\r\n",end-start+1);
                    if stream.write_all(header.as_bytes()).is_err() || head{return}
                    for chunk in data[start..=end].chunks(8192){if stream.write_all(chunk).is_err(){break}if slow.load(Ordering::Relaxed){std::thread::sleep(Duration::from_millis(2))}}
                });
            } else {std::thread::sleep(Duration::from_millis(2))}
        }
    });
    let dir=tempfile::tempdir().unwrap();let path=dir.path().join("download.bin");
    let d=Downloader::builder().build().unwrap();
    let make_spec=||DownloadSpec::new(url.clone()).output_path(path.clone()).resume(true).max_connections(4);
    let first=d.download(make_spec());let mut progress=first.subscribe_progress();
    tokio::time::timeout(Duration::from_secs(20),async{loop{progress.changed().await.unwrap();if progress.borrow().downloaded>512*1024{break}}}).await.expect("initial transfer progress");
    first.pause();let stopped=first.wait().await;assert!(stopped.is_err(),"first download should pause");
    ranges.lock().unwrap().clear();slow.store(false,Ordering::Relaxed);
    let result=tokio::time::timeout(Duration::from_secs(30),d.download(make_spec()).wait()).await.expect("resumed transfer should finish");
    done.store(true,Ordering::Relaxed);server.join().unwrap();
    assert!(result.is_ok(),"resumed transfer failed: {result:?}");
    assert_eq!(std::fs::read(&path).unwrap(),*payload);
    assert!(ranges.lock().unwrap().iter().any(|&offset|offset>0),"resume must request a nonzero byte range");
}
