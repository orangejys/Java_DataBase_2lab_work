/** Одно условие WHERE после привязки к схеме таблицы. */
final class Condition {
    final int position;
    final String operator;
    final Object value;

    Condition(int position, String operator, Object value) {
        this.position = position;
        this.operator = operator;
        this.value = value;
    }

    boolean matches(Object[] row) {
        return matchesValue(row[position]);
    }

    boolean matchesValue(Object field) {
        if ("IS NULL".equals(operator)) {
            return field == null;
        }
        if ("IS NOT NULL".equals(operator)) {
            return field != null;
        }
        if (field == null || value == null) {
            return false;
        }
        int comparison = Column.compare(field, value);
        switch (operator) {
            case "=": return comparison == 0;
            case "<>": case "!=": return comparison != 0;
            case "<": return comparison < 0;
            case "<=": return comparison <= 0;
            case ">": return comparison > 0;
            case ">=": return comparison >= 0;
            default: throw new AssertionError(operator);
        }
    }
}
