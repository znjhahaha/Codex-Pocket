//! Measured proxy observations only. Imported session timestamps are never estimates here.
use super::{Performance, TimingMetric, reader::Failure};

pub(crate) enum Observation<T> {
    Sample(T),
    Missing,
    Invalid,
    Excluded,
}
pub(crate) struct Measurements {
    pub ttft: Observation<u64>,
    pub duration: Observation<u64>,
    pub speed: Observation<(u64, u64)>,
}
fn sum(total: &mut u64, value: u64) -> Result<(), Failure> {
    *total = total.checked_add(value).ok_or(Failure::Overflow)?;
    Ok(())
}
fn timing(metric: &mut TimingMetric, value: &Observation<u64>) -> Result<(), Failure> {
    match value {
        Observation::Sample(ms) => {
            sum(&mut metric.sum_ms, *ms)?;
            metric.sample_rows += 1;
        }
        Observation::Missing => metric.missing_rows += 1,
        Observation::Invalid => metric.invalid_rows += 1,
        Observation::Excluded => metric.excluded_rows += 1,
    }
    Ok(())
}
impl Performance {
    pub(crate) fn add(&mut self, values: &Measurements) -> Result<(), Failure> {
        self.success_rows += 1;
        timing(&mut self.ttft, &values.ttft)?;
        timing(&mut self.duration, &values.duration)?;
        let speed = &mut self.output_speed;
        match &values.speed {
            Observation::Sample((tokens, ms)) => {
                sum(&mut speed.sum_output_tokens, *tokens)?;
                sum(&mut speed.sum_generation_ms, *ms)?;
                speed.sample_rows += 1;
            }
            Observation::Missing => speed.missing_rows += 1,
            Observation::Invalid => speed.invalid_rows += 1,
            Observation::Excluded => speed.excluded_rows += 1,
        }
        Ok(())
    }
    pub(crate) fn finish(&mut self) {
        for metric in [&mut self.ttft, &mut self.duration] {
            metric.average_ms =
                (metric.sample_rows > 0).then(|| metric.sum_ms as f64 / metric.sample_rows as f64);
        }
        let speed = &mut self.output_speed;
        speed.tokens_per_second = (speed.sample_rows > 0)
            .then(|| speed.sum_output_tokens as f64 * 1000.0 / speed.sum_generation_ms as f64);
    }
}
