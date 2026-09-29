package distrilab.jobs;

import java.util.ArrayList;
import java.util.List;

/** Turns free-form user input ("4, 8 15;16\n23 [42]") into number tokens. */
public final class InputParser {

    private InputParser() {
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        String cleaned = text.replaceAll("[\\[\\](){}\"']", " ");
        for (String token : cleaned.split("[\\s,;]+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** True if the token is a number that {@link NumberList} can parse. */
    public static boolean isNumber(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        try {
            new java.math.BigDecimal(token.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
