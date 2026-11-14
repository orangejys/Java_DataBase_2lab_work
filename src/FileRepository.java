import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Хранит один снимок всего каталога на диске. */
final class FileRepository {
    private static final int FORMAT = 0x4C444232; // LDB2

    private final Path folder;
    private final Path current;
    private final Path backup;
    private final Path pending;
    private boolean mainIsReadable;
    private boolean recovered;
    private boolean newDatabase;

    FileRepository(Path folder) throws IOException {
        this.folder = folder;
        Files.createDirectories(folder);
        current = folder.resolve("library.db");
        backup = folder.resolve("library.db.bak");
        pending = folder.resolve("library.db.tmp");
    }

    FileChannel openLockFile() throws IOException {
        return FileChannel.open(folder.resolve("library.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    /** Читает основной снимок или, если он не открывается, резервный. */
    Map<String, Table> load() throws IOException {
        if (Files.notExists(current) && Files.notExists(backup)) {
            newDatabase = true;
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Table> tables = read(current);
            mainIsReadable = true;
            return tables;
        } catch (IOException mainError) {
            try {
                Map<String, Table> tables = read(backup);
                recovered = true;
                return tables;
            } catch (IOException backupError) {
                throw new IOException("Не удалось открыть базу. Основной снимок: "
                        + mainError.getMessage() + ". Резервный снимок: " + backupError.getMessage(), backupError);
            }
        }
    }

    boolean recovered() {
        return recovered;
    }

    boolean newDatabase() {
        return newDatabase;
    }

    /** Сначала записывает новый снимок во временный файл, затем заменяет основной. */
    void save(Map<String, Table> tables) throws IOException {
        try (FileOutputStream file = new FileOutputStream(pending.toFile());
             DataOutputStream out = new DataOutputStream(file)) {
            writeSnapshot(out, tables);
            out.flush();
            file.getFD().sync();
        }
        if (mainIsReadable) {
            Path backupTemp = folder.resolve("library.db.bak.tmp");
            Files.copy(current, backupTemp, StandardCopyOption.REPLACE_EXISTING);
            move(backupTemp, backup);
        }
        mainIsReadable = false;
        move(pending, current);
        mainIsReadable = true;
    }

    private static Map<String, Table> read(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            return readSnapshot(in);
        } catch (IllegalArgumentException | DateTimeException ex) {
            throw new IOException("Некорректный снимок " + path.getFileName(), ex);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeSnapshot(DataOutputStream out, Map<String, Table> tables) throws IOException {
        out.writeInt(FORMAT);
        out.writeInt(tables.size());
        for (Table table : tables.values()) {
            writeText(out, table.name);
            out.writeInt(table.columns.size());
            for (Column column : table.columns) {
                writeText(out, column.name);
                out.writeInt(column.type.ordinal());
                out.writeBoolean(column.primary);
                out.writeBoolean(column.required);
                out.writeBoolean(column.parentTable != null);
                if (column.parentTable != null) {
                    writeText(out, column.parentTable);
                    writeText(out, column.parentColumn);
                }
            }
            out.writeInt(table.rows.size());
            for (Object[] row : table.rows.values()) {
                for (int i = 0; i < table.columns.size(); i++) {
                    writeValue(out, table.columns.get(i).type, row[i]);
                }
            }
            out.writeInt(table.indexes.size());
            for (Index index : table.indexes) {
                writeText(out, index.name);
                out.writeInt(index.position);
                out.writeInt(index.keysByValue.size());
                for (Object value : index.keysByValue.keySet()) {
                    writeValue(out, table.columns.get(index.position).type, value);
                    List<Object> keys = index.keysByValue.get(value);
                    out.writeInt(keys.size());
                    for (Object key : keys) {
                        writeValue(out, table.columns.get(table.primary).type, key);
                    }
                }
            }
        }
    }

    private static Map<String, Table> readSnapshot(DataInputStream in) throws IOException {
        if (in.readInt() != FORMAT) {
            throw new IOException("неизвестный формат файла.");
        }
        Map<String, Table> tables = new LinkedHashMap<>();
        int tableCount = count(in);
        for (int t = 0; t < tableCount; t++) {
            String name = readText(in);
            List<Column> columns = new ArrayList<>();
            int columnCount = count(in);
            for (int i = 0; i < columnCount; i++) {
                String columnName = readText(in);
                int number = in.readInt();
                Column.Type[] types = Column.Type.values();
                if (number < 0 || number >= types.length) {
                    throw new IOException("неизвестный тип столбца");
                }
                boolean primary = in.readBoolean();
                boolean required = in.readBoolean();
                boolean reference = in.readBoolean();
                String parentTable = reference ? readText(in) : null;
                String parentColumn = reference ? readText(in) : null;
                columns.add(new Column(columnName, types[number], primary, required, parentTable, parentColumn));
            }
            Table table = new Table(name, columns);
            int rowCount = count(in);
            for (int i = 0; i < rowCount; i++) {
                Object[] row = new Object[columns.size()];
                for (int j = 0; j < columns.size(); j++) {
                    row[j] = readValue(in, columns.get(j).type);
                }
                table.addRow(table.rows, row);
            }
            int indexCount = count(in);
            for (int i = 0; i < indexCount; i++) {
                table.indexes.add(readIndex(in, table));
            }
            if (tables.put(name, table) != null) {
                throw new IOException("повтор имени таблицы");
            }
        }
        if (in.read() != -1) {
            throw new IOException("лишние данные после снимка");
        }
        Database.validateReferences(tables);
        return tables;
    }

    private static Index readIndex(DataInputStream in, Table table) throws IOException {
        String name = readText(in);
        int position = in.readInt();
        if (position < 0 || position >= table.columns.size()) {
            throw new IOException("неверная позиция столбца индекса");
        }
        Index index = new Index(name, position);
        int groupCount = count(in);
        Map<Object, Object[]> remaining = table.copyRows();
        for (int i = 0; i < groupCount; i++) {
            Object value = readValue(in, table.columns.get(position).type);
            List<Object> keys = new ArrayList<>();
            int keyCount = count(in);
            for (int j = 0; j < keyCount; j++) {
                Object key = readValue(in, table.columns.get(table.primary).type);
                Object[] row = remaining.remove(key);
                if (row == null || !Objects.equals(row[position], value)) {
                    throw new IOException("индекс расходится со строками таблицы");
                }
                keys.add(key);
            }
            if (keys.isEmpty() || index.keysByValue.put(value, keys) != null) {
                throw new IOException("некорректная группа индекса");
            }
        }
        if (!remaining.isEmpty()) {
            throw new IOException("в индексе отсутствуют ключи");
        }
        return index;
    }

    private static void writeValue(DataOutputStream out, Column.Type type, Object value)
            throws IOException {
        out.writeBoolean(value != null);
        if (value == null) {
            return;
        }
        switch (type) {
            case INT: out.writeInt((Integer) value); break;
            case DOUBLE: out.writeDouble((Double) value); break;
            case STRING: writeText(out, (String) value); break;
            case DATE: out.writeLong(((LocalDate) value).toEpochDay()); break;
            case BOOLEAN: out.writeBoolean((Boolean) value); break;
            default: throw new AssertionError(type);
        }
    }

    private static Object readValue(DataInputStream in, Column.Type type) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        switch (type) {
            case INT: return in.readInt();
            case DOUBLE: return in.readDouble();
            case STRING: return readText(in);
            case DATE: return LocalDate.ofEpochDay(in.readLong());
            case BOOLEAN: return in.readBoolean();
            default: throw new AssertionError(type);
        }
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        byte[] bytes = new byte[count(in)];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int count(DataInputStream in) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > in.available()) {
            throw new IOException("неверный размер в снимке");
        }
        return value;
    }
}
