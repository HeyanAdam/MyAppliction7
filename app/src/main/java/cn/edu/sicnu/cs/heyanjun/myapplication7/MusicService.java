package cn.edu.sicnu.cs.heyanjun.myapplication7;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 音乐播放服务：所有播放相关的功能（播放、暂停、停止、上一首、下一首、进度控制）
 * 都在服务中实现。Activity 通过绑定服务拿到 Binder，再读取歌曲名、播放进度等信息。
 */
public class MusicService extends Service {

    public static final String CHANNEL_ID = "music_playback_channel";
    private static final int NOTIFICATION_ID = 1001;

    /** Activity 通过该 Binder 获取服务实例，从而调用服务中的方法、读取状态。 */
    public class MusicBinder extends Binder {
        public MusicService getService() {
            return MusicService.this;
        }
    }

    private final MusicBinder binder = new MusicBinder();
    private MediaPlayer player;
    private final List<Song> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private boolean prepared = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());

        // 异步准备完成后自动开始播放，并弹出通知栏
        player.setOnPreparedListener(mp -> {
            prepared = true;
            mp.start();
            showNotification();
        });
        // 一首播放完毕后自动播放下一首
        player.setOnCompletionListener(mp -> next());
        player.setOnErrorListener((mp, what, extra) -> {
            prepared = false;
            return true;
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        prepared = false;
        super.onDestroy();
    }

    // ==================== 供 Activity 调用的播放控制方法 ====================

    /** 设置播放列表（Activity 从 ContentResolver 查询到后传入）。 */
    public void setPlaylist(List<Song> songs) {
        playlist.clear();
        playlist.addAll(songs);
        currentIndex = -1;
        prepared = false;
        if (player != null) {
            player.reset();
        }
    }

    /** 播放指定位置的歌曲。 */
    public void play(int index) {
        if (playlist.isEmpty() || player == null) {
            return;
        }
        if (index < 0) {
            index = 0;
        }
        if (index >= playlist.size()) {
            index = 0;
        }
        currentIndex = index;
        prepared = false;

        player.reset();
        try {
            player.setDataSource(getApplicationContext(), playlist.get(index).getUri());
            player.prepareAsync();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** 播放/暂停切换；未选歌时默认播放第一首。 */
    public void togglePlayPause() {
        if (currentIndex < 0) {
            play(0);
            return;
        }
        if (!prepared || player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.start();
        }
    }

    public void pause() {
        if (prepared && player != null && player.isPlaying()) {
            player.pause();
        }
    }

    public void stop() {
        if (prepared && player != null) {
            player.pause();
            player.seekTo(0);
        }
    }

    public void next() {
        if (playlist.isEmpty()) {
            return;
        }
        play((currentIndex + 1) % playlist.size());
    }

    public void previous() {
        if (playlist.isEmpty()) {
            return;
        }
        int index = currentIndex - 1;
        if (index < 0) {
            index = playlist.size() - 1;
        }
        play(index);
    }

    /** 拖动 SeekBar 时调用，调整播放进度。 */
    public void seekTo(int positionMs) {
        if (prepared && player != null) {
            player.seekTo(positionMs);
        }
    }

    // ==================== 供 Activity 读取播放信息的方法 ====================

    public boolean isPlaying() {
        return prepared && player != null && player.isPlaying();
    }

    public int getCurrentPosition() {
        return prepared && player != null ? player.getCurrentPosition() : 0;
    }

    public int getDuration() {
        return prepared && player != null ? player.getDuration() : 0;
    }

    public Song getCurrentSong() {
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            return playlist.get(currentIndex);
        }
        return null;
    }

    public String getCurrentSongName() {
        Song song = getCurrentSong();
        return song != null ? song.getTitle() : "";
    }

    // ==================== Notification ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示当前播放的歌曲名称");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /** 播放新歌曲时，把当前歌曲名字显示在通知栏中。 */
    private void showNotification() {
        Song song = getCurrentSong();
        if (song == null) {
            return;
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            return;
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(song.getTitle())
                .setContentText("正在播放：" + song.getArtist())
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
        } catch (SecurityException e) {
            // 未授予 POST_NOTIFICATIONS 权限时忽略
            e.printStackTrace();
        }
    }
}
