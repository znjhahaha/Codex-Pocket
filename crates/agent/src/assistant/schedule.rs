//! Civil-time schedules without a timezone dependency. Named zones require a
//! validated platform rule snapshot, with explicit finite coverage.
use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

const DAY: i64 = 86_400_000;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ZoneTransition {
    pub at_ms: i64,
    pub offset_seconds: i32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ZoneRules {
    pub name: String,
    pub from_ms: i64,
    pub until_ms: i64,
    pub initial_offset_seconds: i32,
    pub transitions: Vec<ZoneTransition>,
}
impl ZoneRules {
    pub fn utc() -> Self {
        Self {
            name: "UTC".into(),
            from_ms: 0,
            until_ms: i64::MAX / 2,
            initial_offset_seconds: 0,
            transitions: vec![],
        }
    }
    pub fn validate(&self) -> Result<()> {
        ensure!(
            !self.name.trim().is_empty() && self.name.len() <= 256 && !self.name.contains('\0'),
            "Invalid timezone name"
        );
        ensure!(
            self.from_ms >= 0 && self.until_ms > self.from_ms && self.until_ms <= i64::MAX / 2,
            "Invalid timezone coverage"
        );
        ensure!(
            self.initial_offset_seconds.unsigned_abs() <= 86400 && self.transitions.len() <= 4096,
            "Invalid timezone rules"
        );
        let mut previous = self.from_ms - 1;
        for t in &self.transitions {
            ensure!(
                t.at_ms > previous
                    && t.at_ms < self.until_ms
                    && t.offset_seconds.unsigned_abs() <= 86400,
                "Invalid timezone transition"
            );
            previous = t.at_ms;
        }
        Ok(())
    }
    fn offset(&self, utc: i64) -> Option<i32> {
        if utc < self.from_ms || utc >= self.until_ms {
            return None;
        }
        Some(
            self.transitions
                .iter()
                .rev()
                .find(|t| t.at_ms <= utc)
                .map_or(self.initial_offset_seconds, |t| t.offset_seconds),
        )
    }
    fn local(&self, utc: i64) -> Result<i64> {
        Ok(utc + i64::from(self.offset(utc).context("Timezone rules out of coverage")?) * 1000)
    }
    /// Gap -> none. Fold -> earliest occurrence, independent of last tick.
    fn resolve(&self, local: i64) -> Option<i64> {
        std::iter::once(self.initial_offset_seconds)
            .chain(self.transitions.iter().map(|t| t.offset_seconds))
            .filter_map(|offset| {
                let utc = local - i64::from(offset) * 1000;
                (self.offset(utc) == Some(offset)).then_some(utc)
            })
            .min()
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum ScheduleKind {
    Once {
        at_ms: i64,
    },
    Daily {
        hour: u8,
        minute: u8,
    },
    /// Monday=0 .. Sunday=6.
    Weekly {
        weekday: u8,
        hour: u8,
        minute: u8,
    },
    Interval {
        anchor_ms: i64,
        every_ms: i64,
    },
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ScheduleSpec {
    pub kind: ScheduleKind,
    pub zone: ZoneRules,
    pub prompt: String,
    pub directory: PathBuf,
    pub writes: bool,
}
impl ScheduleSpec {
    pub fn validate(&self) -> Result<()> {
        self.zone.validate()?;
        ensure!(
            !self.prompt.trim().is_empty()
                && self.prompt.len() <= 65536
                && !self.prompt.contains('\0'),
            "Invalid schedule prompt"
        );
        ensure!(
            self.directory.is_absolute(),
            "Schedule directory must be absolute"
        );
        match self.kind {
            ScheduleKind::Once { at_ms } => ensure!(
                at_ms >= 0 && self.zone.offset(at_ms).is_some(),
                "Invalid one-shot time"
            ),
            ScheduleKind::Daily { hour, minute } => {
                ensure!(hour < 24 && minute < 60, "Invalid daily time")
            }
            ScheduleKind::Weekly {
                weekday,
                hour,
                minute,
            } => ensure!(
                weekday < 7 && hour < 24 && minute < 60,
                "Invalid weekly time"
            ),
            ScheduleKind::Interval {
                anchor_ms,
                every_ms,
            } => ensure!(
                anchor_ms >= 0 && every_ms >= 1000 && every_ms <= DAY * 366,
                "Invalid interval"
            ),
        }
        Ok(())
    }
    /// Strictly after `after_ms`. Never materializes a missed backlog.
    pub fn next_after(&self, after_ms: i64) -> Result<Option<i64>> {
        self.validate()?;
        ensure!(
            after_ms >= -1 && after_ms < i64::MAX / 4,
            "Invalid schedule clock"
        );
        match self.kind {
            ScheduleKind::Once { at_ms } => Ok((at_ms > after_ms).then_some(at_ms)),
            ScheduleKind::Interval {
                anchor_ms,
                every_ms,
            } => {
                let n = if after_ms < anchor_ms {
                    0
                } else {
                    (after_ms - anchor_ms) / every_ms + 1
                };
                let next = anchor_ms
                    .checked_add(n.checked_mul(every_ms).context("Schedule overflow")?)
                    .context("Schedule overflow")?;
                ensure!(
                    self.zone.offset(next).is_some(),
                    "Timezone rules out of coverage"
                );
                Ok(Some(next))
            }
            ScheduleKind::Daily { hour, minute } | ScheduleKind::Weekly { hour, minute, .. } => {
                let day = self
                    .zone
                    .local(after_ms.max(self.zone.from_ms))?
                    .div_euclid(DAY);
                for d in day..day + 370 {
                    if let ScheduleKind::Weekly { weekday, .. } = self.kind {
                        if (d + 3).rem_euclid(7) != i64::from(weekday) {
                            continue;
                        }
                    }
                    let local = d * DAY + i64::from(hour) * 3_600_000 + i64::from(minute) * 60_000;
                    if let Some(utc) = self.zone.resolve(local) {
                        if utc > after_ms {
                            return Ok(Some(utc));
                        }
                    }
                    ensure!(
                        local < self.zone.until_ms + DAY,
                        "Timezone rules out of coverage"
                    );
                }
                anyhow::bail!("No covered schedule occurrence")
            }
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Schedule {
    pub id: String,
    pub spec: ScheduleSpec,
    pub next_ms: Option<i64>,
    pub missed: bool,
    #[serde(default)]
    pub error: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;
    fn spec(kind: ScheduleKind, zone: ZoneRules) -> ScheduleSpec {
        ScheduleSpec {
            kind,
            zone,
            prompt: "task".into(),
            directory: std::env::temp_dir(),
            writes: false,
        }
    }
    fn dst() -> ZoneRules {
        ZoneRules {
            name: "test/DST".into(),
            from_ms: 0,
            until_ms: DAY * 365,
            initial_offset_seconds: 0,
            transitions: vec![
                ZoneTransition {
                    at_ms: DAY + 2 * 3_600_000,
                    offset_seconds: 3600,
                },
                ZoneTransition {
                    at_ms: DAY * 10 + 2 * 3_600_000,
                    offset_seconds: 0,
                },
            ],
        }
    }
    #[test]
    fn gap_is_skipped_and_fold_occurs_once() {
        let s = spec(
            ScheduleKind::Daily {
                hour: 2,
                minute: 30,
            },
            dst(),
        );
        assert_eq!(s.next_after(DAY).unwrap(), Some(DAY * 2 + 90 * 60_000));
        let first = DAY * 10 + 90 * 60_000;
        assert_eq!(s.next_after(DAY * 10).unwrap(), Some(first));
        assert_eq!(s.next_after(first).unwrap(), Some(DAY * 11 + 150 * 60_000));
    }
    #[test]
    fn weekly_once_interval_and_coverage() {
        let z = ZoneRules::utc();
        assert_eq!(
            spec(
                ScheduleKind::Weekly {
                    weekday: 0,
                    hour: 0,
                    minute: 0
                },
                z.clone()
            )
            .next_after(0)
            .unwrap(),
            Some(4 * DAY)
        );
        assert_eq!(
            spec(ScheduleKind::Once { at_ms: 100 }, z.clone())
                .next_after(100)
                .unwrap(),
            None
        );
        assert_eq!(
            spec(
                ScheduleKind::Interval {
                    anchor_ms: 100,
                    every_ms: 1000
                },
                z
            )
            .next_after(5200)
            .unwrap(),
            Some(6100)
        );
        assert!(
            spec(
                ScheduleKind::Daily {
                    hour: 25,
                    minute: 0
                },
                dst()
            )
            .next_after(0)
            .is_err()
        );
        assert!(
            spec(ScheduleKind::Daily { hour: 0, minute: 0 }, dst())
                .next_after(DAY * 366)
                .is_err()
        );
    }
}
