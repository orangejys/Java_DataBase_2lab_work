import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Читает запросы и отображает результат. Правила базы находятся в движке. */
final class Console {
    private final Database database;
    private final BufferedReader input;
    private final PrintWriter output;
    private final boolean interactive;

    Console(Database database, BufferedReader input, PrintWriter output, boolean interactive) {
        this.database = database;
        this.input = input;
        this.output = output;
        this.interactive = interactive;
    }

    void run() throws IOException {
        output.println("СУБД «Библиотека». Введите SQL, HELP или EXIT.");
        while (true) {
            if (interactive) {
                output.print("library> ");
                output.flush();
            }
            String line = input.readLine();
            if (line == null) {
                return;
            }
            line = line.trim();
            if (line.isEmpty() || line.startsWith("--")) {
                continue;
            }
            String command = line.endsWith(";")
                    ? line.substring(0, line.length() - 1).trim() : line;
            if ("EXIT".equalsIgnoreCase(command) || "QUIT".equalsIgnoreCase(command)) {
                return;
            }
            if ("HELP".equalsIgnoreCase(command)) {
                output.println("Один запрос в строке. Команды SQL: INSERT INTO, SELECT, UPDATE, "
                        + "DELETE FROM, CREATE TABLE, CREATE INDEX, DROP TABLE.");
                output.println("Начальные таблицы: authors и books. Пример: SELECT * FROM books;");
                continue;
            }
            try {
                print(database.execute(line));
            } catch (IllegalArgumentException | UncheckedIOException ex) {
                output.println("Ошибка: " + ex.getMessage());
            }
        }
    }

    private void print(Result result) {
        if (result.message != null) {
            output.println(result.message);
            return;
        }
        int[] widths = new int[result.columns.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = result.columns.get(i).length();
        }
        List<String[]> formatted = new ArrayList<>();
        for (Object[] row : result.rows) {
            String[] cells = new String[widths.length];
            for (int i = 0; i < widths.length; i++) {
                cells[i] = row[i] == null ? "NULL" : row[i].toString();
                widths[i] = Math.max(widths[i], cells[i].length());
            }
            formatted.add(cells);
        }
        StringBuilder separator = new StringBuilder("+");
        for (int width : widths) {
            for (int i = 0; i < width + 2; i++) {
                separator.append('-');
            }
            separator.append('+');
        }
        output.println(separator);
        printRow(result.columns.toArray(new String[0]), widths);
        output.println(separator);
        for (String[] cells : formatted) {
            printRow(cells, widths);
        }
        output.println(separator);
        output.println("Строк: " + result.rows.size() + " | План: " + result.plan
                + " | Проверено: " + result.checked);
    }

    private void printRow(String[] cells, int[] widths) {
        StringBuilder line = new StringBuilder("|");
        for (int i = 0; i < cells.length; i++) {
            line.append(' ').append(cells[i]);
            for (int space = cells[i].length(); space <= widths[i]; space++) {
                line.append(' ');
            }
            line.append('|');
        }
        output.println(line);
    }
}
