mod analyzer;
mod compat;
mod sqlite;

use nexa_plugin_compiler_api::{ANALYZER_PROTOCOL_VERSION, AnalysisRequest, AnalysisResponse};
use std::io::{self, BufRead, Write};

fn main() {
    let stdin = io::stdin();
    let stdout = io::stdout();
    let mut output = stdout.lock();
    for line in stdin.lock().lines() {
        let line = match line {
            Ok(line) => line,
            Err(error) => {
                eprintln!("SQLite analyzer could not read request: {error}");
                break;
            }
        };
        let request: AnalysisRequest = match serde_json::from_str(&line) {
            Ok(request) => request,
            Err(error) => {
                eprintln!("SQLite analyzer received an invalid request: {error}");
                break;
            }
        };
        let response = analyzer::analyze(&request);
        if let Err(error) = write_response(&mut output, &response) {
            eprintln!("SQLite analyzer could not write response: {error}");
            break;
        }
    }
}

fn write_response(output: &mut impl Write, response: &AnalysisResponse) -> io::Result<()> {
    serde_json::to_writer(&mut *output, response).map_err(io::Error::other)?;
    output.write_all(b"\n")?;
    output.flush()
}

#[allow(dead_code)]
const _: u16 = ANALYZER_PROTOCOL_VERSION;
