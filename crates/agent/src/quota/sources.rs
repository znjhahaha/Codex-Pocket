use super::{Failure, sandbox};
use serde_json::Value;
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    time::Duration,
};

// Intentionally neither Debug nor Serialize: host credentials never reach RPC/errors/logs.
#[derive(Clone)]
pub struct Provider {
    pub key: String,
    pub enabled: bool,
    pub label: String,
    pub identity: Option<(String, String)>,
    pub code: String,
    pub vars: BTreeMap<String, String>,
    pub interval: u64,
    pub timeout: u64,
    pub custom: bool,
    pub fingerprint: String,
}
pub fn default_path() -> PathBuf {
    std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .map(|home| PathBuf::from(home).join(".cc-switch/cc-switch.db"))
        .unwrap_or_default()
}
pub fn identity(settings: &Value) -> Option<(String, String)> {
    let config: toml::Value = settings["config"].as_str()?.parse().ok()?;
    let id = config.get("model_provider")?.as_str()?;
    let base = config
        .get("model_providers")?
        .get(id)?
        .get("base_url")?
        .as_str()?;
    Some((id.to_owned(), base.trim_end_matches('/').to_owned()))
}
pub fn native_identity(value: &Value) -> Option<(String, String)> {
    let config = &value["config"];
    let id = config["model_provider"].as_str()?;
    if id == "openai" {
        return Some((id.into(), String::new()));
    }
    let base = config["model_providers"][id]["base_url"].as_str()?;
    Some((id.into(), base.trim_end_matches('/').into()))
}
pub fn load(path: &Path) -> Result<Vec<Provider>, Failure> {
    if !path.is_absolute() {
        return Err(Failure::Unavailable);
    }
    if !path.exists() {
        return Ok(vec![]);
    }
    let db = rusqlite::Connection::open_with_flags(
        path,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )
    .map_err(|_| Failure::Unavailable)?;
    db.busy_timeout(Duration::from_millis(250))
        .map_err(|_| Failure::Unavailable)?;
    // Do not deserialize arbitrarily large rows. Never create or migrate the database.
    // Oversized records fail discovery instead of disappearing and causing a false unique match.
    let mut stmt = db.prepare("SELECT CASE WHEN length(CAST(id AS BLOB))<=4096 THEN id END, CASE WHEN length(CAST(name AS BLOB))<=4096 THEN name END, CASE WHEN length(CAST(coalesce(meta,'{}') AS BLOB))<=131072 THEN coalesce(meta,'{}') END, CASE WHEN length(CAST(settings_config AS BLOB))<=262144 THEN settings_config END FROM providers WHERE app_type='codex' LIMIT 129").map_err(|_|Failure::Unavailable)?;
    let rows = stmt
        .query_map([], |r| {
            Ok((
                r.get::<_, Option<String>>(0)?,
                r.get::<_, Option<String>>(1)?,
                r.get::<_, Option<String>>(2)?,
                r.get::<_, Option<String>>(3)?,
            ))
        })
        .map_err(|_| Failure::Unavailable)?;
    let mut result = vec![];
    for row in rows {
        if result.len() >= 128 {
            return Err(Failure::Limit);
        }
        let row = row.map_err(|_| Failure::Unavailable)?;
        let (Some(key), Some(label), Some(meta), Some(settings)) = row else {
            return Err(Failure::Limit);
        };
        let meta: Value = serde_json::from_str(&meta).map_err(|_| Failure::Invalid)?;
        let script = &meta["usage_script"];
        let enabled = script["enabled"] == true && script["language"] == "javascript";
        let settings: Value = serde_json::from_str(&settings).map_err(|_| Failure::Invalid)?;
        let code = script["code"].as_str().unwrap_or_default().to_owned();
        let ident = identity(&settings);
        let nonempty = |v: &Value| v.as_str().filter(|s| !s.is_empty()).map(str::to_owned);
        let api = nonempty(&script["apiKey"])
            .or_else(|| nonempty(&settings["auth"]["OPENAI_API_KEY"]))
            .unwrap_or_default();
        let base = nonempty(&script["baseUrl"])
            .or_else(|| ident.as_ref().map(|i| i.1.clone()))
            .unwrap_or_default();
        let vars: BTreeMap<String, String> = BTreeMap::from([
            ("apiKey".into(), api),
            ("baseUrl".into(), base),
            (
                "accessToken".into(),
                nonempty(&script["accessToken"]).unwrap_or_default(),
            ),
            (
                "userId".into(),
                nonempty(&script["userId"]).unwrap_or_default(),
            ),
        ]);
        let fingerprint = crate::auth::hash(&format!("{script}{settings}"));
        let interval = script["autoQueryInterval"]
            .as_u64()
            .unwrap_or(1)
            .saturating_mul(60);
        let mut label = super::safe_text(&Value::String(label), "Provider");
        let configured_secrets: Vec<_> = vars
            .iter()
            .filter(|(k, v)| k.as_str() != "baseUrl" && !v.is_empty())
            .map(|(_, v)| v.clone())
            .collect();
        if contains_secret(&label, &configured_secrets) {
            label = "[redacted]".into();
        }
        result.push(Provider {
            key,
            enabled,
            label,
            identity: ident,
            code,
            vars,
            interval,
            timeout: script["timeout"].as_u64().unwrap_or(10).clamp(2, 15),
            custom: script["templateType"] == "custom",
            fingerprint,
        });
    }
    Ok(result)
}

#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    url: String,
    method: String,
    #[serde(default)]
    headers: BTreeMap<String, String>,
    #[serde(default)]
    body: Option<String>,
}
fn substitute(value: &str, vars: &BTreeMap<String, String>) -> String {
    let mut value = value.to_owned();
    for (key, val) in vars {
        value = value.replace(&format!("{{{{{key}}}}}"), val);
    }
    value
}
fn request_url(value: &str, base: &str, custom: bool) -> Result<url::Url, Failure> {
    let url = url::Url::parse(value).map_err(|_| Failure::Invalid)?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.fragment().is_some()
        || value.len() > 8192
    {
        return Err(Failure::Invalid);
    }
    if !custom {
        let base = url::Url::parse(base).map_err(|_| Failure::Invalid)?;
        if base.origin() != url.origin() {
            return Err(Failure::Invalid);
        }
    }
    Ok(url)
}
fn public_ip(ip: std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V4(v) => {
            !(v.is_private()
                || v.is_loopback()
                || v.is_link_local()
                || v.is_unspecified()
                || v.is_multicast()
                || v.is_broadcast()
                || v.is_documentation()
                || v.octets()[0] == 0
                || v.octets()[0] >= 240
                || (v.octets()[0] == 100 && (64..=127).contains(&v.octets()[1]))
                || (v.octets()[0] == 198 && [18, 19].contains(&v.octets()[1])))
        }
        std::net::IpAddr::V6(v) => v
            .to_ipv4_mapped()
            .map(|v| public_ip(v.into()))
            .unwrap_or_else(|| {
                !v.is_loopback()
                    && !v.is_unspecified()
                    && !v.is_multicast()
                    && (v.segments()[0] & 0xe000) == 0x2000
                    && v.segments()[0] != 0x2001
                    && v.segments()[0] != 0x2002
            }),
    }
}
pub async fn execute(provider: Provider) -> Result<Vec<Value>, Failure> {
    let code = provider.code.clone();
    let config = tokio::task::spawn_blocking(move || sandbox::evaluate(&code, None))
        .await
        .map_err(|_| Failure::Script)??;
    let mut req: Request = serde_json::from_value(config).map_err(|_| Failure::Invalid)?;
    req.url = substitute(&req.url, &provider.vars);
    for val in req.headers.values_mut() {
        *val = substitute(val, &provider.vars);
    }
    req.body = req.body.map(|b| substitute(&b, &provider.vars));
    // The local script defines a quota endpoint; arbitrary mutation verbs are never allowed.
    if !matches!(req.method.as_str(), "GET" | "POST")
        || req.headers.len() > 32
        || req.headers.iter().any(|(k, v)| {
            k.len() > 128
                || v.len() > 8192
                || matches!(
                    k.to_ascii_lowercase().as_str(),
                    "host" | "content-length" | "transfer-encoding" | "connection"
                )
        })
        || req.body.as_ref().is_some_and(|b| b.len() > 65536)
    {
        return Err(Failure::Invalid);
    }
    let url = request_url(&req.url, &provider.vars["baseUrl"], provider.custom)?;
    let host = url.host_str().ok_or(Failure::Invalid)?;
    // Resolve once and pin the addresses to prevent DNS rebinding; bypass proxy env vars.
    let addresses: Vec<_> =
        tokio::net::lookup_host((host, url.port_or_known_default().ok_or(Failure::Invalid)?))
            .await
            .map_err(|_| Failure::Http)?
            .take(33)
            .collect();
    if addresses.is_empty() || addresses.len() > 32 || addresses.iter().any(|a| !public_ip(a.ip()))
    {
        return Err(Failure::Invalid);
    }
    let client = reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(3))
        .timeout(Duration::from_secs(provider.timeout))
        .resolve_to_addrs(host, &addresses)
        .build()
        .map_err(|_| Failure::Http)?;
    let response = send_http(&client, &req, url).await?;
    let code = provider.code.clone();
    let result = tokio::task::spawn_blocking(move || sandbox::evaluate(&code, Some(&response)))
        .await
        .map_err(|_| Failure::Script)??;
    let mut items = super::normalize(&result)?;
    redact_items(&mut items, &provider, &req);
    Ok(items)
}
async fn send_http(
    client: &reqwest::Client,
    req: &Request,
    url: url::Url,
) -> Result<String, Failure> {
    let mut builder = client.request(req.method.parse().map_err(|_| Failure::Invalid)?, url);
    for (k, v) in &req.headers {
        builder = builder.header(k, v);
    }
    if let Some(body) = &req.body {
        builder = builder.body(body.clone());
    }
    let mut response = builder.send().await.map_err(|_| Failure::Http)?;
    if !response.status().is_success() {
        return Err(Failure::Http);
    }
    if response
        .content_length()
        .is_some_and(|n| n > sandbox::MAX_BODY as u64)
    {
        return Err(Failure::Limit);
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| Failure::Http)? {
        if bytes.len() + chunk.len() > sandbox::MAX_BODY {
            return Err(Failure::Limit);
        }
        bytes.extend_from_slice(&chunk);
    }
    let response = String::from_utf8(bytes).map_err(|_| Failure::Invalid)?;
    Ok(response)
}
fn redact_items(items: &mut [Value], provider: &Provider, req: &Request) {
    // Whitelist normalized fields, and remove configured credentials even if an extractor echoes them.
    let mut secrets: Vec<_> = provider
        .vars
        .iter()
        .filter(|(k, v)| k.as_str() != "baseUrl" && !v.is_empty())
        .map(|(_, v)| v.clone())
        .collect();
    for v in req.headers.values() {
        secrets.push(v.clone());
        if let Some((_, v)) = v.split_once(' ') {
            secrets.push(v.to_owned());
        }
    }
    if let Ok(url) = url::Url::parse(&req.url) {
        secrets.extend(
            url.query_pairs()
                .map(|(_, v)| v.into_owned())
                .filter(|v| !v.is_empty()),
        );
    }
    if let Some(body) = &req.body {
        if let Ok(value) = serde_json::from_str::<Value>(body) {
            collect_strings(&value, &mut secrets);
        } else {
            secrets.push(body.clone());
            secrets
                .extend(url::form_urlencoded::parse(body.as_bytes()).map(|(_, v)| v.into_owned()));
        }
    }
    for item in items {
        for key in ["label", "unit"] {
            if let Some(text) = item[key].as_str()
                && contains_secret(text, &secrets)
            {
                item[key] = Value::String("[redacted]".into());
            }
        }
    }
}

fn collect_strings(value: &Value, strings: &mut Vec<String>) {
    match value {
        Value::String(s) => strings.push(s.clone()),
        Value::Array(a) => {
            for v in a {
                collect_strings(v, strings);
            }
        }
        Value::Object(o) => {
            for v in o.values() {
                collect_strings(v, strings);
            }
        }
        _ => {}
    }
}
fn contains_secret(text: &str, secrets: &[String]) -> bool {
    use base64::Engine;
    secrets.iter().filter(|s| !s.is_empty()).any(|s| {
        text.contains(s)
            || text.contains(&base64::engine::general_purpose::STANDARD.encode(s))
            || text.contains(&base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(s))
            || text
                .contains(&url::form_urlencoded::byte_serialize(s.as_bytes()).collect::<String>())
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn request_query_and_body_secrets_are_redacted() {
        let provider = Provider {
            key: "p".into(),
            enabled: true,
            label: "Provider".into(),
            identity: None,
            code: String::new(),
            vars: BTreeMap::new(),
            interval: 60,
            timeout: 3,
            custom: true,
            fingerprint: String::new(),
        };
        let req = Request {
            url: "https://quota.example/balance?access_token=query-secret".into(),
            method: "POST".into(),
            headers: BTreeMap::new(),
            body: Some(r#"{"auth":{"apiKey":"body-secret"}}"#.into()),
        };
        let mut items=super::super::normalize(&serde_json::json!([{"label":"query-secret","remaining":1},{"label":"body-secret","remaining":2}])).unwrap();
        redact_items(&mut items, &provider, &req);
        assert_eq!(items[0]["label"], "[redacted]");
        assert_eq!(items[1]["label"], "[redacted]");
    }
    #[test]
    fn template_credentials_are_data_and_never_enter_the_vm_or_output() {
        let secret = "secret-'\"` ${process.env}";
        let provider = Provider {
            key: "p".into(),
            enabled: true,
            label: "Provider".into(),
            identity: None,
            code: String::new(),
            vars: BTreeMap::from([("apiKey".into(), secret.into())]),
            interval: 60,
            timeout: 3,
            custom: false,
            fingerprint: String::new(),
        };
        assert_eq!(
            substitute("Bearer {{apiKey}}", &provider.vars),
            format!("Bearer {secret}")
        );
        let req = Request {
            url: "https://quota.example".into(),
            method: "GET".into(),
            headers: BTreeMap::from([(
                "Authorization".into(),
                "Bearer hardcoded-header-secret".into(),
            )]),
            body: None,
        };
        let mut items=super::super::normalize(&serde_json::json!([{"label":secret,"remaining":5,"unit":"USD"},{"label":"hardcoded-header-secret","remaining":3,"unit":"USD"}])).unwrap();
        redact_items(&mut items, &provider, &req);
        assert!(!serde_json::to_string(&items).unwrap().contains(secret));
        assert!(
            !serde_json::to_string(&items)
                .unwrap()
                .contains("hardcoded-header-secret")
        );
        assert_eq!(
            sandbox::evaluate("throw new Error('secret-from-script')", None),
            Err(Failure::Script)
        );
    }
    #[tokio::test]
    async fn http_rejects_redirects_oversized_bodies_and_error_body_secrets() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        for response in [
            "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1/credential-leak\r\nContent-Length: 0\r\n\r\n".to_owned(),
            format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\n\r\n",sandbox::MAX_BODY+1),
            "HTTP/1.1 401 Unauthorized\r\nContent-Length: 22\r\n\r\nsecret-from-http-error".to_owned(),
            format!("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n{:x}\r\n{}\r\n0\r\n\r\n",sandbox::MAX_BODY+1,"x".repeat(sandbox::MAX_BODY+1)),
        ] {
            let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let addr=listener.local_addr().unwrap();
            let server=tokio::spawn(async move {let(mut s,_)=listener.accept().await.unwrap();let mut b=[0;4096];let _=s.read(&mut b).await;let _=s.write_all(response.as_bytes()).await;});
            // Loopback is allowed only in this fixture; production request_url and DNS guards reject it.
            let url=url::Url::parse(&format!("http://{addr}/quota")).unwrap();
            let req=Request {url:url.to_string(),method:"GET".into(),headers:BTreeMap::new(),body:None};
            let client=reqwest::Client::builder().no_proxy().redirect(reqwest::redirect::Policy::none()).timeout(Duration::from_secs(1)).build().unwrap();
            assert!(send_http(&client,&req,url).await.is_err());server.await.unwrap();
        }
    }
    #[test]
    fn rejects_redirect_targets_and_private_networks() {
        assert!(request_url("https://other.example/a", "https://quota.example", false).is_err());
        assert!(request_url("http://quota.example/a", "", true).is_err());
        assert!(request_url("https://key@quota.example/a", "", true).is_err());
        for ip in [
            "127.0.0.1",
            "10.0.0.1",
            "169.254.169.254",
            "100.64.0.1",
            "::1",
            "::ffff:127.0.0.1",
            "fd00::1",
        ] {
            assert!(!public_ip(ip.parse().unwrap()));
        }
        assert!(public_ip("1.1.1.1".parse().unwrap()));
    }
}
