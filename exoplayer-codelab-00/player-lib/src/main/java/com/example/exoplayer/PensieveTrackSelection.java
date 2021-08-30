

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

import android.app.Activity;
import android.content.Context;
import android.icu.util.Output;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.source.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaLoadData;
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
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * A bandwidth based adaptive {@link TrackSelection}, whose selected track is updated to be the one
 * of highest quality given the current network conditions and the state of the buffer.
 */
public class PensieveTrackSelection extends BaseTrackSelection {

    /**
     * Factory for {@link PensieveTrackSelection} instances.
     */
    public static class Factory implements TrackSelection.Factory {

        @Nullable
        private final BandwidthMeter bandwidthMeter;
        private final String video_name;
        private final Context context;
        private final TextView infoText;
        private final OutputStreamWriter outputStreamWriter;
        private final Listener listener;
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
        public Factory(Context context, String video_name, TextView infoText, OutputStreamWriter outputStreamWriter,
                       Listener listener) {
            this(
                    context,
                    video_name,
                    infoText,
                    outputStreamWriter,
                    listener,
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
        public Factory(Context context, String video_name, TextView infoText,
                       OutputStreamWriter outputStreamWriter,
                       Listener listener, BandwidthMeter bandwidthMeter) {
            this(
                    context,
                    video_name,
                    infoText,
                    outputStreamWriter,
                    listener,
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
                Context context,
                String video_name,
                TextView infoText,
                OutputStreamWriter outputStreamWriter,
                Listener listener,
                int minDurationForQualityIncreaseMs,
                int maxDurationForQualityDecreaseMs,
                int minDurationToRetainAfterDiscardMs,
                float bandwidthFraction) {
            this(
                    context,
                    video_name,
                    infoText,
                    outputStreamWriter,
                    listener,
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
                Context context,
                String video_name,
                TextView infoText,
                OutputStreamWriter outputStreamWriter,
                Listener listener,
                BandwidthMeter bandwidthMeter,
                int minDurationForQualityIncreaseMs,
                int maxDurationForQualityDecreaseMs,
                int minDurationToRetainAfterDiscardMs,
                float bandwidthFraction) {
            this(
                    context,
                    video_name,
                    infoText,
                    outputStreamWriter,
                    listener,
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
                Context context,
                String video_name,
                TextView infoText,
                OutputStreamWriter outputStreamWriter,
                Listener listener,
                int minDurationForQualityIncreaseMs,
                int maxDurationForQualityDecreaseMs,
                int minDurationToRetainAfterDiscardMs,
                float bandwidthFraction,
                float bufferedFractionToLiveEdgeForQualityIncrease,
                long minTimeBetweenBufferReevaluationMs,
                Clock clock) {
            this(
                    context,
                    video_name,
                    infoText,
                    outputStreamWriter,
                    listener,
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
                Context context,
                String video_name,
                TextView infoText,
                OutputStreamWriter outputStreamWriter,
                Listener listener,
                @Nullable BandwidthMeter bandwidthMeter,
                int minDurationForQualityIncreaseMs,
                int maxDurationForQualityDecreaseMs,
                int minDurationToRetainAfterDiscardMs,
                float bandwidthFraction,
                float bufferedFractionToLiveEdgeForQualityIncrease,
                long minTimeBetweenBufferReevaluationMs,
                Clock clock) {
            this.context = context;
            this.video_name = video_name;
            this.infoText = infoText;
            this.outputStreamWriter = outputStreamWriter;
            this.listener = listener;
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
            List<PensieveTrackSelection> adaptiveSelections = new ArrayList<>();
            for (int i = 0; i < definitions.length; i++) {
                Definition definition = definitions[i];
                if (definition != null && definition.tracks.length > 1) {
                    PensieveTrackSelection adaptiveSelection =
                            createAdaptiveTrackSelection(
                                    this.context,
                                    this.video_name,
                                    this.infoText,
                                    this.outputStreamWriter,
                                    this.listener,
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
                    PensieveTrackSelection adaptiveSelection = adaptiveSelections.get(i);
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
         *
         * @param video_name               Name of video
         * @param group                    The {@link TrackGroup}.
         * @param bandwidthMeter           A {@link BandwidthMeter} which can be used to select tracks.
         * @param tracks                   The indices of the selected tracks in the track group.
         * @param totalFixedTrackBandwidth The total bandwidth used by all non-adaptive tracks, in bits
         *                                 per second.
         * @return An {@link PensieveTrackSelection} for the specified tracks.
         */
        protected PensieveTrackSelection createAdaptiveTrackSelection(
                Context context,
                String video_name,
                TextView infoText,
                OutputStreamWriter outputStreamWriter,
                Listener listener,
                TrackGroup group,
                BandwidthMeter bandwidthMeter,
                int[] tracks,
                int totalFixedTrackBandwidth) {
            return new PensieveTrackSelection(
                    context,
                    video_name,
                    infoText,
                    outputStreamWriter,
                    listener,
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

    public static final int DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS = 10000;
    public static final int DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS = 25000;
    public static final int DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS = 25000;
    public static final float DEFAULT_BANDWIDTH_FRACTION = 0.7f;
    public static final float DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE = 0.75f;
    public static final long DEFAULT_MIN_TIME_BETWEEN_BUFFER_REEVALUTATION_MS = 500;

    private final Context context;
    private final BandwidthProvider bandwidthProvider;
    private final long minDurationForQualityIncreaseUs;
    private final long maxDurationForQualityDecreaseUs;
    private final long minDurationToRetainAfterDiscardUs;
    private final float bufferedFractionToLiveEdgeForQualityIncrease;
    private final long minTimeBetweenBufferReevaluationMs;
    private final Clock clock;

    private float playbackSpeed;
    private int selectedIndex;
    private int reason;
    private long lastBufferEvaluationMs;

    private static final int S_INFO = 6;
    private static final int S_LEN = 8;
    private static final int A_DIM = 6;
//    private static final Integer[] VIDEO_BIT_RATE = new Integer[]{300, 750, 1200, 1850, 2850, 4300};
    private static final double BUFFER_NORM_FACTOR = 10.0;
    private static final double M_IN_K = 1000.0;
    private static final double REBUF_PENALTY = 4.3;
    private static final double SMOOTH_PENALTY = 1.0;
    // TODO: Should shift to constants file
    private static final int QOE_UNKNOWN = 0;
    private static final int QOE_LINEAR = 1;
    private static final int QOE_LOG = 2;
    private static final int QOE_HD = 3;
    private static final int DEFAULT_BITRATE = 1;
//    private static final int totalChunks = 48;

    private Double[] VIDEO_BIT_RATE;
    private int CHUNK_TIL_VIDEO_END_CAP;
    private final String video_name;
    private final TextView infoText;
    private final String initialText;
    private int totalChunks;

    private OutputStreamWriter outputStreamWriter;
    private float[][][] model_input;
    private float[][] model_output;
    private long previousSelectTimeMs;
    private long previousBufferedDuration;
    private int previousBitrate;
    private int chunksProcessedCount;
    private int qoeType;
    private ArrayList<Double> qoe;
    private double totalQoe = 0;
    private double totalBitrate = 0;
    private HashMap<Integer, int[]> chunksizes;

    private Listener listener;

    /**
     * @param group          The {@link TrackGroup}.
     * @param tracks         The indices of the selected tracks within the {@link TrackGroup}. Must not be
     *                       empty. May be in any order.
     * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
     */
    public PensieveTrackSelection(Context context,
                                  String video_name,
                                  TextView infoText,
                                  OutputStreamWriter outputStreamWriter,
                                  Listener listener,
                                  TrackGroup group, int[] tracks,
                                  BandwidthMeter bandwidthMeter) {
        this(
                context,
                video_name,
                infoText,
                outputStreamWriter,
                listener,
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
    public PensieveTrackSelection(
            Context context,
            String video_name,
            TextView infoText,
            OutputStreamWriter outputStreamWriter,
            Listener listener,
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
                context,
                video_name,
                infoText,
                outputStreamWriter,
                listener,
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

    private PensieveTrackSelection(
            Context context,
            String video_name,
            TextView infoText,
            OutputStreamWriter outputStreamWriter,
            Listener listener,
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
        this.context = context;
        this.video_name = video_name;
        this.infoText = infoText;
        this.initialText = infoText.getText().toString();
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
        this.model_input = new float[1][S_INFO][S_LEN];
        this.model_output = new float[1][A_DIM];
        this.previousBufferedDuration = 0;
        this.previousBitrate = DEFAULT_BITRATE;
        this.chunksProcessedCount = 0;
        this.qoeType = QOE_LINEAR;
        this.qoe = new ArrayList<>();
        this.chunksizes = new HashMap<>();
        this.previousSelectTimeMs = clock.elapsedRealtime();
        switch (video_name){
            case "envivio":
                this.totalChunks = 48;
                this.VIDEO_BIT_RATE = new Double[]{300.0, 750.0, 1200.0, 1850.0, 2850.0, 4300.0};
                break;
            case "tears_of_steel":
                this.totalChunks = 244;
                this.VIDEO_BIT_RATE = new Double[]{686.685,686.685,1116.150,1929.169,2362.822,2470.094};
                break;
            case "redbull_2sec":
                this.totalChunks = 199;
                this.VIDEO_BIT_RATE = new Double[]{300.795,700.051,1179.845,1993.730,2995.671,3992.758};
                break;
            case "bbb_30fps":
                this.totalChunks = 158;
                this.VIDEO_BIT_RATE = new Double[]{507.246,1013.310,1254.758,1883.700,3134.488,4952.892};
                break;
            case "elephants_dream":
                this.totalChunks = 652;
                this.VIDEO_BIT_RATE = new Double[]{344.976, 808.384, 1273.596, 2186.563, 3127.680, 4516.590};
                break;
            case "forest":
                this.totalChunks = 453;
                this.VIDEO_BIT_RATE = new Double[]{279.652, 836.887, 1282.108, 1779.588, 2568.145, 3894.863};
                break;

        }
        this.CHUNK_TIL_VIDEO_END_CAP = this.totalChunks;
        for (int i = 0; i < VIDEO_BIT_RATE.length; i++) {
            int[] chunksizes_ = readChunkSizes(video_name+"/video_size_" + i);
            System.out.println("Constructor, quality " + i + " first chunk " + chunksizes_[0]);
            this.chunksizes.put(i, chunksizes_);
        }
        this.outputStreamWriter = outputStreamWriter;
        this.listener = listener;
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

        // Pensieve start
        if(reason == C.SELECTION_REASON_UNKNOWN){
            reason = C.SELECTION_REASON_INITIAL;
            selectedIndex = this.length - DEFAULT_BITRATE - 1;
            System.out.println("Making initial selection: "+selectedIndex);
        }
        else if (this.listener.getDataType() != C.DATA_TYPE_MEDIA){
            return;
        }
        else {
            long delay = this.listener.getChunkLoadDuration();
            System.out.println("Delay: " + delay);
            try {
                MappedByteBuffer tfliteModel = FileUtil.loadMappedFile(this.context, "pretrained_model.tflite");
                Interpreter interpreter = new Interpreter(tfliteModel, new Interpreter.Options());
                for (int i = 0; i < S_INFO; i++) {
                    float zero_id_value = this.model_input[0][i][0];
                    System.arraycopy(this.model_input[0][i], 1, this.model_input[0][i], 0, S_LEN - 1);
                    this.model_input[0][i][S_LEN - 1] = zero_id_value;
                }
                int currentSelectedIndex = this.length - selectedIndex - 1;
                System.out.println("Current selection: " + currentSelectedIndex);
                this.model_input[0][0][S_LEN - 1] = (VIDEO_BIT_RATE[currentSelectedIndex]).floatValue() / Collections.max(Arrays.asList(VIDEO_BIT_RATE)).floatValue();
                this.model_input[0][1][S_LEN - 1] = ((float) bufferedDurationUs / 1000000) / (float) BUFFER_NORM_FACTOR;
                this.model_input[0][2][S_LEN - 1] = (float) this.chunksizes.get(currentSelectedIndex)[this.chunksProcessedCount] / (float) delay / (float) M_IN_K;

                this.model_input[0][3][S_LEN - 1] = ((float) delay / (float) M_IN_K) / (float) BUFFER_NORM_FACTOR;
                // TODO: Check for better way to get chunk sizes
                // Currently we are loading chunk sized from prebuilt info files for the videos
                // in consideration. For future use, better to modify it to take size directly from
                // manifest files if possible.
                int[] nextChunkSizes = getNextChunkSizes();
                for (int i = 0; i < A_DIM; i++) {
                    this.model_input[0][4][i] = (float) nextChunkSizes[i] / (float) M_IN_K / (float) M_IN_K;
                }
                this.model_input[0][5][S_LEN - 1] = min(totalChunks - this.chunksProcessedCount, CHUNK_TIL_VIDEO_END_CAP) / (float) CHUNK_TIL_VIDEO_END_CAP;
                this.outputStreamWriter.write("-----Chunk number: " + this.chunksProcessedCount + "-----\nState:\n");
                for (int i = 0; i < S_INFO; i++) {
                    for (int j = 0; j < S_LEN; j++) {
                        this.outputStreamWriter.write("" + this.model_input[0][i][j] + "\t");
                    }
                    this.outputStreamWriter.write("\n");
                }
                this.chunksProcessedCount++;
                interpreter.run(this.model_input, this.model_output);
                int predictedBitrateIndex = 0;
                for (int i = 0; i < A_DIM; i++) {
                    if (this.model_output[0][i] > this.model_output[0][predictedBitrateIndex]) {
                        predictedBitrateIndex = i;
                    }
                }
                this.outputStreamWriter.write("Predicted bitrate index: " + predictedBitrateIndex + " value: " + this.VIDEO_BIT_RATE[predictedBitrateIndex] + "\n");
                if (predictedBitrateIndex != currentSelectedIndex) {
                    selectedIndex = max(this.length - predictedBitrateIndex - 1, 0);
                    System.out.println("selectedIndex " + selectedIndex);
                    reason = C.SELECTION_REASON_ADAPTIVE;
                }
                double rebuf = max(delay - (double) this.previousBufferedDuration / 1000.0, 0.0) / 1000.0;
                this.outputStreamWriter.write("Rebuffering time: " + rebuf + "\n");
                System.out.println("Rebuffering time: " + rebuf);
                if (this.qoeType == QOE_LINEAR) {
                    double reward = (double) VIDEO_BIT_RATE[currentSelectedIndex] / M_IN_K
                            - REBUF_PENALTY * rebuf - SMOOTH_PENALTY * abs(VIDEO_BIT_RATE[currentSelectedIndex]
                            - VIDEO_BIT_RATE[previousBitrate]) / M_IN_K;
                    System.out.println("reward: " + reward);
                    qoe.add(reward);
                    totalQoe += reward;
                }
                this.outputStreamWriter.write("Total QOE: " + totalQoe + "\n");
                this.totalBitrate += this.VIDEO_BIT_RATE[predictedBitrateIndex];
                this.outputStreamWriter.write("Total Bitrate: " + this.totalBitrate + "\n");
                String info_text = this.initialText + "\n" + "Qoe: " + totalQoe + "\n" + "Bitrate: " + this.totalBitrate;
                this.previousBitrate = currentSelectedIndex;
                this.previousBufferedDuration = bufferedDurationUs;
                TextView e = (TextView) ((Activity) this.context).findViewById(R.id.info_text);
                System.out.println("text in e: " + e.getText());
                e.setText("");
                e.setText(info_text);
                interpreter.close();

            } catch (Exception e) {
                System.out.println("Exception occurred :'( ");
                e.printStackTrace();
            }
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
     * Provides the allocated bandwidth.
     */
    private interface BandwidthProvider {

        /**
         * Returns the allocated bitrate.
         */
        long getAllocatedBandwidth();
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
     * Calculate lengths of next chunk for each track.
     *
     * @return sizes of next chunk to be processed
     */
    private int[] getNextChunkSizes() {
        int[] sizes = new int[A_DIM];
        System.out.println("Chunk count: "+this.chunksProcessedCount);
        for(int i=0;i<A_DIM;i++){
            if(this.chunksProcessedCount < totalChunks) {
                sizes[i] = this.chunksizes.get(i)[this.chunksProcessedCount + 1];
            }
            else sizes[i] = -1;
            System.out.println("Chunk "+i+" size: "+sizes[i]);
        }
        return sizes;
    }

    private int[] readChunkSizes(String filename) {

        int[] sizes = new int[totalChunks+1];
        try {
            Scanner scanner = new Scanner(this.context.getAssets().open(filename));
            int i = 0;
            while (i < totalChunks && scanner.hasNextInt()) {
                sizes[i++] = scanner.nextInt();
            }
        } catch (FileNotFoundException e) {
            System.out.println("Check again, File not found");
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return sizes;
    }


}
