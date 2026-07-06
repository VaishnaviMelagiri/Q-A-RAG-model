package com.qacopilot.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic citation-integrity guard for a generated answer. Passages are labeled with an
 * {@code S}-prefixed token ({@code [S1]..[SN]}, see {@link PromptSupport#numberedPassages}); a
 * valid citation is one of those labels. LLMs sometimes still emit a marker that maps to nothing —
 * most often by echoing a bare number that appears INSIDE a passage (a source list item "7.")
 * instead of the passage label. Such a dangling citation breaks the project's "every answer cites
 * the retrieved chunks it used" promise.
 *
 * <p><b>Behavior (chosen, documented in ARCHITECTURE.md):</b> any marker that is not a valid
 * {@code [S#]} label with {@code 1 <= # <= passageCount} — i.e. a bare {@code [7]} or an
 * out-of-range {@code [S9]} — is <b>stripped</b> from the text while the sentence is <b>kept</b>.
 * Grounding is the verify layer's responsibility (it checks each claim against the passages
 * regardless of markers), so a wrong <i>attribution</i> should not delete otherwise grounded
 * content. Valid {@code [S#]} labels are preserved untouched.
 *
 * <p><b>Scope:</b> this guard handles the mechanical, deterministic case (dangling {@code [n]}). It
 * also reports {@code hasValidCitation} so the caller can flag the wholesale "no citation at all"
 * case. Detecting whether an <i>individual uncited sentence</i> is a factual claim that <i>should</i>
 * be cited is a semantic judgment, not a mechanical one — that is the verify layer's job (it strips
 * ungrounded claims whether or not they carry a marker), so it is intentionally NOT attempted here.
 */
final class CitationGuard {

    private CitationGuard() {}

    /**
     * Matches a citation-like marker: an {@code [S#]} label (valid form) OR a bare {@code [#]}
     * (an in-text number the model may have echoed). Group 1 = optional "S", group 2 = digits.
     */
    private static final Pattern MARKER = Pattern.compile("\\[(S?)(\\d{1,3})]", Pattern.CASE_INSENSITIVE);

    static Result sanitize(String answer, int passageCount) {
        if (answer == null || answer.isBlank()) {
            return new Result(answer == null ? "" : answer, List.of(), false);
        }
        Matcher m = MARKER.matcher(answer);
        StringBuilder sb = new StringBuilder();
        List<Integer> invalid = new ArrayList<>();
        boolean valid = false;
        while (m.find()) {
            boolean labelled = !m.group(1).isEmpty();      // has the "S" prefix
            int n = Integer.parseInt(m.group(2));
            if (labelled && n >= 1 && n <= passageCount) {
                valid = true;
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                invalid.add(n);
                m.appendReplacement(sb, ""); // strip bare [n] or out-of-range [S#]; keep the sentence
            }
        }
        m.appendTail(sb);
        return new Result(tidy(sb.toString()), List.copyOf(invalid), valid);
    }

    /** Remove whitespace left dangling before punctuation and collapse the resulting double spaces. */
    private static String tidy(String s) {
        return s.replaceAll("\\s+([.,;:!?])", "$1")
                .replaceAll("[ \\t]{2,}", " ")
                .strip();
    }

    /**
     * @param answer           the answer with out-of-range markers removed
     * @param invalidCitations the out-of-range marker numbers that were stripped (for logging)
     * @param hasValidCitation whether at least one in-range citation remains
     */
    record Result(String answer, List<Integer> invalidCitations, boolean hasValidCitation) {}
}
