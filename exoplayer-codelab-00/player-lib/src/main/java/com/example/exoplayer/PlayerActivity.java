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

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;


/**
 * A fullscreen activity to play audio or video streams.
 */
public class PlayerActivity extends AppCompatActivity {

  private PlayerView playerView;
  private SimpleExoPlayer player;
  private boolean playWhenReady = true;
  private int currentWindow = 0;
  private long playbackPosition = 0;
  private static String video_name = "envivio";

  private void releasePlayer() {
    if(player!=null){
      playWhenReady = player.getPlayWhenReady();
      playbackPosition = player.getCurrentPosition();
      currentWindow = player.getCurrentWindowIndex();
      player.release();
      player = null;
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_player);
    playerView = findViewById(R.id.video_view);
  }

  @Override
  protected void onStart() {
    super.onStart();
    if(Util.SDK_INT >= 24){
      initializePlayer();
    }
  }

  @Override
  protected void onStop() {
    super.onStop();
    if(Util.SDK_INT>=24){
      releasePlayer();
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    if(Util.SDK_INT < 24){
      releasePlayer();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    hideSystemUi();
    if((Util.SDK_INT < 24 || player == null)){
      initializePlayer();
    }
  }
//  @SuppressLint("InlinedApi")
  private void hideSystemUi(){
    playerView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
    | View.SYSTEM_UI_FLAG_FULLSCREEN
    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
  }
  private void initializePlayer() {
    if(player==null){
      // Choose the track selector algorithm. AdaptiveTrackSelection is a built-in default ABR algorithm of exoplayer
      // It is a purely throughput based algo.
      DefaultTrackSelector trackSelector = new DefaultTrackSelector(this,new PensieveTrackSelection.Factory(this,video_name));
//      DefaultTrackSelector trackSelector = new DefaultTrackSelector(this, new AdaptiveTrackSelection.Factory());
//      DefaultTrackSelector trackSelector = new DefaultTrackSelector(this, new BolaTrackSelection.Factory(video_name));
      player = new SimpleExoPlayer.Builder(this).setTrackSelector(trackSelector).build();
    }
    playerView.setPlayer(player);
    // In an emulated device, 10.0.2.2 refers to the localhost in the PC running the AVD.
    // Directly giving 127.0.0.1 refers to the localhost of the emulated device itself.
//    Uri uri = Uri.parse("http://10.0.2.2:8000/Manifest.mpd");
    String video_url = "https://saisakethaluru.github.io/"+video_name+"/Manifest.mpd";
    Uri uri = Uri.parse(video_url);
//    MediaItem mediaItem = MediaItem.fromUri(getString(R.string.media_url_mp4));
    MediaItem mediaItem = new MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build();
    player.setMediaItem(mediaItem);
    player.setPlayWhenReady(playWhenReady);
    player.seekTo(currentWindow,playbackPosition);
    player.prepare();
  }
}
