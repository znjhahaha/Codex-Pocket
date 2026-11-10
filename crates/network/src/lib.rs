//! Shared read-only LAN inventory. Enumeration never proves phone reachability.
use serde::Serialize;
use std::{io, net::Ipv4Addr};

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum AdapterKind { Wifi, Ethernet, Virtual, Tunnel, Unknown }

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Interface {
    pub name: String,
    pub address: Ipv4Addr,
    pub interface_index: u32,
    pub kind: AdapterKind,
    pub automatic: bool,
    pub has_gateway: bool,
    pub metric: u32,
}

pub fn classify(if_type: u32, hardware: Option<bool>, tunnel: bool) -> AdapterKind {
    if tunnel || matches!(if_type, 23 | 131) { return AdapterKind::Tunnel; }
    match (if_type, hardware) {
        (71, Some(true)) => AdapterKind::Wifi,
        (6, Some(true)) => AdapterKind::Ethernet,
        (_, Some(false)) => AdapterKind::Virtual,
        _ => AdapterKind::Unknown,
    }
}

pub fn ranked(mut rows: Vec<Interface>) -> Vec<Interface> {
    rows.retain(|r| r.address.is_private());
    for row in &mut rows { row.automatic = matches!(row.kind, AdapterKind::Wifi | AdapterKind::Ethernet); }
    // Physical adapters on every subnet remain candidates even without a gateway.
    rows.sort_by_key(|r| (!r.automatic, !r.has_gateway, r.metric, r.kind, r.interface_index, r.address));
    rows.dedup_by_key(|r| (r.interface_index, r.address));
    rows
}

pub fn preferred(rows: &[Interface], previous: Option<Ipv4Addr>) -> Option<Ipv4Addr> {
    previous.filter(|ip| rows.iter().any(|r| r.automatic && r.address == *ip))
        .or_else(|| rows.iter().find(|r| r.automatic).map(|r| r.address))
}

#[cfg(windows)]
pub fn interfaces() -> io::Result<Vec<Interface>> {
    use windows_sys::Win32::{Foundation::{ERROR_BUFFER_OVERFLOW, ERROR_NO_DATA},
        NetworkManagement::{IpHelper::*, Ndis::IfOperStatusUp}, Networking::WinSock::*};
    let mut bytes = 15 * 1024u32;
    for _ in 0..4 {
        if bytes > 1024 * 1024 { return Err(io::Error::other("adapter_inventory_too_large")); }
        // u64 aligns the native linked-list structures on Windows x86 and x64.
        let mut buffer = vec![0u64; (bytes as usize).div_ceil(8)];
        let first = buffer.as_mut_ptr().cast::<IP_ADAPTER_ADDRESSES_LH>();
        let result = unsafe { GetAdaptersAddresses(AF_INET as u32,
            GAA_FLAG_INCLUDE_GATEWAYS | GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
            std::ptr::null(), first, &mut bytes) };
        if result == ERROR_BUFFER_OVERFLOW { continue; }
        if result == ERROR_NO_DATA { return Ok(vec![]); }
        if result != 0 { return Err(io::Error::from_raw_os_error(result as i32)); }
        let mut output = vec![];
        let mut current = first;
        // All pointers below are owned by the successful native buffer above.
        unsafe {
            while let Some(adapter) = current.as_ref() {
                current = adapter.Next;
                if adapter.OperStatus != IfOperStatusUp || adapter.IfType == 24 { continue; }
                let index = adapter.Anonymous1.Anonymous.IfIndex;
                let mut row = MIB_IF_ROW2::default();
                row.InterfaceIndex = index;
                let hardware = (GetIfEntry2(&mut row) == 0).then_some(row.InterfaceAndOperStatusFlags._bitfield & 1 != 0);
                let kind = classify(adapter.IfType, hardware, adapter.TunnelType != 0);
                let name = if adapter.FriendlyName.is_null() { format!("Interface {index}") } else {
                    let mut n = 0;
                    while n < 256 && *adapter.FriendlyName.add(n) != 0 { n += 1; }
                    String::from_utf16_lossy(std::slice::from_raw_parts(adapter.FriendlyName, n))
                };
                let mut address = adapter.FirstUnicastAddress;
                while let Some(unicast) = address.as_ref() {
                    address = unicast.Next;
                    if unicast.DadState != IpDadStatePreferred || unicast.ValidLifetime == 0 || unicast.Address.lpSockaddr.is_null()
                        || unicast.Address.iSockaddrLength < size_of::<SOCKADDR_IN>() as i32 { continue; }
                    let socket = &*unicast.Address.lpSockaddr.cast::<SOCKADDR_IN>();
                    if socket.sin_family != AF_INET { continue; }
                    let ip = Ipv4Addr::from(socket.sin_addr.S_un.S_addr.to_ne_bytes());
                    output.push(Interface { name: name.clone(), address: ip, interface_index: index, kind, automatic: false,
                        has_gateway: !adapter.FirstGatewayAddress.is_null(), metric: adapter.Ipv4Metric });
                }
            }
        }
        return Ok(ranked(output));
    }
    Err(io::Error::other("adapter_inventory_changed_during_read"))
}

#[cfg(not(windows))]
pub fn interfaces() -> io::Result<Vec<Interface>> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "native_adapter_inventory_unavailable"))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn row(ip: &str, kind: AdapterKind, gateway: bool, metric: u32) -> Interface {
        Interface { name: "fixture".into(), address: ip.parse().unwrap(), interface_index: metric,
            kind, automatic: false, has_gateway: gateway, metric }
    }
    #[test]
    fn vpn_and_virtual_default_routes_cannot_hide_two_physical_subnets() {
        let rows = ranked(vec![row("10.1.0.1", AdapterKind::Tunnel, true, 1),
            row("192.168.20.3", AdapterKind::Ethernet, false, 100), row("192.168.1.4", AdapterKind::Wifi, true, 50),
            row("172.16.1.2", AdapterKind::Virtual, true, 2)]);
        assert_eq!(rows.iter().filter(|r| r.automatic).count(), 2);
        assert_eq!(preferred(&rows, None), Some("192.168.1.4".parse().unwrap()));
        assert_eq!(preferred(&rows, Some("192.168.20.3".parse().unwrap())), Some("192.168.20.3".parse().unwrap()));
        assert_eq!(preferred(&rows, Some("192.168.88.2".parse().unwrap())), Some("192.168.1.4".parse().unwrap()));
    }
    #[test]
    fn unknown_driver_metadata_requires_manual_choice() {
        assert_eq!(classify(6, None, false), AdapterKind::Unknown);
        assert_eq!(classify(6, Some(false), false), AdapterKind::Virtual);
        assert_eq!(classify(71, Some(true), false), AdapterKind::Wifi);
        assert_eq!(classify(6, Some(true), true), AdapterKind::Tunnel);
        assert!(preferred(&ranked(vec![row("10.0.0.2", AdapterKind::Unknown, true, 1)]), None).is_none());
    }
    #[test]
    fn invalid_addresses_are_not_candidates() {
        assert!(ranked(["127.0.0.1", "169.254.3.2", "8.8.8.8", "0.0.0.0"]
            .map(|ip| row(ip, AdapterKind::Ethernet, true, 1)).into()).is_empty());
    }
    #[cfg(windows)]
    #[test]
    fn native_inventory_is_read_only_and_returns_classified_addresses() {
        let rows = interfaces().expect("Windows adapter enumeration");
        assert!(rows.iter().all(|r| r.address.is_private()));
    }
}
