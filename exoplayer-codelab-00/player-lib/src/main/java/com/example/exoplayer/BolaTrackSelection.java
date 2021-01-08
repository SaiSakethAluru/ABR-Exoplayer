/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.exoplayer;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.chunk.MediaChunk;
import com.google.android.exoplayer2.source.chunk.MediaChunkIterator;
import com.google.android.exoplayer2.trackselection.BaseTrackSelection;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.Util;

import org.checkerframework.checker.nullness.compatqual.NullableType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collector;

/**
 * A bandwidth based adaptive {@link TrackSelection}, whose selected track is updated to be the one
 * of highest quality given the current network conditions and the state of the buffer.
 */
public class BolaTrackSelection extends BaseTrackSelection {

    public static final int DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS = 10000;
    public static final int DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS = 25000;
    public static final int DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS = 25000;
    public static final float DEFAULT_BANDWIDTH_FRACTION = 0.7f;
    public static final float DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE = 0.75f;
    public static final long DEFAULT_MIN_TIME_BETWEEN_BUFFER_REEVALUTATION_MS = 2000;

//    private static final Integer[] VIDEO_BIT_RATE = new Integer[]{300, 750, 1200, 1850, 2850, 4300};
    private Double[] VIDEO_BIT_RATE;
    private Double[] UTILITIES;
    private String video_name;
//    private static final Double [] UTILITIES = new Double[]{Math.log(300),Math.log(750),Math.log(1200),Math.log(1850),Math.log(2850),Math.log(4300)};
    private static final double MINIMUM_BUFFER_S = 10.0;
    private static final double MINIMUM_BUFFER_PER_BITRATE_LEVEL_S = 2.0;
    private static final double DEFAULT_STABLE_BUFFER_TIME = 12.0;
    //    private final Context context;
    private final BandwidthProvider bandwidthProvider;
    private final long minDurationForQualityIncreaseUs;
    private final long maxDurationForQualityDecreaseUs;
    private final long minDurationToRetainAfterDiscardUs;
    private final float bufferedFractionToLiveEdgeForQualityIncrease;
    private final long minTimeBetweenBufferReevaluationMs;
    private final Clock clock;
    private int reason;
    private float playbackSpeed;
    private int selectedIndex;
    private long lastBufferEvaluationMs;

    /**
     * @param group          The {@link TrackGroup}.
     * @param tracks         The indices of the selected tracks within the {@link TrackGroup}. Must not be
     *                       empty. May be in any order.
     * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
     */
    public BolaTrackSelection(String video_name, TrackGroup group, int[] tracks,
                              BandwidthMeter bandwidthMeter) {
        this(
                video_name,
                group,
                tracks,
                bandwidthMeter,
                /* reservedBandwidth= */ 0,
                DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
                DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
                DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
                DEFAULT_BANDWIDTH_FRACTION,
                DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
                DEFAULT_MIN_TIME_BETWEEN_BUFFER_REEVALUTATION_MS,
                Clock.DEFAULT);
    }

    /**
     * @param group                                        The {@link TrackGroup}.
     * @param tracks                                       The indices of the selected tracks within the {@link TrackGroup}. Must not be
     *                                                     empty. May be in any order.
     * @param bandwidthMeter                               Provides an estimate of the currently available bandwidth.
     * @param reservedBandwidth                            The reserved bandwidth, which shouldn't be considered available for
     *                                                     use, in bits per second.
     * @param minDurationForQualityIncreaseMs              The minimum duration of buffered data required for the
     *                                                     selected track to switch to one of higher quality.
     * @param maxDurationForQualityDecreaseMs              The maximum duration of buffered data required for the
     *                                                     selected track to switch to one of lower quality.
     * @param minDurationToRetainAfterDiscardMs            When switching to a track of significantly higher
     *                                                     quality, the selection may indicate that media already buffered at the lower quality can be
     *                                                     discarded to speed up the switch. This is the minimum duration of media that must be
     *                                                     retained at the lower quality.
     * @param bandwidthFraction                            The fraction of the available bandwidth that the selection should
     *                                                     consider available for use. Setting to a value less than 1 is recommended to account for
     *                                                     inaccuracies in the bandwidth estimator.
     * @param bufferedFractionToLiveEdgeForQualityIncrease For live streaming, the fraction of the
     *                                                     duration from current playback position to the live edge that has to be buffered before the
     *                                                     selected track can be switched to one of higher quality. This parameter is only applied
     *                                                     when the playback position is closer to the live edge than {@code
     *                                                     minDurationForQualityIncreaseMs}, which would otherwise prevent switching to a higher
     *                                                     quality from happening.
     * @param minTimeBetweenBufferReevaluationMs           The track selection may periodically reevaluate its
     *                                                     buffer and discard some chunks of lower quality to improve the playback quality if network
     *                                                     condition has changed. This is the minimum duration between 2 consecutive buffer
     *                                                     reevaluation calls.
     */
    public BolaTrackSelection(
            String video_name,
            TrackGroup group,
            int[] tracks,
            BandwidthMeter bandwidthMeter,
            long reservedBandwidth,
            long minDurationForQualityIncreaseMs,
            long maxDurationForQualityDecreaseMs,
            long minDurationToRetainAfterDiscardMs,
            float bandwidthFraction,
            float bufferedFractionToLiveEdgeForQualityIncrease,
            long minTimeBetweenBufferReevaluationMs,
            Clock clock) {
        this(
                video_name,
                group,
                tracks,
                new DefaultBandwidthProvider(bandwidthMeter, bandwidthFraction, reservedBandwidth),
                minDurationForQualityIncreaseMs,
                maxDurationForQualityDecreaseMs,
                minDurationToRetainAfterDiscardMs,
                bufferedFractionToLiveEdgeForQualityIncrease,
                minTimeBetweenBufferReevaluationMs,
                clock);
    }

    private BolaTrackSelection(
            String video_name,
            TrackGroup group,
            int[] tracks,
            BandwidthProvider bandwidthProvider,
            long minDurationForQualityIncreaseMs,
            long maxDurationForQualityDecreaseMs,
            long minDurationToRetainAfterDiscardMs,
            float bufferedFractionToLiveEdgeForQualityIncrease,
            long minTimeBetweenBufferReevaluationMs,
            Clock clock) {
        super(group, tracks);
        this.video_name = video_name;
        this.bandwidthProvider = bandwidthProvider;
        this.minDurationForQualityIncreaseUs = minDurationForQualityIncreaseMs * 1000L;
        this.maxDurationForQualityDecreaseUs = maxDurationForQualityDecreaseMs * 1000L;
        this.minDurationToRetainAfterDiscardUs = minDurationToRetainAfterDiscardMs * 1000L;
        this.bufferedFractionToLiveEdgeForQualityIncrease =
                bufferedFractionToLiveEdgeForQualityIncrease;
        this.minTimeBetweenBufferReevaluationMs = minTimeBetweenBufferReevaluationMs;
        this.clock = clock;
        playbackSpeed = 1f;
        reason = C.SELECTION_REASON_UNKNOWN;
        lastBufferEvaluationMs = C.TIME_UNSET;
        switch (video_name){
            case "envivio":
                this.VIDEO_BIT_RATE = new Double[]{300.0, 750.0, 1200.0, 1850.0, 2850.0, 4300.0};
                break;
            case "tears_of_steel":
                this.VIDEO_BIT_RATE = new Double[]{686.685,686.685,111.6150,1929.169,2362.822,2470.094};
                break;
            case "redbull_2sec":
                this.VIDEO_BIT_RATE = new Double[]{300.795,700.051,1179.845,1993.730,2995.671,3992.758};
                break;
            case "bbb_30fps":
                this.VIDEO_BIT_RATE = new Double[]{507.246,1013.310,1254.758,1883.700,3134.488,4952.892};
        }
        this.UTILITIES = new Double[this.VIDEO_BIT_RATE.length];
        for(int i=0;i<this.VIDEO_BIT_RATE.length;i++){
            this.UTILITIES[i] = Math.log(this.VIDEO_BIT_RATE[i]);
        }
    }

    /**
     * Returns allocation checkpoints for allocating bandwidth between multiple adaptive track
     * selections.
     *
     * @param trackBitrates Array of [selectionIndex][trackIndex] -> trackBitrate.
     * @return Array of allocation checkpoints [selectionIndex][checkpointIndex][2] with [0]=total
     * bandwidth at checkpoint and [1]=allocated bandwidth at checkpoint.
     */
    private static long[][][] getAllocationCheckpoints(long[][] trackBitrates) {
        // Algorithm:
        //  1. Use log bitrates to treat all resolution update steps equally.
        //  2. Distribute switch points for each selection equally in the same [0.0-1.0] range.
        //  3. Switch up one format at a time in the order of the switch points.
        double[][] logBitrates = getLogArrayValues(trackBitrates);
        double[][] switchPoints = getSwitchPoints(logBitrates);

        // There will be (count(switch point) + 3) checkpoints:
        // [0] = all zero, [1] = minimum bitrates, [2-(end-1)] = up-switch points,
        // [end] = extra point to set slope for additional bitrate.
        int checkpointCount = countArrayElements(switchPoints) + 3;
        long[][][] checkpoints = new long[logBitrates.length][checkpointCount][2];
        int[] currentSelection = new int[logBitrates.length];
        setCheckpointValues(checkpoints, /* checkpointIndex= */ 1, trackBitrates, currentSelection);
        for (int checkpointIndex = 2; checkpointIndex < checkpointCount - 1; checkpointIndex++) {
            int nextUpdateIndex = 0;
            double nextUpdateSwitchPoint = Double.MAX_VALUE;
            for (int i = 0; i < logBitrates.length; i++) {
                if (currentSelection[i] + 1 == logBitrates[i].length) {
                    continue;
                }
                double switchPoint = switchPoints[i][currentSelection[i]];
                if (switchPoint < nextUpdateSwitchPoint) {
                    nextUpdateSwitchPoint = switchPoint;
                    nextUpdateIndex = i;
                }
            }
            currentSelection[nextUpdateIndex]++;
            setCheckpointValues(checkpoints, checkpointIndex, trackBitrates, currentSelection);
        }
        for (long[][] points : checkpoints) {
            points[checkpointCount - 1][0] = 2 * points[checkpointCount - 2][0];
            points[checkpointCount - 1][1] = 2 * points[checkpointCount - 2][1];
        }
        return checkpoints;
    }

    /**
     * Converts all input values to Math.log(value).
     */
    private static double[][] getLogArrayValues(long[][] values) {
        double[][] logValues = new double[values.length][];
        for (int i = 0; i < values.length; i++) {
            logValues[i] = new double[values[i].length];
            for (int j = 0; j < values[i].length; j++) {
                logValues[i][j] = values[i][j] == Format.NO_VALUE ? 0 : Math.log(values[i][j]);
            }
        }
        return logValues;
    }

    /**
     * Returns idealized switch points for each switch between consecutive track selection bitrates.
     *
     * @param logBitrates Log bitrates with [selectionCount][formatCount].
     * @return Linearly distributed switch points in the range of [0.0-1.0].
     */
    private static double[][] getSwitchPoints(double[][] logBitrates) {
        double[][] switchPoints = new double[logBitrates.length][];
        for (int i = 0; i < logBitrates.length; i++) {
            switchPoints[i] = new double[logBitrates[i].length - 1];
            if (switchPoints[i].length == 0) {
                continue;
            }
            double totalBitrateDiff = logBitrates[i][logBitrates[i].length - 1] - logBitrates[i][0];
            for (int j = 0; j < logBitrates[i].length - 1; j++) {
                double switchBitrate = 0.5 * (logBitrates[i][j] + logBitrates[i][j + 1]);
                switchPoints[i][j] =
                        totalBitrateDiff == 0.0 ? 1.0 : (switchBitrate - logBitrates[i][0]) / totalBitrateDiff;
            }
        }
        return switchPoints;
    }

    /**
     * Returns total number of elements in a 2D array.
     */
    private static int countArrayElements(double[][] array) {
        int count = 0;
        for (double[] subArray : array) {
            count += subArray.length;
        }
        return count;
    }

    /**
     * Sets checkpoint bitrates.
     *
     * @param checkpoints     Output checkpoints with [selectionIndex][checkpointIndex][2] where [0]=Total
     *                        bitrate and [1]=Allocated bitrate.
     * @param checkpointIndex The checkpoint index.
     * @param trackBitrates   The track bitrates with [selectionIndex][trackIndex].
     * @param selectedTracks  The indices of selected tracks for each selection for this checkpoint.
     */
    private static void setCheckpointValues(
            long[][][] checkpoints, int checkpointIndex, long[][] trackBitrates, int[] selectedTracks) {
        long totalBitrate = 0;
        for (int i = 0; i < checkpoints.length; i++) {
            checkpoints[i][checkpointIndex][1] = trackBitrates[i][selectedTracks[i]];
            totalBitrate += checkpoints[i][checkpointIndex][1];
        }
        for (long[][] points : checkpoints) {
            points[checkpointIndex][0] = totalBitrate;
        }
    }

    /**
     * Sets checkpoints to determine the allocation bandwidth based on the total bandwidth.
     *
     * @param allocationCheckpoints List of checkpoints. Each element must be a long[2], with [0]
     *                              being the total bandwidth and [1] being the allocated bandwidth.
     */
    public void experimental_setBandwidthAllocationCheckpoints(long[][] allocationCheckpoints) {
        ((DefaultBandwidthProvider) bandwidthProvider)
                .experimental_setBandwidthAllocationCheckpoints(allocationCheckpoints);
    }

    @Override
    public void enable() {
        lastBufferEvaluationMs = C.TIME_UNSET;
    }

    @Override
    public void onPlaybackSpeed(float playbackSpeed) {
        this.playbackSpeed = playbackSpeed;
    }

    @Override
    public void updateSelectedTrack(
            long playbackPositionUs,
            long bufferedDurationUs,
            long availableDurationUs,
            List<? extends MediaChunk> queue,
            MediaChunkIterator[] mediaChunkIterators) {
        if(reason == C.SELECTION_REASON_UNKNOWN){
            reason = C.SELECTION_REASON_INITIAL;
            selectedIndex = VIDEO_BIT_RATE.length - 1;
            return;
        }
        double bufferTime = Math.max(bufferedDurationUs/1000000.0,MINIMUM_BUFFER_S+MINIMUM_BUFFER_PER_BITRATE_LEVEL_S*VIDEO_BIT_RATE.length);
        double bufferLevel = bufferedDurationUs/1000000.0;
        int highestUtilityIndex = 0;
        for(int i=0;i<UTILITIES.length;i++){
            highestUtilityIndex = UTILITIES[i]>UTILITIES[highestUtilityIndex]? i: highestUtilityIndex;
        }
        if(highestUtilityIndex == 0){
            return;
        }
        double gp = (UTILITIES[highestUtilityIndex]-1)/(bufferTime/MINIMUM_BUFFER_S-1);
        double vp = MINIMUM_BUFFER_S/gp;

        int quality = -1;
        Double score = Double.NaN;
        for(int i=0;i<VIDEO_BIT_RATE.length;i++){
            double s = (vp*(UTILITIES[i]+gp)-bufferLevel) / VIDEO_BIT_RATE[i];
            if(score.isNaN() || s>=score){
                quality = i;
                score = s;
            }
        }
        System.out.println("Selected quality "+quality);
        if(selectedIndex != VIDEO_BIT_RATE.length - 1 - quality){
            selectedIndex = VIDEO_BIT_RATE.length-1-quality;
            reason = C.SELECTION_REASON_ADAPTIVE;
        }

    }

    @Override
    public int getSelectedIndex() {
        return selectedIndex;
    }

    @Override
    public int getSelectionReason() {
        return reason;
    }

    @Override
    @Nullable
    public Object getSelectionData() {
        return null;
    }

    @Override
    public int evaluateQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
        long nowMs = clock.elapsedRealtime();
        if (!shouldEvaluateQueueSize(nowMs)) {
            return queue.size();
        }

        lastBufferEvaluationMs = nowMs;
        if (queue.isEmpty()) {
            return 0;
        }

        int queueSize = queue.size();
        MediaChunk lastChunk = queue.get(queueSize - 1);
        long playoutBufferedDurationBeforeLastChunkUs =
                Util.getPlayoutDurationForMediaDuration(
                        lastChunk.startTimeUs - playbackPositionUs, playbackSpeed);
        long minDurationToRetainAfterDiscardUs = getMinDurationToRetainAfterDiscardUs();
        if (playoutBufferedDurationBeforeLastChunkUs < minDurationToRetainAfterDiscardUs) {
            return queueSize;
        }
//        int idealSelectedIndex = determineIdealSelectedIndex(nowMs);
        int idealSelectedIndex = selectedIndex;
        Format idealFormat = getFormat(idealSelectedIndex);
        // If the chunks contain video, discard from the first SD chunk beyond
        // minDurationToRetainAfterDiscardUs whose resolution and bitrate are both lower than the ideal
        // track.
        for (int i = 0; i < queueSize; i++) {
            MediaChunk chunk = queue.get(i);
            Format format = chunk.trackFormat;
            long mediaDurationBeforeThisChunkUs = chunk.startTimeUs - playbackPositionUs;
            long playoutDurationBeforeThisChunkUs =
                    Util.getPlayoutDurationForMediaDuration(mediaDurationBeforeThisChunkUs, playbackSpeed);
            if (playoutDurationBeforeThisChunkUs >= minDurationToRetainAfterDiscardUs
                    && format.bitrate < idealFormat.bitrate
                    && format.height != Format.NO_VALUE && format.height < 720
                    && format.width != Format.NO_VALUE && format.width < 1280
                    && format.height < idealFormat.height) {
                return i;
            }
        }
        return queueSize;
    }

    /**
     * Called when updating the selected track to determine whether a candidate track can be selected.
     *
     * @param format           The {@link Format} of the candidate track.
     * @param trackBitrate     The estimated bitrate of the track. May differ from {@link Format#bitrate}
     *                         if a more accurate estimate of the current track bitrate is available.
     * @param playbackSpeed    The current playback speed.
     * @param effectiveBitrate The bitrate available to this selection.
     * @return Whether this {@link Format} can be selected.
     */
    @SuppressWarnings("unused")
    protected boolean canSelectFormat(
            Format format, int trackBitrate, float playbackSpeed, long effectiveBitrate) {
        return Math.round(trackBitrate * playbackSpeed) <= effectiveBitrate;
    }

    /**
     * Called from {@link #evaluateQueueSize(long, List)} to determine whether an evaluation should be
     * performed.
     *
     * @param nowMs The current value of {@link Clock#elapsedRealtime()}.
     * @return Whether an evaluation should be performed.
     */
    protected boolean shouldEvaluateQueueSize(long nowMs) {
        return lastBufferEvaluationMs == C.TIME_UNSET
                || nowMs - lastBufferEvaluationMs >= minTimeBetweenBufferReevaluationMs;
    }

    /**
     * Called from {@link #evaluateQueueSize(long, List)} to determine the minimum duration of buffer
     * to retain after discarding chunks.
     *
     * @return The minimum duration of buffer to retain after discarding chunks, in microseconds.
     */
    protected long getMinDurationToRetainAfterDiscardUs() {
        return minDurationToRetainAfterDiscardUs;
    }

    /**
     * Computes the ideal selected index ignoring buffer health.
     *
     * @param nowMs The current time in the timebase of {@link Clock#elapsedRealtime()}, or {@link
     *              Long#MIN_VALUE} to ignore blacklisting.
     */
    private int determineIdealSelectedIndex(long nowMs) {
        long effectiveBitrate = bandwidthProvider.getAllocatedBandwidth();
        int lowestBitrateNonBlacklistedIndex = 0;
        for (int i = 0; i < length; i++) {
            if (nowMs == Long.MIN_VALUE || !isBlacklisted(i, nowMs)) {
                Format format = getFormat(i);
                if (canSelectFormat(format, format.bitrate, playbackSpeed, effectiveBitrate)) {
                    return i;
                } else {
                    lowestBitrateNonBlacklistedIndex = i;
                }
            }
        }
        return lowestBitrateNonBlacklistedIndex;
    }

    private long minDurationForQualityIncreaseUs(long availableDurationUs) {
        boolean isAvailableDurationTooShort = availableDurationUs != C.TIME_UNSET
                && availableDurationUs <= minDurationForQualityIncreaseUs;
        return isAvailableDurationTooShort
                ? (long) (availableDurationUs * bufferedFractionToLiveEdgeForQualityIncrease)
                : minDurationForQualityIncreaseUs;
    }

    /**
     * Calculate total number of chunks in video
     *
     * @param iterator a media chunk iterator
     * @return
     */
    private int evaluateTotalChunks(MediaChunkIterator iterator) {
        int chunkCount = 0;
        while (!iterator.isEnded()) {
            chunkCount++;
            iterator.next();
        }
        iterator.reset();
        iterator.next();
        return chunkCount;
    }

    /**
     * Provides the allocated bandwidth.
     */
    private interface BandwidthProvider {

        /**
         * Returns the allocated bitrate.
         */
        long getAllocatedBandwidth();
    }

    /**
     * Factory for {@link BolaTrackSelection} instances.
     */
    public static class Factory implements TrackSelection.Factory {

        @Nullable
        private final BandwidthMeter bandwidthMeter;
//        private final Context context;
        private String video_name;
        private final int minDurationForQualityIncreaseMs;
        private final int maxDurationForQualityDecreaseMs;
        private final int minDurationToRetainAfterDiscardMs;
        private final float bandwidthFraction;
        private final float bufferedFractionToLiveEdgeForQualityIncrease;
        private final long minTimeBetweenBufferReevaluationMs;
        private final Clock clock;

        /**
         * Creates an adaptive track selection factory with default parameters.
         */
        public Factory(String video_name) {
            this(
                    video_name,
                    DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
                    DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
                    DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
                    DEFAULT_BANDWIDTH_FRACTION,
                    DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
                    DEFAULT_MIN_TIME_BETWEEN_BUFFER_REEVALUTATION_MS,
                    Clock.DEFAULT);
        }

        /**
         * @deprecated Use Factory() instead. Custom bandwidth meter should be directly passed
         * to the player in {@link SimpleExoPlayer.Builder}.
         */
        @Deprecated
        @SuppressWarnings("deprecation")
        public Factory(String video_name,BandwidthMeter bandwidthMeter) {
            this(
                    video_name,
                    bandwidthMeter,
                    DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
                    DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
                    DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
                    DEFAULT_BANDWIDTH_FRACTION,
                    DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
                    DEFAULT_MIN_TIME_BETWEEN_BUFFER_REEVALUTATION_MS,
                    Clock.DEFAULT);
        }

        /**
         * Creates an adaptive track selection factory.
         *
         * @param minDurationForQualityIncreaseMs   The minimum duration of buffered data required for the
         *                                          selected track to switch to one of higher quality.
         * @param maxDurationForQualityDecreaseMs   The maximum duration of buffered data required for the
         *                                          selected track to switch to one of lower quality.
         * @param minDurationToRetainAfterDiscardMs When switching to a track of significantly higher
         *                                          quality, the selection may indicate that media already buffered at the lower quality can
         *                                          be discarded to speed up the switch. This is the minimum duration of media that must be
         *                                          retained at the lower quality.
         * @param bandwidthFraction                 The fraction of the available bandwidth that the selection should
         *                                          consider available for use. Setting to a value less than 1 is recommended to account for
         *                                          inaccuracies in the bandwidth estimator.
         */
        public Factory(
                String video_name,
                int minDurationForQualityIncreaseMs,
                int maxDurationForQualityDecreaseMs,
                int minDurationToRetainAfterDiscardMs,
                float bandwidthFraction) {
            this(
                    video_name,
                    minDurationForQualityIncreaseMs,
                    maxDurationForQualityDecreaseMs,
                    minDurationToRetainAfterDiscardMs,
                    bandwidthFraction,
                    DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
                    DEFAULT_MIN_TIME_BETWEEN_BUFFER_REEVALUTATION_MS,
                    Clock.DEFAULT);
        }

        /**
         * @deprecated Use Factory(int, int, int, float) instead. Custom bandwidth meter should
         * be directly passed to the player in {@link SimpleExoPlayer.Builder}.
         */
        @Deprecated
        @SuppressWarnings("deprecation")
        public Factory(
                String video_name,
                BandwidthMeter bandwidthMeter,
                int minDurationForQualityIncreaseMs,
                int maxDurationForQualityDecreaseMs,
                int minDurationToRetainAfterDiscardMs,
                float bandwidthFraction) {
            this(
                    video_name,
                    bandwidthMeter,
                    minDurationForQualityIncreaseMs,
                    maxDurationForQualityDecreaseMs,
                    minDurationToRetainAfterDiscardMs,
                    bandwidthFraction,
                    DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
                    DEFAULT_MIN_TIME_BETWEEN_BUFFER_REEVALUTATION_MS,
                    Clock.DEFAULT);
        }

        /**
         * Creates an adaptive track selection factory.
         *
         * @param minDurationForQualityIncreaseMs              The minimum duration of buffered data required for the
         *                                                     selected track to switch to one of higher quality.
         * @param maxDurationForQualityDecreaseMs              The maximum duration of buffered data required for the
         *                                                     selected track to switch to one of lower quality.
         * @param minDurationToRetainAfterDiscardMs            When switching to a track of significantly higher
         *                                                     quality, the selection may indicate that media already buffered at the lower quality can
         *                                                     be discarded to speed up the switch. This is the minimum duration of media that must be
         *                                                     retained at the lower quality.
         * @param bandwidthFraction                            The fraction of the available bandwidth that the selection should
         *                                                     consider available for use. Setting to a value less than 1 is recommended to account for
         *                                                     inaccuracies in the bandwidth estimator.
         * @param bufferedFractionToLiveEdgeForQualityIncrease For live streaming, the fraction of the
         *                                                     duration from current playback position to the live edge that has to be buffered before
         *                                                     the selected track can be switched to one of higher quality. This parameter is only
         *                                                     applied when the playback position is closer to the live edge than {@code
         *                                                     minDurationForQualityIncreaseMs}, which would otherwise prevent switching to a higher
         *                                                     quality from happening.
         * @param minTimeBetweenBufferReevaluationMs           The track selection may periodically reevaluate its
         *                                                     buffer and discard some chunks of lower quality to improve the playback quality if
         *                                                     network conditions have changed. This is the minimum duration between 2 consecutive
         *                                                     buffer reevaluation calls.
         * @param clock                                        A {@link Clock}.
         */
        @SuppressWarnings("deprecation")
        public Factory(
                String video_name,
                int minDurationForQualityIncreaseMs,
                int maxDurationForQualityDecreaseMs,
                int minDurationToRetainAfterDiscardMs,
                float bandwidthFraction,
                float bufferedFractionToLiveEdgeForQualityIncrease,
                long minTimeBetweenBufferReevaluationMs,
                Clock clock) {
            this(
                    video_name,
                    /* bandwidthMeter= */ null,
                    minDurationForQualityIncreaseMs,
                    maxDurationForQualityDecreaseMs,
                    minDurationToRetainAfterDiscardMs,
                    bandwidthFraction,
                    bufferedFractionToLiveEdgeForQualityIncrease,
                    minTimeBetweenBufferReevaluationMs,
                    clock);
        }

        /**
         * @deprecated Use Factory(int, int, int, float, float, long, Clock) instead. Custom
         * bandwidth meter should be directly passed to the player in {@link
         * SimpleExoPlayer.Builder}.
         */
        @Deprecated
        public Factory(
                String video_name,
                @Nullable BandwidthMeter bandwidthMeter,
                int minDurationForQualityIncreaseMs,
                int maxDurationForQualityDecreaseMs,
                int minDurationToRetainAfterDiscardMs,
                float bandwidthFraction,
                float bufferedFractionToLiveEdgeForQualityIncrease,
                long minTimeBetweenBufferReevaluationMs,
                Clock clock) {
            this.video_name = video_name;
            this.bandwidthMeter = bandwidthMeter;
            this.minDurationForQualityIncreaseMs = minDurationForQualityIncreaseMs;
            this.maxDurationForQualityDecreaseMs = maxDurationForQualityDecreaseMs;
            this.minDurationToRetainAfterDiscardMs = minDurationToRetainAfterDiscardMs;
            this.bandwidthFraction = bandwidthFraction;
            this.bufferedFractionToLiveEdgeForQualityIncrease =
                    bufferedFractionToLiveEdgeForQualityIncrease;
            this.minTimeBetweenBufferReevaluationMs = minTimeBetweenBufferReevaluationMs;
            this.clock = clock;
        }

        @Override
        public final @NullableType TrackSelection[] createTrackSelections(
                @NullableType Definition[] definitions, BandwidthMeter bandwidthMeter) {
            if (this.bandwidthMeter != null) {
                bandwidthMeter = this.bandwidthMeter;
            }
            TrackSelection[] selections = new TrackSelection[definitions.length];
            int totalFixedBandwidth = 0;
            for (int i = 0; i < definitions.length; i++) {
                Definition definition = definitions[i];
                if (definition != null && definition.tracks.length == 1) {
                    // Make fixed selections first to know their total bandwidth.
                    selections[i] =
                            new FixedTrackSelection(
                                    definition.group, definition.tracks[0], definition.reason, definition.data);
                    int trackBitrate = definition.group.getFormat(definition.tracks[0]).bitrate;
                    if (trackBitrate != Format.NO_VALUE) {
                        totalFixedBandwidth += trackBitrate;
                    }
                }
            }
            List<BolaTrackSelection> adaptiveSelections = new ArrayList<>();
            for (int i = 0; i < definitions.length; i++) {
                Definition definition = definitions[i];
                if (definition != null && definition.tracks.length > 1) {
                    BolaTrackSelection adaptiveSelection =
                            createAdaptiveTrackSelection(
                                    this.video_name,
                                    definition.group,
                                    bandwidthMeter,
                                    definition.tracks,
                                    totalFixedBandwidth);
                    adaptiveSelections.add(adaptiveSelection);
                    selections[i] = adaptiveSelection;
                }
            }
            if (adaptiveSelections.size() > 1) {
                long[][] adaptiveTrackBitrates = new long[adaptiveSelections.size()][];
                for (int i = 0; i < adaptiveSelections.size(); i++) {
                    BolaTrackSelection adaptiveSelection = adaptiveSelections.get(i);
                    adaptiveTrackBitrates[i] = new long[adaptiveSelection.length()];
                    for (int j = 0; j < adaptiveSelection.length(); j++) {
                        adaptiveTrackBitrates[i][j] =
                                adaptiveSelection.getFormat(adaptiveSelection.length() - j - 1).bitrate;
                    }
                }
                long[][][] bandwidthCheckpoints = getAllocationCheckpoints(adaptiveTrackBitrates);
                for (int i = 0; i < adaptiveSelections.size(); i++) {
                    adaptiveSelections
                            .get(i)
                            .experimental_setBandwidthAllocationCheckpoints(bandwidthCheckpoints[i]);
                }
            }
            return selections;
        }

        /**
         * Creates a single adaptive selection for the given group, bandwidth meter and tracks.
         *
         * @param group                    The {@link TrackGroup}.
         * @param bandwidthMeter           A {@link BandwidthMeter} which can be used to select tracks.
         * @param tracks                   The indices of the selected tracks in the track group.
         * @param totalFixedTrackBandwidth The total bandwidth used by all non-adaptive tracks, in bits
         *                                 per second.
         * @return An {@link BolaTrackSelection} for the specified tracks.
         */
        protected BolaTrackSelection createAdaptiveTrackSelection(
                String video_name,
                TrackGroup group,
                BandwidthMeter bandwidthMeter,
                int[] tracks,
                int totalFixedTrackBandwidth) {
            return new BolaTrackSelection(
                    video_name,
                    group,
                    tracks,
                    new DefaultBandwidthProvider(bandwidthMeter, bandwidthFraction, totalFixedTrackBandwidth),
                    minDurationForQualityIncreaseMs,
                    maxDurationForQualityDecreaseMs,
                    minDurationToRetainAfterDiscardMs,
                    bufferedFractionToLiveEdgeForQualityIncrease,
                    minTimeBetweenBufferReevaluationMs,
                    clock);
        }
    }

    private static final class DefaultBandwidthProvider implements BandwidthProvider {

        private final BandwidthMeter bandwidthMeter;
        private final float bandwidthFraction;
        private final long reservedBandwidth;

        @Nullable
        private long[][] allocationCheckpoints;

        /* package */
        // the constructor does not initialize fields: allocationCheckpoints
        @SuppressWarnings("nullness:initialization.fields.uninitialized")
        DefaultBandwidthProvider(
                BandwidthMeter bandwidthMeter, float bandwidthFraction, long reservedBandwidth) {
            this.bandwidthMeter = bandwidthMeter;
            this.bandwidthFraction = bandwidthFraction;
            this.reservedBandwidth = reservedBandwidth;
        }

        // unboxing a possibly-null reference allocationCheckpoints[nextIndex][0]
        @SuppressWarnings("nullness:unboxing.of.nullable")
        @Override
        public long getAllocatedBandwidth() {
            long totalBandwidth = (long) (bandwidthMeter.getBitrateEstimate() * bandwidthFraction);
            long allocatableBandwidth = Math.max(0L, totalBandwidth - reservedBandwidth);
            if (allocationCheckpoints == null) {
                return allocatableBandwidth;
            }
            int nextIndex = 1;
            while (nextIndex < allocationCheckpoints.length - 1
                    && allocationCheckpoints[nextIndex][0] < allocatableBandwidth) {
                nextIndex++;
            }
            long[] previous = allocationCheckpoints[nextIndex - 1];
            long[] next = allocationCheckpoints[nextIndex];
            float fractionBetweenCheckpoints =
                    (float) (allocatableBandwidth - previous[0]) / (next[0] - previous[0]);
            return previous[1] + (long) (fractionBetweenCheckpoints * (next[1] - previous[1]));
        }

        /* package */ void experimental_setBandwidthAllocationCheckpoints(
                long[][] allocationCheckpoints) {
            Assertions.checkArgument(allocationCheckpoints.length >= 2);
            this.allocationCheckpoints = allocationCheckpoints;
        }
    }
}
