package dev.nexa.sqlite

/**
 * Counts SQLite bind slots for Android, whose public prepared-statement APIs
 * do not expose sqlite3_bind_parameter_count(). This follows SQLite token
 * rules for ASCII `?NNN` digits, identifier bytes, and Tcl-style `$` names.
 */
internal object SQLiteParameterScanner {
    fun count(sql: String): Int {
        var position = 0
        var highestIndex = 0L
        var namedIndices: HashMap<String, Long>? = null

        while (position < sql.length) {
            when (sql[position]) {
                '\'', '"', '`' -> position = skipQuoted(sql, position, sql[position])
                '[' -> position = skipBracketIdentifier(sql, position)
                '-' -> {
                    if (sql.getOrNull(position + 1) == '-') {
                        position += 2
                        while (position < sql.length && sql[position] != '\n') position++
                    } else {
                        position++
                    }
                }
                '/' -> {
                    if (sql.getOrNull(position + 1) == '*') {
                        position += 2
                        while (position + 1 < sql.length && !(sql[position] == '*' && sql[position + 1] == '/')) {
                            position++
                        }
                        position = (position + 2).coerceAtMost(sql.length)
                    } else {
                        position++
                    }
                }
                '?' -> {
                    position++
                    val digitsStart = position
                    while (sql.getOrNull(position)?.let { it in '0'..'9' } == true) position++
                    if (digitsStart == position) {
                        highestIndex = (highestIndex + 1).coerceAtMost(Int.MAX_VALUE.toLong())
                    } else {
                        val explicitIndex = sql.substring(digitsStart, position).toLongOrNull()
                            ?: Long.MAX_VALUE
                        highestIndex = maxOf(highestIndex, explicitIndex)
                    }
                }
                ':', '@', '$' -> {
                    val markerPosition = position
                    position++
                    if (sql.getOrNull(position)?.let(::isParameterNameCharacter) != true) continue
                    while (sql.getOrNull(position)?.let(::isParameterNameCharacter) == true) position++
                    if (sql[markerPosition] == '$') {
                        while (sql.getOrNull(position) == ':' && sql.getOrNull(position + 1) == ':') {
                            position += 2
                            val segmentStart = position
                            while (sql.getOrNull(position)?.let(::isParameterNameCharacter) == true) position++
                            if (position == segmentStart) break
                        }
                        if (sql.getOrNull(position) == '(') {
                            val close = sql.indexOf(')', position + 1)
                            position = if (close >= 0) close + 1 else sql.length
                        }
                    }
                    val name = sql.substring(markerPosition, position)
                    val indices = namedIndices ?: HashMap<String, Long>().also { namedIndices = it }
                    val nextIndex = indices[name] ?: (highestIndex.coerceAtMost(Int.MAX_VALUE.toLong() - 1) + 1)
                        .also { indices[name] = it }
                    highestIndex = maxOf(highestIndex, nextIndex)
                }
                else -> {
                    if (isParameterNameCharacter(sql[position])) {
                        while (sql.getOrNull(position)?.let(::isParameterNameCharacter) == true) position++
                    } else {
                        position++
                    }
                }
            }
        }
        return highestIndex.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun statementKind(sql: String): SQLiteStatementKind {
        val first = keywordAt(sql, skipTrivia(sql, 0)) ?: return SQLiteStatementKind.OTHER
        val keyword = if (first.first == "WITH") commandAfterWith(sql, first.second) else first.first
        return when (keyword) {
            "INSERT", "REPLACE" -> if (hasTopLevelKeyword(sql, "RETURNING")) {
                SQLiteStatementKind.QUERY
            } else {
                SQLiteStatementKind.INSERT
            }
            "UPDATE", "DELETE" -> if (hasTopLevelKeyword(sql, "RETURNING")) {
                SQLiteStatementKind.QUERY
            } else {
                SQLiteStatementKind.MUTATION
            }
            "SELECT", "VALUES", "EXPLAIN" -> SQLiteStatementKind.QUERY
            "CREATE", "ALTER", "DROP", "VACUUM", "REINDEX", "ANALYZE",
            "ATTACH", "DETACH" -> SQLiteStatementKind.SCHEMA
            else -> SQLiteStatementKind.OTHER
        }
    }

    private fun hasTopLevelKeyword(sql: String, expected: String): Boolean {
        var position = 0
        var depth = 0
        while (position < sql.length) {
            when {
                sql[position] == '\'' || sql[position] == '"' || sql[position] == '`' -> {
                    position = skipQuoted(sql, position, sql[position])
                }
                sql[position] == '[' -> position = skipBracketIdentifier(sql, position)
                sql[position] == '-' && sql.getOrNull(position + 1) == '-' -> {
                    position = skipTrivia(sql, position)
                }
                sql[position] == '/' && sql.getOrNull(position + 1) == '*' -> {
                    position = skipTrivia(sql, position)
                }
                sql[position] == '(' -> {
                    depth++
                    position++
                }
                sql[position] == ')' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    position++
                }
                sql[position].let(::isAsciiIdentifierStart) -> {
                    val start = position
                    position++
                    while (sql.getOrNull(position)?.let(::isIdentifierCharacter) == true) position++
                    if (depth == 0 && sql.substring(start, position).equals(expected, ignoreCase = true)) {
                        return true
                    }
                }
                else -> position++
            }
        }
        return false
    }

    private fun commandAfterWith(sql: String, afterWith: Int): String? {
        var position = skipTrivia(sql, afterWith)
        val recursive = keywordAt(sql, position)
        if (recursive?.first == "RECURSIVE") position = recursive.second

        while (position < sql.length) {
            position = skipTrivia(sql, position)
            position = skipIdentifier(sql, position)
            position = skipTrivia(sql, position)
            if (sql.getOrNull(position) == '(') {
                position = skipParenthesized(sql, position)
                position = skipTrivia(sql, position)
            }

            val asKeyword = keywordAt(sql, position) ?: return null
            if (asKeyword.first != "AS") return null
            position = skipTrivia(sql, asKeyword.second)

            val optionalMaterialized = keywordAt(sql, position)
            when (optionalMaterialized?.first) {
                "MATERIALIZED" -> position = optionalMaterialized.second
                "NOT" -> {
                    val materialized = keywordAt(sql, optionalMaterialized.second)
                    if (materialized?.first == "MATERIALIZED") position = materialized.second
                }
            }

            position = skipTrivia(sql, position)
            if (sql.getOrNull(position) != '(') return null
            position = skipParenthesized(sql, position)
            position = skipTrivia(sql, position)
            if (sql.getOrNull(position) == ',') {
                position++
                continue
            }
            return keywordAt(sql, position)?.first
        }
        return null
    }

    private fun keywordAt(sql: String, start: Int): Pair<String, Int>? {
        val position = skipTrivia(sql, start)
        if (sql.getOrNull(position)?.let(::isAsciiIdentifierStart) != true) return null
        var end = position + 1
        while (sql.getOrNull(end)?.let(::isIdentifierCharacter) == true) end++
        return sql.substring(position, end).uppercase() to end
    }

    private fun skipTrivia(sql: String, start: Int): Int {
        var position = start
        while (position < sql.length) {
            when {
                sql[position].isWhitespace() -> position++
                sql[position] == '-' && sql.getOrNull(position + 1) == '-' -> {
                    position += 2
                    while (position < sql.length && sql[position] != '\n') position++
                }
                sql[position] == '/' && sql.getOrNull(position + 1) == '*' -> {
                    position += 2
                    while (position + 1 < sql.length && !(sql[position] == '*' && sql[position + 1] == '/')) {
                        position++
                    }
                    position = (position + 2).coerceAtMost(sql.length)
                }
                else -> return position
            }
        }
        return position
    }

    private fun skipIdentifier(sql: String, start: Int): Int = when (sql.getOrNull(start)) {
        '\'', '"', '`' -> skipQuoted(sql, start, sql[start])
        '[' -> skipBracketIdentifier(sql, start)
        else -> keywordAt(sql, start)?.second ?: (start + 1).coerceAtMost(sql.length)
    }

    private fun skipParenthesized(sql: String, start: Int): Int {
        var position = start
        var depth = 0
        while (position < sql.length) {
            when {
                sql[position] == '\'' || sql[position] == '"' || sql[position] == '`' -> {
                    position = skipQuoted(sql, position, sql[position])
                }
                sql[position] == '[' -> position = skipBracketIdentifier(sql, position)
                sql[position] == '-' && sql.getOrNull(position + 1) == '-' -> {
                    position = skipTrivia(sql, position)
                }
                sql[position] == '/' && sql.getOrNull(position + 1) == '*' -> {
                    position = skipTrivia(sql, position)
                }
                sql[position] == '(' -> {
                    depth++
                    position++
                }
                sql[position] == ')' -> {
                    depth--
                    position++
                    if (depth == 0) return position
                }
                else -> position++
            }
        }
        return sql.length
    }

    private fun isAsciiIdentifierStart(value: Char): Boolean =
        value in 'A'..'Z' || value in 'a'..'z' || value == '_'

    private fun isIdentifierCharacter(value: Char): Boolean =
        isParameterNameCharacter(value)

    private fun skipQuoted(sql: String, start: Int, quote: Char): Int {
        var position = start + 1
        while (position < sql.length) {
            if (sql[position] == quote) {
                if (sql.getOrNull(position + 1) == quote) {
                    position += 2
                } else {
                    return position + 1
                }
            } else {
                position++
            }
        }
        return sql.length
    }

    private fun skipBracketIdentifier(sql: String, start: Int): Int {
        val close = sql.indexOf(']', start + 1)
        return if (close >= 0) close + 1 else sql.length
    }

    fun extractReadTables(sql: String): Set<String> {
        val tables = HashSet<String>()
        var position = 0
        while (position < sql.length) {
            when {
                sql[position] == '\'' || sql[position] == '"' || sql[position] == '`' -> {
                    position = skipQuoted(sql, position, sql[position])
                }
                sql[position] == '[' -> position = skipBracketIdentifier(sql, position)
                sql[position] == '-' && sql.getOrNull(position + 1) == '-' -> {
                    position = skipTrivia(sql, position)
                }
                sql[position] == '/' && sql.getOrNull(position + 1) == '*' -> {
                    position = skipTrivia(sql, position)
                }
                isAsciiIdentifierStart(sql[position]) -> {
                    val start = position
                    position++
                    while (sql.getOrNull(position)?.let(::isIdentifierCharacter) == true) position++
                    val word = sql.substring(start, position).uppercase()
                    if (word == "FROM" || word == "JOIN") {
                        val next = skipTrivia(sql, position)
                        if (next < sql.length && sql[next] != '(') {
                            if (sql[next] == '\'' || sql[next] == '"' || sql[next] == '`') {
                                val quote = sql[next]
                                val end = skipQuoted(sql, next, quote)
                                val table = sql.substring(next + 1, (end - 1).coerceAtLeast(next + 1))
                                if (table.isNotEmpty()) tables.add(table)
                                position = end
                            } else if (sql[next] == '[') {
                                val end = skipBracketIdentifier(sql, next)
                                val table = sql.substring(next + 1, (end - 1).coerceAtLeast(next + 1))
                                if (table.isNotEmpty()) tables.add(table)
                                position = end
                            } else if (isAsciiIdentifierStart(sql[next])) {
                                val idStart = next
                                var idEnd = idStart + 1
                                while (sql.getOrNull(idEnd)?.let(::isIdentifierCharacter) == true) idEnd++
                                var table = sql.substring(idStart, idEnd)
                                if (sql.getOrNull(idEnd) == '.') {
                                    val schemaTableStart = idEnd + 1
                                    var schemaTableEnd = schemaTableStart
                                    while (sql.getOrNull(schemaTableEnd)?.let(::isIdentifierCharacter) == true) schemaTableEnd++
                                    if (schemaTableEnd > schemaTableStart) {
                                        table = sql.substring(schemaTableStart, schemaTableEnd)
                                        idEnd = schemaTableEnd
                                    }
                                }
                                if (table.isNotEmpty() && !table.equals("SELECT", ignoreCase = true)) {
                                    tables.add(table)
                                }
                                position = idEnd
                            }
                        }
                    }
                }
                else -> position++
            }
        }
        return tables
    }

    // SQLite's IdChar accepts every non-ASCII byte, including Unicode
    // punctuation and digits, not only characters classified as letters.
    private fun isParameterNameCharacter(value: Char): Boolean =
        value == '_' || value == '$' || value.isLetterOrDigit() || value.code >= 0x80
}

internal enum class SQLiteStatementKind {
    INSERT,
    MUTATION,
    SCHEMA,
    QUERY,
    OTHER,
}
