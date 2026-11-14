import java.util.List;

/** Результат команды SQL, независимый от способа отображения. */
final class Result {
    final String message;
    final List<String> columns;
    final List<Object[]> rows;
    final String plan;
    final int checked;

    private Result(String message, List<String> columns, List<Object[]> rows,
                   String plan, int checked) {
        this.message = message;
        this.columns = columns;
        this.rows = rows;
        this.plan = plan;
        this.checked = checked;
    }

    static Result status(String message) {
        return new Result(message, null, null, null, 0);
    }

    static Result query(List<String> columns, List<Object[]> rows, String plan, int checked) {
        return new Result(null, columns, rows, plan, checked);
    }
}
