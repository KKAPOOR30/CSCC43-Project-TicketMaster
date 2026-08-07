package util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Scanner;

/** Thin helpers around {@link Scanner} for the text UI. */
public final class Console {

    private final Scanner in;

    public Console(Scanner in) {
        this.in = in;
    }

    public String line(String prompt) {
        System.out.print(prompt);
        return in.nextLine().trim();
    }

    public String required(String prompt) {
        while (true) {
            String s = line(prompt);
            if (!s.isEmpty()) {
                return s;
            }
            System.out.println("  Value is required.");
        }
    }

    public int intVal(String prompt) {
        while (true) {
            String s = line(prompt);
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                System.out.println("  Enter an integer.");
            }
        }
    }

    public BigDecimal money(String prompt) {
        while (true) {
            String s = line(prompt);
            try {
                BigDecimal v = new BigDecimal(s);
                if (v.compareTo(BigDecimal.ZERO) <= 0) {
                    System.out.println("  Enter a positive amount.");
                    continue;
                }
                return v;
            } catch (NumberFormatException e) {
                System.out.println("  Enter a number (e.g. 79.99).");
            }
        }
    }

    /** Optional money; empty input returns null. */
    public BigDecimal moneyOpt(String prompt) {
        String s = line(prompt);
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            System.out.println("  Invalid number; ignoring.");
            return null;
        }
    }

    public double dbl(String prompt) {
        while (true) {
            String s = line(prompt);
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                System.out.println("  Enter a number.");
            }
        }
    }

    /** Optional int; empty input returns null. */
    public Integer intOpt(String prompt) {
        String s = line(prompt);
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            System.out.println("  Invalid integer; ignoring.");
            return null;
        }
    }

    /** Optional text; empty input returns null. */
    public String opt(String prompt) {
        String s = line(prompt);
        return s.isEmpty() ? null : s;
    }

    public LocalDate date(String prompt) {
        while (true) {
            String s = line(prompt + " (YYYY-MM-DD): ");
            try {
                return LocalDate.parse(s);
            } catch (DateTimeParseException e) {
                System.out.println("  Invalid date.");
            }
        }
    }

    /** Optional date; empty returns null. */
    public LocalDate dateOpt(String prompt) {
        String s = line(prompt + " (YYYY-MM-DD, blank to skip): ");
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            System.out.println("  Invalid date; skipping.");
            return null;
        }
    }

    public boolean yn(String prompt) {
        while (true) {
            String s = line(prompt + " (y/n): ").toLowerCase();
            if (s.equals("y") || s.equals("yes")) {
                return true;
            }
            if (s.equals("n") || s.equals("no")) {
                return false;
            }
            System.out.println("  Enter y or n.");
        }
    }

    public void pause() {
        System.out.print("\nPress Enter to continue...");
        in.nextLine();
    }
}
