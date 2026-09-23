using ATL;

namespace AirLyrics.Maintainer.Reading;

/// <summary>
/// Read-only probe. ATL supplies tags; container atoms supply original lyrics text.
/// Never calls Track.Save. Source files are opened with FileAccess.Read.
/// </summary>
public sealed class ReadOnlyTagReader
{
    public const string ReaderName = "ATL.Track-read-only-stream+raw-container";

    public TagProbeResult Read(string path)
    {
        using var stream = new FileStream(
            path,
            FileMode.Open,
            FileAccess.Read,
            FileShare.ReadWrite | FileShare.Delete,
            4096,
            FileOptions.SequentialScan);
        return Read(stream, path);
    }

    public TagProbeResult Read(Stream stream, string path)
    {
        if (stream.CanWrite)
        {
            throw new InvalidOperationException($"Refusing writable stream for '{path}'.");
        }

        var start = stream.CanSeek ? stream.Position : 0L;
        var warnings = new List<string>();
        IReadOnlyList<RawLyricText> raw = [];
        if (stream.CanSeek)
        {
            raw = RawContainerLyricsReader.Read(stream);
            stream.Position = start;
        }

        Track? track = null;
        try
        {
            track = new Track(stream);
        }
        catch (Exception ex)
        {
            throw new InvalidDataException($"Metadata read failed for '{path}'. Publication must retain the previous generation.", ex);
        }

        var lyrics = track is null ? [] : DumpLyrics(track, warnings);
        lyrics = MergeRawLyrics(lyrics, raw, warnings);

        return new TagProbeResult
        {
            Path = path,
            OpenedReadOnly = !stream.CanWrite,
            Title = EmptyToNull(track?.Title),
            ArtistRaw = EmptyToNull(track?.Artist),
            AlbumArtist = EmptyToNull(track?.AlbumArtist),
            Album = EmptyToNull(track?.Album),
            Genre = EmptyToNull(track?.Genre),
            TrackNumber = track is { TrackNumber: > 0 } ? track.TrackNumber : null,
            DiscNumber = track is { DiscNumber: > 0 } ? track.DiscNumber : null,
            DurationMs = track is { DurationMs: > 0 } ? (int)Math.Round(track.DurationMs) : null,
            Isrc = EmptyToNull(track?.ISRC),
            Mbid = track is null ? null : FindMbid(track.AdditionalFields),
            AdditionalFields = track?.AdditionalFields.ToDictionary(
                pair => pair.Key,
                pair => pair.Value,
                StringComparer.Ordinal) ?? new Dictionary<string, string>(StringComparer.Ordinal),
            Lyrics = lyrics,
            Warnings = warnings
        };
    }

    private static List<LyricsProbeEntry> DumpLyrics(Track track, List<string> warnings)
    {
        var entries = new List<LyricsProbeEntry>();
        foreach (var info in track.Lyrics)
        {
            var unsync = info.UnsynchronizedLyrics;
            var phrases = info.SynchronizedLyrics
                .Select(phrase => new SynchronizedPhraseDump
                {
                    TimestampMs = phrase.TimestampStart,
                    Text = phrase.Text
                })
                .ToList();

            if (string.IsNullOrEmpty(unsync) && phrases.Count > 0)
            {
                warnings.Add(
                    "ATL returned synchronized phrases without UnsynchronizedLyrics; " +
                    "do not serialize phrases back as original text.");
            }

            entries.Add(new LyricsProbeEntry
            {
                LanguageCode = EmptyToNull(info.LanguageCode),
                Description = EmptyToNull(info.Description),
                ContentType = info.ContentType.ToString(),
                UnsynchronizedLyrics = string.IsNullOrEmpty(unsync) ? null : unsync,
                SynchronizedPhrases = phrases
            });
        }

        return entries;
    }

    private static List<LyricsProbeEntry> MergeRawLyrics(
        List<LyricsProbeEntry> atl,
        IReadOnlyList<RawLyricText> raw,
        List<string> warnings)
    {
        var uniqueRaw = raw
            .Select(item => item.Text)
            .Where(text => !string.IsNullOrEmpty(text))
            .Distinct(StringComparer.Ordinal)
            .ToList();
        if (uniqueRaw.Count == 0)
        {
            return atl;
        }

        var remaining = uniqueRaw
            .Where(text => atl.All(entry =>
                !string.Equals(entry.UnsynchronizedLyrics, text, StringComparison.Ordinal)))
            .ToList();
        if (remaining.Count == 0)
        {
            return atl;
        }

        var rebuilt = new List<LyricsProbeEntry>(atl.Count + remaining.Count);
        var queue = new Queue<string>(remaining);
        foreach (var entry in atl)
        {
            if (!string.IsNullOrEmpty(entry.UnsynchronizedLyrics) || queue.Count == 0)
            {
                rebuilt.Add(entry);
                continue;
            }

            rebuilt.Add(new LyricsProbeEntry
            {
                LanguageCode = entry.LanguageCode,
                Description = entry.Description,
                ContentType = entry.ContentType,
                UnsynchronizedLyrics = queue.Dequeue(),
                SynchronizedPhrases = entry.SynchronizedPhrases
            });
            warnings.Add("Recovered original lyrics from container; ATL UnsynchronizedLyrics was empty.");
        }

        while (queue.Count > 0)
        {
            rebuilt.Add(new LyricsProbeEntry
            {
                ContentType = "raw-container",
                UnsynchronizedLyrics = queue.Dequeue()
            });
            warnings.Add("Recovered original lyrics from container; ATL had no lyrics entry.");
        }

        return rebuilt;
    }

    private static string? FindMbid(IDictionary<string, string> fields)
    {
        foreach (var pair in fields)
        {
            if (pair.Key.Contains("MUSICBRAINZ", StringComparison.OrdinalIgnoreCase) &&
                pair.Key.Contains("TRACK", StringComparison.OrdinalIgnoreCase) &&
                !string.IsNullOrWhiteSpace(pair.Value))
            {
                return pair.Value.Trim();
            }
        }

        return null;
    }

    private static string? EmptyToNull(string? value)
    {
        return string.IsNullOrWhiteSpace(value) ? null : value;
    }
}
