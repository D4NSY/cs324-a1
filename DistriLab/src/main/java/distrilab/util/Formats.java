package distrilab.util;

import java.math.BigDecimal;

/** Text formatting helpers shared by the worker logs, the CLI and the GUI. */
public final class Formats {

    private Formats() {
    }

    /** Plain notation without trailing zeros: 90.00 -> 90, 1E+3 -> 1000. */
    public static String number(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        if (value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    /** Shortens long text for table cells and log lines. */
    public static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)) + "\u2026";
    }
}
