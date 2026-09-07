package com.promptvidya.trustdesk.observability;

import com.promptvidya.trustdesk.domain.AuditTrail;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stop the agent without stopping the application. Three modes: running,
 * read-only (the model may answer and read, but nothing that changes
 * the world runs), and halted (no model call at all). Flipping the
 * switch is an operator action with a bounded reason, and it writes
 * an evidence row like any other decision — so the incident timeline
 * shows who paused the agent, when, and when it came back.
 */
public final class KillSwitch {

    public enum Mode { RUNNING, READ_ONLY, HALTED }

    /** What a caller is about to do; the switch decides whether the mode allows it. */
    public enum Capability { CHAT, TOOL_READ, TOOL_WRITE }

    static final String ACTION = "kill_switch";
    static final int MAXIMUM_REASON_CHARACTERS = 80;
    private static final Pattern REASON = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _:/#.,-]{0,79}");

    private final AuditTrail audit;
    private volatile Mode mode = Mode.RUNNING;
    private volatile String reason = "";

    public KillSwitch(AuditTrail audit) {
        this.audit = Objects.requireNonNull(audit);
    }

    public Mode mode() {
        return mode;
    }

    public String reason() {
        return reason;
    }

    /** An operator flips the switch with a short reason — a ticket id, a sentence, never a payload. */
    public synchronized void set(Mode next, String operator, String why) {
        if (why == null || !REASON.matcher(why).matches()) {
            throw new IllegalArgumentException("a reason is a short reference, at most " + MAXIMUM_REASON_CHARACTERS + " characters");
        }
        mode = Objects.requireNonNull(next);
        reason = why;
        audit.record(operator, ACTION, "agent", next.name());
    }

    public boolean allows(Capability capability) {
        return switch (mode) {
            case RUNNING -> true;
            case READ_ONLY -> capability != Capability.TOOL_WRITE;
            case HALTED -> false;
        };
    }
}
