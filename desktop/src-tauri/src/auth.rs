//! Supabase Auth and user-owned preferences. No service key or files are stored here.
use serde::{Deserialize,Serialize};
use std::time::{Duration,SystemTime,UNIX_EPOCH};

const BASE:&str="https://ysdbcsyhinpxhejrsizj.supabase.co";
// A publishable client key: authorization is enforced by the user's JWT and RLS.
const PUBLISHABLE:&str="sb_publishable_eR8hReVKF4JxfdEPW4xwSA_umaqbllc";

#[derive(Debug,Clone)]
pub struct Session { pub user_id:String, pub email:String, access:String, refresh:String, expires_at:u64 }
#[derive(Deserialize)] struct User { id:String, #[serde(default)] email:String }
#[derive(Deserialize)] struct TokenReply { access_token:String, refresh_token:String, expires_in:u64, user:User }
#[derive(Debug,Clone,Serialize)] pub struct Account { pub user_id:String, pub email:String }
#[derive(Debug,Deserialize)] pub struct Preference { pub game_id:String, pub preset:String }
fn client()->Result<reqwest::Client,String>{reqwest::Client::builder().timeout(Duration::from_secs(20)).user_agent("AMretro/0.2").build().map_err(|e|e.to_string())}
fn now()->u64{SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()}
fn session(t:TokenReply)->Session{Session{user_id:t.user.id,email:t.user.email,access:t.access_token,refresh:t.refresh_token,expires_at:now().saturating_add(t.expires_in)}}
fn validate(email:&str,password:&str)->Result<(),String>{if email.trim().is_empty()||!email.contains('@')||email.len()>254{return Err("ایمیل معتبر وارد کن.".into())}if password.len()<6||password.len()>1024{return Err("رمز باید حداقل ۶ نویسه باشد.".into())}Ok(())}
fn error_text(status:reqwest::StatusCode,value:&serde_json::Value)->String{
    let reason=value.get("msg").or_else(||value.get("error_description")).or_else(||value.get("message")).and_then(|v|v.as_str()).unwrap_or("");
    match reason {"Invalid login credentials"=>"ایمیل یا رمز درست نیست.".into(),"Email not confirmed"=>"ایمیل هنوز تأیید نشده است.".into(),_=>format!("درخواست حساب ناموفق بود ({status}). {reason}")}
}
async fn parse_response(response:reqwest::Response)->Result<serde_json::Value,String>{let status=response.status();let value=response.json::<serde_json::Value>().await.unwrap_or_default();if !status.is_success(){Err(error_text(status,&value))}else{Ok(value)}}
pub async fn sign_in(email:&str,password:&str)->Result<Session,String>{
    validate(email,password)?;let response=client()?.post(format!("{BASE}/auth/v1/token?grant_type=password")).header("apikey",PUBLISHABLE).json(&serde_json::json!({"email":email.trim(),"password":password})).send().await.map_err(|e|format!("اتصال حساب ممکن نشد: {e}"))?;
    let value=parse_response(response).await?;let t:TokenReply=serde_json::from_value(value).map_err(|_|"پاسخ ورود نامعتبر است.".to_string())?;Ok(session(t))
}
pub async fn sign_up(email:&str,password:&str)->Result<String,String>{
    validate(email,password)?;let response=client()?.post(format!("{BASE}/auth/v1/signup")).header("apikey",PUBLISHABLE).json(&serde_json::json!({"email":email.trim(),"password":password})).send().await.map_err(|e|format!("اتصال حساب ممکن نشد: {e}"))?;
    parse_response(response).await?;Ok("درخواست ثبت‌نام انجام شد؛ در صورت دریافت ایمیل، ابتدا آن را تأیید کن و سپس وارد شو.".into())
}
pub fn account(s:&Session)->Account{Account{user_id:s.user_id.clone(),email:s.email.clone()}}
pub async fn refresh_if_needed(s:Session)->Result<Session,String>{
    if s.expires_at>now()+60 {return Ok(s)}
    let response=client()?.post(format!("{BASE}/auth/v1/token?grant_type=refresh_token")).header("apikey",PUBLISHABLE).json(&serde_json::json!({"refresh_token":s.refresh})).send().await.map_err(|e|format!("تمدید ورود ممکن نشد: {e}"))?;
    let value=parse_response(response).await?;let t:TokenReply=serde_json::from_value(value).map_err(|_|"پاسخ تمدید ورود نامعتبر است.".to_string())?;Ok(session(t))
}
pub async fn sign_out(s:&Session){if let Ok(c)=client(){let _=c.post(format!("{BASE}/auth/v1/logout")).header("apikey",PUBLISHABLE).bearer_auth(&s.access).send().await;}}
pub async fn preferences(s:&Session)->Result<Vec<Preference>,String>{
    let response=client()?.get(format!("{BASE}/rest/v1/retrohub_game_preferences")).header("apikey",PUBLISHABLE).bearer_auth(&s.access).query(&[("user_id",format!("eq.{}",s.user_id)),("select","game_id,preset".into())]).send().await.map_err(|e|format!("دریافت تنظیمات حساب ممکن نشد: {e}"))?;
    let status=response.status();if !status.is_success(){return Err(format!("دریافت تنظیمات حساب ناموفق بود ({status})."))}response.json().await.map_err(|e|e.to_string())
}
pub async fn save_preset(s:&Session,id:&str,preset:&str)->Result<(),String>{
    let response=client()?.post(format!("{BASE}/rest/v1/retrohub_game_preferences?on_conflict=user_id,game_id")).header("apikey",PUBLISHABLE).header("Prefer","resolution=merge-duplicates,return=minimal").bearer_auth(&s.access).json(&serde_json::json!({"user_id":s.user_id,"game_id":id,"preset":preset})).send().await.map_err(|e|format!("همگام‌سازی تنظیمات ممکن نشد: {e}"))?;
    if !response.status().is_success(){return Err(format!("همگام‌سازی تنظیمات ناموفق بود ({}).",response.status()))}Ok(())
}

#[cfg(test)]mod tests{
 use super::*;
 #[tokio::test(flavor="multi_thread",worker_threads=2)]#[ignore="requires temporary test accounts"]
 async fn live_auth_and_rls(){
   let email=std::env::var("RETRO_AUTH_EMAIL").unwrap();let password=std::env::var("RETRO_AUTH_PASSWORD").unwrap();let other_email=std::env::var("RETRO_AUTH_OTHER_EMAIL").unwrap();
   let one=sign_in(&email,&password).await.unwrap();let two=sign_in(&other_email,&password).await.unwrap();let id="test-retro-cloud-preset";
   save_preset(&one,id,"quality").await.unwrap();let list=preferences(&one).await.unwrap();assert!(list.iter().any(|p|p.game_id==id&&p.preset=="quality"));
   let response=client().unwrap().get(format!("{BASE}/rest/v1/retrohub_game_preferences")).header("apikey",PUBLISHABLE).bearer_auth(&two.access).query(&[("user_id",format!("eq.{}",one.user_id)),("select","game_id,preset".into())]).send().await.unwrap();assert!(response.status().is_success());let foreign:Vec<serde_json::Value>=response.json().await.unwrap();assert!(foreign.is_empty(),"another user's rows must be hidden by RLS");
   let response=client().unwrap().post(format!("{BASE}/rest/v1/retrohub_game_preferences?on_conflict=user_id,game_id")).header("apikey",PUBLISHABLE).header("Prefer","resolution=merge-duplicates,return=minimal").bearer_auth(&two.access).json(&serde_json::json!({"user_id":one.user_id,"game_id":id,"preset":"performance"})).send().await.unwrap();assert!(!response.status().is_success(),"another user must not change settings");
   assert!(preferences(&one).await.unwrap().iter().any(|p|p.game_id==id&&p.preset=="quality"));sign_out(&one).await;sign_out(&two).await;
 }
}
