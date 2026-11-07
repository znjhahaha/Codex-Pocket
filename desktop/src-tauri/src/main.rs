#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod commands;
use tauri::{Manager,menu::{Menu,MenuItem},tray::{TrayIconBuilder,TrayIconEvent,MouseButton}};
fn show(app:&tauri::AppHandle){if let Some(w)=app.get_webview_window("main"){let _=w.show();let _=w.unminimize();let _=w.set_focus();}}
fn main(){
    tauri::Builder::default()
      .plugin(tauri_plugin_single_instance::init(|app,_,_|show(app)))
      .setup(|app|{
        let args:Vec<_>=std::env::args_os().collect();
        let root=pocket_desktop_runtime::storage::local_app_data()?.join("CodexPocket");
        let explicit=args.iter().position(|a|a=="--state").and_then(|i|args.get(i+1)).map(std::path::PathBuf::from);
        // Even an invalid/redirected installation must open the diagnostic UI.
        let installed=if root.join("desktop-install.json").exists(){pocket_desktop_runtime::launcher::resolve_release(&root).ok()}else{None};
        let state=explicit.or_else(||installed.as_ref().map(|(_,s)|s.clone())).unwrap_or(root.join("agent"));
        pocket_desktop_runtime::storage::plain(&state)?;
        let bin=installed.map(|(b,_)|b).unwrap_or(std::env::current_exe()?.parent().unwrap().to_owned());
        let config=app.config().app.windows.first().unwrap();
        tauri::WebviewWindowBuilder::from_config(app,config)?.on_navigation(|url| {
            url.scheme()=="tauri"&&url.host_str()==Some("localhost") || url.scheme()=="http"&&url.host_str()==Some("tauri.localhost") || cfg!(debug_assertions)&&url.scheme()=="http"&&url.host_str()==Some("localhost")&&url.port()==Some(1420)
        }).build()?;
        let context=commands::Context::new(state,bin);
        app.manage(context.clone());
        let open=MenuItem::with_id(app,"open","打开 Codex Pocket",true,None::<&str>)?;
        let quit=MenuItem::with_id(app,"quit","退出界面 · 保持远程连接",true,None::<&str>)?;
        let menu=Menu::with_items(app,&[&open,&quit])?;
        let tray=TrayIconBuilder::with_id("pocket-status").icon(app.default_window_icon().unwrap().clone()).tooltip("Codex Pocket · 正在读取状态").menu(&menu).show_menu_on_left_click(false)
          .on_menu_event(|app,event|match event.id.as_ref(){"open"=>show(app),"quit"=>app.exit(0),_=>{}})
          .on_tray_icon_event(|tray,event|if matches!(event,TrayIconEvent::DoubleClick{button:MouseButton::Left,..}){show(tray.app_handle())}).build(app)?;
        std::thread::spawn(move||loop{let _=tray.set_tooltip(Some(commands::tray_status(&context)));std::thread::sleep(std::time::Duration::from_secs(5));});
        show(app.handle());Ok(())
      })
      .on_window_event(|window,event|if let tauri::WindowEvent::CloseRequested{api,..}=event{api.prevent_close();let _=window.hide();})
      .invoke_handler(tauri::generate_handler![commands::dashboard,commands::set_remote,commands::pair_device,commands::revoke_device,commands::set_startup,commands::export_diagnostics])
      .run(tauri::generate_context!()).expect("Could not start Codex Pocket");
}
