//! Advisory, versioned compatibility catalog for SQLite SQL in `.nx` sources.
//!
//! The platform version thresholds below are conservative release-family
//! estimates for the SQLite library commonly shipped by the OS. Android
//! vendors may replace the platform SQLite library, so these are warnings,
//! never compile errors or guarantees about the device's runtime version.

use nexa_diagnostics::{CompileWarning, Span};

/// Increment when the compatibility rules or their platform thresholds change.
#[cfg(test)]
pub const SQLITE_COMPAT_CATALOG_VERSION: u32 = 1;

/// The minimum OS version configured for the generated application.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DatabaseTargetMinimum {
    Ios { major: u16, minor: u16, patch: u16 },
    Android { min_sdk: u32 },
}

impl DatabaseTargetMinimum {
    /// Parses `major.minor[.patch]`, such as `15.0` or `17.2.1`.
    pub fn ios(version: &str) -> Option<Self> {
        let mut pieces = version.split('.');
        let major = pieces.next()?.parse().ok()?;
        let minor = pieces.next().unwrap_or("0").parse().ok()?;
        let patch = pieces.next().unwrap_or("0").parse().ok()?;
        if pieces.next().is_some() {
            return None;
        }
        Some(Self::Ios {
            major,
            minor,
            patch,
        })
    }
}

#[derive(Clone, Copy)]
struct Feature {
    name: &'static str,
    message: &'static str,
    ios_minimum: (u16, u16, u16),
    android_min_sdk: u32,
}

// Catalog v1 maps syntax introduction to a conservative OS release floor.
// iOS floors approximate the system SQLite version; Android floors use the
// first API family expected to include that SQLite generation. Device OEMs
// can vary, which is why all results remain advisory.
const FEATURES: &[Feature] = &[
    Feature {
        name: "upsert",
        message: "SQLite UPSERT syntax may be unavailable at the configured minimum platform version; verify the target device's SQLite version.",
        ios_minimum: (12, 0, 0),
        android_min_sdk: 29,
    },
    Feature {
        name: "generated columns",
        message: "SQLite generated columns may be unavailable at the configured minimum platform version; verify the target device's SQLite version.",
        ios_minimum: (14, 0, 0),
        android_min_sdk: 31,
    },
    Feature {
        name: "RETURNING",
        message: "SQLite RETURNING may be unavailable at the configured minimum platform version; verify the target device's SQLite version.",
        ios_minimum: (15, 0, 0),
        android_min_sdk: 34,
    },
    Feature {
        name: "STRICT tables",
        message: "SQLite STRICT tables may be unavailable at the configured minimum platform version; verify the target device's SQLite version.",
        ios_minimum: (16, 0, 0),
        android_min_sdk: 34,
    },
];

/// Finds advisory SQLite compatibility warnings for SQL and one target.
///
/// The caller supplies the span of the SQL-bearing declaration. This function
/// never rejects SQL: syntax validation remains the responsibility of the
/// SQLite parser used by the compiler, and runtime availability varies by OS.
pub fn compatibility_warnings(
    sql: &str,
    span: Span,
    target: DatabaseTargetMinimum,
) -> Vec<CompileWarning> {
    let tokens = sql_words(sql);
    FEATURES
        .iter()
        .filter(|feature| uses_feature(&tokens, feature.name))
        .filter(|feature| below_minimum(target, feature))
        .map(|feature| {
            CompileWarning::new(
                span,
                format!("{} (detected SQLite {}).", feature.message, feature.name),
            )
        })
        .collect()
}

fn below_minimum(target: DatabaseTargetMinimum, feature: &Feature) -> bool {
    match target {
        DatabaseTargetMinimum::Ios {
            major,
            minor,
            patch,
        } => (major, minor, patch) < feature.ios_minimum,
        DatabaseTargetMinimum::Android { min_sdk } => min_sdk < feature.android_min_sdk,
    }
}

fn uses_feature(tokens: &[String], feature: &str) -> bool {
    match feature {
        "upsert" => tokens.windows(2).enumerate().any(|(index, window)| {
            window[0] == "ON"
                && window[1] == "CONFLICT"
                && tokens[index + 2..]
                    .iter()
                    .take_while(|token| token.as_str() != ";")
                    .any(|token| token == "DO")
        }),
        "generated columns" => {
            tokens
                .windows(2)
                .any(|window| window[0] == "GENERATED" && window[1] == "ALWAYS")
                || tokens
                    .windows(2)
                    .any(|window| window[0] == "AS" && window[1] == "(")
        }
        "RETURNING" => tokens.iter().any(|token| token == "RETURNING"),
        "STRICT tables" => tokens
            .windows(2)
            .any(|window| window[0] == ")" && window[1] == "STRICT"),
        _ => false,
    }
}

/// Extracts SQL words while skipping comments, strings, and quoted names.
/// Punctuation is retained because STRICT is a table option after `)`.
fn sql_words(sql: &str) -> Vec<String> {
    let chars = sql.chars().collect::<Vec<_>>();
    let mut tokens = Vec::new();
    let mut index = 0;
    while index < chars.len() {
        let character = chars[index];
        if character.is_whitespace() {
            index += 1;
        } else if character == '-' && chars.get(index + 1) == Some(&'-') {
            index += 2;
            while index < chars.len() && chars[index] != '\n' {
                index += 1;
            }
        } else if character == '/' && chars.get(index + 1) == Some(&'*') {
            index += 2;
            while index + 1 < chars.len() && !(chars[index] == '*' && chars[index + 1] == '/') {
                index += 1;
            }
            index = (index + 2).min(chars.len());
        } else if matches!(character, '\'' | '"' | '`' | '[') {
            let closing = if character == '[' { ']' } else { character };
            index += 1;
            while index < chars.len() {
                if chars[index] == closing {
                    if closing != ']' && chars.get(index + 1) == Some(&closing) {
                        index += 2;
                    } else {
                        index += 1;
                        break;
                    }
                } else {
                    index += 1;
                }
            }
        } else if character.is_ascii_alphabetic() || character == '_' {
            let start = index;
            index += 1;
            while index < chars.len()
                && (chars[index].is_ascii_alphanumeric()
                    || chars[index] == '_'
                    || chars[index] == '$')
            {
                index += 1;
            }
            tokens.push(
                chars[start..index]
                    .iter()
                    .collect::<String>()
                    .to_ascii_uppercase(),
            );
        } else {
            tokens.push(character.to_string());
            index += 1;
        }
    }
    tokens
}

#[cfg(test)]
mod tests {
    use super::{DatabaseTargetMinimum, SQLITE_COMPAT_CATALOG_VERSION, compatibility_warnings};
    use nexa_diagnostics::Span;

    #[test]
    fn warns_for_features_below_configured_platform_floor() {
        let sql = "INSERT INTO notes(title) VALUES (?) RETURNING id";
        let warnings = compatibility_warnings(
            sql,
            Span {
                start: 20,
                end: 63,
                line: 4,
                column: 9,
            },
            DatabaseTargetMinimum::ios("14.0").expect("valid iOS version"),
        );
        assert_eq!(warnings.len(), 1);
        assert_eq!(warnings[0].span.line, 4);
        assert!(warnings[0].message.contains("RETURNING"));
    }

    #[test]
    fn newer_minimum_suppresses_the_advisory() {
        let warnings = compatibility_warnings(
            "CREATE TABLE note (id INTEGER) STRICT",
            Span::default(),
            DatabaseTargetMinimum::Android { min_sdk: 35 },
        );
        assert!(warnings.is_empty());
    }

    #[test]
    fn ignores_feature_words_in_comments_literals_and_quoted_names() {
        let warnings = compatibility_warnings(
            "SELECT 'RETURNING' AS \"generated always\" -- STRICT RETURNING\n/* ON CONFLICT DO UPDATE */",
            Span::default(),
            DatabaseTargetMinimum::Android { min_sdk: 23 },
        );
        assert!(warnings.is_empty());
    }

    #[test]
    fn recognizes_upsert_generated_columns_and_strict_tables() {
        let target = DatabaseTargetMinimum::Android { min_sdk: 23 };
        for sql in [
            "INSERT INTO t(a) VALUES (?) ON CONFLICT(a) DO UPDATE SET a=excluded.a",
            "CREATE TABLE t (a INT GENERATED ALWAYS AS (1) STORED)",
            "CREATE TABLE t (a INT) STRICT",
        ] {
            assert_eq!(
                compatibility_warnings(sql, Span::default(), target).len(),
                1,
                "{sql}"
            );
        }
    }

    #[test]
    fn catalog_version_is_explicit_and_invalid_ios_versions_are_rejected() {
        assert_eq!(SQLITE_COMPAT_CATALOG_VERSION, 1);
        assert!(DatabaseTargetMinimum::ios("17.x").is_none());
        assert!(DatabaseTargetMinimum::ios("17.1.2.3").is_none());
    }
}
