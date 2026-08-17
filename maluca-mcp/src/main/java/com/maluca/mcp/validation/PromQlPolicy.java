package com.maluca.mcp.validation;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.maluca.mcp.config.MalucaMcpProperties;

/** Conservative PromQL allow policy for a fixed, read-only Prometheus endpoint. */
@Component
public class PromQlPolicy {

    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?<![A-Za-z0-9_:])([A-Za-z_:][A-Za-z0-9_:]*)");
    private static final Pattern GROUP_MODIFIER = Pattern.compile(
            "(?i)\\b(?:by|without|on|ignoring|group_left|group_right)\\s*\\([^)]*\\)");
    private static final Pattern RANGE_SELECTOR = Pattern.compile("\\[([^]]+)]");
    private static final Pattern RANK_FUNCTION = Pattern.compile(
            "(?i)\\b(?:topk|bottomk)\\s*\\(\\s*([0-9]+)");
    private static final Set<String> FUNCTIONS = Set.of(
            "sum", "avg", "min", "max", "count", "group", "stddev", "stdvar",
            "topk", "bottomk", "quantile", "count_values",
            "rate", "irate", "increase", "delta", "idelta", "deriv", "resets", "changes",
            "avg_over_time", "min_over_time", "max_over_time", "sum_over_time",
            "count_over_time", "quantile_over_time", "stddev_over_time", "stdvar_over_time",
            "last_over_time", "present_over_time", "histogram_quantile",
            "abs", "ceil", "floor", "round", "clamp", "clamp_min", "clamp_max");
    private static final Set<String> KEYWORDS = Set.of(
            "and", "or", "unless", "bool", "by", "without", "on", "ignoring",
            "group_left", "group_right");

    private final MalucaMcpProperties.Limits limits;
    private final MalucaMcpProperties.Promql promql;

    public PromQlPolicy(MalucaMcpProperties properties) {
        this.limits = properties.limits();
        this.promql = properties.promql();
    }

    public void validateRequest(String query, Instant start, Instant end, Duration step) {
        if (query == null || query.isBlank()) {
            throw new ToolInputException("query is required");
        }
        if (query.length() > limits.maxQueryCharacters()) {
            throw new ToolInputException("query exceeds " + limits.maxQueryCharacters() + " characters");
        }
        if (query.chars().anyMatch(Character::isISOControl) || query.indexOf('#') >= 0) {
            throw new ToolInputException("query contains comments or control characters");
        }
        if (query.indexOf('@') >= 0 || Pattern.compile("(?i)\\boffset\\b").matcher(query).find()) {
            throw new ToolInputException("PromQL @ and offset modifiers are not allowed");
        }
        if (Pattern.compile("(?i)\\bgroup_(?:left|right)\\b").matcher(query).find()) {
            throw new ToolInputException("PromQL many-to-one vector joins are not allowed");
        }
        if (query.contains("=~") || query.contains("!~")) {
            throw new ToolInputException("PromQL regular-expression label matchers are not allowed");
        }
        validateSelectorsHaveAllowedMetricNames(query);
        validateFunctionScalars(query);
        validateRangeSelectors(query);
        validateMetricSelectors(query);

        if (start == null || end == null || !end.isAfter(start)) {
            throw new ToolInputException("end must be after start");
        }
        Duration range = Duration.between(start, end);
        if (range.compareTo(promql.maxRange()) > 0) {
            throw new ToolInputException("metrics range exceeds " + promql.maxRange());
        }
        if (step == null || step.isNegative() || step.isZero()
                || step.compareTo(promql.minStep()) < 0 || step.getNano() != 0) {
            throw new ToolInputException("step must be a whole number of seconds and at least "
                    + promql.minStep());
        }
        long requestedSamples = Math.floorDiv(range.toSeconds(), step.toSeconds()) + 1;
        if (requestedSamples > promql.maxSamples()) {
            throw new ToolInputException("metrics request exceeds " + promql.maxSamples() + " samples");
        }
    }

    private void validateFunctionScalars(String query) {
        Matcher ranks = RANK_FUNCTION.matcher(eraseQuotedStrings(query));
        while (ranks.find()) {
            long rank;
            try {
                rank = Long.parseLong(ranks.group(1));
            } catch (NumberFormatException exception) {
                throw new ToolInputException("PromQL rank parameter is too large");
            }
            if (rank < 1 || rank > promql.maxSeries()) {
                throw new ToolInputException(
                        "PromQL topk/bottomk parameter must be between 1 and " + promql.maxSeries());
            }
        }
    }

    public void validateResponse(JsonNode response) {
        if (!"success".equals(response.path("status").asText())) {
            throw new UpstreamResultException("Prometheus returned a non-success response");
        }
        JsonNode result = response.path("data").path("result");
        if (!result.isArray()) {
            throw new UpstreamResultException("Prometheus response does not contain a result array");
        }
        if (result.size() > promql.maxSeries()) {
            throw new UpstreamResultException("Prometheus result exceeds " + promql.maxSeries() + " series");
        }

        long samples = 0;
        for (JsonNode series : result) {
            JsonNode values = series.path("values");
            if (values.isArray()) {
                samples += values.size();
            } else if (series.has("value")) {
                samples++;
            }
            if (samples > promql.maxSamples()) {
                throw new UpstreamResultException(
                        "Prometheus result exceeds " + promql.maxSamples() + " samples");
            }
        }
    }

    private void validateMetricSelectors(String query) {
        String withoutStrings = eraseQuotedStrings(query);
        String withoutLabels = eraseDelimited(withoutStrings, '{', '}');
        String simplified = GROUP_MODIFIER.matcher(withoutLabels).replaceAll(" ");
        Matcher matcher = IDENTIFIER.matcher(simplified);
        boolean foundMetric = false;
        int metricSelectors = 0;
        while (matcher.find()) {
            String identifier = matcher.group(1);
            int nextIndex = nextNonWhitespace(simplified, matcher.end());
            if (nextIndex < simplified.length() && simplified.charAt(nextIndex) == '(') {
                if (!FUNCTIONS.contains(identifier.toLowerCase(Locale.ROOT))) {
                    throw new ToolInputException("PromQL function is not allowed: " + identifier);
                }
                continue;
            }
            if (KEYWORDS.contains(identifier.toLowerCase(Locale.ROOT)) || isDurationUnit(identifier, simplified, matcher.start())) {
                continue;
            }
            if (!isAllowedMetric(identifier)) {
                throw new ToolInputException("metric is outside the allowed namespaces: " + identifier);
            }
            foundMetric = true;
            metricSelectors++;
            if (metricSelectors > 20) {
                throw new ToolInputException("query contains too many metric selectors");
            }
        }
        if (!foundMetric) {
            throw new ToolInputException("query must contain an explicit allowed metric name");
        }
    }

    private void validateSelectorsHaveAllowedMetricNames(String query) {
        String withoutStrings = eraseQuotedStrings(query);
        if (Pattern.compile("(?i)\\b__name__\\s*(?:=|!=)").matcher(withoutStrings).find()) {
            throw new ToolInputException("PromQL __name__ label selectors are not allowed");
        }
        for (int brace = withoutStrings.indexOf('{'); brace >= 0;
                brace = withoutStrings.indexOf('{', brace + 1)) {
            int end = brace - 1;
            while (end >= 0 && Character.isWhitespace(withoutStrings.charAt(end))) {
                end--;
            }
            int start = end;
            while (start >= 0 && isMetricCharacter(withoutStrings.charAt(start))) {
                start--;
            }
            String metric = withoutStrings.substring(start + 1, end + 1);
            if (metric.isEmpty() || !isAllowedMetric(metric)) {
                throw new ToolInputException(
                        "every PromQL label selector must have an explicit allowed metric name");
            }
        }
    }

    private static boolean isMetricCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == ':';
    }

    private void validateRangeSelectors(String query) {
        Matcher selector = RANGE_SELECTOR.matcher(eraseQuotedStrings(query));
        while (selector.find()) {
            String duration = selector.group(1).trim();
            if (duration.indexOf(':') >= 0) {
                throw new ToolInputException("PromQL subqueries are not allowed");
            }
            Duration parsed = parsePrometheusDuration(duration);
            if (parsed.compareTo(promql.maxRange()) > 0) {
                throw new ToolInputException("PromQL range selector exceeds " + promql.maxRange());
            }
        }
    }

    private static Duration parsePrometheusDuration(String value) {
        Matcher matcher = Pattern.compile("([1-9][0-9]*)(ms|s|m|h|d|w|y)").matcher(value);
        long millis = 0;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) {
                throw new ToolInputException("PromQL range selector has an unsupported duration");
            }
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
                millis = Math.addExact(millis, Math.multiplyExact(amount, unitMillis(matcher.group(2))));
            } catch (ArithmeticException exception) {
                throw new ToolInputException("PromQL range selector duration is too large");
            }
            end = matcher.end();
        }
        if (end != value.length() || millis < 1) {
            throw new ToolInputException("PromQL range selector has an unsupported duration");
        }
        return Duration.ofMillis(millis);
    }

    private static long unitMillis(String unit) {
        return switch (unit) {
            case "ms" -> 1L;
            case "s" -> 1_000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            case "w" -> 604_800_000L;
            case "y" -> 31_536_000_000L;
            default -> throw new ToolInputException("unsupported PromQL duration unit");
        };
    }

    private boolean isAllowedMetric(String metric) {
        return promql.allowedMetricPrefixes().stream()
                .filter(prefix -> prefix != null && !prefix.isBlank())
                .anyMatch(prefix -> prefix.endsWith("_") ? metric.startsWith(prefix) : metric.equals(prefix));
    }

    private static boolean isDurationUnit(String identifier, String query, int start) {
        if (!Set.of("ms", "s", "m", "h", "d", "w", "y").contains(identifier)) {
            return false;
        }
        return start > 0 && Character.isDigit(query.charAt(start - 1));
    }

    private static int nextNonWhitespace(String value, int from) {
        int index = from;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String eraseQuotedStrings(String value) {
        StringBuilder result = new StringBuilder(value);
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < result.length(); i++) {
            char current = result.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
                result.setCharAt(i, ' ');
            } else if (current == '"') {
                quoted = true;
                result.setCharAt(i, ' ');
            }
        }
        if (quoted) {
            throw new ToolInputException("query contains an unterminated string");
        }
        return result.toString();
    }

    private static String eraseDelimited(String value, char open, char close) {
        StringBuilder result = new StringBuilder(value);
        int depth = 0;
        for (int i = 0; i < result.length(); i++) {
            char current = result.charAt(i);
            if (current == open) {
                depth++;
            }
            if (depth > 0) {
                result.setCharAt(i, ' ');
            }
            if (current == close) {
                depth--;
                if (depth < 0) {
                    throw new ToolInputException("query has unbalanced delimiters");
                }
            }
        }
        if (depth != 0) {
            throw new ToolInputException("query has unbalanced delimiters");
        }
        return result.toString();
    }

    public static class UpstreamResultException extends RuntimeException {
        public UpstreamResultException(String message) {
            super(message);
        }
    }
}
