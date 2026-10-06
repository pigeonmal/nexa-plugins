//! Compile-time validation for ordinary calls to the SQLite plugin API.
//!
//! This uses SQLite's parser and schema engine inside the plugin analyzer.
//! Generated apps do not link this validator; their database calls remain
//! native Swift/Kotlin through the SQLite plugin.

use nexa_diagnostics::CompileError;
use nexa_syntax::ast::{self, StructDecl, TypeSyntax};
use rusqlite::{
    Connection,
    hooks::{AuthAction, AuthContext, Authorization},
};
use std::collections::{BTreeSet, HashMap, HashSet};
use std::sync::{Arc, Mutex};

const FUNCTION_RETURN_TYPE_PREFIX: &str = "\0nexa.sqlite.function-return:";

#[derive(Clone, Debug)]
struct DatabaseDecl {
    name: String,
    database_id: String,
    shared_with_widgets: bool,
    migrations: Vec<DatabaseMigration>,
    queries: Vec<DatabaseQueryDecl>,
    commands: Vec<DatabaseCommandDecl>,
    span: nexa_diagnostics::Span,
}

#[derive(Clone, Debug)]
struct DatabaseMigration {
    version: u32,
    sql: String,
    span: nexa_diagnostics::Span,
}

#[derive(Clone, Debug)]
struct DatabaseQueryDecl {
    name: String,
    parameters: Vec<nexa_syntax::ast::FunctionParameter>,
    return_type: TypeSyntax,
    sql: String,
    span: nexa_diagnostics::Span,
    source_file: Option<String>,
    /// Bind parameters present in the source array, counted even when their
    /// types are unknown.
    parameter_count: usize,
    /// The parameter types could not be inferred, so only the count and the SQL
    /// are checked. See [`lenient_parameter_types`].
    allow_dynamic_parameters: bool,
}

#[derive(Clone, Debug)]
struct DatabaseCommandDecl {
    name: String,
    parameters: Vec<nexa_syntax::ast::FunctionParameter>,
    sql: String,
    span: nexa_diagnostics::Span,
    allow_dynamic_parameters: bool,
}

#[derive(Default, Clone)]
struct SqlAccess {
    reads: BTreeSet<String>,
    writes: BTreeSet<String>,
    uses_outer_schema: bool,
    uses_pragma: bool,
    uses_transaction_control: bool,
    uses_temporary_schema: bool,
}

#[allow(dead_code)]
#[derive(Clone, Debug)]
pub(crate) struct ValidatedDatabase {
    pub database_id: String,
    pub migrations: Vec<ValidatedMigration>,
    pub queries: Vec<ValidatedQuery>,
    pub commands: Vec<ValidatedCommand>,
}

#[derive(Clone, Debug)]
pub(crate) struct ValidatedMigration {
    pub statements: Vec<String>,
    pub span: nexa_diagnostics::Span,
}

#[allow(dead_code)]
#[derive(Clone, Debug)]
pub(crate) struct ValidatedQuery {
    pub sql: String,
    pub span: nexa_diagnostics::Span,
    pub source_file: Option<String>,
    pub read_resources: Vec<String>,
}

#[derive(Clone, Debug)]
pub(crate) struct ValidatedCommand {
    pub sql: String,
    pub span: nexa_diagnostics::Span,
}

fn validate_database(
    database: &DatabaseDecl,
    structs: &[StructDecl],
    positional_parameters: bool,
) -> Result<ValidatedDatabase, CompileError> {
    if database.migrations.is_empty() {
        return Err(CompileError::new(
            database.span,
            format!("database `{}` must declare migration 1", database.name),
        ));
    }

    let mut connection = Connection::open_in_memory().map_err(|error| {
        CompileError::new(
            database.span,
            format!("unable to create SQLite validation database: {error}"),
        )
    })?;
    connection
        .execute_batch("PRAGMA foreign_keys = ON")
        .map_err(|error| {
            CompileError::new(
                database.span,
                format!("unable to configure SQLite validation database: {error}"),
            )
        })?;
    let access = Arc::new(Mutex::new(SqlAccess::default()));
    let access_for_authorizer = Arc::clone(&access);
    connection
        .authorizer(Some(move |context: AuthContext<'_>| {
            let Ok(mut access) = access_for_authorizer.lock() else {
                return Authorization::Deny;
            };
            match context.action {
                AuthAction::Read { table_name, .. } => {
                    access.reads.insert(table_name.to_owned());
                }
                AuthAction::Insert { table_name }
                | AuthAction::Delete { table_name }
                | AuthAction::Update { table_name, .. } => {
                    access.writes.insert(table_name.to_owned());
                }
                AuthAction::CreateTrigger { table_name, .. }
                | AuthAction::DropTrigger { table_name, .. } => {
                    access.writes.insert(table_name.to_owned());
                }
                AuthAction::Attach { .. } | AuthAction::Detach { .. } => {
                    access.uses_outer_schema = true;
                    // Never let compilation attach a caller-controlled path.
                    return Authorization::Deny;
                }
                AuthAction::Pragma { .. } => access.uses_pragma = true,
                AuthAction::Transaction { .. } | AuthAction::Savepoint { .. } => {
                    access.uses_transaction_control = true;
                }
                AuthAction::CreateTempIndex { .. }
                | AuthAction::CreateTempTable { .. }
                | AuthAction::CreateTempTrigger { .. }
                | AuthAction::CreateTempView { .. }
                | AuthAction::DropTempIndex { .. }
                | AuthAction::DropTempTable { .. }
                | AuthAction::DropTempTrigger { .. }
                | AuthAction::DropTempView { .. } => access.uses_temporary_schema = true,
                _ => {}
            }
            Authorization::Allow
        }))
        .map_err(|error| {
            CompileError::new(
                database.span,
                format!("unable to configure SQLite validator: {error}"),
            )
        })?;

    let mut expected_version = 1_u32;
    let mut migrations = Vec::with_capacity(database.migrations.len());
    for migration in &database.migrations {
        if migration.version != expected_version {
            return Err(CompileError::new(
                migration.span,
                format!(
                    "database migrations must be declared in order starting at 1; expected migration {expected_version}, found {}",
                    migration.version
                ),
            ));
        }
        if migration.sql.trim().is_empty() {
            return Err(CompileError::new(
                migration.span,
                format!("migration {} cannot be empty", migration.version),
            ));
        }
        let transaction = connection.transaction().map_err(|error| {
            CompileError::new(
                migration.span,
                format!("unable to begin migration validation transaction: {error}"),
            )
        })?;
        // Exclude the validator's own BEGIN from the migration's audit record.
        reset_access(&access, migration.span)?;
        transaction.execute_batch(&migration.sql).map_err(|error| {
            CompileError::new(
                migration.span,
                format!("invalid SQLite migration {}: {error}", migration.version),
            )
        })?;
        let migration_access = access_snapshot(&access, migration.span)?;
        if migration_access.uses_outer_schema
            || migration_access.uses_pragma
            || migration_access.uses_transaction_control
            || migration_access.uses_temporary_schema
        {
            return Err(CompileError::new(
                migration.span,
                format!(
                    "migration {} uses connection-scoped SQLite features that are unsupported; migrations must only change this database's persistent schema",
                    migration.version
                ),
            ));
        }
        transaction.commit().map_err(|error| {
            CompileError::new(
                migration.span,
                format!("invalid SQLite migration {}: {error}", migration.version),
            )
        })?;
        let statements = split_migration_script(&migration.sql, migration.span)?;
        if statements.is_empty() {
            return Err(CompileError::new(
                migration.span,
                format!(
                    "migration {} must contain at least one SQL statement",
                    migration.version
                ),
            ));
        }
        migrations.push(ValidatedMigration {
            statements,
            span: migration.span,
        });
        expected_version = expected_version.checked_add(1).ok_or_else(|| {
            CompileError::new(
                migration.span,
                "database migration version exceeds the supported range",
            )
        })?;
    }

    let mut operation_names =
        HashSet::with_capacity(database.queries.len() + database.commands.len());
    let mut queries = Vec::with_capacity(database.queries.len());
    for query in &database.queries {
        if !operation_names.insert(query.name.as_str()) {
            return Err(CompileError::new(
                query.span,
                format!(
                    "database operation `{}` is declared more than once",
                    query.name
                ),
            ));
        }
        let read_resources =
            validate_query(&connection, &access, query, structs, positional_parameters)?;
        queries.push(ValidatedQuery {
            sql: query.sql.clone(),
            span: query.span,
            source_file: query.source_file.clone(),
            read_resources: read_resources.into_iter().collect(),
        });
    }
    let mut commands = Vec::with_capacity(database.commands.len());
    for command in &database.commands {
        if !operation_names.insert(command.name.as_str()) {
            return Err(CompileError::new(
                command.span,
                format!(
                    "database operation `{}` is declared more than once",
                    command.name
                ),
            ));
        }
        let _ = validate_command(&connection, &access, command, positional_parameters)?;
        commands.push(ValidatedCommand {
            sql: command.sql.clone(),
            span: command.span,
        });
    }
    Ok(ValidatedDatabase {
        database_id: database.database_id.clone(),
        migrations,
        queries,
        commands,
    })
}

fn validate_query(
    connection: &Connection,
    access: &Arc<Mutex<SqlAccess>>,
    query: &DatabaseQueryDecl,
    structs: &[StructDecl],
    positional_parameters: bool,
) -> Result<BTreeSet<String>, CompileError> {
    if has_trailing_statement(&query.sql) {
        return Err(CompileError::new(
            query.span,
            format!(
                "query `{}` must contain exactly one SQL statement",
                query.name
            ),
        ));
    }
    let keywords = sql_keywords(&query.sql);
    if keywords
        .iter()
        .any(|keyword| matches!(keyword.as_str(), "UNION" | "INTERSECT" | "EXCEPT"))
    {
        return Err(CompileError::new(
            query.span,
            format!(
                "query `{}` uses a compound SELECT; SQLite column metadata cannot validate every result arm, so declare each arm as a separate typed query",
                query.name
            ),
        ));
    }
    // SQLite's origin metadata reports the source column's nullability, not
    // the NULL extension introduced by an outer join. Conservatively require
    // optional result fields for every projected value in such a query.
    let outer_join_may_null = keywords
        .windows(2)
        .any(|pair| matches!(pair[0].as_str(), "LEFT" | "RIGHT" | "FULL") && pair[1] == "JOIN")
        || keywords.windows(3).any(|triple| {
            matches!(triple[0].as_str(), "LEFT" | "RIGHT" | "FULL")
                && triple[1] == "OUTER"
                && triple[2] == "JOIN"
        });
    let result_type = match &query.return_type {
        TypeSyntax::Generic(name, values, _) if name == "Array" && values.len() == 1 => &values[0],
        _ => {
            return Err(CompileError::new(
                query.return_type.span(),
                format!(
                    "query `{}` must return `Array<StructName>` so rows can be decoded without reflection",
                    query.name
                ),
            ));
        }
    };
    let TypeSyntax::Named(struct_name, _) = result_type else {
        return Err(CompileError::new(
            result_type.span(),
            format!("query `{}` result must be a declared struct", query.name),
        ));
    };
    let result_struct = structs
        .iter()
        .find(|structure| structure.name == *struct_name)
        .ok_or_else(|| {
            CompileError::new(
                result_type.span(),
                format!(
                    "query `{}` returns unknown struct `{struct_name}`",
                    query.name
                ),
            )
        })?;

    let params = declared_parameters(&query.parameters, &query.name)?;
    reset_access(access, query.span)?;
    let statement = connection.prepare(&query.sql).map_err(|error| {
        CompileError::new(
            query.span,
            format!("invalid SQL in query `{}`: {error}", query.name),
        )
    })?;
    if !statement.readonly() || statement.column_count() == 0 {
        return Err(CompileError::new(
            query.span,
            format!(
                "query `{}` must be a read-only statement that returns rows",
                query.name
            ),
        ));
    }
    // When the parameter types were out of reach the count is still checked,
    // against the SQL's own bind slots, so an arity mistake is still caught.
    if query.allow_dynamic_parameters || positional_parameters {
        validate_positional_parameter_slots(
            &statement,
            query.parameter_count,
            query.span,
            &query.name,
        )?
    } else {
        validate_parameter_slots(&statement, &params, query.span, &query.name)?
    };

    let access_snapshot = access_snapshot(access, query.span)?;
    if access_snapshot.reads.is_empty() || access_snapshot.uses_outer_schema {
        return Err(CompileError::new(
            query.span,
            format!(
                "query `{}` must read only this database's declared schema",
                query.name
            ),
        ));
    }
    let columns = statement.columns_with_metadata();
    let mut selected = HashMap::with_capacity(columns.len());
    for column in &columns {
        if selected.insert(column.name().to_owned(), column).is_some() {
            return Err(CompileError::new(
                query.span,
                format!(
                    "query `{}` returns duplicate column `{}`; add unique SQL aliases",
                    query.name,
                    column.name()
                ),
            ));
        }
    }

    if columns.len() != result_struct.fields.len() {
        return Err(CompileError::new(
            query.span,
            format!(
                "query `{}` returns {} columns, but `{struct_name}` has {} fields",
                query.name,
                columns.len(),
                result_struct.fields.len()
            ),
        ));
    }

    let mut schema_columns = HashMap::<(String, String), ColumnInfo>::new();
    for field in &result_struct.fields {
        let column = selected.get(&field.name).ok_or_else(|| {
            CompileError::new(
                query.span,
                format!(
                    "query `{}` is missing result column `{}` required by `{struct_name}`",
                    query.name, field.name
                ),
            )
        })?;
        if let (Some(table), Some(origin)) = (column.table_name(), column.origin_name()) {
            let key = (table.to_owned(), origin.to_owned());
            if !schema_columns.contains_key(&key) {
                let info = column_info(connection, table, origin).map_err(|error| {
                    CompileError::new(
                        query.span,
                        format!(
                            "cannot resolve `{table}.{origin}` in query `{}`: {error}",
                            query.name
                        ),
                    )
                })?;
                schema_columns.insert(key.clone(), info);
            }
            let Some(info) = schema_columns.get(&key) else {
                return Err(CompileError::new(
                    query.span,
                    format!(
                        "cannot resolve result column `{}` in query `{}`",
                        field.name, query.name
                    ),
                ));
            };
            validate_field_type(&field.ty, info, query, &field.name, outer_join_may_null)?;
        } else {
            let expression = select_projection_expression(&query.sql, &field.name).ok_or_else(|| {
                CompileError::new(
                    query.span,
                    format!(
                        "query `{}` computes `{}`; give computed columns a named `AS {}` projection",
                        query.name, field.name, field.name
                    ),
                )
            })?;
            let info = infer_expression_column_info(
                connection,
                &access_snapshot.reads,
                &expression,
                outer_join_may_null,
                query.span,
                query,
                &field.name,
            )?;
            validate_field_type(&field.ty, &info, query, &field.name, outer_join_may_null)?;
        }
    }
    Ok(access_snapshot.reads)
}

fn select_projection_expression(sql: &str, column_name: &str) -> Option<String> {
    let select = find_top_level_keyword(sql, "SELECT", 0)?;
    let list_start = select + "SELECT".len();
    let from = find_top_level_keyword(sql, "FROM", list_start)?;
    for projection in split_top_level_commas(&sql[list_start..from]) {
        let Some(alias_start) = find_top_level_keyword(projection, "AS", 0) else {
            continue;
        };
        let alias = projection[alias_start + 2..]
            .trim()
            .trim_end_matches(';')
            .trim()
            .trim_matches('"')
            .trim_matches('`')
            .trim_matches('[')
            .trim_matches(']');
        if alias.eq_ignore_ascii_case(column_name) {
            return Some(projection[..alias_start].trim().to_owned());
        }
    }
    None
}

fn find_top_level_keyword(sql: &str, keyword: &str, start: usize) -> Option<usize> {
    let bytes = sql.as_bytes();
    let keyword = keyword.as_bytes();
    let mut cursor = start;
    let mut depth = 0_usize;
    let mut quote = None;
    let mut line_comment = false;
    let mut block_comment = false;
    while cursor < bytes.len() {
        let byte = bytes[cursor];
        if line_comment {
            if byte == b'\n' {
                line_comment = false;
            }
            cursor += 1;
            continue;
        }
        if block_comment {
            if byte == b'*' && bytes.get(cursor + 1) == Some(&b'/') {
                block_comment = false;
                cursor += 2;
            } else {
                cursor += 1;
            }
            continue;
        }
        if let Some(delimiter) = quote {
            if byte == delimiter {
                if bytes.get(cursor + 1) == Some(&delimiter) {
                    cursor += 2;
                    continue;
                }
                quote = None;
            }
            cursor += 1;
            continue;
        }
        if byte == b'-' && bytes.get(cursor + 1) == Some(&b'-') {
            line_comment = true;
            cursor += 2;
            continue;
        }
        if byte == b'/' && bytes.get(cursor + 1) == Some(&b'*') {
            block_comment = true;
            cursor += 2;
            continue;
        }
        if matches!(byte, b'\'' | b'"' | b'`') {
            quote = Some(byte);
            cursor += 1;
            continue;
        }
        if byte == b'[' {
            quote = Some(b']');
            cursor += 1;
            continue;
        }
        if byte == b'(' {
            depth += 1;
            cursor += 1;
            continue;
        }
        if byte == b')' {
            depth = depth.saturating_sub(1);
            cursor += 1;
            continue;
        }
        if depth == 0
            && bytes
                .get(cursor..cursor + keyword.len())
                .is_some_and(|found| found.eq_ignore_ascii_case(keyword))
            && (cursor == 0 || !is_sql_identifier_byte(bytes[cursor - 1]))
            && bytes
                .get(cursor + keyword.len())
                .is_none_or(|next| !is_sql_identifier_byte(*next))
        {
            return Some(cursor);
        }
        cursor += 1;
    }
    None
}

fn split_top_level_commas(sql: &str) -> Vec<&str> {
    let bytes = sql.as_bytes();
    let mut pieces = Vec::new();
    let mut start = 0;
    let mut cursor = 0;
    let mut depth = 0_usize;
    let mut quote = None;
    let mut line_comment = false;
    let mut block_comment = false;
    while cursor < bytes.len() {
        let byte = bytes[cursor];
        if line_comment {
            if byte == b'\n' {
                line_comment = false;
            }
            cursor += 1;
            continue;
        }
        if block_comment {
            if byte == b'*' && bytes.get(cursor + 1) == Some(&b'/') {
                block_comment = false;
                cursor += 2;
            } else {
                cursor += 1;
            }
            continue;
        }
        if let Some(delimiter) = quote {
            if byte == delimiter {
                if bytes.get(cursor + 1) == Some(&delimiter) {
                    cursor += 2;
                    continue;
                }
                quote = None;
            }
            cursor += 1;
            continue;
        }
        if byte == b'-' && bytes.get(cursor + 1) == Some(&b'-') {
            line_comment = true;
            cursor += 2;
            continue;
        }
        if byte == b'/' && bytes.get(cursor + 1) == Some(&b'*') {
            block_comment = true;
            cursor += 2;
            continue;
        }
        if matches!(byte, b'\'' | b'"' | b'`') {
            quote = Some(byte);
            cursor += 1;
            continue;
        }
        if byte == b'[' {
            quote = Some(b']');
            cursor += 1;
            continue;
        }
        match byte {
            b'(' => depth += 1,
            b')' => depth = depth.saturating_sub(1),
            b',' if depth == 0 => {
                pieces.push(sql[start..cursor].trim());
                start = cursor + 1;
            }
            _ => {}
        }
        cursor += 1;
    }
    if start < sql.len() || !sql.trim().is_empty() {
        pieces.push(sql[start..].trim());
    }
    pieces
}

fn is_sql_identifier_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'$'
}

fn infer_expression_column_info(
    connection: &Connection,
    tables: &BTreeSet<String>,
    expression: &str,
    outer_join_may_null: bool,
    span: nexa_diagnostics::Span,
    query: &DatabaseQueryDecl,
    field_name: &str,
) -> Result<ColumnInfo, CompileError> {
    let Some(info) = infer_sql_expression(connection, tables, expression, outer_join_may_null)
    else {
        return Err(CompileError::new(
            span,
            format!(
                "query `{}` cannot prove the type of computed column `{field_name}`; use a direct schema column or an explicitly typed `CAST`",
                query.name
            ),
        ));
    };
    Ok(info)
}

fn infer_sql_expression(
    connection: &Connection,
    tables: &BTreeSet<String>,
    expression: &str,
    outer_join_may_null: bool,
) -> Option<ColumnInfo> {
    let expression = strip_outer_parentheses(expression.trim());
    if expression.is_empty() {
        return None;
    }
    if expression.eq_ignore_ascii_case("NULL") {
        return Some(expression_info("NULL", false));
    }
    if matches!(expression.to_ascii_uppercase().as_str(), "TRUE" | "FALSE") {
        return Some(expression_info("INTEGER", true));
    }
    if expression.parse::<i64>().is_ok() {
        return Some(expression_info("INTEGER", true));
    }
    if expression.parse::<f64>().is_ok() {
        return Some(expression_info("REAL", true));
    }
    if is_sql_string_literal(expression) {
        return Some(expression_info("TEXT", true));
    }

    if let Some(inner) = function_inner(expression, "CAST") {
        let as_position = find_top_level_keyword(inner, "AS", 0)?;
        let value = infer_sql_expression(
            connection,
            tables,
            inner[..as_position].trim(),
            outer_join_may_null,
        )?;
        let cast_type = inner[as_position + 2..].trim();
        let cast_type = cast_type.split_whitespace().next()?.to_ascii_uppercase();
        let declared = match cast_type.as_str() {
            "INT" | "INTEGER" | "SMALLINT" | "BIGINT" => "INTEGER",
            "REAL" | "FLOAT" | "DOUBLE" => "REAL",
            "TEXT" | "CHAR" | "VARCHAR" => "TEXT",
            "BLOB" => "BLOB",
            "NUMERIC" => "NUMERIC",
            _ => return None,
        };
        return Some(expression_info(declared, value.not_null));
    }

    if let Some(arguments) = function_arguments(expression, "COALESCE")
        .or_else(|| function_arguments(expression, "IFNULL"))
    {
        let values = arguments
            .iter()
            .map(|argument| infer_sql_expression(connection, tables, argument, outer_join_may_null))
            .collect::<Option<Vec<_>>>()?;
        let non_null_values = values
            .iter()
            .filter(|value| !value.declared_type.eq_ignore_ascii_case("NULL"))
            .cloned()
            .collect::<Vec<_>>();
        let declared = merge_expression_types(&non_null_values)?;
        return Some(expression_info(
            declared,
            values.iter().any(|value| value.not_null),
        ));
    }

    if let Some((arms, has_else)) = case_result_expressions(expression) {
        let mut values = arms
            .iter()
            .map(|arm| infer_sql_expression(connection, tables, arm, outer_join_may_null))
            .collect::<Option<Vec<_>>>()?;
        if !has_else {
            values.push(expression_info("NULL", false));
        }
        let non_null_values = values
            .iter()
            .filter(|value| !value.declared_type.eq_ignore_ascii_case("NULL"))
            .cloned()
            .collect::<Vec<_>>();
        let declared = merge_expression_types(&non_null_values)?;
        return Some(expression_info(
            declared,
            values.iter().all(|value| value.not_null),
        ));
    }

    if let Some(inner) = expression
        .strip_prefix('(')
        .and_then(|value| value.strip_suffix(')'))
        .or_else(|| expression.strip_prefix("SELECT "))
        .filter(|inner| inner.trim_start().starts_with("SELECT "))
    {
        let query = if expression.trim_start().starts_with("SELECT ") {
            expression
        } else {
            inner
        };
        let projection_start = find_top_level_keyword(query, "SELECT", 0)? + "SELECT".len();
        let projection_end = find_top_level_keyword(query, "FROM", projection_start)?;
        let projection = query[projection_start..projection_end].trim();
        let value = infer_sql_expression(connection, tables, projection, outer_join_may_null)?;
        if sql_keywords(projection).iter().any(|word| word == "COUNT") {
            return Some(value);
        }
        return Some(expression_info(&value.declared_type, false));
    }

    let words = sql_keywords(expression);
    if words
        .iter()
        .any(|word| word == "COUNT" || word == "ROW_NUMBER")
    {
        return Some(expression_info("INTEGER", true));
    }
    if let Some(arguments) = function_arguments(expression, "TOTAL") {
        if arguments.is_empty() {
            return None;
        }
        return Some(expression_info("REAL", true));
    }
    for name in ["AVG", "SUM", "MIN", "MAX", "LENGTH", "ABS", "ROUND"] {
        let Some(arguments) = function_arguments(expression, name) else {
            continue;
        };
        let argument = arguments.first()?;
        let value = infer_sql_expression(connection, tables, argument, outer_join_may_null)?;
        let affinity = sqlite_affinity(&value.declared_type);
        let (declared, not_null) = match name {
            "AVG" => ("REAL", false),
            "LENGTH" => ("INTEGER", value.not_null),
            "ROUND" => ("REAL", value.not_null),
            "ABS" => (declared_numeric_type(&affinity)?, value.not_null),
            "SUM" | "MIN" | "MAX" => (declared_numeric_type(&affinity)?, false),
            _ => return None,
        };
        return Some(expression_info(declared, not_null));
    }

    if let Some((left, operator, right)) = split_top_level_arithmetic(expression) {
        let left = infer_sql_expression(connection, tables, left, outer_join_may_null)?;
        let right = infer_sql_expression(connection, tables, right, outer_join_may_null)?;
        let left_affinity = sqlite_affinity(&left.declared_type);
        let right_affinity = sqlite_affinity(&right.declared_type);
        if !is_numeric_affinity(&left_affinity) || !is_numeric_affinity(&right_affinity) {
            return None;
        }
        let result_type = if operator == b'/'
            || left_affinity == SqliteAffinity::Real
            || right_affinity == SqliteAffinity::Real
        {
            "REAL"
        } else if left_affinity == SqliteAffinity::Numeric
            || right_affinity == SqliteAffinity::Numeric
        {
            "NUMERIC"
        } else {
            "INTEGER"
        };
        return Some(expression_info(
            result_type,
            left.not_null && right.not_null,
        ));
    }

    if let Some(operand) = expression
        .strip_prefix('-')
        .or_else(|| expression.strip_prefix('+'))
    {
        let value = infer_sql_expression(connection, tables, operand, outer_join_may_null)?;
        if !is_numeric_affinity(&sqlite_affinity(&value.declared_type)) {
            return None;
        }
        return Some(value);
    }

    if let Some(column_name) = expression_column_name(expression) {
        let mut found: Option<ColumnInfo> = None;
        for table in tables {
            let Ok(column) = column_info(connection, table, column_name) else {
                continue;
            };
            if found.as_ref().is_some_and(|previous| {
                sqlite_affinity(&previous.declared_type) != sqlite_affinity(&column.declared_type)
            }) {
                return None;
            }
            found = Some(column);
        }
        if let Some(mut column) = found {
            if outer_join_may_null {
                column.not_null = false;
                column.integer_primary_key = false;
            } else if column.integer_primary_key {
                column.not_null = true;
            }
            return Some(column);
        }
    }
    None
}

fn expression_info(declared_type: &str, not_null: bool) -> ColumnInfo {
    ColumnInfo {
        declared_type: declared_type.to_owned(),
        not_null,
        integer_primary_key: false,
    }
}

fn declared_numeric_type(affinity: &SqliteAffinity) -> Option<&'static str> {
    match affinity {
        SqliteAffinity::Integer => Some("INTEGER"),
        SqliteAffinity::Real => Some("REAL"),
        SqliteAffinity::Numeric => Some("NUMERIC"),
        SqliteAffinity::Text | SqliteAffinity::Blob => None,
    }
}

fn is_numeric_affinity(affinity: &SqliteAffinity) -> bool {
    matches!(
        affinity,
        SqliteAffinity::Integer | SqliteAffinity::Real | SqliteAffinity::Numeric
    )
}

fn merge_expression_types(values: &[ColumnInfo]) -> Option<&'static str> {
    let mut result: Option<SqliteAffinity> = None;
    for value in values {
        let next = sqlite_affinity(&value.declared_type);
        if let Some(current) = &result
            && current != &next
            && !(is_numeric_affinity(current) && is_numeric_affinity(&next))
        {
            return None;
        }
        result = Some(match result {
            Some(SqliteAffinity::Real) => SqliteAffinity::Real,
            Some(SqliteAffinity::Numeric) => SqliteAffinity::Numeric,
            Some(SqliteAffinity::Integer) if next == SqliteAffinity::Real => SqliteAffinity::Real,
            Some(SqliteAffinity::Integer) if next == SqliteAffinity::Numeric => {
                SqliteAffinity::Numeric
            }
            Some(current) => current,
            None => next,
        });
    }
    match result? {
        SqliteAffinity::Integer => Some("INTEGER"),
        SqliteAffinity::Real => Some("REAL"),
        SqliteAffinity::Text => Some("TEXT"),
        SqliteAffinity::Blob => Some("BLOB"),
        SqliteAffinity::Numeric => Some("NUMERIC"),
    }
}

fn strip_outer_parentheses(mut expression: &str) -> &str {
    loop {
        let Some(inner) = expression
            .strip_prefix('(')
            .and_then(|value| value.strip_suffix(')'))
        else {
            return expression;
        };
        let mut depth = 0_i32;
        let mut closes_at_end = true;
        for (index, byte) in expression.bytes().enumerate() {
            match byte {
                b'(' => depth += 1,
                b')' => {
                    depth -= 1;
                    if depth == 0 && index + 1 != expression.len() {
                        closes_at_end = false;
                        break;
                    }
                }
                _ => {}
            }
        }
        if !closes_at_end || depth != 0 {
            return expression;
        }
        expression = inner.trim();
    }
}

fn is_sql_string_literal(expression: &str) -> bool {
    expression.len() >= 2 && expression.starts_with('\'') && expression.ends_with('\'')
}

fn expression_column_name(expression: &str) -> Option<&str> {
    let segment = expression.rsplit('.').next()?.trim();
    let segment = segment
        .strip_prefix('[')
        .and_then(|value| value.strip_suffix(']'))
        .or_else(|| {
            segment
                .strip_prefix('"')
                .and_then(|value| value.strip_suffix('"'))
        })
        .or_else(|| {
            segment
                .strip_prefix('`')
                .and_then(|value| value.strip_suffix('`'))
        })
        .unwrap_or(segment);
    (!segment.is_empty() && segment.bytes().all(is_sql_identifier_byte)).then_some(segment)
}

fn function_inner<'a>(expression: &'a str, name: &str) -> Option<&'a str> {
    let expression = expression.trim();
    let prefix = expression.get(..name.len())?;
    if !prefix.eq_ignore_ascii_case(name) {
        return None;
    }
    expression[name.len()..]
        .trim_start()
        .strip_prefix('(')?
        .strip_suffix(')')
        .map(str::trim)
}

fn function_arguments<'a>(expression: &'a str, name: &str) -> Option<Vec<&'a str>> {
    Some(split_top_level_commas(function_inner(expression, name)?))
}

fn split_top_level_arithmetic(expression: &str) -> Option<(&str, u8, &str)> {
    let bytes = expression.as_bytes();
    let mut cursor = 0;
    let mut depth = 0_i32;
    let mut quote = None;
    let mut previous_nonspace = None;
    while cursor < bytes.len() {
        let byte = bytes[cursor];
        if let Some(delimiter) = quote {
            if byte == delimiter {
                if bytes.get(cursor + 1) == Some(&delimiter) {
                    cursor += 2;
                    continue;
                }
                quote = None;
            }
            cursor += 1;
            continue;
        }
        if matches!(byte, b'\'' | b'"' | b'`') {
            quote = Some(byte);
        } else if byte == b'[' {
            quote = Some(b']');
        } else if byte == b'(' {
            depth += 1;
        } else if byte == b')' {
            depth -= 1;
        } else if depth == 0 && matches!(byte, b'+' | b'-' | b'*' | b'/' | b'%') {
            let unary = previous_nonspace
                .is_none_or(|previous| matches!(previous, b'(' | b'+' | b'-' | b'*' | b'/' | b'%'));
            if !unary {
                let left = expression[..cursor].trim();
                let right = expression[cursor + 1..].trim();
                if !left.is_empty() && !right.is_empty() {
                    return Some((left, byte, right));
                }
            }
        }
        if !byte.is_ascii_whitespace() {
            previous_nonspace = Some(byte);
        }
        cursor += 1;
    }
    None
}

fn case_result_expressions(expression: &str) -> Option<(Vec<&str>, bool)> {
    let bytes = expression.as_bytes();
    let mut cursor = 0;
    let mut case_depth = 0_i32;
    let mut parentheses = 0_i32;
    let mut quote = None;
    let mut line_comment = false;
    let mut block_comment = false;
    let mut result_start = None;
    let mut has_else = false;
    let mut results = Vec::new();
    while cursor < bytes.len() {
        let byte = bytes[cursor];
        if line_comment {
            if byte == b'\n' {
                line_comment = false;
            }
            cursor += 1;
            continue;
        }
        if block_comment {
            if byte == b'*' && bytes.get(cursor + 1) == Some(&b'/') {
                block_comment = false;
                cursor += 2;
            } else {
                cursor += 1;
            }
            continue;
        }
        if let Some(delimiter) = quote {
            if byte == delimiter {
                if bytes.get(cursor + 1) == Some(&delimiter) {
                    cursor += 2;
                    continue;
                }
                quote = None;
            }
            cursor += 1;
            continue;
        }
        if byte == b'-' && bytes.get(cursor + 1) == Some(&b'-') {
            line_comment = true;
            cursor += 2;
            continue;
        }
        if byte == b'/' && bytes.get(cursor + 1) == Some(&b'*') {
            block_comment = true;
            cursor += 2;
            continue;
        }
        if matches!(byte, b'\'' | b'"' | b'`') {
            quote = Some(byte);
            cursor += 1;
            continue;
        }
        if byte == b'[' {
            quote = Some(b']');
            cursor += 1;
            continue;
        }
        if byte.is_ascii_alphabetic() || byte == b'_' {
            let start = cursor;
            cursor += 1;
            while bytes
                .get(cursor)
                .is_some_and(|next| next.is_ascii_alphanumeric() || *next == b'_')
            {
                cursor += 1;
            }
            if parentheses == 0 {
                match expression[start..cursor].to_ascii_uppercase().as_str() {
                    "CASE" => case_depth += 1,
                    "WHEN" if case_depth == 1 => {
                        if let Some(value_start) = result_start.take() {
                            results.push(expression[value_start..start].trim());
                        }
                    }
                    "THEN" if case_depth == 1 => result_start = Some(cursor),
                    "ELSE" if case_depth == 1 => {
                        let value_start = result_start.take()?;
                        results.push(expression[value_start..start].trim());
                        result_start = Some(cursor);
                        has_else = true;
                    }
                    "END" if case_depth == 1 => {
                        let value_start = result_start.take()?;
                        results.push(expression[value_start..start].trim());
                        return (!results.is_empty()).then_some((results, has_else));
                    }
                    "END" if case_depth > 1 => case_depth -= 1,
                    _ => {}
                }
            }
            continue;
        }
        match byte {
            b'(' => parentheses += 1,
            b')' => parentheses -= 1,
            _ => {}
        }
        cursor += 1;
    }
    None
}

#[cfg(test)]
mod ordinary_call_tests {
    use super::validate_ordinary_calls;

    fn app_from_source(source: &str) -> nexa_syntax::ast::App {
        let mut program = nexa_syntax::parse_program(source).expect("valid test source");
        let mut app = program.app.take().expect("app declaration");
        app.plugins = program.plugins;
        app.structs = program.structs;
        app.globals = program.globals;
        app.functions = program.functions;
        app.classes = program.classes;
        app.enums = program.enums;
        app.components = program.components;
        app.screens = program.screens;
        // The app body is retained. It was previously dropped here, so every test
        // in this module exercised declarations only -- while real applications
        // put `migrate`, writes, and queries in the body's `OnAppear async`
        // block. That omission hid the entire node-walking path.
        app
    }

    const SOURCE: &str = r#"
        plugin "dev.nexa.sqlite" as SQLite

        struct Task {
            id: Int64,
            title: String,
        }

        let migrations = [
            SQLite.Migration(1, [
                "CREATE TABLE tasks (id INTEGER PRIMARY KEY, title TEXT NOT NULL)"
            ])
        ]
        let database = SQLite.Database("todo", false)

        fn prepare() -> Void {
            let ignored = await database.migrate(migrations)
        }

        fn find(id: Int64) -> Void {
            let ignored = await database.query<Task>(
                "SELECT id, title FROM tasks WHERE id = ?",
                [id]
            )
        }

        app Todo {
            body { Text("ready") }
        }
    "#;

    #[test]
    fn validates_ordinary_database_calls_and_migration_history() {
        let app = app_from_source(SOURCE);
        let databases =
            validate_ordinary_calls(&app, "SQLite").expect("ordinary calls should validate");
        assert_eq!(databases.len(), 1);
        assert_eq!(databases[0].migrations.len(), 1);
        assert_eq!(databases[0].queries.len(), 1);
    }

    #[test]
    fn infers_nested_case_coalesce_aggregate_arithmetic_and_subquery_results() {
        let source = r#"
            plugin "dev.nexa.sqlite" as SQLite
            struct Metric { value: Int64 }
            let migrations = [SQLite.Migration(1, [
                "CREATE TABLE tasks (id INTEGER PRIMARY KEY, title TEXT NOT NULL)"
            ])]
            let database = SQLite.Database("todo", false)
            fn inspect() -> Void {
                let ignored = await database.migrate(migrations)
                let rows: Array<Metric> = await database.query<Metric>(
                    "SELECT CASE WHEN id > 0 THEN COALESCE(MAX(id), 0) + (SELECT COUNT(*) FROM tasks) ELSE CAST(0 AS INTEGER) END AS value FROM tasks",
                    []
                )
            }
            app Todo { body { Text("ready") } }
        "#;
        let app = app_from_source(source);
        validate_ordinary_calls(&app, "SQLite")
            .expect("the computed integer expression should be provable");
    }

    #[test]
    fn rejects_computed_case_expressions_with_mixed_result_affinities() {
        let source = r#"
            plugin "dev.nexa.sqlite" as SQLite
            struct Label { value: String }
            let migrations = [SQLite.Migration(1, [
                "CREATE TABLE tasks (id INTEGER PRIMARY KEY, title TEXT NOT NULL)"
            ])]
            let database = SQLite.Database("todo", false)
            fn inspect() -> Void {
                let ignored = await database.migrate(migrations)
                let rows: Array<Label> = await database.query<Label>(
                    "SELECT CASE WHEN id > 0 THEN 'yes' ELSE 0 END AS value FROM tasks",
                    []
                )
            }
            app Todo { body { Text("ready") } }
        "#;
        let app = app_from_source(source);
        let error = validate_ordinary_calls(&app, "SQLite")
            .expect_err("mixed CASE result affinities should not be guessed");
        assert!(
            error
                .message
                .contains("cannot prove the type of computed column")
        );
    }

    #[test]
    fn checks_sql_calls_in_state_initializers() {
        let source = SOURCE.replace(
            "app Todo {\n            body",
            "app Todo {\n            state tasks = database.query<Task>(\"SELECT id, title FROM tasks\", [])\n            body",
        );
        let app = app_from_source(&source);
        let databases = validate_ordinary_calls(&app, "SQLite")
            .expect("state initializers should receive SQL validation");
        assert_eq!(databases[0].queries.len(), 2);
    }

    #[test]
    fn reports_positional_bind_slot_mismatch_at_call_site() {
        let source = SOURCE.replace(
            "\"SELECT id, title FROM tasks WHERE id = ?\",\n                [id]",
            "\"SELECT id, title FROM tasks WHERE id = ? AND title = ?\",\n                [id]",
        );
        let app = app_from_source(&source);
        let error = validate_ordinary_calls(&app, "SQLite").expect_err("bind mismatch should fail");
        assert!(error.message.contains("2 bind slot(s)"));
    }

    #[test]
    fn requires_literal_sql_for_typed_queries() {
        let source = SOURCE.replace(
            "\"SELECT id, title FROM tasks WHERE id = ?\",\n                [id]",
            "queryText,\n                [id]",
        );
        let app = app_from_source(&source);
        let error =
            validate_ordinary_calls(&app, "SQLite").expect_err("dynamic SQL should use queryRaw");
        assert!(error.message.contains("use `queryRaw` for dynamic SQL"));
    }

    #[test]
    fn validates_each_static_batch_row_shape_and_type() {
        let source = SOURCE.replace(
            "app Todo {\n            body",
            "app Todo {\n            state tasks = database.query<Task>(\"SELECT id, title FROM tasks\", [])\n            body",
        )
        .replace(
            "fn find(id: Int64) -> Void {",
            "fn insert() -> Void {\n            let result = await database.executeBatch(\"INSERT INTO tasks(id, title) VALUES (?, ?)\", [[1, \"first\"], [\"wrong\", \"second\"]])\n        }\n\n        fn find(id: Int64) -> Void {",
        );
        let app = app_from_source(&source);
        let error =
            validate_ordinary_calls(&app, "SQLite").expect_err("mismatched batch rows should fail");
        assert!(error.message.contains("different value types or arity"));
    }

    #[test]
    fn rejects_conflicting_widget_sharing_for_one_static_database_id() {
        let source = SOURCE.replace(
            "let database = SQLite.Database(\"todo\", false)",
            "let database = SQLite.Database(\"todo\", false)\n        let sharedDatabase = SQLite.Database(\"todo\", true)",
        )
        .replace(
            "fn find(id: Int64) -> Void {",
            "fn inspect() -> Void {\n            let tasks = await sharedDatabase.query<Task>(\"SELECT id, title FROM tasks\", [])\n        }\n\n        fn find(id: Int64) -> Void {",
        );
        let app = app_from_source(&source);
        let error =
            validate_ordinary_calls(&app, "SQLite").expect_err("sharing mode conflict should fail");
        assert!(
            error
                .message
                .contains("conflicting `sharedWithWidgets` values")
        );
    }
}

fn validate_command(
    connection: &Connection,
    access: &Arc<Mutex<SqlAccess>>,
    command: &DatabaseCommandDecl,
    positional_parameters: bool,
) -> Result<(Vec<String>, Vec<String>), CompileError> {
    if has_trailing_statement(&command.sql) {
        return Err(CompileError::new(
            command.span,
            format!(
                "command `{}` must contain exactly one SQL statement",
                command.name
            ),
        ));
    }
    let params = declared_parameters(&command.parameters, &command.name)?;
    reset_access(access, command.span)?;
    let statement = connection.prepare(&command.sql).map_err(|error| {
        CompileError::new(
            command.span,
            format!("invalid SQL in command `{}`: {error}", command.name),
        )
    })?;
    if statement.readonly() || statement.column_count() > 0 {
        return Err(CompileError::new(
            command.span,
            format!(
                "command `{}` must be a write statement without returned rows",
                command.name
            ),
        ));
    }
    let parameter_order = if command.allow_dynamic_parameters {
        (1..=statement.parameter_count())
            .map(|index| format!("${}", index - 1))
            .collect()
    } else if positional_parameters {
        validate_positional_parameter_slots(&statement, params.len(), command.span, &command.name)?
    } else {
        validate_parameter_slots(&statement, &params, command.span, &command.name)?
    };
    let access = access_snapshot(access, command.span)?;
    if access.writes.is_empty() || access.uses_outer_schema {
        return Err(CompileError::new(
            command.span,
            format!(
                "command `{}` must write only to this database's declared tables",
                command.name
            ),
        ));
    }
    Ok((parameter_order, access.writes.into_iter().collect()))
}

fn declared_parameters<'a>(
    parameters: &'a [nexa_syntax::ast::FunctionParameter],
    operation: &str,
) -> Result<HashMap<&'a str, &'a TypeSyntax>, CompileError> {
    let mut declared = HashMap::with_capacity(parameters.len());
    for parameter in parameters {
        if !is_supported_parameter_type(&parameter.ty) {
            return Err(CompileError::new(
                parameter.span,
                format!(
                    "SQL parameter `{}` in `{operation}` must use a SQLite scalar type",
                    parameter.name
                ),
            ));
        }
        if declared
            .insert(parameter.name.as_str(), &parameter.ty)
            .is_some()
        {
            return Err(CompileError::new(
                parameter.span,
                format!(
                    "SQL parameter `{}` is declared more than once in `{operation}`",
                    parameter.name
                ),
            ));
        }
    }
    Ok(declared)
}

fn is_supported_parameter_type(ty: &TypeSyntax) -> bool {
    let scalar = match ty {
        TypeSyntax::Optional(inner, _) => inner.as_ref(),
        other => other,
    };
    matches!(
        scalar,
        TypeSyntax::Named(name, _)
            if matches!(name.as_str(), "String" | "Bool" | "Int32" | "Int64" | "Float64" | "Bytes")
    )
}

fn validate_parameter_slots(
    statement: &rusqlite::Statement<'_>,
    declared: &HashMap<&str, &TypeSyntax>,
    span: nexa_diagnostics::Span,
    operation: &str,
) -> Result<Vec<String>, CompileError> {
    let mut used = HashSet::with_capacity(statement.parameter_count());
    let mut raw_names = HashMap::<&str, &str>::with_capacity(statement.parameter_count());
    let mut ordered = Vec::with_capacity(statement.parameter_count());
    for index in 1..=statement.parameter_count() {
        let Some(raw_name) = statement.parameter_name(index) else {
            return Err(CompileError::new(
                span,
                format!("SQL operation `{operation}` must use named parameters such as `:id`"),
            ));
        };
        let name = raw_name.trim_start_matches([':', '@', '$']);
        if !declared.contains_key(name) {
            return Err(CompileError::new(
                span,
                format!("SQL operation `{operation}` uses undeclared parameter `{name}`"),
            ));
        }
        if let Some(previous_raw_name) = raw_names.get(name)
            && *previous_raw_name != raw_name
        {
            return Err(CompileError::new(
                span,
                format!(
                    "SQL operation `{operation}` binds more than one SQLite parameter slot to Nexa argument `{name}`; use one consistent SQLite parameter name"
                ),
            ));
        }
        raw_names.insert(name, raw_name);
        used.insert(name);
        ordered.push(name.to_owned());
    }
    if let Some(missing) = declared.keys().find(|name| !used.contains(**name)) {
        return Err(CompileError::new(
            span,
            format!("SQL operation `{operation}` declares unused parameter `{missing}`"),
        ));
    }
    Ok(ordered)
}

fn validate_positional_parameter_slots(
    statement: &rusqlite::Statement<'_>,
    declared_count: usize,
    span: nexa_diagnostics::Span,
    operation: &str,
) -> Result<Vec<String>, CompileError> {
    let count = statement.parameter_count();
    if count != declared_count {
        return Err(CompileError::new(
            span,
            format!(
                "SQL operation `{operation}` has {count} bind slot(s), but its parameter array contains {declared_count} value(s)"
            ),
        ));
    }
    Ok((0..count).map(|index| format!("${index}")).collect())
}

/// Validates ordinary SQLite plugin calls against statically declared handles
/// and migration arrays. This deliberately resolves only immutable top-level
/// and class-static bindings, plus a single-name alias to one of those values;
/// it does not attempt whole-program data-flow analysis.
pub(crate) fn validate_ordinary_calls(
    app: &ast::App,
    namespace: &str,
) -> Result<Vec<ValidatedDatabase>, CompileError> {
    let handle_aliases = resolve_static_database_handles(app, namespace)?;
    if handle_aliases.is_empty() {
        return Ok(Vec::new());
    }

    let migration_values = resolve_static_migrations(app, namespace);
    let mut databases = HashMap::<String, DatabaseDecl>::new();
    let mut module_environment = HashMap::<String, TypeSyntax>::new();
    for function in &app.functions {
        module_environment.insert(
            format!("{FUNCTION_RETURN_TYPE_PREFIX}{}", function.name),
            function.return_type.clone(),
        );
    }
    for class in &app.classes {
        for function in class.methods.iter().chain(&class.static_methods) {
            module_environment.insert(
                format!(
                    "{FUNCTION_RETURN_TYPE_PREFIX}{}.{}",
                    class.name, function.name
                ),
                function.return_type.clone(),
            );
        }
    }
    for state in app.globals.iter().chain(&app.states) {
        if let Some(ty) = state
            .ty
            .clone()
            .or_else(|| infer_static_type(&state.initial, &module_environment, &app.structs))
        {
            module_environment.insert(state.name.clone(), ty);
        }
    }
    for class in &app.classes {
        for field in &class.static_fields {
            if let Some(ty) = field
                .ty
                .clone()
                .or_else(|| infer_static_type(&field.initial, &module_environment, &app.structs))
            {
                module_environment.insert(format!("{}.{}", class.name, field.name), ty);
            }
        }
    }

    let current_source_file = std::cell::RefCell::new(None::<String>);
    let mut inspect_call = |expression: &ast::Expr,
                            environment: &HashMap<String, TypeSyntax>,
                            expected_type: Option<&TypeSyntax>|
     -> Result<(), CompileError> {
        let ast::Expr::MethodCall {
            base,
            name,
            type_arguments,
            arguments,
            named_arguments,
            span,
        } = expression
        else {
            return Ok(());
        };
        let Some(handle_name) = expression_reference_name(base) else {
            return Ok(());
        };
        let Some((database_id, shared_with_widgets)) = handle_aliases.get(&handle_name) else {
            return Ok(());
        };
        if let Some(database) = databases.get(database_id)
            && database.shared_with_widgets != *shared_with_widgets
        {
            return Err(CompileError::new(
                *span,
                format!(
                    "database `{database_id}` is opened with conflicting `sharedWithWidgets` values"
                ),
            ));
        }
        if name == "queryRaw" || name == "executeRaw" {
            return Ok(());
        }
        let database_key = database_id.clone();
        let database = databases
            .entry(database_key.clone())
            .or_insert_with(|| DatabaseDecl {
                name: format!("SQLiteDatabase_{}", sanitize_database_name(&database_key)),
                database_id: database_key.clone(),
                shared_with_widgets: *shared_with_widgets,
                migrations: Vec::new(),
                queries: Vec::new(),
                commands: Vec::new(),
                span: *span,
            });

        match name.as_str() {
            "migrate" => {
                let migrations_expr = call_argument(arguments, named_arguments, 0, "migrations")
                    .ok_or_else(|| {
                        CompileError::new(
                            *span,
                            "SQLite.Database.migrate requires a static migration array",
                        )
                    })?;
                let migrations = resolve_migration_argument(migrations_expr, &migration_values, namespace)
                    .ok_or_else(|| {
                        CompileError::new(
                            migrations_expr.span(),
                            "SQLite migrations must be a compile-time array of `SQLite.Migration` values; store the immutable array at file or class scope",
                        )
                    })?;
                if !database.migrations.is_empty()
                    && !same_migration_history(&database.migrations, &migrations)
                {
                    return Err(CompileError::new(
                        *span,
                        format!(
                            "database `{database_key}` is migrated with more than one static migration history"
                        ),
                    ));
                }
                database.migrations = migrations;
            }
            "query" | "observeQuery" => {
                let sql_expr =
                    call_argument(arguments, named_arguments, 0, "sql").ok_or_else(|| {
                        CompileError::new(*span, format!("SQLite.Database.{name} requires `sql`"))
                    })?;
                let sql = static_string(sql_expr).ok_or_else(|| {
                    CompileError::new(
                        sql_expr.span(),
                        "typed SQLite queries require a compile-time SQL string; use `queryRaw` for dynamic SQL",
                    )
                })?;
                let row_type = type_arguments
                    .first()
                    .or_else(|| expected_query_row_type(expected_type, &app.structs))
                    .ok_or_else(|| {
                        CompileError::new(
                            *span,
                            format!("typed SQLite queries need a row type; add a result annotation such as `database.{name}<Task>(...)` or specify `{name}<Task>(...)`"),
                        )
                    })?;
                let parameters_expr = call_argument(arguments, named_arguments, 1, "parameters")
                    .ok_or_else(|| {
                        CompileError::new(*span, format!("SQLite.Database.{name} requires a parameter array"))
                    })?;
                let inferred =
                    lenient_parameter_types(parameters_expr, environment, &app.structs)?;
                let parameter_count = bind_parameter_count(parameters_expr)?;
                let parameters = inferred.clone().unwrap_or_default();
                let result_type =
                    TypeSyntax::Generic("Array".to_owned(), vec![row_type.clone()], *span);
                database.queries.push(DatabaseQueryDecl {
                    name: format!("query_{}_{}", span.line, span.column),
                    parameters,
                    return_type: result_type,
                    sql,
                    span: *span,
                    source_file: current_source_file.borrow().clone(),
                    parameter_count,
                    allow_dynamic_parameters: inferred.is_none(),
                });
            }
            "execute" => {
                let sql_expr =
                    call_argument(arguments, named_arguments, 0, "sql").ok_or_else(|| {
                        CompileError::new(*span, "SQLite.Database.execute requires `sql`")
                    })?;
                let sql = static_string(sql_expr).ok_or_else(|| {
                    CompileError::new(
                        sql_expr.span(),
                        "SQLite `execute` requires a compile-time SQL string; use `executeRaw` for dynamic SQL",
                    )
                })?;
                let parameters_expr = call_argument(arguments, named_arguments, 1, "parameters")
                    .ok_or_else(|| {
                        CompileError::new(
                            *span,
                            "SQLite.Database.execute requires a parameter array",
                        )
                    })?;
                let inferred =
                    lenient_parameter_types(parameters_expr, environment, &app.structs)?;
                database.commands.push(DatabaseCommandDecl {
                    name: format!("execute_{}_{}", span.line, span.column),
                    parameters: inferred.clone().unwrap_or_default(),
                    sql,
                    span: *span,
                    allow_dynamic_parameters: inferred.is_none(),
                });
            }
            "executeBatch" => {
                let sql_expr =
                    call_argument(arguments, named_arguments, 0, "sql").ok_or_else(|| {
                        CompileError::new(*span, "SQLite.Database.executeBatch requires `sql`")
                    })?;
                let sql = static_string(sql_expr).ok_or_else(|| {
                    CompileError::new(
                        sql_expr.span(),
                        "SQLite `executeBatch` requires a compile-time SQL string",
                    )
                })?;
                let rows_expr =
                    call_argument(arguments, named_arguments, 1, "rows").ok_or_else(|| {
                        CompileError::new(*span, "SQLite.Database.executeBatch requires batch rows")
                    })?;
                let (parameters, allow_dynamic_parameters) = match rows_expr {
                    ast::Expr::Array(rows, _) if !rows.is_empty() => {
                        // Rows are compared against row 1. When any row's value
                        // types are out of the analyzer's reach -- a plugin call
                        // such as `Bytes.fromText(...)`, a member read from a
                        // contract struct -- the batch degrades to an arity
                        // comparison rather than being rejected, because
                        // disagreeing row widths are the mistake that actually
                        // happens here.
                        let first_arity = bind_parameter_count(&rows[0])?;
                        let first_types = lenient_parameter_types(&rows[0], environment, &app.structs)?;
                        for (row_index, row) in rows.iter().enumerate().skip(1) {
                            let row_arity = bind_parameter_count(row)?;
                            let row_types =
                                lenient_parameter_types(row, environment, &app.structs)?;
                            let mismatched = match (&first_types, &row_types) {
                                (Some(first), Some(current)) => !same_parameter_types(first, current),
                                _ => row_arity != first_arity,
                            };
                            if mismatched {
                                return Err(CompileError::new(
                                    row.span(),
                                    format!(
                                        "SQLite batch row {} has different value types or arity from row 1",
                                        row_index + 1
                                    ),
                                ));
                            }
                        }
                        let dynamic = first_types.is_none();
                        (first_types.unwrap_or_default(), dynamic)
                    }
                    ast::Expr::Array(_, _) => (Vec::new(), true),
                    _ => {
                        let row_type = infer_static_type(rows_expr, environment, &app.structs);
                        if !is_sql_value_matrix_type(row_type.as_ref(), namespace) {
                            return Err(CompileError::new(
                                rows_expr.span(),
                                "runtime SQLite batches must be typed as `Array<Array<SQLite.Value>>`",
                            ));
                        }
                        (Vec::new(), true)
                    }
                };
                database.commands.push(DatabaseCommandDecl {
                    name: format!("execute_batch_{}_{}", span.line, span.column),
                    parameters,
                    sql,
                    span: *span,
                    allow_dynamic_parameters,
                });
            }
            _ => return Ok(()),
        }
        Ok(())
    };

    // Initializers are expression contexts too. This is needed in particular
    // for reactive queries such as `state tasks = store.observeQuery(...)`.
    for state in app.globals.iter().chain(&app.states) {
        *current_source_file.borrow_mut() = state.source_file.clone();
        inspect_expression_calls_with_expected(
            &state.initial,
            &module_environment,
            state.ty.as_ref(),
            &mut inspect_call,
        )?;
    }
    for component in &app.components {
        // A component's own state types have to be visible while its body is
        // walked, or `todos = await store.query(...)` inside a `Button` action
        // block has no expected type and therefore no row type.
        let mut scope_environment = module_environment.clone();
        for parameter in &component.parameters {
            scope_environment.insert(parameter.name.clone(), parameter.ty.clone());
        }
        for state in &component.states {
            if let Some(ty) = state
                .ty
                .clone()
                .or_else(|| infer_static_type(&state.initial, &scope_environment, &app.structs))
            {
                scope_environment.insert(state.name.clone(), ty);
            }
        }
        for state in &component.states {
            *current_source_file.borrow_mut() = state.source_file.clone();
            inspect_expression_calls_with_expected(
                &state.initial,
                &scope_environment,
                state.ty.as_ref(),
                &mut inspect_call,
            )?;
        }
        inspect_nodes(
            &component.body,
            &scope_environment,
            &mut inspect_call,
            &app.structs,
        )?;
    }
    // The app body carries the lifecycle blocks (`OnAppear async { ... }`) that
    // own startup migrations and subscription attachment, so it must be walked
    // for the schema to be known at all.
    inspect_nodes(
        &app.body,
        &module_environment,
        &mut inspect_call,
        &app.structs,
    )?;
    for screen in &app.screens {
        let mut scope_environment = module_environment.clone();
        for parameter in &screen.parameters {
            scope_environment.insert(parameter.name.clone(), parameter.ty.clone());
        }
        for state in &screen.states {
            if let Some(ty) = state
                .ty
                .clone()
                .or_else(|| infer_static_type(&state.initial, &scope_environment, &app.structs))
            {
                scope_environment.insert(state.name.clone(), ty);
            }
        }
        for state in &screen.states {
            *current_source_file.borrow_mut() = state.source_file.clone();
            inspect_expression_calls_with_expected(
                &state.initial,
                &scope_environment,
                state.ty.as_ref(),
                &mut inspect_call,
            )?;
        }
        inspect_nodes(
            &screen.body,
            &scope_environment,
            &mut inspect_call,
            &app.structs,
        )?;
    }
    for class in &app.classes {
        for field in class.fields.iter().chain(&class.static_fields) {
            *current_source_file.borrow_mut() = class.source_file.clone();
            inspect_expression_calls_with_expected(
                &field.initial,
                &module_environment,
                field.ty.as_ref(),
                &mut inspect_call,
            )?;
        }
    }

    for function in &app.functions {
        *current_source_file.borrow_mut() = function.source_file.clone();
        let mut environment = module_environment.clone();
        environment.extend(
            function
                .parameters
                .iter()
                .map(|parameter| (parameter.name.clone(), parameter.ty.clone())),
        );
        environment.insert(RETURN_TYPE_KEY.to_owned(), function.return_type.clone());
        inspect_statements(
            &function.body,
            &mut environment,
            &mut inspect_call,
            &app.structs,
        )?;
    }
    for class in &app.classes {
        for function in class.methods.iter().chain(&class.static_methods) {
            *current_source_file.borrow_mut() = function.source_file.clone();
            let mut environment = module_environment.clone();
            environment.insert(RETURN_TYPE_KEY.to_owned(), function.return_type.clone());
            // A method body sees its own class's fields under their bare names,
            // which is how instance code refers to them. Without this a query
            // assigned to a field-typed target inside a method had no expected
            // type and therefore no row type.
            for field in class.fields.iter().chain(&class.static_fields) {
                if let Some(ty) = field
                    .ty
                    .clone()
                    .or_else(|| infer_static_type(&field.initial, &environment, &app.structs))
                {
                    environment.insert(field.name.clone(), ty);
                }
            }
            environment.extend(
                function
                    .parameters
                    .iter()
                    .map(|parameter| (parameter.name.clone(), parameter.ty.clone())),
            );
            inspect_statements(
                &function.body,
                &mut environment,
                &mut inspect_call,
                &app.structs,
            )?;
        }
    }

    let mut validated = Vec::with_capacity(databases.len());
    for database in databases.values() {
        if database.queries.is_empty()
            && database.commands.is_empty()
            && database.migrations.is_empty()
        {
            continue;
        }
        validated.push(validate_database(database, &app.structs, true)?);
    }
    Ok(validated)
}

fn expected_query_row_type<'a>(
    expected: Option<&'a TypeSyntax>,
    structs: &[StructDecl],
) -> Option<&'a TypeSyntax> {
    let expected = expected?;
    // Peels the containers a typed query's result can arrive in. `Signal<Array<T>>`
    // is what a reactive query returns; `Result<Array<T>, E>` is what a fallible
    // one returns from an idiomatic `Ok(await ... ?)` method. Handling only the
    // bare array meant the two shapes that real code actually uses both had to
    // carry an explicit `query<T>` type argument.
    let mut candidate = expected;
    loop {
        match candidate {
            TypeSyntax::Generic(name, arguments, _) if name == "Array" && arguments.len() == 1 => {
                candidate = &arguments[0];
                break;
            }
            TypeSyntax::Generic(name, arguments, _)
                if (name == "Signal" || name == "Result") && arguments.len() >= 1 =>
            {
                candidate = &arguments[0];
            }
            _ => return None,
        }
    }
    let TypeSyntax::Named(row_name, _) = candidate else {
        return None;
    };
    structs
        .iter()
        .any(|structure| structure.name == *row_name)
        .then_some(candidate)
}

fn is_sql_value_matrix_type(ty: Option<&TypeSyntax>, namespace: &str) -> bool {
    let Some(TypeSyntax::Generic(outer, outer_arguments, _)) = ty else {
        return false;
    };
    let [TypeSyntax::Generic(inner, inner_arguments, _)] = outer_arguments.as_slice() else {
        return false;
    };
    let [TypeSyntax::Named(value_type, _)] = inner_arguments.as_slice() else {
        return false;
    };
    outer == "Array"
        && inner == "Array"
        && (value_type == "Value" || value_type == &format!("{namespace}.Value"))
}

/// Walks a UI node tree, inspecting every expression it contains.
///
/// A database call is usually made inside an action or lifecycle block —
/// `OnAppear async { await database.migrate(...) }`, `Button("Save") { ... }`,
/// `.onRefresh { ... }`, a plugin event handler — not in a declaration. Before
/// this existed the analyzer inspected only declaration initializers and
/// function bodies, so every such call was invisible: writes were never
/// validated, and a database whose only `migrate` call sat in `OnAppear` was
/// reported as having no schema at all.
///
/// The walk is deliberately structural. It visits every expression and action
/// block it can reach, so a missed call becomes a missed *diagnostic* rather
/// than a wrong one.
fn inspect_nodes(
    nodes: &[ast::Node],
    environment: &HashMap<String, TypeSyntax>,
    inspect: &mut impl FnMut(
        &ast::Expr,
        &HashMap<String, TypeSyntax>,
        Option<&TypeSyntax>,
    ) -> Result<(), CompileError>,
    structs: &[StructDecl],
) -> Result<(), CompileError> {
    for node in nodes {
        match node {
            ast::Node::Platform { children, .. } => {
                inspect_nodes(children, environment, inspect, structs)?;
            }
            ast::Node::ComponentInvocation(invocation) => {
                for expression in &invocation.positional {
                    inspect_expression_calls(expression, environment, inspect)?;
                }
                for expression in invocation.arguments.values() {
                    inspect_expression_calls(expression, environment, inspect)?;
                }
                match &invocation.children {
                    ast::ChildBody::None => {}
                    ast::ChildBody::Nodes(children) => {
                        inspect_nodes(children, environment, inspect, structs)?;
                    }
                    ast::ChildBody::Actions(actions) => {
                        inspect_statements(actions, &mut environment.clone(), inspect, structs)?;
                    }
                    ast::ChildBody::Tabs(tabs) => {
                        for tab in tabs {
                            for expression in [
                                Some(&tab.index),
                                Some(&tab.label),
                                tab.icon.as_ref(),
                                tab.badge.as_ref(),
                                tab.role.as_ref(),
                            ]
                            .into_iter()
                            .flatten()
                            {
                                inspect_expression_calls(expression, environment, inspect)?;
                            }
                            inspect_nodes(&tab.children, environment, inspect, structs)?;
                        }
                    }
                    ast::ChildBody::SplitPanes { sidebar, detail } => {
                        inspect_nodes(sidebar, environment, inspect, structs)?;
                        inspect_nodes(detail, environment, inspect, structs)?;
                    }
                    ast::ChildBody::Rows(rows) => {
                        inspect_nodes(&rows.children, environment, inspect, structs)?;
                    }
                }
                for modifier in &invocation.modifiers {
                    for expression in modifier.arguments.values() {
                        inspect_expression_calls(expression, environment, inspect)?;
                    }
                    match &modifier.body {
                        ast::ModifierBody::None => {}
                        ast::ModifierBody::Actions(actions) => {
                            inspect_statements(
                                actions,
                                &mut environment.clone(),
                                inspect,
                                structs,
                            )?;
                        }
                        ast::ModifierBody::EventActions { actions, .. } => {
                            inspect_statements(
                                actions,
                                &mut environment.clone(),
                                inspect,
                                structs,
                            )?;
                        }
                        ast::ModifierBody::Nodes(children) => {
                            inspect_nodes(children, environment, inspect, structs)?;
                        }
                    }
                }
            }
            ast::Node::If {
                condition,
                then_body,
                else_body,
                ..
            } => {
                inspect_expression_calls(condition, environment, inspect)?;
                inspect_nodes(then_body, environment, inspect, structs)?;
                if let Some(else_body) = else_body {
                    inspect_nodes(else_body, environment, inspect, structs)?;
                }
            }
            ast::Node::When {
                value,
                cases,
                else_body,
                ..
            } => {
                inspect_expression_calls(value, environment, inspect)?;
                for case in cases {
                    inspect_expression_calls(&case.value, environment, inspect)?;
                    inspect_nodes(&case.body, environment, inspect, structs)?;
                }
                inspect_nodes(else_body, environment, inspect, structs)?;
            }
            ast::Node::ComponentCall {
                arguments, children, ..
            } => {
                for expression in arguments.values() {
                    inspect_expression_calls(expression, environment, inspect)?;
                }
                if let Some(children) = children {
                    inspect_nodes(children, environment, inspect, structs)?;
                }
            }
            ast::Node::NativeComponentCall {
                arguments,
                children,
                event_handlers,
                ..
            } => {
                for expression in arguments.values() {
                    inspect_expression_calls(expression, environment, inspect)?;
                }
                if let Some(children) = children {
                    inspect_nodes(children, environment, inspect, structs)?;
                }
                for handler in event_handlers {
                    inspect_statements(
                        &handler.actions,
                        &mut environment.clone(),
                        inspect,
                        structs,
                    )?;
                }
            }
        }
    }
    Ok(())
}

fn inspect_statements(
    statements: &[ast::Stmt],
    environment: &mut HashMap<String, TypeSyntax>,
    inspect: &mut impl FnMut(
        &ast::Expr,
        &HashMap<String, TypeSyntax>,
        Option<&TypeSyntax>,
    ) -> Result<(), CompileError>,
    structs: &[StructDecl],
) -> Result<(), CompileError> {
    for statement in statements {
        match statement {
            ast::Stmt::Expression { expression, .. } => {
                inspect_expression_calls(expression, environment, inspect)?
            }
            ast::Stmt::Let {
                name, ty, initial, ..
            } => {
                inspect_expression_calls_with_expected(initial, environment, ty.as_ref(), inspect)?;
                let inferred = ty
                    .clone()
                    .or_else(|| infer_static_type(initial, environment, structs));
                if let Some(ty) = inferred {
                    environment.insert(name.clone(), ty);
                }
            }
            ast::Stmt::Assign { name, value, .. } => {
                // The assignment target's declared type is what tells a typed
                // query its row type. Without this, the ordinary pattern
                // `state notes: Array<Note> = []` followed by
                // `notes = await database.query("SELECT ...")` inside
                // `OnAppear async` has no row type at all, because only
                // declaration initializers carried an expected type.
                inspect_expression_calls_with_expected(
                    value,
                    environment,
                    environment.get(name).cloned().as_ref(),
                    inspect,
                )?
            }
            ast::Stmt::Return { value, .. } => {
                // A method that returns `Array<Note>` and writes
                // `return await database.query(...)` carries its row type in
                // the return type alone, so it has to reach the call.
                // The enclosing function's declared return type is what tells a
                // typed query its row type in `return await
                // database.query(...)`. It travels in the environment because
                // that is already threaded through every nested block, so a
                // `return` inside an `if` or a loop sees the function's type too.
                let declared = environment.get(RETURN_TYPE_KEY).cloned();
                inspect_expression_calls_with_expected(
                    value,
                    environment,
                    declared.as_ref(),
                    inspect,
                )?
            }
            ast::Stmt::NativePropertyAssign {
                receiver, value, ..
            } => {
                inspect_expression_calls(receiver, environment, inspect)?;
                inspect_expression_calls(value, environment, inspect)?;
            }
            ast::Stmt::NativeEventSubscribe {
                receiver, actions, ..
            } => {
                inspect_expression_calls(receiver, environment, inspect)?;
                inspect_statements(actions, &mut environment.clone(), inspect, structs)?;
            }
            ast::Stmt::CollectionMutation { arguments, .. } => {
                for argument in arguments {
                    inspect_expression_calls(argument, environment, inspect)?;
                }
            }
            ast::Stmt::TaskLaunch { body, .. } | ast::Stmt::WithAnimation { body, .. } => {
                inspect_statements(body, &mut environment.clone(), inspect, structs)?
            }
            ast::Stmt::If {
                condition,
                then_branch,
                else_branch,
                ..
            } => {
                inspect_expression_calls(condition, environment, inspect)?;
                inspect_statements(then_branch, &mut environment.clone(), inspect, structs)?;
                if let Some(else_branch) = else_branch {
                    inspect_statements(else_branch, &mut environment.clone(), inspect, structs)?;
                }
            }
            ast::Stmt::For { iterable, body, .. } | ast::Stmt::ForMap { iterable, body, .. } => {
                inspect_expression_calls(iterable, environment, inspect)?;
                inspect_statements(body, &mut environment.clone(), inspect, structs)?;
            }
            ast::Stmt::While {
                condition, body, ..
            } => {
                inspect_expression_calls(condition, environment, inspect)?;
                inspect_statements(body, &mut environment.clone(), inspect, structs)?;
            }
            ast::Stmt::TryCatch {
                body,
                error_catches,
                catch_body,
                ..
            } => {
                inspect_statements(body, &mut environment.clone(), inspect, structs)?;
                for arm in error_catches {
                    inspect_statements(&arm.body, &mut environment.clone(), inspect, structs)?;
                }
                if let Some(catch_body) = catch_body {
                    inspect_statements(catch_body, &mut environment.clone(), inspect, structs)?;
                }
            }
            ast::Stmt::TaskCancel { .. } | ast::Stmt::Break { .. } | ast::Stmt::Continue { .. } => {
            }
        }
    }
    Ok(())
}

fn inspect_expression_calls(
    expression: &ast::Expr,
    environment: &HashMap<String, TypeSyntax>,
    inspect: &mut impl FnMut(
        &ast::Expr,
        &HashMap<String, TypeSyntax>,
        Option<&TypeSyntax>,
    ) -> Result<(), CompileError>,
) -> Result<(), CompileError> {
    inspect_expression_calls_with_expected(expression, environment, None, inspect)
}

fn inspect_expression_calls_with_expected(
    expression: &ast::Expr,
    environment: &HashMap<String, TypeSyntax>,
    expected_type: Option<&TypeSyntax>,
    inspect: &mut impl FnMut(
        &ast::Expr,
        &HashMap<String, TypeSyntax>,
        Option<&TypeSyntax>,
    ) -> Result<(), CompileError>,
) -> Result<(), CompileError> {
    inspect(expression, environment, expected_type)?;
    use ast::Expr as E;
    match expression {
        E::Await(value, _) | E::Try { expr: value, .. } => {
            inspect_expression_calls_with_expected(value, environment, expected_type, inspect)?
        }
        E::Add(a, b, _)
        | E::Coalesce(a, b, _)
        | E::Binary(a, _, b, _)
        | E::Arithmetic(a, _, b, _) => {
            inspect_expression_calls(a, environment, inspect)?;
            inspect_expression_calls(b, environment, inspect)?;
        }
        E::Negate(value, _) | E::Not(value, _) => {
            inspect_expression_calls(value, environment, inspect)?
        }
        E::Array(values, _) => {
            for value in values {
                inspect_expression_calls(value, environment, inspect)?;
            }
        }
        E::Map(values, _) => {
            for (key, value) in values {
                inspect_expression_calls(key, environment, inspect)?;
                inspect_expression_calls(value, environment, inspect)?;
            }
        }
        E::Pair(a, b, _) => {
            inspect_expression_calls(a, environment, inspect)?;
            inspect_expression_calls(b, environment, inspect)?;
        }
        E::Triple(a, b, c, _) => {
            inspect_expression_calls(a, environment, inspect)?;
            inspect_expression_calls(b, environment, inspect)?;
            inspect_expression_calls(c, environment, inspect)?;
        }
        E::Call(name, _, args, _) => {
            let inner = result_value_type(name, expected_type);
            for (index, arg) in args.iter().enumerate() {
                inspect_expression_calls_with_expected(
                    arg,
                    environment,
                    if index == 0 { inner.as_ref() } else { None },
                    inspect,
                )?;
            }
        }
        E::CallNamed { name, arguments, .. } => {
            let inner = result_value_type(name, expected_type);
            for arg in arguments.values() {
                inspect_expression_calls_with_expected(
                    arg,
                    environment,
                    inner.as_ref(),
                    inspect,
                )?;
            }
        }
        E::QualifiedCall {
            name,
            arguments,
            named_arguments,
            ..
        } => {
            let inner = result_value_type(name, expected_type);
            for (index, arg) in arguments.iter().enumerate() {
                inspect_expression_calls_with_expected(
                    arg,
                    environment,
                    if index == 0 { inner.as_ref() } else { None },
                    inspect,
                )?;
            }
            for arg in named_arguments.values() {
                inspect_expression_calls(arg, environment, inspect)?;
            }
        }
        E::MethodCall {
            base,
            arguments,
            named_arguments,
            ..
        } => {
            inspect_expression_calls(base, environment, inspect)?;
            for arg in arguments {
                inspect_expression_calls(arg, environment, inspect)?;
            }
            for arg in named_arguments.values() {
                inspect_expression_calls(arg, environment, inspect)?;
            }
        }
        E::Closure { body, .. } => inspect_expression_calls(body, environment, inspect)?,
        E::Index {
            collection, index, ..
        } => {
            inspect_expression_calls(collection, environment, inspect)?;
            inspect_expression_calls(index, environment, inspect)?;
        }
        E::Member { base, .. } => inspect_expression_calls(base, environment, inspect)?,
        E::Range {
            start, end, step, ..
        } => {
            inspect_expression_calls(start, environment, inspect)?;
            inspect_expression_calls(end, environment, inspect)?;
            if let Some(step) = step {
                inspect_expression_calls(step, environment, inspect)?;
            }
        }
        E::Conditional {
            condition,
            then_value,
            else_value,
            ..
        } => {
            inspect_expression_calls(condition, environment, inspect)?;
            inspect_expression_calls(then_value, environment, inspect)?;
            inspect_expression_calls(else_value, environment, inspect)?;
        }
        E::Interpolation(parts, _) => {
            for part in parts {
                if let ast::StringPart::Expression(value) = part {
                    inspect_expression_calls(value, environment, inspect)?;
                }
            }
        }
        E::String(_, _)
        | E::Number(_, _)
        | E::Bool(_, _)
        | E::Name(_, _)
        | E::EnumCase { .. }
        | E::ThemeToken(_, _)
        | E::IsRegularWidth(_)
        | E::IsCompactWidth(_)
        | E::IsRegularHeight(_)
        | E::IsCompactHeight(_)
        | E::Null(_) => {}
    }
    Ok(())
}

/// Every immutable binding a static initializer may refer to, by name.
///
/// Both module scope and app scope must be included. The parser files a
/// top-level `let` into `App::globals`, but a `let` written inside `app { ... }`
/// — the documented way to declare app-wide state — lands in `App::states`.
/// Scanning only `globals` meant that every real application, which opens its
/// database inside the app block, produced an empty handle map: no database was
/// discovered, so no query, write, or migration was ever validated.
fn static_bindings(app: &ast::App) -> Vec<(String, &ast::Expr)> {
    let mut bindings = Vec::new();
    for global in &app.globals {
        bindings.push((global.name.clone(), &global.initial));
    }
    for state in &app.states {
        bindings.push((state.name.clone(), &state.initial));
    }
    // Components and screens own their own scope, and a feature component that
    // opens its own database is the ordinary shape: the compiler rejects
    // passing a native class instance into a component that also disposes it,
    // so an app-level handle cannot simply be handed down.
    for screen in &app.screens {
        for state in &screen.states {
            bindings.push((state.name.clone(), &state.initial));
        }
    }
    for component in &app.components {
        for state in &component.states {
            bindings.push((state.name.clone(), &state.initial));
        }
    }
    for class in &app.classes {
        for field in &class.static_fields {
            bindings.push((format!("{}.{}", class.name, field.name), &field.initial));
        }
        // An instance method refers to its own field by bare name, so the field
        // is also offered unbound. A class that owns the connection is the
        // ordinary way to write an application -- one `DatabaseHelper` holding
        // the handle and the operations -- and without this the analyzer saw no
        // database at all in that shape. `resolve_static_database_handles` drops
        // a bare name that two classes bind to *different* databases, so
        // offering them all cannot attribute one class's SQL to another's.
        for field in &class.fields {
            bindings.push((field.name.clone(), &field.initial));
        }
        for field in &class.static_fields {
            bindings.push((field.name.clone(), &field.initial));
        }
    }
    bindings
}

fn resolve_static_database_handles(
    app: &ast::App,
    namespace: &str,
) -> Result<HashMap<String, (String, bool)>, CompileError> {
    let mut bindings = Vec::<(String, &ast::Expr)>::new();
    for (name, initial) in static_bindings(app) {
        bindings.push((name, initial));
    }
    let mut resolved: HashMap<String, (String, bool)> = HashMap::with_capacity(bindings.len());
    let mut conflicting: HashSet<String> = HashSet::new();
    for _ in 0..=bindings.len() {
        let mut changed = false;
        for (name, value) in &bindings {
            // A name that two scopes bind to *different* databases stays
            // unresolved on purpose. Picking either one would attribute a
            // component's SQL to the app's database, which is a wrong
            // diagnostic; skipping it loses only that check. Component-local
            // databases are the normal shape -- passing a native class instance
            // into a component that also has lifecycle callbacks is rejected by
            // the compiler -- so their queries must still be validated, but
            // never against a same-named handle from another scope.
            if resolved.contains_key(name) || conflicting.contains(name) {
                continue;
            }
            let direct = sqlite_database_constructor(value, namespace)?;
            let alias =
                expression_reference_name(value).and_then(|target| resolved.get(&target).cloned());
            if let Some(database) = direct.or(alias) {
                let clash = bindings.iter().any(|(other, other_value)| {
                    other == name && {
                        sqlite_database_constructor(other_value, namespace)
                            .ok()
                            .flatten()
                            .or_else(|| {
                                expression_reference_name(other_value).and_then(|target| {
                                    resolved.get(&target).cloned()
                                })
                            })
                            .is_some_and(|candidate| candidate != database)
                    }
                });
                if clash {
                    conflicting.insert(name.clone());
                    continue;
                }
                resolved.insert(name.clone(), database);
                changed = true;
            }
        }
        if !changed {
            break;
        }
    }
    Ok(resolved)
}

fn sqlite_database_constructor(
    expression: &ast::Expr,
    expected_namespace: &str,
) -> Result<Option<(String, bool)>, CompileError> {
    let ast::Expr::QualifiedCall {
        namespace,
        name,
        arguments,
        named_arguments,
        span,
        ..
    } = expression
    else {
        return Ok(None);
    };
    if namespace != expected_namespace || name != "Database" {
        return Ok(None);
    }
    let database_id = call_argument(arguments, named_arguments, 0, "name")
        .and_then(static_string)
        .ok_or_else(|| {
            CompileError::new(*span, "SQLite.Database requires a literal database name")
        })?;
    if database_id.is_empty()
        || database_id.len() > 64
        || !database_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
    {
        return Err(CompileError::new(
            *span,
            "SQLite database names must contain 1–64 ASCII letters, digits, underscores, or hyphens",
        ));
    }
    let shared = call_argument(arguments, named_arguments, 1, "sharedWithWidgets")
        .and_then(static_bool)
        .ok_or_else(|| {
            CompileError::new(
                *span,
                "SQLite.Database requires a literal `sharedWithWidgets` Boolean",
            )
        })?;
    Ok(Some((database_id, shared)))
}

fn resolve_static_migrations(
    app: &ast::App,
    namespace: &str,
) -> HashMap<String, Vec<DatabaseMigration>> {
    let values = static_bindings(app);
    let mut resolved = HashMap::new();
    for (name, value) in &values {
        if let Some(migrations) = parse_migrations(value, namespace) {
            resolved.insert(name.clone(), migrations);
        }
    }
    for _ in 0..=values.len() {
        let mut changed = false;
        for (name, value) in &values {
            if resolved.contains_key(name) {
                continue;
            }
            if let Some(alias) =
                expression_reference_name(value).and_then(|key| resolved.get(&key).cloned())
            {
                resolved.insert(name.clone(), alias);
                changed = true;
            }
        }
        if !changed {
            break;
        }
    }
    resolved
}

fn parse_migrations(expression: &ast::Expr, namespace: &str) -> Option<Vec<DatabaseMigration>> {
    let ast::Expr::Array(values, _) = expression else {
        return None;
    };
    values
        .iter()
        .map(|value| parse_migration(value, namespace))
        .collect()
}

fn parse_migration(expression: &ast::Expr, expected_namespace: &str) -> Option<DatabaseMigration> {
    let ast::Expr::QualifiedCall {
        namespace,
        name,
        arguments,
        named_arguments,
        span,
        ..
    } = expression
    else {
        return None;
    };
    if namespace != expected_namespace || name != "Migration" {
        return None;
    }
    let version = static_u32(call_argument(arguments, named_arguments, 0, "version")?)?;
    let statements_expr = call_argument(arguments, named_arguments, 1, "statements")?;
    let ast::Expr::Array(statements, _) = statements_expr else {
        return None;
    };
    let statements = statements
        .iter()
        .map(static_string)
        .collect::<Option<Vec<_>>>()?;
    Some(DatabaseMigration {
        version,
        sql: statements.join(";\n"),
        span: *span,
    })
}

fn resolve_migration_argument(
    expression: &ast::Expr,
    values: &HashMap<String, Vec<DatabaseMigration>>,
    namespace: &str,
) -> Option<Vec<DatabaseMigration>> {
    parse_migrations(expression, namespace).or_else(|| {
        expression_reference_name(expression).and_then(|name| values.get(&name).cloned())
    })
}

fn call_argument<'a>(
    arguments: &'a [ast::Expr],
    named: &'a std::collections::BTreeMap<String, ast::Expr>,
    index: usize,
    name: &str,
) -> Option<&'a ast::Expr> {
    arguments.get(index).or_else(|| named.get(name))
}

fn expression_reference_name(expression: &ast::Expr) -> Option<String> {
    match expression {
        ast::Expr::Name(name, _) => Some(name.clone()),
        ast::Expr::Member {
            base,
            name,
            optional: false,
            ..
        } => Some(format!("{}.{}", expression_reference_name(base)?, name)),
        _ => None,
    }
}

fn static_string(expression: &ast::Expr) -> Option<String> {
    match expression {
        ast::Expr::String(value, _) => Some(value.clone()),
        _ => None,
    }
}

fn static_bool(expression: &ast::Expr) -> Option<bool> {
    match expression {
        ast::Expr::Bool(value, _) => Some(*value),
        _ => None,
    }
}

fn static_u32(expression: &ast::Expr) -> Option<u32> {
    match expression {
        ast::Expr::Number(value, _) => value.parse().ok(),
        _ => None,
    }
}

fn same_migration_history(left: &[DatabaseMigration], right: &[DatabaseMigration]) -> bool {
    left.len() == right.len()
        && left
            .iter()
            .zip(right)
            .all(|(left, right)| left.version == right.version && left.sql == right.sql)
}

fn same_parameter_types(
    left: &[nexa_syntax::ast::FunctionParameter],
    right: &[nexa_syntax::ast::FunctionParameter],
) -> bool {
    left.len() == right.len()
        && left
            .iter()
            .zip(right)
            .all(|(left, right)| same_type_syntax(&left.ty, &right.ty))
}

fn same_type_syntax(left: &TypeSyntax, right: &TypeSyntax) -> bool {
    match (left, right) {
        (TypeSyntax::Named(left, _), TypeSyntax::Named(right, _)) => left == right,
        (
            TypeSyntax::Generic(left_name, left_values, _),
            TypeSyntax::Generic(right_name, right_values, _),
        ) => {
            left_name == right_name
                && left_values.len() == right_values.len()
                && left_values
                    .iter()
                    .zip(right_values)
                    .all(|(left, right)| same_type_syntax(left, right))
        }
        (TypeSyntax::Optional(left, _), TypeSyntax::Optional(right, _)) => {
            same_type_syntax(left, right)
        }
        _ => false,
    }
}

fn sanitize_database_name(name: &str) -> String {
    name.chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() {
                character
            } else {
                '_'
            }
        })
        .collect()
}

/// Counts bind parameters in a source array without inferring their types.
fn bind_parameter_count(expression: &ast::Expr) -> Result<usize, CompileError> {
    match expression {
        ast::Expr::Array(values, _) => Ok(values.len()),
        _ => Err(CompileError::new(
            expression.span(),
            "statically checked SQLite parameters must use an array literal",
        )),
    }
}

/// The value type inside an expected `Result<V, E>`, for `Ok`/`Err` calls.
///
/// `return Ok(await database.query("...")?)` is the idiomatic shape for a
/// fallible typed query, and the row type lives two wrappers down: the declared
/// `Result<Array<Note>, Failure>` and the `Ok` that re-wraps it. Descending into
/// `Ok` without peeling the result loses the row type and the analyzer then
/// demands an explicit `query<Note>` that the author had no reason to write.
fn result_value_type<'a>(
    name: &str,
    expected_type: Option<&'a TypeSyntax>,
) -> Option<TypeSyntax> {
    let function = name.rsplit('.').next()?;
    if function != "Ok" && function != "Err" {
        return None;
    }
    match expected_type? {
        TypeSyntax::Generic(generic, arguments, _) if generic == "Result" => arguments.first().cloned(),
        _ => None,
    }
}

/// Environment key carrying the enclosing function's declared return type.
///
/// A leading NUL cannot appear in a `.nx` identifier, so it cannot shadow or be
/// shadowed by a real binding name.
const RETURN_TYPE_KEY: &str = "\u{0}return-type";

fn lenient_parameter_types(
    expression: &ast::Expr,
    environment: &HashMap<String, TypeSyntax>,
    structs: &[StructDecl],
) -> Result<Option<Vec<nexa_syntax::ast::FunctionParameter>>, CompileError> {
    let ast::Expr::Array(values, _) = expression else {
        return Err(CompileError::new(
            expression.span(),
            "statically checked SQLite parameters must use an array literal",
        ));
    };
    let mut parameters = Vec::with_capacity(values.len());
    for (index, value) in values.iter().enumerate() {
        let Some(ty) = infer_static_type(value, environment, structs) else {
            return Ok(None);
        };
        parameters.push(nexa_syntax::ast::FunctionParameter {
            name: format!("p{index}"),
            ty,
            span: value.span(),
        });
    }
    Ok(Some(parameters))
}

fn infer_static_type(
    expression: &ast::Expr,
    environment: &HashMap<String, TypeSyntax>,
    structs: &[StructDecl],
) -> Option<TypeSyntax> {
    let span = expression.span();
    match expression {
        ast::Expr::String(_, _) => Some(TypeSyntax::Named("String".to_owned(), span)),
        ast::Expr::Bool(_, _) => Some(TypeSyntax::Named("Bool".to_owned(), span)),
        ast::Expr::Number(value, _) => Some(TypeSyntax::Named(
            if value.contains('.') {
                "Float64"
            } else {
                "Int64"
            }
            .to_owned(),
            span,
        )),
        ast::Expr::Null(_) => Some(TypeSyntax::Optional(
            Box::new(TypeSyntax::Named("Int64".to_owned(), span)),
            span,
        )),
        ast::Expr::Name(name, _) => environment.get(name).cloned(),
        ast::Expr::Member {
            base,
            name,
            optional: false,
            ..
        } => {
            let base_type = infer_static_type(base, environment, structs)?;
            let TypeSyntax::Named(struct_name, _) = base_type else {
                return None;
            };
            let structure = structs
                .iter()
                .find(|structure| structure.name == struct_name)?;
            structure
                .fields
                .iter()
                .find(|field| field.name == *name)
                .map(|field| field.ty.clone())
        }
        ast::Expr::Call(name, _, _, _) | ast::Expr::CallNamed { name, .. } => {
            let function = name.rsplit('.').next()?;
            structs
                .iter()
                .find(|structure| structure.name == function)
                .map(|structure| TypeSyntax::Named(structure.name.clone(), span))
                .or_else(|| {
                    environment
                        .get(&format!("{FUNCTION_RETURN_TYPE_PREFIX}{name}"))
                        .cloned()
                })
        }
        ast::Expr::Coalesce(left, right, _) => {
            let left_type = infer_static_type(left, environment, structs);
            let unwrapped = match left_type {
                Some(TypeSyntax::Optional(inner, _)) => Some(*inner),
                other => other,
            };
            unwrapped.or_else(|| infer_static_type(right, environment, structs))
        }
        ast::Expr::Await(value, _) | ast::Expr::Try { expr: value, .. } => {
            infer_static_type(value, environment, structs)
        }
        ast::Expr::Binary(_, _, _, _) | ast::Expr::Not(_, _) => {
            Some(TypeSyntax::Named("Bool".to_owned(), span))
        }
        ast::Expr::Add(left, right, _) => {
            let left = infer_static_type(left, environment, structs)?;
            let right = infer_static_type(right, environment, structs)?;
            if matches!((&left, &right),
                (TypeSyntax::Named(left, _), TypeSyntax::Named(right, _))
                    if left == "String" && right == "String")
            {
                Some(TypeSyntax::Named("String".to_owned(), span))
            } else {
                Some(left)
            }
        }
        ast::Expr::Arithmetic(left, _, _, _) | ast::Expr::Negate(left, _) => {
            infer_static_type(left, environment, structs)
        }
        _ => None,
    }
}

#[derive(Clone)]
struct ColumnInfo {
    declared_type: String,
    not_null: bool,
    integer_primary_key: bool,
}

fn column_info(connection: &Connection, table: &str, column: &str) -> rusqlite::Result<ColumnInfo> {
    let sql = format!("PRAGMA table_info({})", quote_identifier(table));
    let mut statement = connection.prepare(&sql)?;
    let mut rows = statement.query([])?;
    while let Some(row) = rows.next()? {
        let name: String = row.get(1)?;
        if name.eq_ignore_ascii_case(column) {
            let declared_type: String = row.get(2)?;
            let primary_key_position = row.get::<_, i32>(5)?;
            let integer_primary_key = primary_key_position != 0
                && declared_type.trim().eq_ignore_ascii_case("INTEGER")
                && !has_primary_key_index(connection, table)?;
            return Ok(ColumnInfo {
                declared_type,
                not_null: row.get::<_, i32>(3)? != 0,
                integer_primary_key,
            });
        }
    }
    Err(rusqlite::Error::InvalidColumnName(column.to_owned()))
}

/// SQLite implements a rowid alias without a backing PRIMARY KEY index. This
/// distinguishes `id INTEGER PRIMARY KEY` from nullable legacy primary keys,
/// including the special `INTEGER PRIMARY KEY DESC` spelling.
fn has_primary_key_index(connection: &Connection, table: &str) -> rusqlite::Result<bool> {
    let sql = format!("PRAGMA index_list({})", quote_identifier(table));
    let mut statement = connection.prepare(&sql)?;
    let mut rows = statement.query([])?;
    while let Some(row) = rows.next()? {
        let origin: Option<String> = row.get(3)?;
        if origin.as_deref() == Some("pk") {
            return Ok(true);
        }
    }
    Ok(false)
}

fn validate_field_type(
    syntax: &TypeSyntax,
    column: &ColumnInfo,
    query: &DatabaseQueryDecl,
    field_name: &str,
    outer_join_may_null: bool,
) -> Result<(), CompileError> {
    let (inner, optional) = match syntax {
        TypeSyntax::Optional(inner, _) => (inner.as_ref(), true),
        other => (other, false),
    };
    if !optional && (outer_join_may_null || (!column.not_null && !column.integer_primary_key)) {
        return Err(CompileError::new(
            query.span,
            format!(
                "query `{}` maps nullable SQLite column `{field_name}` to a non-optional field; declare it as `{}?`",
                query.name,
                type_name(inner)
            ),
        ));
    }
    let TypeSyntax::Named(name, _) = inner else {
        return Err(CompileError::new(
            syntax.span(),
            format!(
                "query `{}` field `{field_name}` must use a SQLite scalar type",
                query.name
            ),
        ));
    };
    let affinity = sqlite_affinity(&column.declared_type);
    let compatible = match name.as_str() {
        "Bool" | "Int8" | "Int16" | "Int32" | "Int64" | "UInt8" | "UInt16" | "UInt32"
        | "UInt64" => affinity == SqliteAffinity::Integer,
        "Float32" | "Float64" => {
            matches!(affinity, SqliteAffinity::Integer | SqliteAffinity::Real)
        }
        "String" => affinity == SqliteAffinity::Text,
        "Bytes" => affinity == SqliteAffinity::Blob,
        _ => false,
    };
    if !compatible {
        return Err(CompileError::new(
            syntax.span(),
            format!(
                "query `{}` field `{field_name}` uses `{name}`, but SQLite column type `{}` has incompatible affinity",
                query.name, column.declared_type
            ),
        ));
    }
    Ok(())
}

fn type_name(ty: &TypeSyntax) -> &str {
    match ty {
        TypeSyntax::Named(name, _) => name,
        _ => "Scalar",
    }
}

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord)]
enum SqliteAffinity {
    Integer,
    Real,
    Text,
    Blob,
    Numeric,
}

fn sqlite_affinity(declared_type: &str) -> SqliteAffinity {
    let ty = declared_type.to_ascii_uppercase();
    if ty.contains("INT") {
        SqliteAffinity::Integer
    } else if ty.contains("CHAR") || ty.contains("CLOB") || ty.contains("TEXT") {
        SqliteAffinity::Text
    } else if ty.is_empty() || ty.contains("BLOB") {
        SqliteAffinity::Blob
    } else if ty.contains("REAL") || ty.contains("FLOA") || ty.contains("DOUB") {
        SqliteAffinity::Real
    } else {
        SqliteAffinity::Numeric
    }
}

fn quote_identifier(value: &str) -> String {
    format!("\"{}\"", value.replace('"', "\"\""))
}

/// Extract unquoted SQL keywords for conservative query-shape checks. SQLite
/// remains the parser of record; this scanner only avoids treating words in
/// comments, string literals, or quoted identifiers as syntax.
fn sql_keywords(sql: &str) -> Vec<String> {
    let bytes = sql.as_bytes();
    let mut keywords = Vec::new();
    let mut cursor = 0;
    let mut quote = None;
    let mut line_comment = false;
    let mut block_comment = false;
    while cursor < bytes.len() {
        let byte = bytes[cursor];
        if line_comment {
            if byte == b'\n' {
                line_comment = false;
            }
            cursor += 1;
            continue;
        }
        if block_comment {
            if byte == b'*' && bytes.get(cursor + 1) == Some(&b'/') {
                block_comment = false;
                cursor += 2;
            } else {
                cursor += 1;
            }
            continue;
        }
        if let Some(delimiter) = quote {
            if byte == delimiter {
                if bytes.get(cursor + 1) == Some(&delimiter) {
                    cursor += 2;
                    continue;
                }
                quote = None;
            }
            cursor += 1;
            continue;
        }
        if byte == b'-' && bytes.get(cursor + 1) == Some(&b'-') {
            line_comment = true;
            cursor += 2;
        } else if byte == b'/' && bytes.get(cursor + 1) == Some(&b'*') {
            block_comment = true;
            cursor += 2;
        } else if matches!(byte, b'\'' | b'"' | b'`') {
            quote = Some(byte);
            cursor += 1;
        } else if byte == b'[' {
            quote = Some(b']');
            cursor += 1;
        } else if byte.is_ascii_alphabetic() || byte == b'_' {
            let start = cursor;
            cursor += 1;
            while bytes
                .get(cursor)
                .is_some_and(|next| next.is_ascii_alphanumeric() || *next == b'_')
            {
                cursor += 1;
            }
            keywords.push(sql[start..cursor].to_ascii_uppercase());
        } else {
            cursor += 1;
        }
    }
    keywords
}

/// Splits a migration script into the single statements consumed by the
/// native plugin. Semicolons inside quoted values/comments and trigger bodies
/// are kept intact; this is deliberately only a statement boundary scanner,
/// while SQLite itself remains the parser and validator.
fn split_migration_script(
    sql: &str,
    span: nexa_diagnostics::Span,
) -> Result<Vec<String>, CompileError> {
    let bytes = sql.as_bytes();
    let mut statements = Vec::new();
    let mut start = 0;
    let mut cursor = 0;
    let mut quote = None;
    let mut line_comment = false;
    let mut block_comment = false;
    let mut has_token = false;
    let mut create_seen = false;
    let mut trigger_seen = false;
    let mut trigger_as_seen = false;
    let mut trigger_depth = 0_i32;
    let mut case_depth = 0_i32;

    while cursor < bytes.len() {
        let byte = bytes[cursor];
        if line_comment {
            if byte == b'\n' {
                line_comment = false;
            }
            cursor += 1;
            continue;
        }
        if block_comment {
            if byte == b'*' && bytes.get(cursor + 1) == Some(&b'/') {
                block_comment = false;
                cursor += 2;
            } else {
                cursor += 1;
            }
            continue;
        }
        if let Some(delimiter) = quote {
            if byte == delimiter {
                if bytes.get(cursor + 1) == Some(&delimiter) {
                    cursor += 2;
                    continue;
                }
                quote = None;
            }
            cursor += 1;
            continue;
        }
        if byte == b'-' && bytes.get(cursor + 1) == Some(&b'-') {
            line_comment = true;
            cursor += 2;
            continue;
        }
        if byte == b'/' && bytes.get(cursor + 1) == Some(&b'*') {
            block_comment = true;
            cursor += 2;
            continue;
        }
        if matches!(byte, b'\'' | b'"' | b'`') {
            quote = Some(byte);
            has_token = true;
            cursor += 1;
            continue;
        }
        if byte == b'[' {
            quote = Some(b']');
            has_token = true;
            cursor += 1;
            continue;
        }
        if byte == b';' {
            if !trigger_seen || (trigger_as_seen && trigger_depth == 0 && case_depth == 0) {
                if has_token {
                    statements.push(sql[start..=cursor].trim().to_owned());
                }
                start = cursor + 1;
                has_token = false;
                create_seen = false;
                trigger_seen = false;
                trigger_as_seen = false;
                trigger_depth = 0;
                case_depth = 0;
            }
            cursor += 1;
            continue;
        }
        if byte.is_ascii_alphabetic() || byte == b'_' {
            let word_start = cursor;
            cursor += 1;
            while bytes
                .get(cursor)
                .is_some_and(|next| next.is_ascii_alphanumeric() || *next == b'_')
            {
                cursor += 1;
            }
            let word = sql[word_start..cursor].to_ascii_uppercase();
            has_token = true;
            if word == "CREATE" && !trigger_seen {
                create_seen = true;
            } else if create_seen && word == "TRIGGER" {
                trigger_seen = true;
            } else if trigger_seen {
                match word.as_str() {
                    "AS" => trigger_as_seen = true,
                    "BEGIN" if trigger_as_seen => trigger_depth += 1,
                    "CASE" if trigger_as_seen => case_depth += 1,
                    "END" if trigger_as_seen && case_depth > 0 => case_depth -= 1,
                    "END" if trigger_as_seen && trigger_depth > 0 => trigger_depth -= 1,
                    _ => {}
                }
            }
            continue;
        }
        if !byte.is_ascii_whitespace() {
            has_token = true;
        }
        cursor += 1;
    }

    if quote.is_some() || block_comment {
        return Err(CompileError::new(
            span,
            "migration contains an unterminated SQL string or comment",
        ));
    }
    if has_token {
        statements.push(sql[start..].trim().to_owned());
    }
    if statements.is_empty() {
        return Ok(Vec::new());
    }
    Ok(statements)
}

fn reset_access(
    access: &Arc<Mutex<SqlAccess>>,
    span: nexa_diagnostics::Span,
) -> Result<(), CompileError> {
    let mut access = access.lock().map_err(|_| {
        CompileError::new(
            span,
            "SQLite query analysis was interrupted by a poisoned lock",
        )
    })?;
    *access = SqlAccess::default();
    Ok(())
}

fn access_snapshot(
    access: &Arc<Mutex<SqlAccess>>,
    span: nexa_diagnostics::Span,
) -> Result<SqlAccess, CompileError> {
    access
        .lock()
        .map(|access| access.clone())
        .map_err(|_| CompileError::new(span, "SQLite query analysis lock was poisoned"))
}

/// SQLite's prepare APIs accept a first statement and leave a second statement
/// in the tail. Statically named operations intentionally reject that tail.
fn has_trailing_statement(sql: &str) -> bool {
    let mut state = SqlLexState::Normal;
    let mut saw_token = false;
    let mut statement_ended = false;
    let mut chars = sql.chars().peekable();
    while let Some(ch) = chars.next() {
        match state {
            SqlLexState::Normal => match ch {
                '\'' => {
                    if statement_ended {
                        return true;
                    }
                    saw_token = true;
                    state = SqlLexState::SingleQuote;
                }
                '"' => {
                    if statement_ended {
                        return true;
                    }
                    saw_token = true;
                    state = SqlLexState::DoubleQuote;
                }
                '`' => {
                    if statement_ended {
                        return true;
                    }
                    saw_token = true;
                    state = SqlLexState::Backtick;
                }
                '[' => {
                    if statement_ended {
                        return true;
                    }
                    saw_token = true;
                    state = SqlLexState::Bracket;
                }
                '-' if chars.peek() == Some(&'-') => {
                    chars.next();
                    state = SqlLexState::LineComment;
                }
                '/' if chars.peek() == Some(&'*') => {
                    chars.next();
                    state = SqlLexState::BlockComment;
                }
                ';' => {
                    if saw_token {
                        statement_ended = true;
                    }
                }
                ch if ch.is_whitespace() => {}
                _ => {
                    if statement_ended {
                        return true;
                    }
                    saw_token = true;
                }
            },
            SqlLexState::SingleQuote => {
                if ch == '\'' {
                    if chars.peek() == Some(&'\'') {
                        chars.next();
                    } else {
                        state = SqlLexState::Normal;
                    }
                }
            }
            SqlLexState::DoubleQuote => {
                if ch == '"' {
                    if chars.peek() == Some(&'"') {
                        chars.next();
                    } else {
                        state = SqlLexState::Normal;
                    }
                }
            }
            SqlLexState::Backtick => {
                if ch == '`' {
                    state = SqlLexState::Normal;
                }
            }
            SqlLexState::Bracket => {
                if ch == ']' {
                    state = SqlLexState::Normal;
                }
            }
            SqlLexState::LineComment => {
                if ch == '\n' {
                    state = SqlLexState::Normal;
                }
            }
            SqlLexState::BlockComment => {
                if ch == '*' && chars.peek() == Some(&'/') {
                    chars.next();
                    state = SqlLexState::Normal;
                }
            }
        }
    }
    false
}

#[derive(Clone, Copy)]
enum SqlLexState {
    Normal,
    SingleQuote,
    DoubleQuote,
    Backtick,
    Bracket,
    LineComment,
    BlockComment,
}

#[cfg(test)]
mod tests {
    use super::split_migration_script;
    use nexa_diagnostics::Span;

    #[test]
    fn migration_splitter_keeps_trigger_case_and_quoted_semicolons() {
        let sql = r#"
            CREATE TABLE "note; log" (message TEXT NOT NULL);
            /* A semicolon here is not a statement boundary: ; */
            CREATE TRIGGER note_log AFTER INSERT ON notes BEGIN
                INSERT INTO "note; log"(message)
                VALUES (CASE WHEN NEW.title = '' THEN 'empty; title' ELSE NEW.title END);
                UPDATE notes SET title = title WHERE id = NEW.id;
            END;
        "#;

        let statements = split_migration_script(sql, Span::default())
            .expect("valid migration script should split");
        assert_eq!(statements.len(), 2);
        assert!(statements[0].contains("CREATE TABLE \"note; log\""));
        assert!(statements[1].contains("'empty; title'"));
        assert!(statements[1].contains("UPDATE notes SET title = title"));
        assert!(statements[1].ends_with("END;"));
    }

    #[test]
    fn migration_splitter_ignores_comment_only_bodies() {
        assert!(
            split_migration_script(
                "-- only a comment;\n/* still a comment; */",
                Span::default()
            )
            .expect("comments are valid SQL text")
            .is_empty()
        );
    }
}
