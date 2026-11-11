use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DataSource {
    #[default]
    Proxy,
    CodexSession,
}
impl DataSource {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::Proxy => "proxy",
            Self::CodexSession => "codex_session",
        }
    }
}
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StatusFilter {
    #[default]
    All,
    Success,
    Error,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Filters {
    pub app_type: String,
    pub data_source: DataSource,
    pub provider_id: Option<String>,
    pub model: Option<String>,
    pub status: StatusFilter,
}
impl Default for Filters {
    fn default() -> Self {
        Self {
            app_type: "codex".into(),
            data_source: DataSource::Proxy,
            provider_id: None,
            model: None,
            status: StatusFilter::All,
        }
    }
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Page {
    pub offset: usize,
    pub limit: usize,
}
impl Default for Page {
    fn default() -> Self {
        Self {
            offset: 0,
            limit: 50,
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UsageQuery {
    pub start_at: i64,
    pub end_at: i64,
    pub bucket_edges: Vec<i64>,
    #[serde(default)]
    pub filters: Filters,
    #[serde(default)]
    pub provider_page: Page,
    #[serde(default)]
    pub model_page: Page,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Availability {
    Ready,
    Missing,
    Locked,
    UnsupportedSchema,
    InvalidPath,
    Rejected,
    Unavailable,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Coverage {
    pub scope: String,
    pub complete: bool,
    pub matched_rows: u64,
    pub first_observed_at: Option<i64>,
    pub last_observed_at: Option<i64>,
    pub rollups_present: bool,
    pub rollups_included: bool,
    pub cost_known_rows: u64,
    pub cost_missing_rows: u64,
    pub cost_invalid_rows: u64,
    pub zero_cost_rows: u64,
    pub token_invalid_rows: u64,
    pub confirmed_free_rows: Option<u64>,
    pub zero_cost_interpretation: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SourceInfo {
    pub kind: String,
    pub table: String,
    pub app_type: String,
    pub data_source: DataSource,
    pub schema_version: Option<i64>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UsageError {
    pub code: String,
    pub message: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UsageResponse {
    pub availability: Availability,
    pub coverage: Coverage,
    pub source: SourceInfo,
    pub errors: Vec<UsageError>,
    pub data: Option<UsageData>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct TokenTotals {
    pub fresh_input: u64,
    pub cache_read: u64,
    pub cache_write: u64,
    pub output: u64,
    pub total: u64,
    pub invalid_rows: u64,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CostAmount {
    pub known_usd: String,
    pub known_rows: u64,
    pub missing_rows: u64,
    pub invalid_rows: u64,
    pub zero_rows: u64,
}
impl Default for CostAmount {
    fn default() -> Self {
        Self {
            known_usd: "0".into(),
            known_rows: 0,
            missing_rows: 0,
            invalid_rows: 0,
            zero_rows: 0,
        }
    }
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct CostTotals {
    pub input: CostAmount,
    pub output: CostAmount,
    pub cache_read: CostAmount,
    pub cache_write: CostAmount,
    pub total: CostAmount,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Totals {
    pub requests: u64,
    pub success: u64,
    pub errors: u64,
    pub tokens: TokenTotals,
    pub costs: CostTotals,
    pub performance: Performance,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct TimingMetric {
    pub average_ms: Option<f64>,
    pub sum_ms: u64,
    pub sample_rows: u64,
    pub missing_rows: u64,
    pub invalid_rows: u64,
    pub excluded_rows: u64,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct SpeedMetric {
    pub tokens_per_second: Option<f64>,
    pub sum_output_tokens: u64,
    pub sum_generation_ms: u64,
    pub sample_rows: u64,
    pub missing_rows: u64,
    pub invalid_rows: u64,
    pub excluded_rows: u64,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Performance {
    pub scope: String,
    pub success_rows: u64,
    pub ttft: TimingMetric,
    pub duration: TimingMetric,
    pub output_speed: SpeedMetric,
}
impl Default for Performance {
    fn default() -> Self {
        Self {
            scope: "successful_proxy_observations".into(),
            success_rows: 0,
            ttft: TimingMetric::default(),
            duration: TimingMetric::default(),
            output_speed: SpeedMetric::default(),
        }
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrendBucket {
    pub start_at: i64,
    pub end_at: i64,
    pub totals: Totals,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GroupPage<T> {
    pub items: Vec<T>,
    pub offset: usize,
    pub limit: usize,
    pub total_groups: usize,
    pub has_more: bool,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderGroup {
    pub provider_id: String,
    pub app_type: String,
    pub name: Option<String>,
    pub totals: Totals,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelGroup {
    pub model: String,
    pub totals: Totals,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UsageData {
    pub totals: Totals,
    pub trend: Vec<TrendBucket>,
    pub provider_groups: GroupPage<ProviderGroup>,
    pub model_groups: GroupPage<ModelGroup>,
}
