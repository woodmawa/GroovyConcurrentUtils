package org.softwood.dag.script

import groovy.transform.CompileStatic

/**
 * 1.4.1 (decision D2, {@code allow 'sql'}): what a statement given to {@code sql} is, read without a
 * database.
 *
 * <p>The text is scanned once, skipping quoted text ({@code '...'}, {@code "..."}, {@code `...`},
 * with doubled quotes as escapes) and comments ({@code /* ... *&#47;}, and {@code --} followed by a
 * space or the end of the line). What remains is the code. One statement only: a semicolon in the
 * code may be followed by nothing but space and comments. Where the scan and a database could
 * disagree it errs towards seeing more code: a {@code --} with no space after it is code (MySQL
 * reads it so), comments do not nest, and {@code #} and dollar quoting are code.</p>
 *
 * <p>This is a reduction, not a parser. A read-only datasource should be backed by a database
 * account that cannot write: that is the real control.</p>
 */
@CompileStatic
final class SqlStatementCheck {

    private SqlStatementCheck() {}

    /** Words that make a statement a write, wherever they stand in the code. */
    static final Set<String> WRITE_WORDS = ['INSERT', 'UPDATE', 'DELETE', 'MERGE', 'UPSERT', 'REPLACE', 'INTO',
                                            'DROP', 'ALTER', 'CREATE', 'TRUNCATE', 'GRANT', 'REVOKE', 'RENAME',
                                            'CALL', 'EXEC', 'EXECUTE', 'COPY', 'LOCK', 'VACUUM', 'ATTACH',
                                            'DETACH', 'PRAGMA', 'SHUTDOWN', 'BACKUP', 'RESTORE', 'SCRIPT', 'RUNSCRIPT'] as Set<String>

    /** Words a statement given to update() may not contain: changes to the schema or to rights, and procedure calls. */
    static final Set<String> SCHEMA_WORDS = ['DROP', 'ALTER', 'CREATE', 'TRUNCATE', 'GRANT', 'REVOKE', 'RENAME',
                                             'CALL', 'EXEC', 'EXECUTE', 'COPY', 'LOCK', 'VACUUM', 'ATTACH', 'DETACH',
                                             'PRAGMA', 'SHUTDOWN', 'BACKUP', 'RESTORE', 'SCRIPT', 'RUNSCRIPT'] as Set<String>

    static final Set<String> READ_LEADS = ['SELECT', 'WITH'] as Set<String>
    static final Set<String> WRITE_LEADS = ['INSERT', 'UPDATE', 'DELETE', 'MERGE'] as Set<String>

    /** The result of a scan. */
    static final class Scanned {
        /** The statement to send: the text before a trailing semicolon. */
        final String statement
        /** The code words, upper case, in order. */
        final List<String> words

        Scanned(String statement, List<String> words) {
            this.statement = statement
            this.words = words
        }

        String getLead() { words ? words[0] : '' }
    }

    /** Scan a statement, refusing a second one. */
    static Scanned scan(String sql) {
        if (sql == null || !sql.trim()) throw new IllegalArgumentException('sql: a statement is required')
        StringBuilder code = new StringBuilder()
        int n = sql.length()
        int i = 0
        int end = -1     // the index of the first semicolon in code
        while (i < n) {
            char c = sql.charAt(i)
            if (c == '\'' as char || c == '"' as char || c == '`' as char) {
                int j = i + 1
                while (j < n) {
                    if (sql.charAt(j) == c) {
                        if (j + 1 < n && sql.charAt(j + 1) == c) { j += 2; continue }
                        break
                    }
                    j++
                }
                if (j >= n) throw new SecurityException('sql: the statement has an unclosed quote')
                if (end >= 0) return refuseSecond()
                code.append(' ')
                i = j + 1
                continue
            }
            if (c == '/' as char && i + 1 < n && sql.charAt(i + 1) == '*' as char) {
                int close = sql.indexOf('*/', i + 2)
                if (close < 0) throw new SecurityException('sql: the statement has an unclosed comment')
                code.append(' ')
                i = close + 2
                continue
            }
            if (c == '-' as char && i + 1 < n && sql.charAt(i + 1) == '-' as char &&
                    (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2)))) {
                int nl = sql.indexOf('\n', i)
                code.append(' ')
                i = nl < 0 ? n : nl
                continue
            }
            if (c == ';' as char) {
                if (end >= 0) return refuseSecond()
                end = i
                i++
                continue
            }
            if (end >= 0 && !Character.isWhitespace(c)) return refuseSecond()
            code.append(c)
            i++
        }
        String statement = end >= 0 ? sql.substring(0, end) : sql
        List<String> words = []
        code.toString().toUpperCase().findAll(/[A-Z_][A-Z0-9_$]*/).each { Object w -> words << (String) w }
        return new Scanned(statement, words)
    }

    /** A statement a read may send: it starts with SELECT or WITH and has no write word anywhere in its code. */
    static Scanned requireRead(String sql) {
        Scanned s = scan(sql)
        if (!READ_LEADS.contains(s.lead)) {
            throw new SecurityException("sql: a query must start with SELECT or WITH, not ${s.lead ?: 'nothing'}".toString())
        }
        String write = s.words.find { String w -> WRITE_WORDS.contains(w) }
        if (write) throw new SecurityException("sql: a query cannot contain ${write}".toString())
        return s
    }

    /** A statement update() may send: INSERT, UPDATE, DELETE or MERGE, with no schema change, rights or procedure call. */
    static Scanned requireWrite(String sql) {
        Scanned s = scan(sql)
        if (!WRITE_LEADS.contains(s.lead)) {
            throw new SecurityException("sql: update takes INSERT, UPDATE, DELETE or MERGE, not ${s.lead ?: 'nothing'}".toString())
        }
        String schema = s.words.find { String w -> SCHEMA_WORDS.contains(w) }
        if (schema) throw new SecurityException("sql: update cannot contain ${schema}".toString())
        return s
    }

    private static Scanned refuseSecond() {
        throw new SecurityException('sql: one statement per call: there is more after the semicolon')
    }
}
