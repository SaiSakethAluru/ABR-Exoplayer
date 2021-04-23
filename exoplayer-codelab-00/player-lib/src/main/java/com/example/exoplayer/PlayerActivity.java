/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.source.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaLoadData;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MediaSourceEventListener;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;


/**
 * A fullscreen activity to play audio or video streams.
 */
public class PlayerActivity extends AppCompatActivity {
    private final String[] algorithms = {"pensieve", "bola"};
    private final String[] videoNames = {"envivio", "bbb_30fps", "redbull_2sec", "tears_of_steel","elephants_dream"};
    private PlayerView playerView;
    private TextView infoText;
    private Button nextButton;
    private SimpleExoPlayer player;
    private MediaSource mediaSource;
    private boolean playWhenReady = true;
    private int currentWindow = 0;
    private long playbackPosition = 0;
    private int algoIdx = 1;
    private int videoIdx = 4;
    private OutputStreamWriter outputStreamWriter;
//    private PlaybackStateListener playbackStateListener;
//    private ProcessBuilder pb;
    private Listener listener;

    private void releasePlayer() {
        if (player != null) {
            playWhenReady = player.getPlayWhenReady();
            playbackPosition = player.getCurrentPosition();
            currentWindow = player.getCurrentWindowIndex();
//            player.removeListener(playbackStateListener);
//            player.removeListener((Player.EventListener) listener);
            mediaSource.removeEventListener(listener);
            player.release();
            player = null;
            try {
                outputStreamWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        playerView = findViewById(R.id.video_view);
        infoText = findViewById(R.id.info_text);
        nextButton = findViewById(R.id.nextButton);
        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseNext();
            }
        });
//        playbackStateListener = new PlaybackStateListener();
        listener = new Listener();
    }

    @Override
    protected void onStart() {
        super.onStart();
//        System.out.println("clearing batterystats");
//        ProcessBuilder pb = new ProcessBuilder("dumpsys","batterystats","--reset");
//        try {
//            pb.start();
//            System.out.println("Cleared batterystats");
//        } catch (IOException e) {
//            System.out.println("Error in clearing");
//            e.printStackTrace();
//        }
        initializePlayer();
        Resources res = getResources();
        infoText.setText(String.format(res.getString(R.string.info_text), algorithms[algoIdx], videoNames[videoIdx]));
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (player == null) {
            initializePlayer();
        }
    }

    //  @SuppressLint("InlinedApi")
    private void hideSystemUi() {
        playerView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void chooseNext() {
//        String cmd = "dumpsys batterystats > " + algorithms[algoIdx]+"_"+videoNames[videoIdx]+".txt";
//        System.out.println("Dumping batterystats");
////        System.out.println("Command: "+cmd);
////        try {
////            Process process = Runtime.getRuntime().exec(cmd);
////            BufferedReader bufferedReader = new BufferedReader(
////                    new InputStreamReader(process.getInputStream()));
////            System.out.println("Dumped batterystats");
////        } catch (IOException e) {
////            System.out.println("Couldn't dump batterystats");
////            e.printStackTrace();
////        }
//        ProcessBuilder pb = new ProcessBuilder("dumpsys","batterystats");
//        String filename = this.getFilesDir()+"/" +"batterystats_"+algorithms[algoIdx]+"_"+videoNames[videoIdx]+".txt";
//        System.out.println("Filename for storing batterystats: "+filename);
//        File file = new File(filename);
//        try {
//            file.createNewFile();
//        } catch (IOException e){
//            e.printStackTrace();
//        }
//        pb.redirectOutput(file);
//        try {
//            pb.start();
//            System.out.println("dumped batterystats");
//        } catch (IOException e) {
//            System.out.println("Unable to dump batterystats");
//            e.printStackTrace();
//        }
        releasePlayer();
        playbackPosition = 0;
        algoIdx = (algoIdx + 1) % algorithms.length;
        if (algoIdx == 0) {
            videoIdx = (videoIdx + 1) % videoNames.length;
        }
        onStart();
    }

    private void initializePlayer() {
        String algorithm = algorithms[algoIdx];
        String videoName = videoNames[videoIdx];
        try{
            this.outputStreamWriter = new OutputStreamWriter(this.openFileOutput(algorithm+"_"+videoName+".txt", Context.MODE_PRIVATE));
        }
        catch (IOException e){
            System.out.println("Shouldn't reach here. ");
        }
        if (player == null) {
            DefaultTrackSelector trackSelector;
//            player = new SimpleExoPlayer.Builder(this).build();
            switch (algorithm) {
                case "pensieve":
                    trackSelector = new DefaultTrackSelector(this, new PensieveTrackSelection.Factory(this, videoName, infoText, outputStreamWriter, listener));
                    break;
                case "bola":
                    trackSelector = new DefaultTrackSelector(this, new BolaTrackSelection.Factory(videoName, infoText, outputStreamWriter, listener));
                    break;
                default:
                    trackSelector = new DefaultTrackSelector(this, new AdaptiveTrackSelection.Factory());
            }
            player = new SimpleExoPlayer.Builder(this).setTrackSelector(trackSelector).build();
//            player.addListener((Player.EventListener) listener);
//            player.addListener(listener);
//            player.addListener(playbackStateListener);
        }
        playerView.setPlayer(player);
        // In an emulated device, 10.0.2.2 refers to the localhost in the PC running the AVD.
        // Directly giving 127.0.0.1 refers to the localhost of the emulated device itself.
//    Uri uri = Uri.parse("http://10.0.2.2:8000/Manifest.mpd");
//        String video_url = "https://saisakethaluru.github.io/" + videoName + "/Manifest.mpd";
        String video_url = "http://10.42.0.1:8000/"+videoName+"/Manifest.mpd";
        Uri uri = Uri.parse(video_url);
//        MediaItem mediaItem = new MediaItem.Builder()
//                .setUri(uri)
//                .setMimeType(MimeTypes.APPLICATION_MPD)
//                .build();
//        player.setMediaItem(mediaItem);
        DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(this, listener, new DefaultDataSourceFactory(this));
//        DefaultHttpDataSourceFactory dataSourceFactory = new DefaultHttpDataSourceFactory();
        mediaSource = new DashMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri));

        player.setMediaSource(mediaSource);
        Handler handler = new Handler();
        mediaSource.addEventListener(handler,listener);
        player.setPlayWhenReady(playWhenReady);
        player.seekTo(currentWindow, playbackPosition);
        player.prepare();
    }

    private class PlaybackStateListener implements Player.EventListener {
//        @RequiresApi(api = Build.VERSION_CODES.O)
        @Override
        public void onPlaybackStateChanged(int state) {
            switch (state) {
                case ExoPlayer.STATE_ENDED:
                    try {
                        outputStreamWriter.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    infoText.setText("");
                    chooseNext();
                    break;
                case Player.STATE_BUFFERING:
                case Player.STATE_IDLE:
                case Player.STATE_READY:
                    break;
            }
        }
    }
}

class Listener implements MediaSourceEventListener, TransferListener {

    private long chunkLoadStartTime = 0;
    private long chunkLoadEndTime = 0;
    private long chunkLoadDuration = 0;

    public int getDataType() {
        return dataType;
    }

    private int dataType = C.DATA_TYPE_UNKNOWN;

    public long getChunkLoadStartTime() {
        return chunkLoadStartTime;
    }

    public long getChunkLoadEndTime() {
        return chunkLoadEndTime;
    }

    public long getChunkLoadDuration(){
        return chunkLoadDuration;
    }
    @Override
    public void onLoadStarted(int windowIndex, @Nullable MediaSource.MediaPeriodId mediaPeriodId, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
        if(mediaLoadData.dataType == C.DATA_TYPE_MEDIA) {
            System.out.println("New load started" + loadEventInfo.elapsedRealtimeMs);
            this.chunkLoadStartTime = loadEventInfo.elapsedRealtimeMs;
        }
    }

    @Override
    public void onLoadCompleted(int windowIndex, @Nullable MediaSource.MediaPeriodId mediaPeriodId, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
        if(mediaLoadData.dataType == C.DATA_TYPE_MEDIA) {
            this.chunkLoadEndTime = loadEventInfo.elapsedRealtimeMs;
            this.chunkLoadDuration = loadEventInfo.loadDurationMs;
            System.out.println("load complete" + loadEventInfo.elapsedRealtimeMs);
            System.out.println("mediaLoadData.mediaStartTimeMs: "+mediaLoadData.mediaStartTimeMs);
        }
        this.dataType = mediaLoadData.dataType;
    }

    @Override
    public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {

    }

    @Override
    public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
//        System.out.println("Transfer start" + source.toString());
//        System.out.println("headers"+dataSpec.httpRequestHeaders);
//        this.chunkLoadStartTime = Clock.DEFAULT.elapsedRealtime();

    }

    @Override
    public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {
//        System.out.println("Bytes transfer" + source.toString());
    }

    @Override
    public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
//        System.out.println("transfer end" + source.toString());
//        this.chunkLoadEndTime = Clock.DEFAULT.elapsedRealtime();
//        this.chunkLoadDuration = this.chunkLoadEndTime-this.chunkLoadStartTime;
    }
}