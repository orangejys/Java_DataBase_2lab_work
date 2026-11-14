import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Описание столбца и правила преобразования SQL-литералов. */
final class Column {
    enum Type {
        INT, DOUBLE, STRING, DATE, BOOLEAN;

        static Type parse(String name) {
            switch (name) {
                case "int": case "integer": return INT;
                case "double": case "float": case "real": return DOUBLE;
                case "string": case "text": case "varchar": return STRING;
                case "date": return DATE;
                case "boolean": case "bool": return BOOLEAN;
                default: throw new IllegalArgumentException("Неизвестный тип " + name);
            }
        }

        Object convert(SqlParser.Literal literal) {
            try {
                switch (this) {
                    case INT:
                        if (literal.kind == SqlParser.Kind.NUMBER) {
                            return Integer.valueOf(literal.text);
                        }
                        break;
                    case DOUBLE:
                        if (literal.kind == SqlParser.Kind.NUMBER) {
                            double number = Double.parseDouble(literal.text);
                            if (Double.isFinite(number)) {
                                return number == 0.0 ? 0.0 : number;
                            }
                        }
                        break;
                    case STRING:
                        if (literal.kind == SqlParser.Kind.STRING) {
                            return literal.text;
                        }
                        break;
                    case DATE:
                        if (literal.kind == SqlParser.Kind.STRING
                                && literal.text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                            return LocalDate.parse(literal.text);
                        }
                        break;
                    case BOOLEAN:
                        if (literal.kind == SqlParser.Kind.WORD) {
                            if ("true".equalsIgnoreCase(literal.text)) {
                                return true;
                            }
                            if ("false".equalsIgnoreCase(literal.text)) {
                                return false;
                            }
                        }
                        break;
                    default: throw new AssertionError(this);
                }
            } catch (NumberFormatException | DateTimeParseException ex) {
                throw invalid(literal);
            }
            throw invalid(literal);
        }

        private IllegalArgumentException invalid(SqlParser.Literal literal) {
            return new IllegalArgumentException("Ожидалось значение типа " + this + ", получено «"
                    + literal.text + "».");
        }
    }

    final String name;
    final Type type;
    final boolean primary;
    final boolean required;
    final String parentTable;
    final String parentColumn;

    Column(String name, Type type, boolean primary, boolean required,
           String parentTable, String parentColumn) {
        this.name = name;
        this.type = type;
        this.primary = primary;
        this.required = required || primary;
        this.parentTable = parentTable;
        this.parentColumn = parentColumn;
    }

    /** Преобразует литерал для записи в столбец или сравнения в WHERE. */
    Object value(SqlParser.Literal literal) {
        return literal.isNull() ? null : type.convert(literal);
    }

    /** У обоих значений один тип, заданный схемой таблицы. */
    static int compare(Object left, Object right) {
        if (left instanceof Integer) {
            return Integer.compare((Integer) left, (Integer) right);
        }
        if (left instanceof Double) {
            return Double.compare((Double) left, (Double) right);
        }
        if (left instanceof Boolean) {
            return Boolean.compare((Boolean) left, (Boolean) right);
        }
        if (left instanceof LocalDate) {
            return ((LocalDate) left).compareTo((LocalDate) right);
        }
        return ((String) left).compareTo((String) right);
    }
}
