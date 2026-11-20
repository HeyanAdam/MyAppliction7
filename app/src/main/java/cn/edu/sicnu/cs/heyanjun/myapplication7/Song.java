package cn.edu.sicnu.cs.heyanjun.myapplication7;

import android.net.Uri;

/**
 * 音乐文件信息（由 ContentResolver 从 MediaStore 查询得到）。
 */
public class Song {

    private final String title;
    private final String artist;
    private final Uri uri;
    private final long duration;

    public Song(String title, String artist, Uri uri, long duration) {
        this.title = title;
        this.artist = artist;
        this.uri = uri;
        this.duration = duration;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public Uri getUri() {
        return uri;
    }

    public long getDuration() {
        return duration;
    }

    @Override
    public String toString() {
        return title + " - " + artist;
    }
}
