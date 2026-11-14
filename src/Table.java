import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Схема, записи и вторичные индексы одной таблицы. */
final class Table {
    final String name;
    final List<Column> columns;
    final int primary;
    final Map<Object, Object[]> rows;
    final List<Index> indexes;

    Table(String name, List<Column> columns) {
        this.name = name;
        this.columns = new ArrayList<>(columns);
        this.primary = primaryPosition(columns);
        this.rows = new LinkedHashMap<>();
        this.indexes = new ArrayList<>();
    }

    private Table(Table source, Map<Object, Object[]> rows, List<Index> indexes) {
        this.name = source.name;
        this.columns = source.columns;
        this.primary = source.primary;
        this.rows = rows;
        this.indexes = indexes;
    }

    private static int primaryPosition(List<Column> columns) {
        int position = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).primary) {
                if (position >= 0) {
                    throw new IllegalArgumentException("Допускается один столбец PRIMARY KEY.");
                }
                position = i;
            }
        }
        if (position < 0) {
            throw new IllegalArgumentException("В таблице должен быть столбец PRIMARY KEY.");
        }
        return position;
    }

    int position(String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name.equals(columnName)) {
                return i;
            }
        }
        throw new IllegalArgumentException("В таблице " + name + " нет столбца " + columnName + ".");
    }

    /** Создаёт версию таблицы с новыми строками и согласованными индексами. */
    Table withRows(Map<Object, Object[]> nextRows) {
        List<Index> nextIndexes = new ArrayList<>();
        for (Index index : indexes) {
            nextIndexes.add(new Index(index.name, index.position, nextRows));
        }
        return new Table(this, nextRows, nextIndexes);
    }

    /** Строит дополнительный индекс по уже существующим строкам. */
    Table withIndex(String indexName, int position) {
        List<Index> nextIndexes = new ArrayList<>(indexes);
        nextIndexes.add(new Index(indexName, position, rows));
        return new Table(this, rows, nextIndexes);
    }

    void addRow(Map<Object, Object[]> destination, Object[] row) {
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            if (column.required && row[i] == null) {
                throw new IllegalArgumentException("Столбец " + name + "." + column.name + " требует значение.");
            }
        }
        Object id = row[primary];
        if (destination.putIfAbsent(id, row) != null) {
            throw new IllegalArgumentException("Повтор первичного ключа в таблице " + name + " (" + id + ").");
        }
    }

    /** Выполняет поиск и формирует результат с фактическим количеством проверок. */
    Result select(List<String> requested, Condition condition, boolean useIndexes) {
        List<String> headers = new ArrayList<>();
        if (requested.isEmpty()) {
            for (Column column : columns) {
                headers.add(column.name);
            }
        } else {
            headers.addAll(requested);
        }
        int[] projection = new int[headers.size()];
        for (int i = 0; i < projection.length; i++) {
            projection[i] = position(headers.get(i));
        }

        Iterable<Object[]> candidates = rows.values();
        String plan = "FULL SCAN";
        if (useIndexes && condition != null) {
            for (Index index : indexes) {
                if (index.position == condition.position) {
                    List<Object[]> indexedRows = new ArrayList<>();
                    for (Object key : index.candidates(condition)) {
                        indexedRows.add(rows.get(key));
                    }
                    candidates = indexedRows;
                    plan = "INDEX " + index.name;
                    break;
                }
            }
        }

        List<Object[]> selected = new ArrayList<>();
        int checked = 0;
        for (Object[] row : candidates) {
            checked++;
            if (condition == null || condition.matches(row)) {
                Object[] values = new Object[projection.length];
                for (int i = 0; i < projection.length; i++) {
                    values[i] = row[projection[i]];
                }
                selected.add(values);
            }
        }
        return Result.query(headers, selected, plan, checked);
    }

    Map<Object, Object[]> copyRows() {
        return new LinkedHashMap<>(rows);
    }
}
