package com.hilalsameer.videoaistudio.media

/**
 * SRT merge (TD-009, the owner's rule: "in order, from the first
 * file to the last file, through to the production stage").
 *
 * Parts are merged strictly in the order given; every part's
 * timestamps are shifted by the CUMULATIVE EXACT DURATIONS of the
 * video parts before it — never by a preceding file's last
 * subtitle timestamp, which ends before its part does and would
 * accumulate drift. The merged list is the single production
 * input for translation and export.
 */
object SubtitleMerge {

    /**
     * @param parts segments of each part, in merge order.
     * @param partDurationsSec the exact duration of each part's
     *        VIDEO, same order and count as [parts]. The last
     *        part's duration is never used as an offset but is
     *        still required, so a miscounted form cannot slip
     *        through silently.
     */
    fun merge(
        parts: List<List<AsrSegment>>,
        partDurationsSec: List<Double>
    ): List<AsrSegment> {
        require(parts.size >= 2) { "merge needs at least two parts" }
        require(partDurationsSec.size == parts.size) {
            "every part needs its video duration"
        }
        require(partDurationsSec.all { it > 0.0 }) { "durations must be positive" }
        val out = ArrayList<AsrSegment>()
        var offset = 0.0
        parts.forEachIndexed { i, segs ->
            for (s in segs) {
                out.add(AsrSegment(s.startSec + offset, s.endSec + offset, s.text))
            }
            offset += partDurationsSec[i]
        }
        return out
    }
}
