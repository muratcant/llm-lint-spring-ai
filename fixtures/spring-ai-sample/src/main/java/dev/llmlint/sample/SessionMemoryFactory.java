package dev.llmlint.sample;

import java.util.List;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;

/**
 * Spring AI 2.1 session memory, the replacement of the deprecated ChatMemory API.
 * Plain factory methods (not wired as beans), to show both shapes side by side.
 */
public final class SessionMemoryFactory {

    private SessionMemoryFactory() {
    }

    /**
     * BUG (intentional): session memory sends the whole active history on every call by default
     * (EventFilter.all()), so input tokens grow with every turn.
     */
    public static List<Advisor> unboundedSession(SessionService sessions) {
        return SessionAdvisors.builder(sessions).build();
    }

    /** OK: only the last 20 events go into the prompt. */
    public static List<Advisor> windowedSession(SessionService sessions) {
        return SessionAdvisors.builder(sessions)
                .memory(memory -> memory.eventFilter(EventFilter.lastN(20)))
                .build();
    }
}
