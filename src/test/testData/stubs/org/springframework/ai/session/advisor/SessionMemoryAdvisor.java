package org.springframework.ai.session.advisor;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionService;

public final class SessionMemoryAdvisor implements Advisor {
    public static final String EVENT_FILTER_CONTEXT_KEY = "chat_memory_event_filter";

    public static Builder builder(SessionService sessionService) { return new Builder(); }

    public static final class Builder {
        public Builder order(int order) { return this; }
        public Builder eventFilter(EventFilter eventFilter) { return this; }
        public SessionMemoryAdvisor build() { return new SessionMemoryAdvisor(); }
    }
}
