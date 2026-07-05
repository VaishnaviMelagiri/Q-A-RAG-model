package com.qacopilot.ingest;

/**
 * A loaded source document reduced to plain text, before chunking.
 *
 * @param sourceName original filename (used in citations)
 * @param sourceType detected type: pdf | html | markdown | text
 * @param text       extracted UTF-8 text
 */
public record RawDoc(String sourceName, String sourceType, String text) {}
