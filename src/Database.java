import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Движок СУБД. Проверяет ограничения и фиксирует каждую команду изменения целиком. */
final class Database {
    private final FileRepository repository;
    private Map<String, Table> tables;

    Database(FileRepository repository, Map<String, Table> tables) {
        this.repository = repository;
        this.tables = new LinkedHashMap<>(tables);
    }

    /** Создаёт и сохраняет начальный каталог после первого запуска. */
    static Map<String, Table> createInitialCatalog(FileRepository repository) throws IOException {
        Database catalog = new Database(null, new LinkedHashMap<>());
        catalog.execute("CREATE TABLE authors (id INT PRIMARY KEY, name STRING NOT NULL)");
        catalog.execute("CREATE TABLE books (id INT PRIMARY KEY, title STRING NOT NULL, "
                + "author_id INT NOT NULL REFERENCES authors(id), price DOUBLE, "
                + "published_on DATE, available BOOLEAN)");
        catalog.execute("INSERT INTO authors VALUES (1, 'Александр Пушкин'), "
                + "(2, 'Антон Чехов'), (3, 'Михаил Булгаков')");
        catalog.execute("INSERT INTO books VALUES "
                + "(1, 'Капитанская дочка', 1, 550.0, NULL, TRUE), "
                + "(2, 'Вишнёвый сад', 2, 480.0, NULL, TRUE), "
                + "(3, 'Мастер и Маргарита', 3, 790.0, NULL, FALSE)");
        Map<String, Table> snapshot = catalog.tables;
        repository.save(snapshot);
        return snapshot;
    }

    /** Выполняет запрос с обычным планом поиска. */
    Result execute(String sql) {
        return execute(sql, true);
    }

    /** Позволяет повторить SELECT с полным обходом при замере индекса. */
    Result execute(String sql, boolean useIndexes) {
        return new SqlParser(this, sql, useIndexes).execute();
    }

    Result createTable(String name, List<Column> columns) {
        if (tables.containsKey(name)) {
            throw new IllegalArgumentException("Таблица " + name + " уже существует.");
        }
        Set<String> names = new HashSet<>();
        for (Column column : columns) {
            if (!names.add(column.name)) {
                throw new IllegalArgumentException("Столбец " + column.name + " указан дважды.");
            }
        }

        Table table = new Table(name, columns);
        Map<String, Table> next = new LinkedHashMap<>(tables);
        next.put(name, table);
        for (Column column : columns) {
            if (column.parentTable != null) {
                Table parent = table(next, column.parentTable);
                if (parent.position(column.parentColumn) != parent.primary
                        || parent.columns.get(parent.primary).type != column.type) {
                    throw new IllegalArgumentException("Внешний ключ должен ссылаться на PRIMARY KEY того же типа.");
                }
            }
        }
        commit(next);
        return Result.status("Создана таблица " + name + ".");
    }

    Result dropTable(String name) {
        table(tables, name);
        for (Table table : tables.values()) {
            if (!name.equals(table.name)) {
                for (Column column : table.columns) {
                    if (name.equals(column.parentTable)) {
                        throw new IllegalArgumentException("Таблица " + table.name + " ссылается на " + name + ".");
                    }
                }
            }
        }
        Map<String, Table> next = new LinkedHashMap<>(tables);
        next.remove(name);
        commit(next);
        return Result.status("Удалена таблица " + name + ".");
    }

    Result createIndex(String name, String tableName, String columnName) {
        Table table = table(tables, tableName);
        int position = table.position(columnName);
        for (Table existing : tables.values()) {
            for (Index index : existing.indexes) {
                if (index.name.equals(name)) {
                    throw new IllegalArgumentException("Индекс " + name + " уже существует.");
                }
                if (existing == table && index.position == position) {
                    throw new IllegalArgumentException("На этом столбце уже есть индекс " + index.name + ".");
                }
            }
        }
        replaceTable(table.withIndex(name, position));
        return Result.status("Создан индекс " + name + ".");
    }

    Result insert(String name, List<String> columnNames, List<List<SqlParser.Literal>> values) {
        Table table = table(tables, name);
        List<Integer> positions = new ArrayList<>();
        if (columnNames.isEmpty()) {
            for (int i = 0; i < table.columns.size(); i++) {
                positions.add(i);
            }
        } else {
            Set<String> used = new HashSet<>();
            for (String columnName : columnNames) {
                if (!used.add(columnName)) {
                    throw new IllegalArgumentException("Столбец " + columnName + " указан дважды.");
                }
                positions.add(table.position(columnName));
            }
        }
        Map<Object, Object[]> rows = table.copyRows();
        for (List<SqlParser.Literal> valuesInRow : values) {
            if (valuesInRow.size() != positions.size()) {
                throw new IllegalArgumentException("Количество значений не совпадает с количеством столбцов.");
            }
            Object[] row = new Object[table.columns.size()];
            for (int i = 0; i < positions.size(); i++) {
                int position = positions.get(i);
                row[position] = table.columns.get(position).value(valuesInRow.get(i));
            }
            table.addRow(rows, row);
        }
        replaceTable(table.withRows(rows));
        return Result.status("Добавлено строк: " + values.size() + ".");
    }

    Result select(String name, List<String> columnNames, SqlParser.Where where, boolean useIndexes) {
        Table table = table(tables, name);
        return table.select(columnNames, bind(table, where), useIndexes);
    }

    Result update(String name, Map<String, SqlParser.Literal> assignments, SqlParser.Where where) {
        Table table = table(tables, name);
        Condition condition = bind(table, where);
        Map<Integer, Object> changedValues = new LinkedHashMap<>();
        for (Map.Entry<String, SqlParser.Literal> entry : assignments.entrySet()) {
            int position = table.position(entry.getKey());
            Column column = table.columns.get(position);
            Object value = column.value(entry.getValue());
            if (column.required && value == null) {
                throw new IllegalArgumentException("Столбец " + name + "." + column.name + " требует значение.");
            }
            changedValues.put(position, value);
        }
        Map<Object, Object[]> rows = new LinkedHashMap<>();
        int changed = 0;
        for (Object[] original : table.rows.values()) {
            Object[] row = original;
            if (condition.matches(original)) {
                row = original.clone();
                for (Map.Entry<Integer, Object> entry : changedValues.entrySet()) {
                    row[entry.getKey()] = entry.getValue();
                }
                changed++;
            }
            table.addRow(rows, row);
        }
        if (changed > 0) {
            replaceTable(table.withRows(rows));
        }
        return Result.status("Обновлено строк: " + changed + ".");
    }

    Result delete(String name, SqlParser.Where where) {
        Table table = table(tables, name);
        Condition condition = bind(table, where);
        Map<Object, Object[]> rows = new LinkedHashMap<>();
        for (Object key : table.rows.keySet()) {
            Object[] row = table.rows.get(key);
            if (!condition.matches(row)) {
                rows.put(key, row);
            }
        }
        int changed = table.rows.size() - rows.size();
        if (changed > 0) {
            replaceTable(table.withRows(rows));
        }
        return Result.status("Удалено строк: " + changed + ".");
    }

    private static Condition bind(Table table, SqlParser.Where where) {
        if (where == null) {
            return null;
        }
        int position = table.position(where.column);
        Object value = where.literal == null ? null : table.columns.get(position).value(where.literal);
        return new Condition(position, where.operator, value);
    }

    private static Table table(Map<String, Table> tables, String name) {
        Table table = tables.get(name);
        if (table == null) {
            throw new IllegalArgumentException("Таблица " + name + " не найдена.");
        }
        return table;
    }

    private void replaceTable(Table replacement) {
        Map<String, Table> next = new LinkedHashMap<>(tables);
        next.put(replacement.name, replacement);
        commit(next);
    }

    private void commit(Map<String, Table> next) {
        validateReferences(next);
        if (repository != null) {
            try {
                repository.save(next);
            } catch (IOException ex) {
                throw new UncheckedIOException("Ошибка записи базы: " + ex.getMessage(), ex);
            }
        }
        tables = next;
    }

    /** Проверяет связи по итоговому состоянию всех таблиц до сохранения. */
    static void validateReferences(Map<String, Table> tables) {
        for (Table table : tables.values()) {
            for (int i = 0; i < table.columns.size(); i++) {
                Column column = table.columns.get(i);
                if (column.parentTable == null) {
                    continue;
                }
                Table parent = table(tables, column.parentTable);
                for (Object[] row : table.rows.values()) {
                    Object key = row[i];
                    if (key != null && !parent.rows.containsKey(key)) {
                        throw new IllegalArgumentException("Внешний ключ " + table.name + "." + column.name
                                + " (" + key + ") ссылается на отсутствующую запись в " + parent.name + ".");
                    }
                }
            }
        }
    }
}
