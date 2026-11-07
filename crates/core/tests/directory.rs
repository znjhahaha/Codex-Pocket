use pocket_core::directory::*;
use std::collections::BTreeSet;
fn key(pc: &str, store: &str, native: &str) -> ThreadKey {
    ThreadKey {
        computer_id: pc.into(),
        config_store_id: store.into(),
        native_thread_id: native.into(),
    }
}
fn advert(pc: &str, id: &str, online: bool) -> RuntimeAdvertisement {
    RuntimeAdvertisement {
        id: RuntimeId {
            computer_id: pc.into(),
            runtime_id: id.into(),
        },
        source: RuntimeSource::Desktop,
        config_store_id: "store".into(),
        online,
        loaded_native_thread_ids: BTreeSet::from(["t".into()]),
    }
}
#[test]
fn unique_online_owner_and_identical_duplicates() {
    let a = advert("pc", "r", true);
    let d = RuntimeDirectory::new([a.clone(), a.clone()]).unwrap();
    assert_eq!(d.resolve_write(&key("pc", "store", "t")), Ok(a.id));
}
#[test]
fn distinct_online_owners_fail_closed() {
    let d = RuntimeDirectory::new([advert("pc", "a", true), advert("pc", "b", true)]).unwrap();
    assert_eq!(
        d.resolve_write(&key("pc", "store", "t")),
        Err(ResolveError::AmbiguousOwners)
    );
}
#[test]
fn disconnected_claim_is_uncertain_instead_of_inactive_history() {
    for online_competitor in [false, true] {
        let mut adverts = vec![advert("pc", "old", false)];
        if online_competitor {
            adverts.push(advert("pc", "new", true));
        }
        let result = RuntimeDirectory::new(adverts)
            .unwrap()
            .resolve_write(&key("pc", "store", "t"));
        assert!(
            result.is_err(),
            "a disconnected claimant must block a competing owner"
        );
        assert_ne!(result, Err(ResolveError::NoOnlineOwner));
    }
}
#[test]
fn conflicting_duplicate_advertisements_are_rejected() {
    let a = advert("pc", "r", true);
    for b in [
        advert("pc", "r", false),
        RuntimeAdvertisement {
            source: RuntimeSource::Cli,
            ..a.clone()
        },
        RuntimeAdvertisement {
            config_store_id: "other".into(),
            ..a.clone()
        },
        RuntimeAdvertisement {
            loaded_native_thread_ids: BTreeSet::new(),
            ..a.clone()
        },
    ] {
        assert!(matches!(
            RuntimeDirectory::new([a.clone(), b]),
            Err(ResolveError::ConflictingAdvertisement(_))
        ));
    }
}
#[test]
fn unloaded_wrong_computer_and_store_have_no_owner() {
    let d =
        RuntimeDirectory::new([advert("pc", "off", false), advert("other", "r", true)]).unwrap();
    for k in [
        key("other", "wrong", "t"),
        key("other", "store", "unloaded"),
    ] {
        assert_eq!(d.resolve_write(&k), Err(ResolveError::NoOnlineOwner));
    }
    assert_eq!(
        d.resolve_write(&key("pc", "store", "t")),
        Err(ResolveError::UncertainOwner)
    );
}
#[test]
fn runtime_identity_includes_computer() {
    let d = RuntimeDirectory::new([advert("a", "same", true), advert("b", "same", true)]).unwrap();
    assert_eq!(
        d.resolve_write(&key("b", "store", "t"))
            .unwrap()
            .computer_id,
        "b"
    );
}
#[test]
fn structured_thread_keys_do_not_have_delimiter_collisions() {
    let a = key("a|b", "c", "d");
    let b = key("a", "b|c", "d");
    assert_ne!(a, b);
    assert_ne!(
        serde_json::to_string(&a).unwrap(),
        serde_json::to_string(&b).unwrap()
    );
}
#[test]
fn history_includes_nonowned_without_claiming_ownership() {
    let d = RuntimeDirectory::new([advert("pc", "r", true)]).unwrap();
    let rows = d.list_history([key("pc", "store", "t"), key("pc", "store", "old")]);
    assert!(rows[0].write_owner.is_ok());
    assert_eq!(rows[1].write_owner, Err(ResolveError::NoOnlineOwner));
}
#[test]
fn empty_identities_are_rejected() {
    assert!(matches!(
        RuntimeDirectory::new([advert("", "r", true)]),
        Err(ResolveError::InvalidIdentity)
    ));
    let d = RuntimeDirectory::new([]).unwrap();
    assert_eq!(
        d.resolve_write(&key("pc", "", "t")),
        Err(ResolveError::InvalidIdentity)
    );
}
