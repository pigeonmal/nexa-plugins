package dev.nexa.sqlite

import org.junit.Assert.assertEquals
import org.junit.Test

public class SQLiteParameterScannerTest {
    @Test
    public fun dollarNamesAndDollarInsideIdentifiersFollowSqliteTokens() {
        assertEquals(
            2,
            SQLiteParameterScanner.count("SELECT item\$price, \$name, \$name, \$scope::member(suffix)"),
        )
    }

    @Test
    public fun onlyAsciiDigitsExtendQuestionMarkParameterNumbers() {
        assertEquals(2, SQLiteParameterScanner.count("SELECT ?١, ?"))
    }

    @Test
    public fun ignoresMarkersInQuotedValuesIdentifiersAndComments() {
        assertEquals(
            2,
            SQLiteParameterScanner.count("SELECT '?', \"?\", [?], ? -- ?\n/* ? */ , ?"),
        )
    }

    @Test
    public fun numberedSlotsAndRepeatedNamesFollowSqliteBindingIndexes() {
        assertEquals(6, SQLiteParameterScanner.count("SELECT ?5, ?, ?2"))
        assertEquals(2, SQLiteParameterScanner.count("SELECT :same, :same, @same"))
    }

    @Test
    public fun nonLetterUnicodeCharactersAreAcceptedInsideNamedParameters() {
        assertEquals(2, SQLiteParameterScanner.count("SELECT :a☂, :a☂, :β"))
    }

    @Test
    public fun classifiesMutatingStatementsAndSchemaStatements() {
        assertEquals(SQLiteStatementKind.INSERT, SQLiteParameterScanner.statementKind("INSERT INTO notes VALUES (?)"))
        assertEquals(SQLiteStatementKind.INSERT, SQLiteParameterScanner.statementKind("REPLACE INTO notes VALUES (?)"))
        assertEquals(SQLiteStatementKind.MUTATION, SQLiteParameterScanner.statementKind("UPDATE notes SET title = ?"))
        assertEquals(SQLiteStatementKind.MUTATION, SQLiteParameterScanner.statementKind("DELETE FROM notes"))
        assertEquals(SQLiteStatementKind.SCHEMA, SQLiteParameterScanner.statementKind("CREATE TABLE notes (id INTEGER)"))
        assertEquals(SQLiteStatementKind.SCHEMA, SQLiteParameterScanner.statementKind("-- comment\nALTER TABLE notes ADD COLUMN title TEXT"))
        assertEquals(SQLiteStatementKind.OTHER, SQLiteParameterScanner.statementKind("PRAGMA user_version = 3"))
        assertEquals(SQLiteStatementKind.QUERY, SQLiteParameterScanner.statementKind("SELECT 1"))
    }

    @Test
    public fun classifiesWriteCommandsAfterCommonTableExpressions() {
        assertEquals(
            SQLiteStatementKind.MUTATION,
            SQLiteParameterScanner.statementKind("WITH selected AS (SELECT id FROM notes) DELETE FROM notes WHERE id IN (SELECT id FROM selected)"),
        )
        assertEquals(
            SQLiteStatementKind.INSERT,
            SQLiteParameterScanner.statementKind("WITH RECURSIVE ids(id) AS NOT MATERIALIZED (SELECT 1) INSERT INTO notes SELECT id FROM ids"),
        )
        assertEquals(
            SQLiteStatementKind.QUERY,
            SQLiteParameterScanner.statementKind("WITH ids AS (SELECT 1) SELECT * FROM ids"),
        )
    }

    @Test
    public fun routesReturningWritesToQueryInsteadOfExecute() {
        assertEquals(
            SQLiteStatementKind.QUERY,
            SQLiteParameterScanner.statementKind("INSERT INTO notes(title) VALUES (?) RETURNING id"),
        )
        assertEquals(
            SQLiteStatementKind.QUERY,
            SQLiteParameterScanner.statementKind("WITH target AS (SELECT id FROM notes) UPDATE notes SET title = ? RETURNING id"),
        )
        assertEquals(
            SQLiteStatementKind.QUERY,
            SQLiteParameterScanner.statementKind("DELETE FROM notes RETURNING id"),
        )
        assertEquals(
            SQLiteStatementKind.INSERT,
            SQLiteParameterScanner.statementKind("INSERT INTO notes(title) VALUES ('RETURNING') -- RETURNING\n"),
        )
    }
}
