using ATL;

namespace AirLyrics.Maintainer.Tests;

internal static class TestAudio
{
    public static void WriteWav(string path, string? title, string? artist, string? album, string? lyrics)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, MinimalWav());
        var track = new Track(path)
        {
            Title = title ?? "",
            Artist = artist ?? "",
            AlbumArtist = artist ?? "",
            Album = album ?? "",
            Genre = "J-POP"
        };
        track.Lyrics.Clear();
        if (lyrics is not null)
        {
            track.Lyrics.Add(new LyricsInfo
            {
                LanguageCode = "jpn",
                Description = "test",
                UnsynchronizedLyrics = lyrics
            });
        }

        track.Save();
    }

    public static byte[] MinimalWav()
    {
        const int sampleRate = 8000;
        const int channels = 1;
        const int bits = 16;
        const int samples = 800;
        var dataSize = samples * channels * (bits / 8);
        using var stream = new MemoryStream(44 + dataSize);
        using var writer = new BinaryWriter(stream);
        writer.Write("RIFF"u8.ToArray());
        writer.Write(36 + dataSize);
        writer.Write("WAVE"u8.ToArray());
        writer.Write("fmt "u8.ToArray());
        writer.Write(16);
        writer.Write((short)1);
        writer.Write((short)channels);
        writer.Write(sampleRate);
        writer.Write(sampleRate * channels * bits / 8);
        writer.Write((short)(channels * bits / 8));
        writer.Write((short)bits);
        writer.Write("data"u8.ToArray());
        writer.Write(dataSize);
        writer.Write(new byte[dataSize]);
        return stream.ToArray();
    }
}
