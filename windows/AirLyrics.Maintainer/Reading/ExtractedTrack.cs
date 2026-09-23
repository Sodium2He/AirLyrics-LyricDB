using AirLyrics.Maintainer.Lyrics;

namespace AirLyrics.Maintainer.Reading;

public sealed class ExtractedLyrics
{
    public required string TagKind { get; init; }
    public string? Language { get; init; }
    public string? Description { get; init; }
    public required int VariantOrder { get; init; }
    public required string Format { get; init; }
    public string? RawText { get; init; }
    public string? TextHash { get; init; }
    public string? Diagnostic { get; init; }
}

public sealed class ExtractedTrack
{
    public required bool OpenedReadOnly { get; init; }
    public string? Title { get; init; }
    public string? ArtistRaw { get; init; }
    public string? Album { get; init; }
    public string? AlbumArtist { get; init; }
    public string? Genre { get; init; }
    public int? TrackNumber { get; init; }
    public int? DiscNumber { get; init; }
    public int? DurationMs { get; init; }
    public string? Isrc { get; init; }
    public string? Mbid { get; init; }
    public required string LyricsStatus { get; init; }
    public List<ExtractedLyrics> Lyrics { get; init; } = [];
    public List<string> Diagnostics { get; init; } = [];
}

public interface ITagReader
{
    ExtractedTrack Read(Stream stream, string pathForErrors);
}

public sealed class AtlTagReader : ITagReader
{
    private readonly ReadOnlyTagReader _inner = new();

    public ExtractedTrack Read(Stream stream, string pathForErrors)
    {
        var probe = _inner.Read(stream, pathForErrors);
        return Map(probe);
    }

    public static ExtractedTrack Map(TagProbeResult probe)
    {
        var lyrics = new List<ExtractedLyrics>();
        var diagnostics = new List<string>(probe.Warnings);
        var variant = 0;

        foreach (var entry in probe.Lyrics)
        {
            var raw = string.IsNullOrEmpty(entry.UnsynchronizedLyrics) ? null : entry.UnsynchronizedLyrics;
            string? diagnostic = null;
            string format;
            string tagKind = string.IsNullOrEmpty(entry.ContentType) ? "USLT-or-unsync" : entry.ContentType;

            if (raw is null)
            {
                if (entry.SynchronizedPhrases.Count > 0)
                {
                    diagnostic =
                        "original_text_unavailable; sylt_or_phrases_not_serialized; timestamp_unit_unverified";
                    format = "sylt";
                    tagKind = "SYLT";
                    diagnostics.Add(diagnostic);
                    lyrics.Add(new ExtractedLyrics
                    {
                        TagKind = tagKind,
                        Language = entry.LanguageCode,
                        Description = entry.Description,
                        VariantOrder = variant++,
                        Format = format,
                        RawText = null,
                        TextHash = null,
                        Diagnostic = diagnostic
                    });
                }

                continue;
            }

            format = LyricsFormatClassifier.Classify(raw);
            if (LyricsFormatClassifier.LooksMixed(raw))
            {
                diagnostic = "mixed_elrc_and_lrc_lines";
                diagnostics.Add(diagnostic);
            }

            lyrics.Add(new ExtractedLyrics
            {
                TagKind = tagKind,
                Language = entry.LanguageCode,
                Description = entry.Description,
                VariantOrder = variant++,
                Format = format,
                RawText = raw,
                TextHash = Publish.Sha256Hex.OfUtf8(raw),
                Diagnostic = diagnostic
            });
        }

        var hasRaw = lyrics.Any(item => !string.IsNullOrEmpty(item.RawText));
        var hasErrorOnly = lyrics.Count > 0 && !hasRaw;
        var status = hasRaw ? "present" : hasErrorOnly ? "error" : "absent";

        return new ExtractedTrack
        {
            OpenedReadOnly = probe.OpenedReadOnly,
            Title = probe.Title,
            ArtistRaw = probe.ArtistRaw,
            Album = probe.Album,
            AlbumArtist = probe.AlbumArtist,
            Genre = probe.Genre,
            TrackNumber = probe.TrackNumber,
            DiscNumber = probe.DiscNumber,
            DurationMs = probe.DurationMs,
            Isrc = probe.Isrc,
            Mbid = probe.Mbid,
            LyricsStatus = status,
            Lyrics = lyrics,
            Diagnostics = diagnostics
        };
    }
}
