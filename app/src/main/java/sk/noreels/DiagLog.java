package sk.noreels;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Krátky záznam v pamäti (nič sa neukladá ani neodosiela). Slúži na doladenie,
 * keď Instagram/YouTube zmení názvy prvkov — text sa dá skopírovať z appky.
 */
final class DiagLog {
    private static final int MAX = 80;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private DiagLog() {}

    static synchronized void add(String line) {
        if (LINES.size() >= MAX) LINES.removeFirst();
        LINES.addLast(FMT.format(new Date()) + "  " + line);
    }

    static synchronized String dump() {
        if (LINES.isEmpty()) return "(zatiaľ prázdne)";
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }

    static synchronized void clear() {
        LINES.clear();
    }
}
