package com.promptvidya.trustdesk.hardening;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Every untrusted byte enters the prompt the same way: normalized so
 * invisible characters cannot hide instructions, stripped of anything
 * that could close or forge a fence, capped so a document cannot flood
 * the window, and wrapped in a label the model can be told to treat as
 * data. The label carries a digest so evidence can name exactly which
 * text was seen without storing it.
 */
public final class UntrustedText {

    public static final String TAG = "untrusted";
    static final int MAXIMUM_CHARACTERS = 4000;
    static final String TRUNCATION_MARKER = "\n[truncated by input hardening]";

    /** C0/C1 controls except tab and newline, bidi overrides, zero-width and joiner characters. */
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\p{Cc}&&[^\\t\\n]]|[\\u200B-\\u200F\\u2028-\\u202E\\u2060-\\u2064\\uFEFF]");
    /** Anything that looks like it opens or closes one of our fences, or an HTML/XML tag at all. */
    private static final Pattern TAG_LIKE = Pattern.compile("</?\\s*[A-Za-z_][A-Za-z0-9_:-]*[^>]*>");
    private static final Pattern SOURCE_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private UntrustedText() {}

    /** The one entry point: source names the origin; kind names what the text claims to be. */
    public static String fence(String source, String kind, String raw) {
        requireName(source, "source");
        requireName(kind, "kind");
        var body = sanitize(raw);
        return "<" + TAG + " source=\"" + source + "\" kind=\"" + kind + "\" trust=\"untrusted-data\" sha256=\""
                + digest(body) + "\">\n" + body + "\n</" + TAG + ">";
    }

    /** Normalize, strip invisibles, neutralize tags, collapse whitespace runs, cap. */
    public static String sanitize(String raw) {
        var text = Normalizer.normalize(Objects.requireNonNullElse(raw, ""), Normalizer.Form.NFKC);
        text = INVISIBLE.matcher(text).replaceAll("");
        text = TAG_LIKE.matcher(text).replaceAll(match -> "[tag removed]");
        text = text.replaceAll("[ \\t]{2,}", " ").replaceAll("\\n{3,}", "\n\n").strip();
        if (text.length() > MAXIMUM_CHARACTERS) {
            text = text.substring(0, MAXIMUM_CHARACTERS) + TRUNCATION_MARKER;
        }
        return text;
    }

    /** A reference for the trail: which bytes the model saw, without keeping them. */
    public static String digest(String text) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is mandatory on the JVM", exception);
        }
    }

    private static void requireName(String value, String field) {
        if (value == null || !SOURCE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a short lowercase identifier");
        }
    }
}
