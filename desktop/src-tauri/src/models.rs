use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Console { pub id: String, pub name: String, pub slug: String, pub core: String, pub extensions: String, pub icon_url: String }
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Game {
    pub id: String, pub title: String, pub console_id: String, pub console_name: String,
    pub console_core: String, pub description: String, pub cover_url: String, pub icon_url: String,
    pub download_url: String, pub file_name: String, pub size_bytes: u64, pub sha256: String,
    pub images: Vec<GameImage>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct GameImage { pub id:String, pub url:String, pub alt_text:String }
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Bios {
    pub id:String, pub name:String, pub console_id:String, pub console_name:String,
    pub system_path:String, pub download_url:String, pub file_name:String, pub sha256:String,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Cheat { pub id:String, pub name:String, pub code:String, pub description:String }
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Catalog { pub consoles:Vec<Console>, pub games:Vec<Game> }
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstalledGame {
    pub id:String, pub title:String, pub console_id:String, pub console_name:String, pub core:String,
    pub cover_url:String, pub icon_url:String, pub game_path:String, pub preset:String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppSettings {
    pub api_url:String, pub games_dir:String, pub retroarch_path:String, pub cores_dir:String, pub system_dir:String,
}
#[derive(Debug, Clone, Serialize)]
pub struct ProgressEvent {
    pub id:String, pub kind:String, pub state:String, pub message:String,
    pub downloaded:u64, pub total:Option<u64>, pub speed:f64, pub eta:Option<f64>,
}
#[derive(Deserialize)]
pub struct Envelope<T> { pub data:T }
#[derive(Deserialize)]
pub struct GamePage { pub data:Vec<Game>, pub total:u64 }
