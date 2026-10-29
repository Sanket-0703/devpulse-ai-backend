package com.devpulse.backend.dto;

/**
 * One finding as the AI model reports it. Free text from a model: the
 * review job validates every field before anything is saved.
 *
 * @param file the path from the "File:" header of the reviewed diff; null for a plain snippet
 * @param line the line number in the new file, as numbered in the diff; null for the whole file
 */
public record AIReviewFinding(
        String severity,
        String category,
        String title,
        String description,
        String recommendation,
        String file,
        Integer line
) {
}
