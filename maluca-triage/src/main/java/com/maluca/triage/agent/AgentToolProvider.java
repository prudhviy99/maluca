package com.maluca.triage.agent;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.maluca.triage.config.TriageProperties;

/** Applies a fail-closed name allowlist to all remotely discovered MCP tools. */
@Component
public class AgentToolProvider {

    /** Mutating MCP callbacks can never be enabled by configuration alone. */
    static final Set<String> SAFE_TOOL_NAMES = Set.of(
            "get_incidents",
            "get_decisions",
            "get_signal_breakdown",
            "query_metrics",
            "list_policies",
            "search_runbooks");

    private final ObjectProvider<ToolCallbackProvider> providers;
    private final Set<String> allowedNames;
    private final ThreadLocal<InvocationBudget> budget = new ThreadLocal<>();

    public AgentToolProvider(ObjectProvider<ToolCallbackProvider> providers, TriageProperties properties) {
        this.providers = providers;
        Set<String> configured = Set.copyOf(properties.agent().allowedTools());
        if (!SAFE_TOOL_NAMES.containsAll(configured)) {
            Set<String> unsafe = new java.util.HashSet<>(configured);
            unsafe.removeAll(SAFE_TOOL_NAMES);
            throw new IllegalArgumentException(
                    "agent.allowed-tools contains non-read-only callbacks: " + unsafe);
        }
        this.allowedNames = configured;
    }

    public List<ToolCallback> callbacks() {
        return providers.orderedStream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .filter(callback -> allowedNames.contains(callback.getToolDefinition().name()))
                .map(callback -> (ToolCallback) new BoundedToolCallback(callback))
                .toList();
    }

    public BudgetScope openBudget(Duration timeout, int maxCalls) {
        if (budget.get() != null) {
            throw new IllegalStateException("tool invocation budget is already active");
        }
        InvocationBudget invocation = new InvocationBudget(
                System.nanoTime() + timeout.toNanos(), maxCalls, new AtomicInteger());
        budget.set(invocation);
        return () -> budget.remove();
    }

    private void consume(String name) {
        InvocationBudget invocation = budget.get();
        if (invocation == null) {
            throw new IllegalStateException("tool invocation attempted outside bounded triage orchestration");
        }
        if (System.nanoTime() > invocation.deadlineNanos()) {
            throw new IllegalStateException("triage orchestration deadline expired before tool " + name);
        }
        if (invocation.calls().incrementAndGet() > invocation.maxCalls()) {
            throw new IllegalStateException(
                    "triage tool-call budget exceeded before tool " + name);
        }
    }

    public interface BudgetScope extends AutoCloseable {
        @Override
        void close();
    }

    private record InvocationBudget(long deadlineNanos, int maxCalls, AtomicInteger calls) {
    }

    private final class BoundedToolCallback implements ToolCallback {
        private final ToolCallback delegate;

        private BoundedToolCallback(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String input) {
            consume(getToolDefinition().name());
            return delegate.call(input);
        }

        @Override
        public String call(String input, ToolContext context) {
            consume(getToolDefinition().name());
            return delegate.call(input, context);
        }
    }
}
