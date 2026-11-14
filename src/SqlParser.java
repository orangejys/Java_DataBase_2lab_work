import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разбирает один SQL-запрос целиком до передачи операции движку. */
final class SqlParser {
    private static final Pattern TOKEN = Pattern.compile(
            "([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)|"
            + "([A-Za-z_][A-Za-z_0-9]*)|(<=|>=|<>|!=|[(),;*=<>])");

    enum Kind { STRING, NUMBER, WORD, SYMBOL, END }

    static final class Literal {
        final Kind kind;
        final String text;

        Literal(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        boolean isNull() {
            return kind == Kind.WORD && "null".equalsIgnoreCase(text);
        }
    }

    static final class Where {
        final String column;
        final String operator;
        final Literal literal;

        Where(String column, String operator, Literal literal) {
            this.column = column;
            this.operator = operator;
            this.literal = literal;
        }
    }

    private final Database database;
    private final boolean useIndexes;
    private final List<Literal> tokens = new ArrayList<>();
    private int cursor;

    SqlParser(Database database, String sql, boolean useIndexes) {
        this.database = database;
        this.useIndexes = useIndexes;
        Matcher matcher = TOKEN.matcher(sql);
        int offset = 0;
        while (offset < sql.length()) {
            if (Character.isWhitespace(sql.charAt(offset))) {
                offset++;
                continue;
            }
            if (sql.charAt(offset) == '\'') {
                int start = offset++;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (offset < sql.length()) {
                    char character = sql.charAt(offset++);
                    if (character != '\'') {
                        value.append(character);
                    } else if (offset < sql.length() && sql.charAt(offset) == '\'') {
                        value.append(character);
                        offset++;
                    } else {
                        closed = true;
                        break;
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("Незакрытая строка на позиции " + (start + 1) + ".");
                }
                tokens.add(new Literal(Kind.STRING, value.toString()));
                continue;
            }
            matcher.region(offset, sql.length());
            if (!matcher.lookingAt()) {
                throw new IllegalArgumentException("Неверный символ на позиции " + (offset + 1) + ".");
            }
            String token = matcher.group();
            if (matcher.group(1) != null) {
                tokens.add(new Literal(Kind.NUMBER, token));
            } else if (matcher.group(2) != null) {
                tokens.add(new Literal(Kind.WORD, token));
            } else {
                tokens.add(new Literal(Kind.SYMBOL, token));
            }
            offset = matcher.end();
        }
        tokens.add(new Literal(Kind.END, "конец запроса"));
    }

    Result execute() {
        switch (identifier()) {
            case "insert": return insert();
            case "select": return select();
            case "update": return update();
            case "delete": return delete();
            case "create": return create();
            case "drop":
                expect("TABLE");
                String name = identifier();
                finish();
                return database.dropTable(name);
            default: throw new IllegalArgumentException("Неизвестная команда SQL.");
        }
    }

    private Result insert() {
        expect("INTO");
        String table = identifier();
        List<String> columns = Collections.emptyList();
        if (take("(")) {
            columns = names();
            expect(")");
        }
        expect("VALUES");
        List<List<Literal>> rows = new ArrayList<>();
        do {
            expect("(");
            List<Literal> row = new ArrayList<>();
            do {
                row.add(literal());
            } while (take(","));
            expect(")");
            rows.add(row);
        } while (take(","));
        finish();
        return database.insert(table, columns, rows);
    }

    private Result select() {
        List<String> columns = take("*") ? Collections.emptyList() : names();
        expect("FROM");
        String table = identifier();
        Where where = where();
        finish();
        return database.select(table, columns, where, useIndexes);
    }

    private Result update() {
        String table = identifier();
        expect("SET");
        Map<String, Literal> assignments = new LinkedHashMap<>();
        do {
            String column = identifier();
            expect("=");
            if (assignments.putIfAbsent(column, literal()) != null) {
                throw new IllegalArgumentException("Столбец " + column + " указан дважды.");
            }
        } while (take(","));
        expect("WHERE");
        Where where = comparison();
        finish();
        return database.update(table, assignments, where);
    }

    private Result delete() {
        expect("FROM");
        String table = identifier();
        expect("WHERE");
        Where where = comparison();
        finish();
        return database.delete(table, where);
    }

    private Result create() {
        if (take("INDEX")) {
            String index = identifier();
            expect("ON");
            String table = identifier();
            expect("(");
            String column = identifier();
            expect(")");
            finish();
            return database.createIndex(index, table, column);
        }
        expect("TABLE");
        String table = identifier();
        expect("(");
        List<Column> columns = new ArrayList<>();
        do {
            columns.add(column());
        } while (take(","));
        expect(")");
        finish();
        return database.createTable(table, columns);
    }

    private Column column() {
        String name = identifier();
        Column.Type type = Column.Type.parse(identifier());
        boolean primary = false;
        boolean required = false;
        String parentTable = null;
        String parentColumn = null;
        while (true) {
            if (!primary && take("PRIMARY")) {
                expect("KEY");
                primary = true;
            } else if (!required && take("NOT")) {
                expect("NULL");
                required = true;
            } else if (parentTable == null && take("REFERENCES")) {
                parentTable = identifier();
                expect("(");
                parentColumn = identifier();
                expect(")");
            } else {
                return new Column(name, type, primary, required, parentTable, parentColumn);
            }
        }
    }

    private Where where() {
        return take("WHERE") ? comparison() : null;
    }

    private Where comparison() {
        String column = identifier();
        if (take("IS")) {
            boolean negate = take("NOT");
            expect("NULL");
            return new Where(column, negate ? "IS NOT NULL" : "IS NULL", null);
        }
        Literal operator = peek();
        if (operator.kind != Kind.SYMBOL || !operator.text.matches("=|<>|!=|<|<=|>|>=")) {
            throw new IllegalArgumentException("Ожидался оператор сравнения, получено «" + operator.text + "».");
        }
        cursor++;
        return new Where(column, operator.text, literal());
    }

    private List<String> names() {
        List<String> names = new ArrayList<>();
        do {
            names.add(identifier());
        } while (take(","));
        return names;
    }

    private String identifier() {
        Literal token = peek();
        if (token.kind != Kind.WORD) {
            throw new IllegalArgumentException("Ожидалось имя, получено «" + token.text + "».");
        }
        cursor++;
        return token.text.toLowerCase(Locale.ROOT);
    }

    private Literal literal() {
        Literal token = peek();
        if (token.kind == Kind.END || token.kind == Kind.SYMBOL) {
            throw new IllegalArgumentException("Ожидалось значение, получено «" + token.text + "».");
        }
        cursor++;
        return token;
    }

    private Literal peek() {
        return tokens.get(cursor);
    }

    private boolean take(String expected) {
        Literal token = peek();
        if ((token.kind == Kind.WORD || token.kind == Kind.SYMBOL)
                && token.text.equalsIgnoreCase(expected)) {
            cursor++;
            return true;
        }
        return false;
    }

    private void expect(String expected) {
        if (!take(expected)) {
            throw new IllegalArgumentException("Ожидалось " + expected + ", получено «" + peek().text + "».");
        }
    }

    private void finish() {
        take(";");
        if (peek().kind != Kind.END) {
            throw new IllegalArgumentException("Лишняя часть запроса около «" + peek().text + "».");
        }
    }
}
