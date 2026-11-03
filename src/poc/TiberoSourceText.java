package poc;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Editable source text: complete, schema-qualified CREATE OR REPLACE statements, as Oracle source
 * editors show them. A package carries its body as a second statement after a "/" line.
 */
public final class TiberoSourceText {
    private TiberoSourceText() {}
    // ALL_SOURCE starts at the object keyword: "PROCEDURE NAME ...", "PACKAGE BODY NAME ...".
    private static final Pattern HEADER=Pattern.compile("^\\s*((?:PACKAGE|TYPE)\\s+BODY|PROCEDURE|FUNCTION|PACKAGE|TYPE|TRIGGER)\\s+(\"[^\"]+\"|[A-Za-z_][\\w$#]*)(\\s*\\.)?",Pattern.CASE_INSENSITIVE);
    private static final Pattern SLASH_LINE=Pattern.compile("(?m)^[ \\t]*/[ \\t]*$");

    /** ALL_SOURCE text → statement. The name is qualified so a submitted edit replaces the object in its own schema, not the session's. */
    public static String ddl(String schema,String source) {
        if(source==null || source.isBlank()) return "";
        var header=HEADER.matcher(source);
        if(header.find() && header.group(3)==null)
            source=source.substring(0,header.start(2)) + identifier(schema) + "." + source.substring(header.start(2));
        return "CREATE OR REPLACE " + source.strip();
    }
    public static String packageSource(String specification,String body) {
        return specification.isEmpty() || body.isEmpty() ? specification : specification + "\n/\n" + body;
    }
    public static String view(String schema,String name,String query) {
        return query==null || query.isBlank() ? "" : "CREATE OR REPLACE VIEW " + identifier(schema) + "." + identifier(name) + " AS\n" + query.strip();
    }
    public static String identifier(String name) {
        return name.matches("[A-Z][A-Z0-9_$#]*") ? name : "\"" + name.replace("\"","\"\"") + "\"";
    }
    /** Statements separated by lines holding only "/". */
    public static List<String> statements(CharSequence source) {
        return SLASH_LINE.splitAsStream(source).map(String::strip).filter(part -> !part.isEmpty()).toList();
    }
}
