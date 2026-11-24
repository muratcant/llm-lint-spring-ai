package org.springframework.ai.session.advisor;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;

public final class SessionAdvisors {
    public static Builder builder(SessionService sessionService) { return new Builder(); }

    public static final class Builder {
        public Builder compaction(CompactionTrigger trigger, CompactionStrategy strategy) { return this; }
        public Builder asyncCompaction(boolean asyncCompaction) { return this; }
        public Builder memory(Consumer<SessionMemoryAdvisor.Builder> memory) { return this; }
        public List<Advisor> build() { return List.of(); }
    }
}
