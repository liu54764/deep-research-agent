package com.dra.domain.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic anti-hallucination gate for evidence extraction.
 *
 * <p>The model only acts as a <em>locator</em>: its quote is matched against the fetched
 * body and the server keeps the exact source span, so a stored quote is always a literal
 * substring of the source. Characters models normalize on their own (typographic quotes,
 * dashes, ellipsis, NBSP, soft hyphens) are folded before matching, a quote wrapped in
 * decoration quotes is unwrapped, and a quote with an elided middle ("...") is accepted
 * only when every part exists in the source, in order.
 */
public final class QuoteValidator {

    private static final int MIN_ELISION_PART = 20;
    private static final int MAX_ELISION_PARTS = 4;
    private static final String WRAPPERS =
            "\"'\u201C\u201D\u2018\u2019\u00AB\u00BB\u2039\u203A\u300C\u300D\u300E\u300F[]()*_";

    private QuoteValidator() {
    }

    /**
     * A quote located in the source: {@code quote} is the verbatim source text,
     * {@code start}/{@code end} are character offsets into the body, and {@code rewritten}
     * is true when the model's original string had to be repaired.
     */
    public record Resolved(String quote, int start, int end, boolean rewritten) {
    }

    public static boolean isSupportedBy(String quote, String content) {
        return resolve(quote, content).isPresent();
    }

    public static Optional<Resolved> resolve(String quote, String content) {
        if (quote == null || quote.isBlank() || content == null || content.isBlank()) {
            return Optional.empty();
        }
        Folded folded = Folded.of(content);
        Optional<Resolved> direct = locate(folded, quote);
        if (direct.isPresent()) {
            return direct;
        }
        String stripped = stripWrapping(quote);
        Optional<Resolved> repaired = stripped.equals(quote)
                ? Optional.empty()
                : locate(folded, stripped);
        if (repaired.isPresent()) {
            Resolved resolved = repaired.get();
            return Optional.of(resolved.rewritten()
                    ? resolved
                    : new Resolved(resolved.quote(), resolved.start(), resolved.end(), true));
        }
        return locateElision(folded, stripped);
    }

    static String normalize(String value) {
        return fold(value);
    }

    private static Optional<Resolved> locate(Folded folded, String quote) {
        String foldedQuote = fold(quote);
        if (foldedQuote.isEmpty()) {
            return Optional.empty();
        }
        int index = folded.text().indexOf(foldedQuote);
        if (index < 0) {
            return Optional.empty();
        }
        return folded.slice(index, index + foldedQuote.length(), quote);
    }

    /**
     * Quotes where the model elided a middle part with "..." are accepted only if every
     * part still appears verbatim, in order, and the recovered span is not much longer
     * than the parts themselves.
     */
    private static Optional<Resolved> locateElision(Folded folded, String quote) {
        String[] rawParts = quote.split("\\.{2,}|\u2026");
        List<String> parts = new ArrayList<>(rawParts.length);
        for (String part : rawParts) {
            String trimmed = stripWrapping(part.trim());
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        if (parts.size() < 2 || parts.size() > MAX_ELISION_PARTS) {
            return Optional.empty();
        }
        int partsLength = 0;
        for (String part : parts) {
            if (part.length() < MIN_ELISION_PART) {
                return Optional.empty();
            }
            partsLength += part.length();
        }
        int from = 0;
        int start = -1;
        int end = -1;
        for (String part : parts) {
            String foldedPart = fold(part);
            int index = folded.text().indexOf(foldedPart, from);
            if (index < 0) {
                return Optional.empty();
            }
            if (start < 0) {
                start = index;
            }
            end = index + foldedPart.length();
            from = end;
        }
        if (end - start > partsLength * 2 + 400) {
            return Optional.empty();
        }
        return folded.slice(start, end, quote);
    }

    private static String stripWrapping(String value) {
        String result = value.trim();
        boolean changed = true;
        while (changed && !result.isEmpty()) {
            changed = false;
            result = result.trim();
            if (!result.isEmpty() && WRAPPERS.indexOf(result.charAt(0)) >= 0) {
                result = result.substring(1);
                changed = true;
            }
            if (!result.isEmpty() && WRAPPERS.indexOf(result.charAt(result.length() - 1)) >= 0) {
                result = result.substring(0, result.length() - 1);
                changed = true;
            }
            String withoutLeadingEllipsis = result.replaceFirst("^(\\.{2,}|\u2026)", "");
            if (!withoutLeadingEllipsis.equals(result)) {
                result = withoutLeadingEllipsis;
                changed = true;
            }
            String withoutTrailingEllipsis = result.replaceFirst("(\\.{2,}|\u2026)$", "");
            if (!withoutTrailingEllipsis.equals(result)) {
                result = withoutTrailingEllipsis;
                changed = true;
            }
        }
        return result.trim();
    }

    private static String fold(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        boolean pendingSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isSpace(c)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (isInvisible(c)) {
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            appendFolded(out, c);
        }
        return out.toString();
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c)
                || c == '\u00A0' || c == '\u2007' || c == '\u202F';
    }

    private static boolean isInvisible(char c) {
        return c == '\u00AD' || c == '\u200B' || c == '\u200C'
                || c == '\u200D' || c == '\uFEFF';
    }

    private static void appendFolded(StringBuilder out, char c) {
        switch (c) {
            case '\u2018', '\u2019', '\u201A', '\u201B', '\u2032', '\uFF07' -> out.append('\'');
            case '\u201C', '\u201D', '\u201E', '\u201F', '\u2033', '\uFF02' -> out.append('"');
            case '\u00AB', '\u00BB', '\u2039', '\u203A' -> out.append('"');
            case '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015',
                 '\u2212', '\uFE58', '\uFE63', '\uFF0D' -> out.append('-');
            case '\u2026' -> out.append("...");
            case '\uFF08' -> out.append('(');
            case '\uFF09' -> out.append(')');
            case '\uFF1A' -> out.append(':');
            case '\uFF0C' -> out.append(',');
            default -> out.append(c);
        }
    }

    /**
     * Folded source text plus a map from every folded character back to the original one,
     * so a match can be rewritten to the exact source span.
     */
    private record Folded(String original, String text, int[] origin) {

        static Folded of(String content) {
            StringBuilder folded = new StringBuilder(content.length() + 16);
            List<Integer> map = new ArrayList<>(content.length() + 16);
            boolean pendingSpace = false;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (isSpace(c)) {
                    pendingSpace = folded.length() > 0;
                    continue;
                }
                if (isInvisible(c)) {
                    continue;
                }
                if (pendingSpace) {
                    folded.append(' ');
                    map.add(i);
                    pendingSpace = false;
                }
                int before = folded.length();
                appendFolded(folded, c);
                for (int j = before; j < folded.length(); j++) {
                    map.add(i);
                }
            }
            int[] origin = new int[map.size()];
            for (int i = 0; i < origin.length; i++) {
                origin[i] = map.get(i);
            }
            return new Folded(content, folded.toString(), origin);
        }

        Optional<Resolved> slice(int start, int end, String requested) {
            int from = start;
            int to = end;
            while (from < to && text.charAt(from) == ' ') {
                from++;
            }
            while (to > from && text.charAt(to - 1) == ' ') {
                to--;
            }
            if (from >= to) {
                return Optional.empty();
            }
            int originalStart = origin[from];
            int originalEnd = origin[to - 1] + 1;
            String quote = original.substring(originalStart, originalEnd);
            return Optional.of(new Resolved(quote, originalStart, originalEnd, !quote.equals(requested)));
        }
    }
}
