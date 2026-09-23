using System.Text;
using ATL;
using AirLyrics.Maintainer.Reading;
using Xunit;

namespace AirLyrics.Maintainer.Tests;

public class ReadOnlyTagReaderTests
{
    [Fact]
    public void RawFlacLyricsPreserveOriginalText()
    {
        const string text = "[00:01.125]<00:01.125>hello<00:03.000>\n[00:01.125]translation";
        using var block = new MemoryStream();
        using (var writer = new BinaryWriter(block, Encoding.UTF8, leaveOpen: true))
        {
            writer.Write(0); // vendor length
            writer.Write(1); // comment count
            var value = Encoding.UTF8.GetBytes("LYRICS=" + text);
            writer.Write(value.Length);
            writer.Write(value);
        }
        var payload = block.ToArray();
        var file = Concat("fLaC"u8.ToArray(), new byte[] { 0x84, (byte)(payload.Length >> 16), (byte)(payload.Length >> 8), (byte)payload.Length }, payload);
        using var stream = new MemoryStream(file, writable: false);
        var raw = Assert.Single(RawContainerLyricsReader.Read(stream));
        Assert.Equal(text, raw.Text);
        Assert.Equal("flac-vorbis:LYRICS", raw.Source);
    }

    [Fact]
    public void TruncatedFlacMetadataIsAnErrorNotAbsentLyrics()
    {
        using var stream = new MemoryStream(Concat("fLaC"u8.ToArray(), new byte[] { 0x84, 0, 0, 20 }), writable: false);
        Assert.Throws<InvalidDataException>(() => RawContainerLyricsReader.Read(stream));
    }

    [Fact]
    public void Read_preservesPlainUnsynchronizedLyricsAndKeepsArtistUnsplit()
    {
        var directory = Path.Combine(Path.GetTempPath(), "airlyrics-tag-probe", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "sample.wav");
        try
        {
            File.WriteAllBytes(path, MinimalWav());
            const string originalLyrics = "hello\nworld";
            WriteTaggedWav(path, originalLyrics);

            var result = new ReadOnlyTagReader().Read(path);

            Assert.True(result.OpenedReadOnly);
            Assert.Equal("Probe Song", result.Title);
            Assert.Equal("Alpha / Beta", result.ArtistRaw);
            Assert.Equal("Album Artist", result.AlbumArtist);
            Assert.Equal(
                originalLyrics,
                result.Lyrics.Select(entry => entry.UnsynchronizedLyrics).FirstOrDefault(value => !string.IsNullOrEmpty(value)));
        }
        finally
        {
            DeleteDir(directory);
        }
    }

    [Fact]
    public void Read_doesNotTreatParsedPhrasesAsOriginalWhenLrcLikeUnsyncIsLost()
    {
        var directory = Path.Combine(Path.GetTempPath(), "airlyrics-tag-probe", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "lrc-like.wav");
        try
        {
            File.WriteAllBytes(path, MinimalWav());
            const string originalLyrics = "[00:01.00]<00:01.00>hello <00:01.40>world\n[offset:500]";
            WriteTaggedWav(path, originalLyrics);

            var result = new ReadOnlyTagReader().Read(path);
            var unsync = result.Lyrics.Select(entry => entry.UnsynchronizedLyrics)
                .FirstOrDefault(value => !string.IsNullOrEmpty(value));
            var phrases = result.Lyrics.SelectMany(entry => entry.SynchronizedPhrases).ToList();

            if (unsync == originalLyrics)
            {
                return;
            }

            Assert.True(
                result.Warnings.Any(warning => warning.Contains("UnsynchronizedLyrics", StringComparison.Ordinal)),
                "ATL dropped original lyrics; the probe must warn instead of reconstructing from phrases.");
            Assert.NotEqual(
                originalLyrics,
                string.Join('\n', phrases.Select(phrase => phrase.Text)));
        }
        finally
        {
            DeleteDir(directory);
        }
    }

    private static void WriteTaggedWav(string path, string unsynchronizedLyrics)
    {
        var track = new Track(path)
        {
            Title = "Probe Song",
            Artist = "Alpha / Beta",
            AlbumArtist = "Album Artist",
            Album = "Probe Album",
            Genre = "J-POP"
        };
        track.Lyrics.Clear();
        track.Lyrics.Add(new LyricsInfo
        {
            LanguageCode = "jpn",
            Description = "probe",
            UnsynchronizedLyrics = unsynchronizedLyrics
        });
        track.Save();
    }

    private static void DeleteDir(string directory)
    {
        if (Directory.Exists(directory))
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void Read_opensShareReadWriteWithoutWriteAccess()
    {
        var directory = Path.Combine(Path.GetTempPath(), "airlyrics-tag-probe", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "locked.wav");
        try
        {
            File.WriteAllBytes(path, MinimalWav());
            using var writeShare = new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite);
            var result = new ReadOnlyTagReader().Read(path);
            Assert.True(result.OpenedReadOnly);
        }
        finally
        {
            if (Directory.Exists(directory))
            {
                Directory.Delete(directory, recursive: true);
            }
        }
    }

    [Fact]
    public void RawMp4CopyrightLyr_isReadWithoutAtlParse()
    {
        const string original = "[00:01.00]<00:01.00>hello <00:01.40>world\n[offset:500]";
        using var stream = new MemoryStream(MinimalMp4WithCopyrightLyr(original), writable: false);
        var raw = RawContainerLyricsReader.Read(stream);
        Assert.Equal(original, Assert.Single(raw).Text);
        Assert.Equal("mp4-©lyr", raw[0].Source);
    }

    [Fact]
    public void Read_mergesRawMp4LyricsWhenAtlHasNoUnsync()
    {
        const string original = "[00:01.00]<00:01.00>あ<00:01.40>い";
        using var stream = new MemoryStream(MinimalMp4WithCopyrightLyr(original), writable: false);
        var result = new ReadOnlyTagReader().Read(stream, "probe.m4a");
        Assert.Contains(
            original,
            result.Lyrics.Select(entry => entry.UnsynchronizedLyrics));
        Assert.True(result.OpenedReadOnly);
    }

    private static byte[] MinimalMp4WithCopyrightLyr(string text)
    {
        var utf8 = Encoding.UTF8.GetBytes(text);
        var data = Box("data", Concat(UInt32(1), UInt32(0), utf8));
        var lyr = Box("\u00a9lyr", data);
        var ilst = Box("ilst", lyr);
        var meta = Box("meta", Concat(UInt32(0), ilst));
        var udta = Box("udta", meta);
        var moov = Box("moov", udta);
        var ftyp = Box("ftyp", Concat(Encoding.ASCII.GetBytes("isom"), UInt32(0), Encoding.ASCII.GetBytes("isom")));
        return Concat(ftyp, moov);
    }

    private static byte[] Box(string type, byte[] payload)
    {
        var typeBytes = Encoding.Latin1.GetBytes(type);
        if (typeBytes.Length != 4)
        {
            throw new ArgumentException(type);
        }

        return Concat(UInt32((uint)(8 + payload.Length)), typeBytes, payload);
    }

    private static byte[] UInt32(uint value)
    {
        var bytes = BitConverter.GetBytes(value);
        if (BitConverter.IsLittleEndian)
        {
            Array.Reverse(bytes);
        }

        return bytes;
    }

    private static byte[] Concat(params byte[][] parts)
    {
        using var stream = new MemoryStream();
        foreach (var part in parts)
        {
            stream.Write(part);
        }

        return stream.ToArray();
    }

    private static byte[] MinimalWav()
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
