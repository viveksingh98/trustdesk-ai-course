package com.promptvidya.trustdesk.approval;

import com.promptvidya.trustdesk.approval.ApprovalQueue.PendingAction;
import com.promptvidya.trustdesk.approval.ApprovalQueue.State;
import com.promptvidya.trustdesk.domain.AuditTrail;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Approvals that do not rot. Every proposal is announced once, reminded
 * once when half its life is gone, and announced once more when it
 * expires — never twice for the same reason, however often the sweep
 * runs, and never for a proposal a person already decided. Each
 * notification is a reference, not a payload: id, tool, and time left;
 * the intent stays in the queue for the person to read there.
 */
public final class ApprovalNotifier {

    public enum Kind { NEW, REMINDER, EXPIRED }

    public record Notification(Kind kind, String actionId, String subject, String tool, Duration timeLeft) {}

    /** Where notifications go — chat, email, a ticket — decided by the application. */
    public interface Channel {
        void deliver(Notification notification);
    }

    static final String NOTIFY_ACTION = "approval_notified";

    private final ApprovalQueue queue;
    private final Channel channel;
    private final AuditTrail audit;
    private final Clock clock;
    private final Set<String> delivered = ConcurrentHashMap.newKeySet();

    public ApprovalNotifier(ApprovalQueue queue, Channel channel, AuditTrail audit, Clock clock) {
        this.queue = Objects.requireNonNull(queue);
        this.channel = Objects.requireNonNull(channel);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    /** One pass: announce new proposals, remind on the ones half gone, report the ones that just expired. */
    public List<Notification> sweep(List<String> knownIds) {
        var now = clock.instant();
        var sent = new ArrayList<Notification>();
        for (var id : knownIds) {
            var action = queue.get(id).orElse(null);
            if (action == null) {
                continue;
            }
            if (action.staleAt(now) || action.state() == State.EXPIRED) {
                queue.pending();
                deliverOnce(Kind.EXPIRED, action, Duration.ZERO, sent);
                continue;
            }
            if (action.state() == State.PENDING) {
                var left = Duration.between(now, action.expiresAt());
                var life = Duration.between(action.requestedAt(), action.expiresAt());
                deliverOnce(Kind.NEW, action, left, sent);
                if (left.compareTo(life.dividedBy(2)) <= 0) {
                    deliverOnce(Kind.REMINDER, action, left, sent);
                }
            }
        }
        return List.copyOf(sent);
    }

    private void deliverOnce(Kind kind, PendingAction action, Duration timeLeft, List<Notification> sent) {
        if (!delivered.add(kind + ":" + action.id())) {
            return;
        }
        var notification = new Notification(kind, action.id(), action.subject(), action.tool(), timeLeft);
        channel.deliver(notification);
        audit.record(action.subject(), NOTIFY_ACTION, action.id(), kind.name());
        sent.add(notification);
    }
}
