use crate::{compat, sqlite};
use nexa_diagnostics::{CompileError, CompileWarning, Span};
use nexa_plugin_compiler_api::{
    ANALYZER_PROTOCOL_VERSION, AnalysisDiagnostic, AnalysisRequest, AnalysisResponse,
    DiagnosticSeverity,
};
use nexa_syntax::ast::{self, App, Program};

pub(crate) fn analyze(request: &AnalysisRequest) -> AnalysisResponse {
    let diagnostics = match parse_graph(request) {
        Ok(app) => validate_and_warn(&app, request),
        Err(diagnostic) => vec![diagnostic],
    };
    AnalysisResponse {
        protocol_version: ANALYZER_PROTOCOL_VERSION,
        diagnostics,
    }
}

fn parse_graph(request: &AnalysisRequest) -> Result<App, AnalysisDiagnostic> {
    let mut programs = Vec::<(String, Program)>::with_capacity(request.source_files.len());
    for source in &request.source_files {
        let program = nexa_syntax::parse_program(&source.contents).map_err(|error| {
            to_diagnostic(
                &CompileError {
                    span: error.span,
                    message: error.message,
                    file: Some(source.path.clone()),
                },
                DiagnosticSeverity::Error,
                None,
                &request.entry_file,
            )
        })?;
        programs.push((source.path.clone(), program));
    }

    let entry_index = programs
        .iter()
        .position(|(path, program)| path == &request.entry_file && program.app.is_some())
        .or_else(|| {
            programs
                .iter()
                .position(|(_, program)| program.app.is_some())
        })
        .ok_or_else(|| AnalysisDiagnostic {
            severity: DiagnosticSeverity::Error,
            message: "SQLite analysis could not find the project's app declaration".to_owned(),
            file: request.entry_file.clone(),
            start: 0,
            end: 0,
            line: 1,
            column: 1,
            target: None,
        })?;

    let entry_path = programs
        .get(entry_index)
        .map(|(path, _)| path.clone())
        .unwrap_or_else(|| request.entry_file.clone());
    let (_, entry_program) = programs
        .get_mut(entry_index)
        .ok_or_else(|| AnalysisDiagnostic {
            severity: DiagnosticSeverity::Error,
            message: "SQLite analyzer could not access the parsed app source".to_owned(),
            file: request.entry_file.clone(),
            start: 0,
            end: 0,
            line: 1,
            column: 1,
            target: None,
        })?;
    let mut app = entry_program.app.take().ok_or_else(|| AnalysisDiagnostic {
        severity: DiagnosticSeverity::Error,
        message: "SQLite analyzer could not access the app declaration".to_owned(),
        file: request.entry_file.clone(),
        start: 0,
        end: 0,
        line: 1,
        column: 1,
        target: None,
    })?;
    set_app_source(&mut app, &entry_path);

    for (path, program) in &mut programs {
        append_program(&mut app, program, path);
    }
    Ok(app)
}

fn append_program(app: &mut App, program: &mut Program, source_file: &str) {
    for structure in &mut program.structs {
        structure.source_file = Some(source_file.to_owned());
    }
    for class in &mut program.classes {
        set_class_source(class, source_file);
    }
    for function in &mut program.functions {
        function.source_file = Some(source_file.to_owned());
    }
    for state in &mut program.globals {
        state.source_file = Some(source_file.to_owned());
    }
    for component in &mut program.components {
        component.source_file = Some(source_file.to_owned());
        for state in &mut component.states {
            state.source_file = Some(source_file.to_owned());
        }
    }
    for screen in &mut program.screens {
        screen.source_file = Some(source_file.to_owned());
        for state in &mut screen.states {
            state.source_file = Some(source_file.to_owned());
        }
    }
    for widget in &mut program.widgets {
        widget.source_file = Some(source_file.to_owned());
    }

    app.enums.append(&mut program.enums);
    app.structs.append(&mut program.structs);
    app.classes.append(&mut program.classes);
    app.functions.append(&mut program.functions);
    app.globals.append(&mut program.globals);
    app.components.append(&mut program.components);
    app.screens.append(&mut program.screens);
    app.widgets.append(&mut program.widgets);
    app.tests.append(&mut program.tests);

    if let Some(mut source_app) = program.app.take() {
        set_app_source(&mut source_app, source_file);
        app.enums.append(&mut source_app.enums);
        app.structs.append(&mut source_app.structs);
        app.classes.append(&mut source_app.classes);
        app.functions.append(&mut source_app.functions);
        app.globals.append(&mut source_app.globals);
        app.states.append(&mut source_app.states);
        app.components.append(&mut source_app.components);
        app.screens.append(&mut source_app.screens);
        app.widgets.append(&mut source_app.widgets);
        app.tests.append(&mut source_app.tests);
    }
}

fn set_app_source(app: &mut App, source_file: &str) {
    for state in app.states.iter_mut().chain(&mut app.globals) {
        state.source_file = Some(source_file.to_owned());
    }
    for function in &mut app.functions {
        function.source_file = Some(source_file.to_owned());
    }
    for class in &mut app.classes {
        set_class_source(class, source_file);
    }
    for structure in &mut app.structs {
        structure.source_file = Some(source_file.to_owned());
    }
    for component in &mut app.components {
        component.source_file = Some(source_file.to_owned());
        for state in &mut component.states {
            state.source_file = Some(source_file.to_owned());
        }
    }
    for screen in &mut app.screens {
        screen.source_file = Some(source_file.to_owned());
        for state in &mut screen.states {
            state.source_file = Some(source_file.to_owned());
        }
    }
    for widget in &mut app.widgets {
        widget.source_file = Some(source_file.to_owned());
    }
}

fn set_class_source(class: &mut ast::ClassDecl, source_file: &str) {
    class.source_file = Some(source_file.to_owned());
    for method in class.methods.iter_mut().chain(&mut class.static_methods) {
        method.source_file = Some(source_file.to_owned());
    }
}

fn validate_and_warn(
    app: &App,
    request: &AnalysisRequest,
) -> Vec<AnalysisDiagnostic> {
    let databases = match sqlite::validate_ordinary_calls(app, &request.namespace) {
        Ok(databases) => databases,
        Err(error) => {
            let mut diagnostic =
                to_diagnostic(&error, DiagnosticSeverity::Error, None, &request.entry_file);
            if error.file.is_none() {
                diagnostic.file = file_for_span(app, error.span, &request.entry_file);
            }
            return vec![diagnostic];
        }
    };

    let mut diagnostics = Vec::new();
    for target in &request.targets {
        let minimum = match target.target.as_str() {
            "swift" => target
                .ios_minimum_version
                .as_deref()
                .and_then(compat::DatabaseTargetMinimum::ios),
            "kotlin" => target
                .android_min_sdk
                .map(|min_sdk| compat::DatabaseTargetMinimum::Android { min_sdk }),
            _ => None,
        };
        let Some(minimum) = minimum else {
            continue;
        };
        for database in &databases {
            for migration in &database.migrations {
                for sql in &migration.statements {
                    let warning = compat::compatibility_warnings(sql, migration.span, minimum);
                    append_warnings(
                        &mut diagnostics,
                        warning,
                        Some(target.target.clone()),
                        app,
                        &request.entry_file,
                    );
                }
            }
            for query in &database.queries {
                let warning = compat::compatibility_warnings(&query.sql, query.span, minimum);
                append_warnings(
                    &mut diagnostics,
                    warning,
                    Some(target.target.clone()),
                    app,
                    &request.entry_file,
                );
            }
            for command in &database.commands {
                let warning = compat::compatibility_warnings(&command.sql, command.span, minimum);
                append_warnings(
                    &mut diagnostics,
                    warning,
                    Some(target.target.clone()),
                    app,
                    &request.entry_file,
                );
            }
        }
    }
    diagnostics
}

fn append_warnings(
    diagnostics: &mut Vec<AnalysisDiagnostic>,
    warnings: Vec<CompileWarning>,
    target: Option<String>,
    app: &App,
    entry_file: &str,
) {
    for warning in warnings {
        let mut diagnostic = to_diagnostic(
            &CompileError {
                span: warning.span,
                message: warning.message.clone(),
                file: None,
            },
            DiagnosticSeverity::Warning,
            target.clone(),
            entry_file,
        );
        diagnostic.file = file_for_span(app, warning.span, entry_file);
        diagnostics.push(diagnostic);
    }
}

fn to_diagnostic(
    error: &CompileError,
    severity: DiagnosticSeverity,
    target: Option<String>,
    entry_file: &str,
) -> AnalysisDiagnostic {
    AnalysisDiagnostic {
        severity,
        message: error.message.clone(),
        file: error.file.clone().unwrap_or_else(|| entry_file.to_owned()),
        start: error.span.start,
        end: error.span.end,
        line: error.span.line,
        column: error.span.column,
        target,
    }
}

fn file_for_span(app: &App, span: Span, fallback: &str) -> String {
    let mut candidates = Vec::<(usize, String)>::new();
    let mut add = |owner: Span, source_file: Option<&str>| {
        if let Some(source_file) = source_file
            && owner.start <= span.start
            && owner.end >= span.end
        {
            candidates.push((
                owner.end.saturating_sub(owner.start),
                source_file.to_owned(),
            ));
        }
    };
    for state in app.states.iter().chain(&app.globals) {
        add(state.span, state.source_file.as_deref());
    }
    for function in &app.functions {
        add(function_range(function), function.source_file.as_deref());
    }
    for class in &app.classes {
        add(class.span, class.source_file.as_deref());
        for method in class.methods.iter().chain(&class.static_methods) {
            add(function_range(method), method.source_file.as_deref());
        }
    }
    for component in &app.components {
        add(component.span, component.source_file.as_deref());
        for state in &component.states {
            add(state.span, state.source_file.as_deref());
        }
    }
    for screen in &app.screens {
        add(screen.span, screen.source_file.as_deref());
        for state in &screen.states {
            add(state.span, state.source_file.as_deref());
        }
    }
    for widget in &app.widgets {
        add(widget.span, widget.source_file.as_deref());
    }
    candidates
        .into_iter()
        .min_by_key(|(size, _)| *size)
        .map(|(_, file)| file)
        .unwrap_or_else(|| fallback.to_owned())
}

fn function_range(function: &ast::FunctionDecl) -> Span {
    let end = function
        .body
        .iter()
        .map(statement_end)
        .max()
        .unwrap_or(function.span.end)
        .max(function.span.end);
    Span {
        end,
        ..function.span
    }
}

fn statement_end(statement: &ast::Stmt) -> usize {
    use ast::Stmt as S;
    match statement {
        S::Expression { expression, span } => span.end.max(expression_end(expression)),
        S::Let { initial, span, .. } => span.end.max(expression_end(initial)),
        S::Assign { value, span, .. } | S::Return { value, span } => {
            span.end.max(expression_end(value))
        }
        S::NativePropertyAssign {
            receiver,
            value,
            span,
            ..
        } => span
            .end
            .max(expression_end(receiver))
            .max(expression_end(value)),
        S::CollectionMutation {
            arguments, span, ..
        } => arguments
            .iter()
            .map(expression_end)
            .fold(span.end, usize::max),
        S::NativeEventSubscribe {
            receiver,
            actions,
            span,
            ..
        } => actions
            .iter()
            .map(statement_end)
            .fold(span.end.max(expression_end(receiver)), usize::max),
        S::TaskLaunch { body, span, .. }
        | S::WithAnimation { body, span, .. }
        | S::While { body, span, .. }
        | S::For { body, span, .. }
        | S::ForMap { body, span, .. } => body.iter().map(statement_end).fold(span.end, usize::max),
        S::If {
            condition,
            then_branch,
            else_branch,
            span,
        } => then_branch
            .iter()
            .chain(else_branch.iter().flatten())
            .map(statement_end)
            .fold(span.end.max(expression_end(condition)), usize::max),
        S::TryCatch {
            body,
            error_catches,
            catch_body,
            span,
        } => body
            .iter()
            .chain(error_catches.iter().flat_map(|arm| &arm.body))
            .chain(catch_body.iter().flatten())
            .map(statement_end)
            .fold(span.end, usize::max),
        S::TaskCancel { span, .. } | S::Break { span } | S::Continue { span } => span.end,
    }
}

fn expression_end(expression: &ast::Expr) -> usize {
    use ast::Expr as E;
    let end = expression.span().end;
    let children_end = match expression {
        E::Interpolation(parts, _) => parts
            .iter()
            .filter_map(|part| match part {
                ast::StringPart::Expression(expression) => Some(expression_end(expression)),
                ast::StringPart::Literal(_) | ast::StringPart::Name(_) => None,
            })
            .max()
            .unwrap_or(end),
        E::Add(left, right, _)
        | E::Arithmetic(left, _, right, _)
        | E::Binary(left, _, right, _)
        | E::Coalesce(left, right, _) => expression_end(left).max(expression_end(right)),
        E::Negate(value, _)
        | E::Not(value, _)
        | E::Await(value, _)
        | E::Try { expr: value, .. } => expression_end(value),
        E::Array(items, _) | E::Call(_, _, items, _) => {
            items.iter().map(expression_end).max().unwrap_or(end)
        }
        E::Map(items, _) => items
            .iter()
            .flat_map(|(key, value)| [expression_end(key), expression_end(value)])
            .max()
            .unwrap_or(end),
        E::Pair(first, second, _) => expression_end(first).max(expression_end(second)),
        E::Triple(first, second, third, _) => expression_end(first)
            .max(expression_end(second))
            .max(expression_end(third)),
        E::CallNamed { arguments, .. } => {
            arguments.values().map(expression_end).max().unwrap_or(end)
        }
        E::MethodCall {
            base,
            arguments,
            named_arguments,
            ..
        } => std::iter::once(expression_end(base))
            .chain(arguments.iter().map(expression_end))
            .chain(named_arguments.values().map(expression_end))
            .max()
            .unwrap_or(end),
        E::Closure { body, .. } => expression_end(body),
        E::QualifiedCall {
            arguments,
            named_arguments,
            ..
        } => arguments
            .iter()
            .map(expression_end)
            .chain(named_arguments.values().map(expression_end))
            .max()
            .unwrap_or(end),
        E::Index {
            collection, index, ..
        } => expression_end(collection).max(expression_end(index)),
        E::Member { base, .. } => expression_end(base),
        E::Range {
            start,
            end: finish,
            step,
            ..
        } => expression_end(start)
            .max(expression_end(finish))
            .max(step.as_deref().map(expression_end).unwrap_or(end)),
        E::Conditional {
            condition,
            then_value,
            else_value,
            ..
        } => expression_end(condition)
            .max(expression_end(then_value))
            .max(expression_end(else_value)),
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
        | E::Null(_) => end,
    };
    end.max(children_end)
}

#[cfg(test)]
mod tests {
    use super::analyze;
    use nexa_plugin_compiler_api::{
        ANALYZER_PROTOCOL_VERSION, AnalysisRequest, DiagnosticSeverity, SourceFile,
        TargetConfiguration,
    };

    fn request(source: &str, targets: Vec<TargetConfiguration>) -> AnalysisRequest {
        AnalysisRequest::new(
            "dev.nexa.sqlite",
            "SQLite",
            "/plugins/sqlite",
            "/project/App.nx",
            targets,
            vec![SourceFile {
                path: "/project/App.nx".to_owned(),
                contents: source.to_owned(),
            }],
        )
    }

    const BASE: &str = r#"
plugin "dev.nexa.sqlite" as SQLite
struct Todo { id: Int64, title: String }
let store = SQLite.Database("todos", false)
let migrations = [SQLite.Migration(1, ["CREATE TABLE todos (id INTEGER PRIMARY KEY, title TEXT NOT NULL)"])]
async fn prepare() -> Void {
    let version = await store.migrate(migrations)
}
app TodoApp {
    body { Text("Todo") }
}
"#;

    /// A database opened inside `app { ... }`, with its migration and its reads
    /// in the body's `OnAppear async` block -- the shape every real application
    /// uses.
    ///
    /// This fixture is the regression test for three separate holes, each of
    /// which made the analyzer silently validate nothing at all:
    ///
    /// 1. handles were resolved only from `App::globals`, but a `let` written
    ///    inside the app block lands in `App::states`, so no database was found;
    /// 2. only declarations and function bodies were walked, so a `migrate` call
    ///    inside `OnAppear async` was invisible and the schema never existed;
    /// 3. assignment targets carried no expected type, so a typed query assigned
    ///    to a declared `Array<Todo>` state had no row type to check against.
    const APP_SCOPED: &str = r#"
plugin "dev.nexa.sqlite" as SQLite
struct Todo { id: Int64, title: String }
app TodoApp {
    let store = SQLite.Database("todos", false)
    let migrations = [SQLite.Migration(1, ["CREATE TABLE todos (id INTEGER PRIMARY KEY, title TEXT NOT NULL)"])]
    state todos: Array<Todo> = []
    body {
        OnAppear async {
            await store.migrate(migrations)
            todos = await store.query("SELECT id, title FROM todos WHERE id = ?", [1])
        }
    }
}
"#;

    fn error_messages(source: &str) -> Vec<String> {
        analyze(&request(source, Vec::new()))
            .diagnostics
            .into_iter()
            .map(|diagnostic| diagnostic.message)
            .collect()
    }

    #[test]
    fn accepts_an_app_scoped_database_whose_work_lives_in_on_appear() {
        let messages = error_messages(APP_SCOPED);
        assert!(
            messages.is_empty(),
            "expected no diagnostics, got {messages:?}"
        );
    }

    #[test]
    fn rejects_a_query_against_a_table_no_migration_creates() {
        let source = APP_SCOPED.replace(
            "SELECT id, title FROM todos WHERE id = ?",
            "SELECT id, title FROM missing WHERE id = ?",
        );
        let messages = error_messages(&source);
        assert!(
            messages.iter().any(|message| message.contains("missing")),
            "expected the unknown table to be rejected, got {messages:?}"
        );
    }

    #[test]
    fn rejects_a_write_statement_used_as_a_query() {
        let source = APP_SCOPED.replace(
            "todos = await store.query(\"SELECT id, title FROM todos WHERE id = ?\", [1])",
            "todos = await store.query(\"DELETE FROM todos\", [])",
        );
        let messages = error_messages(&source);
        assert!(
            messages.iter().any(|message| message.contains("read-only")),
            "expected a read-only diagnostic, got {messages:?}"
        );
    }

    #[test]
    fn rejects_a_query_whose_columns_do_not_match_the_row_struct() {
        let source = APP_SCOPED.replace(
            "SELECT id, title FROM todos WHERE id = ?",
            "SELECT id FROM todos WHERE id = ?",
        );
        let messages = error_messages(&source);
        assert!(
            messages
                .iter()
                .any(|message| message.contains("but `Todo` has 2 fields")),
            "expected a column/field arity diagnostic, got {messages:?}"
        );
    }

    #[test]
    fn rejects_migrations_declared_out_of_order() {
        let source = APP_SCOPED.replace("SQLite.Migration(1,", "SQLite.Migration(3,");
        let messages = error_messages(&source);
        assert!(
            messages
                .iter()
                .any(|message| message.contains("declared in order starting at 1")),
            "expected a migration ordering diagnostic, got {messages:?}"
        );
    }

    #[test]
    fn rejects_a_mismatched_bind_arity_inside_an_action_block() {
        let source = APP_SCOPED.replace(
            "todos = await store.query(\"SELECT id, title FROM todos WHERE id = ?\", [1])",
            "todos = await store.query(\"SELECT id, title FROM todos WHERE id = ?\", [])",
        );
        let messages = error_messages(&source);
        assert!(
            messages.iter().any(|message| message.contains("bind slot")),
            "expected a bind arity diagnostic, got {messages:?}"
        );
    }

    /// A parameter the analyzer cannot type -- read from a plugin contract struct
    /// it never parses -- must degrade to an arity check rather than being
    /// rejected. The analyzer guards SQL, not types, and refusing to check such an
    /// application defeats the point.
    #[test]
    fn accepts_a_parameter_whose_type_is_outside_the_analyzer() {
        let source = APP_SCOPED
            .replace(
                "    state todos: Array<Todo> = []",
                "    state todos: Array<Todo> = []\n    state inserted = SQLite.Inserted(0)",
            )
            .replace(
                "todos = await store.query(\"SELECT id, title FROM todos WHERE id = ?\", [1])",
                "let written = await store.execute(\"INSERT INTO todos (id, title) VALUES (?, ?)\", [1, \"a\"])\n            todos = await store.query(\"SELECT id, title FROM todos WHERE id = ?\", [inserted.lastInsertRowId])",
            );
        let messages = error_messages(&source);
        assert!(
            messages.is_empty(),
            "expected no diagnostics, got {messages:?}"
        );
    }

    /// A component that owns the database itself, which is the shape a real app
    /// uses for a feature screen. Custom components get their own `body`, so the
    /// node walk has to reach them as well as the app body.
    #[test]
    fn validates_a_database_opened_inside_a_custom_component() {
        let source = APP_SCOPED.replace(
            "app TodoApp {\n    let store = SQLite.Database(\"todos\", false)\n    let migrations = [SQLite.Migration(1, [\"CREATE TABLE todos (id INTEGER PRIMARY KEY, title TEXT NOT NULL)\"])]",
            "component TodoList() {\n    let store = SQLite.Database(\"todos\", false)\n    let migrations = [SQLite.Migration(1, [\"CREATE TABLE todos (id INTEGER PRIMARY KEY, title TEXT NOT NULL)\"])]\n    state todos: Array<Todo> = []\n    body {\n        Button(\"Load\") {\n            await store.migrate(migrations)\n            todos = await store.query(\"SELECT id, title FROM todos WHERE id = ?\", [1])\n        }\n    }\n}\napp TodoApp {",
        )
        .replace(
            "    state todos: Array<Todo> = []\n    body {\n        OnAppear async {\n            await store.migrate(migrations)\n            todos = await store.query(\"SELECT id, title FROM todos WHERE id = ?\", [1])\n        }\n    }",
            "    body {\n        TodoList()\n    }",
        );
        let messages = error_messages(&source);
        assert!(
            messages.is_empty(),
            "expected no diagnostics for a component-owned database, got {messages:?}"
        );
    }

    #[test]
    fn rejects_a_bad_query_inside_a_custom_component() {
        let source = APP_SCOPED
            .replace(
                "app TodoApp {\n    let store = SQLite.Database(\"todos\", false)\n    let migrations = [SQLite.Migration(1, [\"CREATE TABLE todos (id INTEGER PRIMARY KEY, title TEXT NOT NULL)\"])]",
                "component TodoList() {\n    let store = SQLite.Database(\"todos\", false)\n    let migrations = [SQLite.Migration(1, [\"CREATE TABLE todos (id INTEGER PRIMARY KEY, title TEXT NOT NULL)\"])]\n    state todos: Array<Todo> = []\n    body {\n        Button(\"Load\") {\n            await store.migrate(migrations)\n            todos = await store.query(\"SELECT id, title FROM missing WHERE id = ?\", [1])\n        }\n    }\n}\napp TodoApp {",
            )
            .replace(
                "    state todos: Array<Todo> = []\n    body {\n        OnAppear async {\n            await store.migrate(migrations)\n            todos = await store.query(\"SELECT id, title FROM todos WHERE id = ?\", [1])\n        }\n    }",
                "    body {\n        TodoList()\n    }",
            );
        let messages = error_messages(&source);
        assert!(
            messages.iter().any(|message| message.contains("missing")),
            "expected the component's query to be rejected, got {messages:?}"
        );
    }

    /// A named screen owns its own scope, exactly as a component does, and its
    /// body carries the same kinds of calls.
    #[test]
    fn validates_a_database_opened_inside_a_named_screen() {
        let source = APP_SCOPED.replace(
            "app TodoApp {\n    let store = SQLite.Database(\"todos\", false)\n    let migrations = [SQLite.Migration(1, [\"CREATE TABLE todos (id INTEGER PRIMARY KEY, title TEXT NOT NULL)\"])]",
            "screen TodoScreen {\n    let store = SQLite.Database(\"todos\", false)\n    let migrations = [SQLite.Migration(1, [\"CREATE TABLE todos (id INTEGER PRIMARY KEY, title TEXT NOT NULL)\"])]\n    state todos: Array<Todo> = []\n    body {\n        Button(\"Load\") {\n            await store.migrate(migrations)\n            todos = await store.query(\"SELECT id, title FROM todos WHERE id = ?\", [1])\n        }\n    }\n}\napp TodoApp {",
        )
        .replace(
            "    state todos: Array<Todo> = []\n    body {\n        OnAppear async {\n            await store.migrate(migrations)\n            todos = await store.query(\"SELECT id, title FROM todos WHERE id = ?\", [1])\n        }\n    }",
            "    body {\n        NavigationStack(root: TodoScreen)\n    }",
        );
        let messages = error_messages(&source);
        assert!(
            messages.is_empty(),
            "expected no diagnostics for a screen-owned database, got {messages:?}"
        );
    }

    #[test]
    fn rejects_a_bad_query_inside_a_named_screen() {
        let source = APP_SCOPED
            .replace(
                "app TodoApp {\n    let store = SQLite.Database(\"todos\", false)\n    let migrations = [SQLite.Migration(1, [\"CREATE TABLE todos (id INTEGER PRIMARY KEY, title TEXT NOT NULL)\"])]",
                "screen TodoScreen {\n    let store = SQLite.Database(\"todos\", false)\n    let migrations = [SQLite.Migration(1, [\"CREATE TABLE todos (id INTEGER PRIMARY KEY, title TEXT NOT NULL)\"])]\n    state todos: Array<Todo> = []\n    body {\n        Button(\"Load\") {\n            await store.migrate(migrations)\n            todos = await store.query(\"SELECT id, title FROM gone WHERE id = ?\", [1])\n        }\n    }\n}\napp TodoApp {",
            )
            .replace(
                "    state todos: Array<Todo> = []\n    body {\n        OnAppear async {\n            await store.migrate(migrations)\n            todos = await store.query(\"SELECT id, title FROM todos WHERE id = ?\", [1])\n        }\n    }",
                "    body {\n        NavigationStack(root: TodoScreen)\n    }",
            );
        let messages = error_messages(&source);
        assert!(
            messages.iter().any(|message| message.contains("gone")),
            "expected the screen's query to be rejected, got {messages:?}"
        );
    }

    /// The shape a real application uses: one class owns the connection and the
    /// operations, and the UI calls its methods.
    ///
    /// A class instance method refers to its own field by bare name, so a bare
    /// `database` inside `fn` bodies must resolve to the class's handle. Class
    /// instance fields were not scanned at all, which left every class-based
    /// application unvalidated.
    #[test]
    fn validates_a_database_owned_by_a_class() {
        let source = r#"
plugin "dev.nexa.sqlite" as SQLite
struct Note { id: Int64, title: String }
class NoteStore {
    let database = SQLite.Database("notes", false)
    let migrations = [SQLite.Migration(1, ["CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT NOT NULL)"])]

    async fn prepare() -> Int32 {
        return await database.migrate(migrations)
    }

    fn all() -> Signal<Array<Note>> {
        return database.observeQuery<Note>("SELECT id, title FROM notes", [])
    }
}
app NotesApp {
    let store = NoteStore()
    body { Text("notes") }
}
"#;
        let messages = error_messages(source);
        assert!(
            messages.is_empty(),
            "expected no diagnostics for a class-owned database, got {messages:?}"
        );
    }

    #[test]
    fn rejects_a_bad_query_inside_a_class_method() {
        let source = r#"
plugin "dev.nexa.sqlite" as SQLite
struct Note { id: Int64, title: String }
class NoteStore {
    let database = SQLite.Database("notes", false)
    let migrations = [SQLite.Migration(1, ["CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT NOT NULL)"])]

    async fn prepare() -> Int32 {
        return await database.migrate(migrations)
    }

    fn all() -> Signal<Array<Note>> {
        return database.observeQuery<Note>("SELECT id, title FROM nowhere", [])
    }
}
app NotesApp {
    let store = NoteStore()
    body { Text("notes") }
}
"#;
        let messages = error_messages(source);
        assert!(
            messages.iter().any(|message| message.contains("nowhere")),
            "expected the class method's query to be rejected, got {messages:?}"
        );
    }

    /// A class method that fills a declared field with a typed query relies on
    /// the field's type reaching the method's environment; the query carries no
    /// type argument of its own.
    #[test]
    fn uses_a_class_fields_declared_type_inside_its_methods() {
        let source = r#"
plugin "dev.nexa.sqlite" as SQLite
struct Note { id: Int64, title: String }
class NoteStore {
    let database = SQLite.Database("notes", false)
    let migrations = [SQLite.Migration(1, ["CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT NOT NULL)"])]
    let notes: Array<Note> = []

    async fn reload() -> Void {
        await database.migrate(migrations)
        notes = await database.query("SELECT id FROM notes", [])
    }
}
app NotesApp {
    let store = NoteStore()
    body { Text("notes") }
}
"#;
        // `notes` has two fields and the query projects one, so the column/field
        // arity check is reachable only when the declared type was available.
        let messages = error_messages(source);
        assert!(
            messages
                .iter()
                .any(|message| message.contains("but `Note` has 2 fields")),
            "expected the field's declared type to reach the method, got {messages:?}"
        );
    }

    #[test]
    fn validates_ordinary_database_handle_migration_and_typed_query() {
        let source = BASE.replace(
            "app TodoApp {",
            "fn loadTodos() -> Void {\n    let todos = await store.query<Todo>(\"SELECT id, title FROM todos WHERE id = ?\", [1])\n}\napp TodoApp {",
        );
        let response = analyze(&request(&source, Vec::new()));
        assert_eq!(response.protocol_version, ANALYZER_PROTOCOL_VERSION);
        assert!(
            response.diagnostics.is_empty(),
            "{:?}",
            response.diagnostics
        );
    }

    #[test]
    fn validates_observe_query_calls() {
        let source = BASE.replace(
            "app TodoApp {",
            "app TodoApp {\n    state todos: Signal<Array<Todo>> = store.observeQuery<Todo>(\"SELECT id, title FROM todos\", [])",
        );
        let response = analyze(&request(&source, Vec::new()));
        assert!(
            response.diagnostics.is_empty(),
            "{:?}",
            response.diagnostics
        );
    }

    #[test]
    fn invalid_sql_on_observe_query_reports_diagnostic() {
        let source = BASE.replace(
            "app TodoApp {",
            "app TodoApp {\n    state todos: Signal<Array<Todo>> = store.observeQuery<Todo>(\"SELECT missing FROM todos\", [])",
        );
        let response = analyze(&request(&source, Vec::new()));
        assert_eq!(response.diagnostics.len(), 1, "{:?}", response.diagnostics);
    }

    #[test]
    fn reports_sql_validation_error_at_the_source_file() {
        let source = BASE.replace(
            "app TodoApp {",
            "fn loadTodos() -> Void {\n    let todos = await store.query<Todo>(\"SELECT missing FROM todos\", [])\n}\napp TodoApp {",
        );
        let response = analyze(&request(&source, Vec::new()));
        assert_eq!(response.diagnostics.len(), 1);
        assert_eq!(response.diagnostics[0].severity, DiagnosticSeverity::Error);
        assert_eq!(response.diagnostics[0].file, "/project/App.nx");
        assert!(response.diagnostics[0].message.contains("invalid SQL"));
    }

    #[test]
    fn validates_functions_from_other_files_in_the_resolved_source_graph() {
        let root = BASE.replace(
            "plugin \"dev.nexa.sqlite\" as SQLite",
            "import \"Storage.nx\"\nplugin \"dev.nexa.sqlite\" as SQLite",
        );
        let mut request = request(&root, Vec::new());
        request.source_files.push(SourceFile {
            path: "/project/Storage.nx".to_owned(),
            contents: "fn loadTodos() -> Void {\n    let todos = await store.query<Todo>(\"SELECT missing FROM todos\", [])\n}\n".to_owned(),
        });
        let response = analyze(&request);
        assert_eq!(response.diagnostics.len(), 1, "{:?}", response.diagnostics);
        assert_eq!(response.diagnostics[0].severity, DiagnosticSeverity::Error);
        assert_eq!(response.diagnostics[0].file, "/project/Storage.nx");
        assert!(response.diagnostics[0].message.contains("invalid SQL"));
    }

    #[test]
    fn warns_about_features_below_the_target_sqlite_floor() {
        let source = BASE.replace(
            "CREATE TABLE todos (id INTEGER PRIMARY KEY, title TEXT NOT NULL)",
            "CREATE TABLE todos (id INTEGER PRIMARY KEY, title TEXT NOT NULL) STRICT",
        );
        let response = analyze(&request(
            &source,
            vec![TargetConfiguration {
                target: "swift".to_owned(),
                ios_minimum_version: Some("14.0".to_owned()),
                android_min_sdk: None,
            }],
        ));
        assert_eq!(response.diagnostics.len(), 1, "{:?}", response.diagnostics);
        let warning = &response.diagnostics[0];
        assert_eq!(warning.severity, DiagnosticSeverity::Warning, "{warning:?}");
        assert_eq!(warning.target.as_deref(), Some("swift"));
        assert!(warning.message.contains("STRICT tables"));
        assert_eq!(warning.file, "/project/App.nx");
    }
}
