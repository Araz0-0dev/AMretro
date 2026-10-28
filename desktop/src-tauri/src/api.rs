use crate::models::{Bios, Catalog, Cheat, Console, Envelope, Game, GamePage};
use std::time::Duration;
fn endpoint(base:&str,path:&str)->Result<reqwest::Url,String>{
    let base=reqwest::Url::parse(base).map_err(|_|"آدرس API نامعتبر است.".to_string())?;
    if !matches!(base.scheme(),"https"|"http"){return Err("آدرس API باید HTTP یا HTTPS باشد.".into())}
    base.join(path).map_err(|e|e.to_string())
}
fn client()->Result<reqwest::Client,String>{reqwest::Client::builder().timeout(Duration::from_secs(20)).user_agent("AMretro/0.3").build().map_err(|e|e.to_string())}
async fn get<T:serde::de::DeserializeOwned>(base:&str,path:&str)->Result<T,String>{
    client()?.get(endpoint(base,path)?).send().await.map_err(|e|format!("اتصال به پنل برقرار نشد: {e}"))?.error_for_status().map_err(|e|format!("خطای API: {e}"))?.json().await.map_err(|e|format!("پاسخ API نامعتبر است: {e}"))
}
pub async fn catalog(base:&str)->Result<Catalog,String>{
    let consoles:Envelope<Vec<Console>>=get(base,"/api/consoles").await?;let mut games=Vec::new();
    for page in 1..=100 {let data:GamePage=get(base,&format!("/api/games?limit=100&page={page}")).await?;let n=data.data.len();games.extend(data.data);if n==0||games.len() as u64>=data.total{break}}
    Ok(Catalog{consoles:consoles.data,games})
}
pub async fn game(base:&str,id:&str)->Result<Game,String>{crate::install::validate_id(id)?;let v:Envelope<Game>=get(base,&format!("/api/games/{id}")).await?;Ok(v.data)}
pub async fn bios(base:&str)->Result<Vec<Bios>,String>{let v:Envelope<Vec<Bios>>=get(base,"/api/bios").await?;Ok(v.data)}
pub async fn cheats(base:&str,id:&str)->Result<Vec<Cheat>,String>{crate::install::validate_id(id)?;let v:Envelope<Vec<Cheat>>=get(base,&format!("/api/cheats/{id}")).await?;Ok(v.data)}
