use super::reader::Failure;
use super::{decimal::Decimal, *};
use std::collections::BTreeMap;

pub(crate) enum Cost {
    Known(Decimal),
    Missing,
    Invalid,
}
pub(crate) struct Record {
    pub at: i64,
    pub provider_id: String,
    pub model: String,
    pub status: i64,
    pub tokens: Option<[u64; 4]>,
    pub token_anomaly: bool,
    pub costs: [Cost; 5],
    pub measurements: super::performance::Measurements,
}
#[derive(Default)]
struct Amount {
    sum: Decimal,
    known: u64,
    missing: u64,
    invalid: u64,
    zero: u64,
}
impl Amount {
    fn add(&mut self, cost: &Cost) -> Result<(), Failure> {
        match cost {
            Cost::Known(v) => {
                self.sum.add(*v).ok_or(Failure::Overflow)?;
                self.known += 1;
                self.zero += u64::from(v.is_zero());
            }
            Cost::Missing => self.missing += 1,
            Cost::Invalid => self.invalid += 1,
        }
        Ok(())
    }
    fn finish(self) -> CostAmount {
        CostAmount {
            known_usd: self.sum.text(),
            known_rows: self.known,
            missing_rows: self.missing,
            invalid_rows: self.invalid,
            zero_rows: self.zero,
        }
    }
}
#[derive(Default)]
struct Accumulator {
    counts: Totals,
    costs: [Amount; 5],
}
fn add(a: &mut u64, b: u64) -> Result<(), Failure> {
    *a = a.checked_add(b).ok_or(Failure::Overflow)?;
    Ok(())
}
impl Accumulator {
    fn add(&mut self, r: &Record) -> Result<(), Failure> {
        self.counts.requests += 1;
        if (200..300).contains(&r.status) {
            self.counts.success += 1;
            self.counts.performance.add(&r.measurements)?;
        } else {
            self.counts.errors += 1;
        }
        if r.token_anomaly {
            self.counts.tokens.invalid_rows += 1;
        }
        if let Some([fresh, read, write, output]) = r.tokens {
            let t = &mut self.counts.tokens;
            let total = fresh
                .checked_add(read)
                .and_then(|v| v.checked_add(write))
                .and_then(|v| v.checked_add(output))
                .ok_or(Failure::Overflow)?;
            add(&mut t.fresh_input, fresh)?;
            add(&mut t.cache_read, read)?;
            add(&mut t.cache_write, write)?;
            add(&mut t.output, output)?;
            add(&mut t.total, total)?;
        }
        for (amount, cost) in self.costs.iter_mut().zip(&r.costs) {
            amount.add(cost)?;
        }
        Ok(())
    }
    fn finish(mut self) -> Totals {
        self.counts.performance.finish();
        let [input, output, read, write, total] = self.costs;
        self.counts.costs = CostTotals {
            input: input.finish(),
            output: output.finish(),
            cache_read: read.finish(),
            cache_write: write.finish(),
            total: total.finish(),
        };
        self.counts
    }
}
pub(crate) struct Aggregate {
    totals: Accumulator,
    buckets: Vec<Accumulator>,
    providers: BTreeMap<String, Accumulator>,
    models: BTreeMap<String, Accumulator>,
    pub first: Option<i64>,
    pub last: Option<i64>,
}
impl Aggregate {
    pub fn new(query: &UsageQuery) -> Self {
        Self {
            totals: Accumulator::default(),
            buckets: (0..query.bucket_edges.len() - 1)
                .map(|_| Accumulator::default())
                .collect(),
            providers: BTreeMap::new(),
            models: BTreeMap::new(),
            first: None,
            last: None,
        }
    }
    pub fn push(&mut self, r: Record, q: &UsageQuery) -> Result<(), Failure> {
        if self.totals.counts.requests >= super::MAX_ROWS as u64 {
            return Err(Failure::Limit);
        }
        self.totals.add(&r)?;
        let bucket = q.bucket_edges.partition_point(|at| *at <= r.at) - 1;
        self.buckets[bucket].add(&r)?;
        self.providers
            .entry(r.provider_id.clone())
            .or_default()
            .add(&r)?;
        self.models.entry(r.model.clone()).or_default().add(&r)?;
        if self.providers.len() > super::MAX_GROUPS || self.models.len() > super::MAX_GROUPS {
            return Err(Failure::Limit);
        }
        self.first = Some(self.first.map_or(r.at, |v| v.min(r.at)));
        self.last = Some(self.last.map_or(r.at, |v| v.max(r.at)));
        Ok(())
    }
    pub fn provider_ids(&self) -> impl Iterator<Item = &String> {
        self.providers.keys()
    }
    pub fn finish(self, q: &UsageQuery, names: BTreeMap<String, Option<String>>) -> UsageData {
        let trend = self
            .buckets
            .into_iter()
            .enumerate()
            .map(|(i, a)| TrendBucket {
                start_at: q.bucket_edges[i],
                end_at: q.bucket_edges[i + 1],
                totals: a.finish(),
            })
            .collect();
        let providers = self
            .providers
            .into_iter()
            .map(|(id, a)| ProviderGroup {
                name: names.get(&id).cloned().flatten(),
                provider_id: id,
                app_type: q.filters.app_type.clone(),
                totals: a.finish(),
            })
            .collect();
        let models = self
            .models
            .into_iter()
            .map(|(model, a)| ModelGroup {
                model,
                totals: a.finish(),
            })
            .collect();
        UsageData {
            totals: self.totals.finish(),
            trend,
            provider_groups: page(providers, q.provider_page),
            model_groups: page(models, q.model_page),
        }
    }
}
fn page<T>(items: Vec<T>, p: Page) -> GroupPage<T> {
    let total_groups = items.len();
    let has_more = p.offset.saturating_add(p.limit) < total_groups;
    GroupPage {
        items: items.into_iter().skip(p.offset).take(p.limit).collect(),
        offset: p.offset,
        limit: p.limit,
        total_groups,
        has_more,
    }
}

pub(crate) fn normalize(
    app: &str,
    semantics: i64,
    input: u64,
    read: u64,
    write: u64,
    output: u64,
) -> (Option<[u64; 4]>, bool) {
    if !(0..=2).contains(&semantics) {
        return (None, true);
    }
    let inclusive = matches!(app, "codex" | "gemini" | "grokbuild");
    let deduct_read = inclusive && semantics != 2;
    let deduct_write = inclusive && semantics == 1;
    let anomaly =
        (deduct_read && read > input) || (deduct_write && write > input.saturating_sub(read));
    let fresh = if deduct_write {
        input.saturating_sub(read).saturating_sub(write)
    } else if deduct_read {
        input.saturating_sub(read)
    } else {
        input
    };
    (Some([fresh, read, write, output]), anomaly)
}
