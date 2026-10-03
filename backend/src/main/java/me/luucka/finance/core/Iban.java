package me.luucka.finance.core;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** International bank account numbers (ISO 13616): normalized, without spaces, checksum verified. */
public final class Iban {

    private static final Pattern SHAPE = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private Iban() {
    }

    /**
     * The IBAN in its electronic form ({@code CH9300762011623852957}), or empty when the text is not
     * a valid one. Spaces and case do not matter.
     */
    public static Optional<String> normalize(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String iban = text.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!SHAPE.matcher(iban).matches()) {
            return Optional.empty();
        }
        // Country and check digits move to the end, letters become numbers (A=10 … Z=35): mod 97 is 1
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(NINETY_SEVEN).intValue() == 1 ? Optional.of(iban) : Optional.empty();
    }
}
