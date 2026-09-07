package com.promptvidya.trustdesk.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Attacks as fixtures. Every string that ever got past a gate — or was
 * tried against one — lives in {@code attacks/corpus.tsv} with the gate
 * that must stop it, and the regression suite replays the whole file
 * against every gate on every change. A new attack goes into the file
 * first and gets fixed second, so the fix is proven by a test that
 * already fails.
 */
public final class AttackCorpus {

    public enum Gate { FENCE, PROMPT, TOOL, GUARD }

    /** One attack: what it tries, which property must stop it, and the argument that property needs. */
    public record Attack(String id, Gate gate, String argument, String payload) {
        @Override
        public String toString() {
            return id;
        }
    }

    static final String RESOURCE = "/attacks/corpus.tsv";
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    private AttackCorpus() {}

    public static List<Attack> load() {
        try (var stream = AttackCorpus.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("attack corpus missing: " + RESOURCE);
            }
            var attacks = new ArrayList<Attack>();
            for (var line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var columns = line.split("\t", 4);
                if (columns.length != 4) {
                    throw new IllegalStateException("corpus row needs four columns: " + line);
                }
                attacks.add(new Attack(columns[0], Gate.valueOf(columns[1]), columns[2], unescape(columns[3])));
            }
            return List.copyOf(attacks);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The file stays readable: invisible characters are written as escapes and decoded here. */
    static String unescape(String text) {
        var decoded = UNICODE_ESCAPE.matcher(text)
                .replaceAll(match -> String.valueOf((char) Integer.parseInt(match.group(1), 16)));
        return decoded.replace("\\t", "\t").replace("\\n", "\n");
    }
}
