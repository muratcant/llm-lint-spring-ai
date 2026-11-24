package org.springframework.ai.session.compaction;

public final class WindowCompactionStrategy implements CompactionStrategy {
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        public Builder lastTurns(int n) { return this; }
        public WindowCompactionStrategy build() { return new WindowCompactionStrategy(); }
    }
}
