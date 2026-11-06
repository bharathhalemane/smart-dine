package com.example.qrorder.tables.service;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TableNumbers {

    private static final Pattern SPLIT = Pattern.compile("^(\\D*)(\\d*)(.*)$");

    private TableNumbers() {}

    /** "  t  1 " becomes "T 1": the same table must not be creatable twice with different spelling. */
    static String normalize(String raw) {
        return raw.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** Natural order: T2 before T10, "A1" before "B1". */
    static int compare(String a, String b) {
        Matcher x = SPLIT.matcher(a);
        Matcher y = SPLIT.matcher(b);
        x.matches();
        y.matches();

        int byPrefix = x.group(1).compareTo(y.group(1));
        if (byPrefix != 0) {
            return byPrefix;
        }
        String digitsA = x.group(2);
        String digitsB = y.group(2);
        if (!digitsA.isEmpty() && !digitsB.isEmpty()) {
            int byNumber = new BigInteger(digitsA).compareTo(new BigInteger(digitsB));
            if (byNumber != 0) {
                return byNumber;
            }
        } else if (digitsA.isEmpty() != digitsB.isEmpty()) {
            return digitsA.isEmpty() ? -1 : 1;
        }
        return x.group(3).compareTo(y.group(3));
    }
}