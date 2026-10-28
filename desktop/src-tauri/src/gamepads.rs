//! One gilrs context drives discovery and UI events; its IDs are never used as RetroArch indices.
use serde::Serialize;
use std::{sync::{Arc,Mutex},time::Duration};
use tauri::{AppHandle,Emitter};

#[derive(Clone,Debug,Serialize,PartialEq,Eq)]
pub struct Device {pub id:String,pub name:String,pub guid:String,pub vendor_id:Option<u16>,pub product_id:Option<u16>}
#[derive(Clone,Debug,Serialize,PartialEq,Eq)]
pub struct Snapshot {pub status:String,pub devices:Vec<Device>}
impl Default for Snapshot {fn default()->Self{Self{status:"starting".into(),devices:Vec::new()}}}
#[derive(Clone,Serialize)]struct Binding {binding:String,device_id:String,guid:String}
pub type Shared=Arc<Mutex<Snapshot>>;
fn bind(button:gilrs::Button)->&'static str{
    match button{gilrs::Button::South=>"b",gilrs::Button::East=>"a",gilrs::Button::West=>"y",gilrs::Button::North=>"x",gilrs::Button::Select=>"select",gilrs::Button::Start=>"start",gilrs::Button::DPadUp=>"up",gilrs::Button::DPadDown=>"down",gilrs::Button::DPadLeft=>"left",gilrs::Button::DPadRight=>"right",gilrs::Button::LeftTrigger=>"l",gilrs::Button::RightTrigger=>"r",gilrs::Button::LeftTrigger2=>"l2",gilrs::Button::RightTrigger2=>"r2",gilrs::Button::LeftThumb=>"l3",gilrs::Button::RightThumb=>"r3",_=>""}
}
fn navigation(button:gilrs::Button)->&'static str{
    match button{gilrs::Button::DPadUp=>"up",gilrs::Button::DPadDown=>"down",gilrs::Button::DPadLeft=>"left",gilrs::Button::DPadRight=>"right",gilrs::Button::South=>"activate",gilrs::Button::East=>"back",_=>""}
}
pub fn start(app:AppHandle,shared:Shared){std::thread::spawn(move||{
    let mut gilrs=match gilrs::Gilrs::new(){Ok(g)=>g,Err(_)=>{if let Ok(mut s)=shared.lock(){s.status="unavailable".into();}let _=app.emit("gamepads-changed",());return}};
    loop{
        while let Some(event)=gilrs.next_event(){if let gilrs::EventType::ButtonPressed(button,_)=event.event{
            let b=bind(button);if !b.is_empty(){let pad=gilrs.gamepad(event.id);let payload=Binding{binding:b.into(),device_id:format!("{:?}",event.id),guid:uuid::Uuid::from_bytes(pad.uuid()).to_string()};let _=app.emit("gamepad-bind",payload);}
            let a=navigation(button);if !a.is_empty(){let _=app.emit("gamepad-input",a);}
        }}
        let devices=gilrs.gamepads().map(|(id,p)|Device{id:format!("{:?}",id),name:p.name().to_string(),guid:uuid::Uuid::from_bytes(p.uuid()).to_string(),vendor_id:p.vendor_id(),product_id:p.product_id()}).collect::<Vec<_>>();
        let changed=if let Ok(mut s)=shared.lock(){let next=Snapshot{status:"ready".into(),devices};if *s!=next{*s=next;true}else{false}}else{false};
        if changed{let _=app.emit("gamepads-changed",());}
        std::thread::sleep(Duration::from_millis(50));
    }
});}
#[cfg(test)]mod tests{use super::*;#[test]fn logical_buttons_and_navigation_are_independent(){assert_eq!(bind(gilrs::Button::South),"b");assert_eq!(navigation(gilrs::Button::South),"activate");assert_eq!(bind(gilrs::Button::Unknown),"");}}
