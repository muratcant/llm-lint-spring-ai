package org.springframework.ai.session;

public final class EventFilter {
    public static EventFilter all() { return new EventFilter(); }
    public static EventFilter active() { return new EventFilter(); }
    public static EventFilter lastN(int n) { return new EventFilter(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        public Builder lastN(Integer lastN) { return this; }
        public Builder excludeSynthetic(boolean excludeSynthetic) { return this; }
        public EventFilter build() { return new EventFilter(); }
    }
}
