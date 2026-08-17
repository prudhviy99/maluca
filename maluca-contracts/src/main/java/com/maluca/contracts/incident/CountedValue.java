package com.maluca.contracts.incident;

/** One value and its frequency in a bounded incident summary. */
public record CountedValue(String value, long count) {
}
