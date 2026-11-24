package org.springframework.ai.session.advisor;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;

public final class SessionTurnAdvisor implements Advisor {
    public static Builder builder(SessionService sessionService) { return new Builder(); }

    public static final class Builder {
        public Builder compactionTrigger(CompactionTrigger trigger) { return this; }
        public Builder compactionStrategy(CompactionStrategy strategy) { return this; }
        public SessionTurnAdvisor build() { return new SessionTurnAdvisor(); }
    }
}
