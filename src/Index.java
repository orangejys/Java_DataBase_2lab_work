import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Словарь «значение столбца → список первичных ключей». */
final class Index {
    final String name;
    final int position;
    final Map<Object, List<Object>> keysByValue = new LinkedHashMap<>();

    Index(String name, int position) {
        this.name = name;
        this.position = position;
    }

    Index(String name, int position, Map<Object, Object[]> rows) {
        this(name, position);
        for (Object id : rows.keySet()) {
            Object value = rows.get(id)[position];
            List<Object> keys = keysByValue.get(value);
            if (keys == null) {
                keys = new ArrayList<>();
                keysByValue.put(value, keys);
            }
            keys.add(id);
        }
    }

    /** Для равенства берёт список из словаря, для диапазона перебирает разные значения. */
    List<Object> candidates(Condition condition) {
        if ("IS NULL".equals(condition.operator)) {
            return keysFor(null);
        }
        if ("=".equals(condition.operator)) {
            return condition.value == null ? new ArrayList<>() : keysFor(condition.value);
        }

        List<Object> result = new ArrayList<>();
        for (Object value : keysByValue.keySet()) {
            if (condition.matchesValue(value)) {
                result.addAll(keysByValue.get(value));
            }
        }
        return result;
    }

    private List<Object> keysFor(Object value) {
        List<Object> keys = keysByValue.get(value);
        return keys == null ? new ArrayList<>() : keys;
    }
}
