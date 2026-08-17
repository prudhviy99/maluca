package com.maluca.mcp.validation;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.maluca.contracts.incident.IncidentStatus;
import com.maluca.mcp.config.MalucaMcpProperties;

@Component
public class ToolInputValidator {

    private static final Set<String> ACTIONS = Set.of(
            "ALLOW", "OBSERVE", "SOFT_LIMIT", "HARD_LIMIT", "CHALLENGE", "BLOCK");

    private final MalucaMcpProperties.Limits limits;

    public ToolInputValidator(MalucaMcpProperties properties) {
        this.limits = properties.limits();
    }

    public String optionalStatus(String status) {
        String normalized = optionalText("status", status, 64);
        if (normalized == null) {
            return null;
        }
        try {
            return IncidentStatus.valueOf(normalized.toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            throw new ToolInputException("status is not a supported incident status");
        }
    }

    public String optionalAction(String action) {
        String normalized = optionalText("action", action, 64);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!ACTIONS.contains(normalized)) {
            throw new ToolInputException("action is not a supported mitigation action");
        }
        return normalized;
    }

    public String requiredText(String name, String value, int maxCharacters) {
        String normalized = optionalText(name, value, maxCharacters);
        if (normalized == null) {
            throw new ToolInputException(name + " is required");
        }
        return normalized;
    }

    public String optionalText(String name, String value, int maxCharacters) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxCharacters) {
            throw new ToolInputException(name + " exceeds " + maxCharacters + " characters");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new ToolInputException(name + " contains control characters");
        }
        return normalized;
    }

    public int resultLimit(Integer requested) {
        if (requested == null) {
            return limits.defaultResultLimit();
        }
        if (requested < 1 || requested > limits.maxResultLimit()) {
            throw new ToolInputException("limit must be between 1 and " + limits.maxResultLimit());
        }
        return requested;
    }

    public int incidentLimit(Integer requested) {
        int limit = resultLimit(requested);
        if (limit > 100) {
            throw new ToolInputException("incident limit must be between 1 and 100");
        }
        return limit;
    }

    public int runbookLimit(Integer requested) {
        if (requested == null) {
            return limits.defaultRunbookLimit();
        }
        if (requested < 1 || requested > limits.maxRunbookLimit()) {
            throw new ToolInputException("limit must be between 1 and " + limits.maxRunbookLimit());
        }
        return requested;
    }

    public Instant optionalInstant(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ToolInputException(name + " must be an RFC 3339 UTC instant");
        }
    }

    public Instant requiredInstant(String name, String value) {
        Instant result = optionalInstant(name, value);
        if (result == null) {
            throw new ToolInputException(name + " is required");
        }
        return result;
    }

    public void validateWindow(Instant from, Instant to) {
        if (from == null && to == null) {
            return;
        }
        if (from == null || to == null) {
            throw new ToolInputException("from and to must be provided together");
        }
        if (!to.isAfter(from)) {
            throw new ToolInputException("to must be after from");
        }
        if (Duration.between(from, to).compareTo(limits.maxEvidenceWindow()) > 0) {
            throw new ToolInputException("evidence window exceeds " + limits.maxEvidenceWindow());
        }
    }

    public int maxQueryCharacters() {
        return limits.maxQueryCharacters();
    }
}
