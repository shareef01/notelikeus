//! Local midnights for the search box's dates (`before:today`, `after:2026-08-01`) and the Filters
//! sheet's presets: days are the user's, in their time zone, as on the other clients.

use chrono::{Days as ChronoDays, Local, NaiveDate, TimeZone};
use notelikeus_core::query::Days;

/// "Today" as of `now` (epoch millis), in the computer's time zone.
pub struct LocalDays {
    today: NaiveDate,
}

impl LocalDays {
    pub fn at(now: i64) -> LocalDays {
        let today =
            Local.timestamp_millis_opt(now).single().map_or_else(|| Local::now().date_naive(), |t| t.date_naive());
        LocalDays { today }
    }
}

/// Milliseconds at local midnight of `date`. Where a clock change skips midnight, the first instant of
/// that day.
fn midnight(date: NaiveDate) -> Option<i64> {
    let start = date.and_hms_opt(0, 0, 0)?;
    let local = Local
        .from_local_datetime(&start)
        .earliest()
        .or_else(|| (1..=3).find_map(|h| Local.from_local_datetime(&date.and_hms_opt(h, 0, 0)?).earliest()))?;
    Some(local.timestamp_millis())
}

impl Days for LocalDays {
    fn day_start(&self, days_from_today: i64) -> i64 {
        let magnitude = ChronoDays::new(days_from_today.unsigned_abs());
        let date = if days_from_today >= 0 {
            self.today.checked_add_days(magnitude)
        } else {
            self.today.checked_sub_days(magnitude)
        };
        date.and_then(midnight).unwrap_or(0)
    }

    fn day_start_of_date(&self, year: i64, month: u32, day: u32) -> Option<i64> {
        let year = i32::try_from(year).ok()?;
        midnight(NaiveDate::from_ymd_opt(year, month, day)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn days_are_local_midnights_and_impossible_dates_are_none() {
        let now = Local::now();
        let days = LocalDays::at(now.timestamp_millis());
        let today = days.day_start(0);
        assert!(today <= now.timestamp_millis() && now.timestamp_millis() - today < 25 * 3_600_000);
        let tomorrow = days.day_start(1);
        assert!((23..=25).contains(&((tomorrow - today) / 3_600_000)), "one day later, give or take a clock change");
        assert!(days.day_start(-1) < today);
        assert!(days.day_start_of_date(2023, 2, 29).is_none());
        assert!(days.day_start_of_date(2024, 2, 29).is_some());
    }
}
