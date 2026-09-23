using System.Text.Json.Serialization;

namespace AirLyrics.Maintainer.Reading;

public sealed class TagProbeResult
{
    public required string Path { get; init; }
    public required bool OpenedReadOnly { get; init; }
    public string? Title { get; init; }
    public string? ArtistRaw { get; init; }
    public string? AlbumArtist { get; init; }
    public string? Album { get; init; }
    public string? Genre { get; init; }
    public int? TrackNumber { get; init; }
    public int? DiscNumber { get; init; }
    public int? DurationMs { get; init; }
    public string? Isrc { get; init; }
    public string? Mbid { get; init; }
    public Dictionary<string, string> AdditionalFields { get; init; } = new();
    public List<LyricsProbeEntry> Lyrics { get; init; } = [];
    public List<string> Warnings { get; init; } = [];
}

public sealed class LyricsProbeEntry
{
    public string? LanguageCode { get; init; }
    public string? Description { get; init; }
    public string? ContentType { get; init; }
    public string? UnsynchronizedLyrics { get; init; }
    public List<SynchronizedPhraseDump> SynchronizedPhrases { get; init; } = [];
}

public sealed class SynchronizedPhraseDump
{
    public int TimestampMs { get; init; }
    public string? Text { get; init; }
}

public sealed class TagProbeReport
{
    public required string GeneratedAtUtc { get; init; }
    public required string SourceRoot { get; init; }
    public required string Reader { get; init; }
    public required string AtlPackageNote { get; init; }
    public List<TagProbeResult> Files { get; init; } = [];
    public List<string> Errors { get; init; } = [];
}

public sealed class TagProbeSummary
{
    public required string GeneratedAtUtc { get; init; }
    public required string SourceRoot { get; init; }
    public required string Reader { get; init; }
    public required string FullReportPath { get; init; }
    public required string SummaryPath { get; init; }
    public int FileCount { get; init; }
    public int ErrorCount { get; init; }
    public int FilesOpenedReadOnly { get; init; }
    public int FilesWithUnsynchronizedLyrics { get; init; }
    public int FilesWithPhrasesWithoutUnsync { get; init; }
    public int FilesWithSlashInArtist { get; init; }
    public int FilesWithMultipleLyricsEntries { get; init; }
    public List<string> DistinctWarnings { get; init; } = [];
    public List<string> SamplePhrasesWithoutUnsync { get; init; } = [];
    public List<string> SampleUnsyncPreserved { get; init; } = [];
}

[JsonSourceGenerationOptions(WriteIndented = true)]
[JsonSerializable(typeof(TagProbeReport))]
[JsonSerializable(typeof(TagProbeSummary))]
internal partial class TagProbeJsonContext : JsonSerializerContext;
