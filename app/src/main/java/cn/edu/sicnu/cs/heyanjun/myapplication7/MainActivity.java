package cn.edu.sicnu.cs.heyanjun.myapplication7;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 音乐播放器主界面：
 * 1. 用 ContentResolver 查询 MediaStore 得到音乐文件列表；
 * 2. 绑定 MusicService，通过 Binder 读取歌曲名、播放进度并控制播放；
 * 3. 后台线程 + Handler 实时刷新 SeekBar，拖动 SeekBar 可调整进度。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 100;

    private TextView tvTitle;
    private TextView tvProgress;
    private TextView tvListHeader;
    private SeekBar seekBar;
    private Button btnPlay;
    private Button btnPrev;
    private Button btnStop;
    private Button btnNext;
    private ListView lvSongs;
    private ArrayAdapter<String> adapter;

    private final List<Song> songs = new ArrayList<>();

    private MusicService musicService;
    private volatile boolean bound = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Thread progressThread;
    private volatile boolean progressLoopRunning = false;
    private volatile boolean userDragging = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.MusicBinder binder = (MusicService.MusicBinder) service;
            musicService = binder.getService();
            bound = true;
            // 把 Activity 查询到的播放列表交给服务
            musicService.setPlaylist(new ArrayList<>(songs));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            musicService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();

        Intent serviceIntent = new Intent(this, MusicService.class);
        // 先启动服务，使 Activity 退出后音乐仍可继续播放；再绑定以获取 Binder
        startService(serviceIntent);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);

        if (hasMediaPermission()) {
            loadSongs();
        } else {
            // 没有媒体权限时，至少保证内置歌曲可以播放
            songs.add(getBuiltInSong());
            refreshSongList();
            ActivityCompat.requestPermissions(this, neededPermissions(), REQUEST_PERMISSIONS);
        }
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tv_title);
        tvProgress = findViewById(R.id.tv_progress);
        tvListHeader = findViewById(R.id.tv_list_header);
        seekBar = findViewById(R.id.seek_bar);
        btnPlay = findViewById(R.id.btn_play);
        btnPrev = findViewById(R.id.btn_prev);
        btnStop = findViewById(R.id.btn_stop);
        btnNext = findViewById(R.id.btn_next);
        lvSongs = findViewById(R.id.lv_songs);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        lvSongs.setAdapter(adapter);

        lvSongs.setOnItemClickListener((parent, view, position, id) -> playAt(position));

        btnPlay.setOnClickListener(v -> {
            if (musicService != null && bound) {
                musicService.togglePlayPause();
            }
        });
        btnPrev.setOnClickListener(v -> {
            if (musicService != null && bound) {
                musicService.previous();
            }
        });
        btnStop.setOnClickListener(v -> {
            if (musicService != null && bound) {
                musicService.stop();
            }
        });
        btnNext.setOnClickListener(v -> {
            if (musicService != null && bound) {
                musicService.next();
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    tvProgress.setText(formatTime(progress) + " / " + formatTime(sb.getMax()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userDragging = false;
                if (bound && musicService != null) {
                    musicService.seekTo(sb.getProgress());
                }
            }
        });
    }

    /** 用 ContentResolver 查询音乐文件列表。 */
    private void loadSongs() {
        songs.clear();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

        try (Cursor cursor = getContentResolver().query(
                uri, projection, selection, null, sortOrder)) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String title = cursor.getString(titleColumn);
                    String artist = cursor.getString(artistColumn);
                    long duration = cursor.getLong(durationColumn);
                    Uri songUri = ContentUris.withAppendedId(uri, id);
                    songs.add(new Song(title, artist, songUri, duration));
                }
            }
        } catch (SecurityException e) {
            Toast.makeText(this, "没有读取媒体文件的权限", Toast.LENGTH_LONG).show();
        }

        // 追加内置在 res/raw 中的示例歌曲
        songs.add(getBuiltInSong());

        refreshSongList();
    }

    /** 项目内置的歌曲（app/src/main/res/raw/natural.mp3）。 */
    private Song getBuiltInSong() {
        Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.natural);
        return new Song("Natural", "Imagine Dragons", uri, 0);
    }

    private void refreshSongList() {
        adapter.clear();
        for (Song song : songs) {
            adapter.add(song.toString());
        }
        adapter.notifyDataSetChanged();
        tvListHeader.setText(String.format(Locale.getDefault(), "音乐列表（%d 首）", songs.size()));

        if (songs.isEmpty()) {
            Toast.makeText(this, "未找到音乐文件，请先向设备中添加音乐", Toast.LENGTH_LONG).show();
        }
        if (bound && musicService != null) {
            musicService.setPlaylist(new ArrayList<>(songs));
        }
    }

    private void playAt(int position) {
        if (position < 0 || position >= songs.size()) {
            return;
        }
        if (bound && musicService != null) {
            musicService.play(position);
        }
    }

    // ==================== 后台线程刷新 SeekBar ====================

    private void startProgressLoop() {
        if (progressThread != null) {
            return;
        }
        progressLoopRunning = true;
        progressThread = new Thread(() -> {
            while (progressLoopRunning) {
                MusicService service = bound ? musicService : null;
                final int position = service != null ? service.getCurrentPosition() : 0;
                final int duration = service != null ? service.getDuration() : 0;
                final String title = service != null ? service.getCurrentSongName() : "";
                final boolean playing = service != null && service.isPlaying();

                // 子线程只负责读取，界面更新切回主线程
                uiHandler.post(() -> updateProgressUi(position, duration, title, playing));

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "ProgressThread");
        progressThread.start();
    }

    private void stopProgressLoop() {
        progressLoopRunning = false;
        progressThread = null;
    }

    private void updateProgressUi(int position, int duration, String title, boolean playing) {
        if (duration > 0) {
            seekBar.setMax(duration);
        }
        if (!userDragging) {
            seekBar.setProgress(position);
            tvProgress.setText(formatTime(position) + " / " + formatTime(duration));
        }
        tvTitle.setText(title == null || title.isEmpty() ? "未选择歌曲" : title);
        btnPlay.setText(playing ? "暂停" : "播放");
    }

    private static String formatTime(int ms) {
        int totalSeconds = ms / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d",
                totalSeconds / 60, totalSeconds % 60);
    }

    // ==================== 权限 ====================

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasMediaPermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasMediaPermission()) {
                loadSongs();
            } else {
                Toast.makeText(this, "需要媒体读取权限才能显示音乐列表", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onStart() {
        super.onStart();
        startProgressLoop();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopProgressLoop();
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(connection);
            bound = false;
            musicService = null;
        }
        super.onDestroy();
    }
}
