//! Nonnegative decimal amounts. No binary floating point or price inference.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub(crate) struct Decimal {
    coefficient: u128,
    scale: u32,
}
impl Decimal {
    pub(crate) fn parse(text: &str) -> Option<Self> {
        let text = text.trim();
        if text.is_empty() || text.len() > 128 {
            return None;
        }
        let mut coefficient = 0u128;
        let mut scale = 0;
        let mut dot = false;
        let mut digits = 0;
        for b in text.bytes() {
            if b == b'.' && !dot {
                dot = true;
                continue;
            }
            if !b.is_ascii_digit() {
                return None;
            }
            digits += 1;
            if dot {
                scale += 1;
                if scale > 28 {
                    return None;
                }
            }
            coefficient = coefficient
                .checked_mul(10)?
                .checked_add(u128::from(b - b'0'))?;
            if coefficient > i128::MAX as u128 {
                return None;
            }
        }
        if digits == 0 || text.starts_with('.') || text.ends_with('.') {
            return None;
        }
        let mut value = Self { coefficient, scale };
        value.normalize();
        Some(value)
    }
    fn normalize(&mut self) {
        while self.scale > 0 && self.coefficient % 10 == 0 {
            self.coefficient /= 10;
            self.scale -= 1;
        }
    }
    pub(crate) fn add(&mut self, other: Self) -> Option<()> {
        let scale = self.scale.max(other.scale);
        let a = self
            .coefficient
            .checked_mul(10u128.checked_pow(scale - self.scale)?)?;
        let b = other
            .coefficient
            .checked_mul(10u128.checked_pow(scale - other.scale)?)?;
        let coefficient = a.checked_add(b)?;
        if coefficient > i128::MAX as u128 {
            return None;
        }
        *self = Self { coefficient, scale };
        self.normalize();
        Some(())
    }
    pub(crate) fn is_zero(self) -> bool {
        self.coefficient == 0
    }
    pub(crate) fn text(self) -> String {
        if self.scale == 0 {
            return self.coefficient.to_string();
        }
        let text = format!(
            "{:0width$}",
            self.coefficient,
            width = self.scale as usize + 1
        );
        let at = text.len() - self.scale as usize;
        format!("{}.{}", &text[..at], &text[at..])
    }
}
