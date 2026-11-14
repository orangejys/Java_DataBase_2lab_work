import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.Map;

/** Точка входа. Открывает папку базы и запускает консоль. */
public final class Main {
    private Main() {
    }

    /**
     * Запускает СУБД. При перенаправлении ввода и вывода используется UTF-8.
     *
     * @param args необязательный путь к папке данных, по умолчанию data
     */
    public static void main(String[] args) {
        java.io.Console terminal = System.console();
        BufferedReader input = terminal == null
                ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                : new BufferedReader(terminal.reader());
        PrintWriter output = terminal == null
                ? new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)
                : terminal.writer();
        if (args.length > 1) {
            output.println("Использование: java -cp out Main [папка_данных]");
            System.exit(1);
        }
        try {
            FileRepository repository = new FileRepository(
                    Paths.get(args.length == 0 ? "data" : args[0]));
            try (FileChannel channel = repository.openLockFile()) {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    throw new IOException("Эта база уже открыта в другом процессе.");
                }
                run(repository, input, output, terminal != null);
            }
        } catch (IOException | InvalidPathException ex) {
            output.println("Ошибка запуска или ввода-вывода: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static void run(FileRepository repository, BufferedReader input, PrintWriter output,
                            boolean interactive) throws IOException {
        Map<String, Table> tables = repository.load();
        if (repository.newDatabase()) {
            tables = Database.createInitialCatalog(repository);
        }
        if (repository.recovered()) {
            output.println("База загружена из предыдущего снимка.");
        }
        new Console(new Database(repository, tables), input, output, interactive).run();
    }
}
