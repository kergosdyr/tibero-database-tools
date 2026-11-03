package poc;

import java.util.Locale;
import java.util.Set;

/** Statement-boundary guard, not a SQL grammar parser. The server validates SQL syntax. */
final class ExplainSql {
    static String singleStatement(String sql) { return statement(sql, false); }
    static String analysisStatement(String sql) { return statement(sql, true); }
    private static String statement(String sql, boolean actual) {
        if (sql == null || sql.isBlank()) throw bad();
        int end = sql.length();
        String first = null;
        boolean terminated = false;
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (sql.startsWith("--", i)) {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                continue;
            }
            if (sql.startsWith("/*", i)) {
                int close = sql.indexOf("*/", i + 2);
                if (close < 0) throw bad();
                i = close + 2; continue;
            }
            if (terminated) throw bad();
            if (first == null) {
                int start = i;
                while (i < sql.length() && Character.isLetter(sql.charAt(i))) i++;
                first = sql.substring(start, i).toUpperCase(Locale.ROOT);
                if (!Set.of("SELECT", "WITH", "INSERT", "UPDATE", "DELETE").contains(first)) throw bad();
                if (actual && !Set.of("SELECT", "WITH").contains(first)) throw analysisBad();
                continue;
            }
            if (c == ';') { end = i; terminated = true; i++; continue; }
            // Tibero alternative quoting: q'[text]', q'{text}', q'(text)', q'<text>', q'!text!'.
            if ((c == 'q' || c == 'Q') && i + 2 < sql.length() && sql.charAt(i + 1) == '\''
                    && (i == 0 || !(Character.isJavaIdentifierPart(sql.charAt(i - 1)) || sql.charAt(i - 1) == '#'))) {
                char open = sql.charAt(i + 2);
                char close = switch (open) { case '[' -> ']'; case '{' -> '}'; case '(' -> ')'; case '<' -> '>'; default -> open; };
                if (Character.isWhitespace(open) || open == '\'') throw bad();
                int finish = sql.indexOf("" + close + '\'', i + 3);
                if (finish < 0) throw bad();
                i = finish + 2; continue;
            }
            if (c == '\'' || c == '"') {
                char quote = c; boolean closed = false; i++;
                while (i < sql.length()) {
                    if (sql.charAt(i++) != quote) continue;
                    if (i < sql.length() && sql.charAt(i) == quote) { i++; continue; }
                    closed = true; break;
                }
                if (!closed) throw bad();
                continue;
            }
            if (actual && (Character.isLetter(c) || c == '_')) {
                int start = i++;
                while (i < sql.length() && (Character.isJavaIdentifierPart(sql.charAt(i)) || sql.charAt(i) == '#')) i++;
                String token = sql.substring(start, i).toUpperCase(Locale.ROOT);
                if (Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "INTO", "FUNCTION", "PROCEDURE").contains(token)) throw analysisBad();
                continue;
            }
            i++;
        }
        if (first == null) throw bad();
        return sql.substring(0, end).stripTrailing();
    }
    private static IllegalArgumentException analysisBad() {
        return new IllegalArgumentException("Explain Analyse executes SQL. Only SELECT/WITH queries are supported; DML, SELECT INTO, FOR UPDATE and inline routines are not accepted");
    }
    private static IllegalArgumentException bad() {
        return new IllegalArgumentException("Explain Plan requires one SELECT/WITH/INSERT/UPDATE/DELETE statement; scripts and DDL are not accepted");
    }
}
