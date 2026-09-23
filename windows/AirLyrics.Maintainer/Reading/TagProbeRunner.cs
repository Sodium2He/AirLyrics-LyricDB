using System.Text.Json;
using AirLyrics.Maintainer.Paths;
using AirLyrics.Maintainer.Scanning;

namespace AirLyrics.Maintainer.Reading;

public sealed class TagProbeRunner
{
    private readonly MaintainerPaths _paths;

    public TagProbeRunner(MaintainerPaths paths)
    {
        _paths = paths;
    }

    public TagProbeReport Run(string sourceRoot, IProgress<string>? progress = null)
    {
        var fullSource = Path.GetFullPath(sourceRoot);
        _paths.RejectIfOverlapsSource(fullSource);
        _paths.EnsureDatabaseLayout();

        var report = new TagProbeReport
        {
            GeneratedAtUtc = DateTime.UtcNow.ToString("O"),
            SourceRoot = fullSource,
            Reader = ReadOnlyTagReader.ReaderName,
            AtlPackageNote = "ATL 7.17.0 drops LRC/ELRC from UnsynchronizedLyrics; raw ©lyr/USLT/LYRICS is merged back. ATL is not locked as the sole lyrics reader."
        };

        var reader = new ReadOnlyTagReader();
        var snapshot = SourceWalker.WalkComplete(fullSource, new RealSourceFileSystem());
        foreach (var file in snapshot.Files)
        {
            progress?.Report(file.FullPath);
            try
            {
                report.Files.Add(reader.Read(file.FullPath));
            }
            catch (Exception ex)
            {
                report.Errors.Add($"{file.FullPath}: {ex.Message}");
            }
        }

        var stamp = DateTime.UtcNow.ToString("yyyyMMddTHHmmssZ");
        var reportPath = Path.Combine(_paths.ReportsDirectory, $"tag-probe-{stamp}.json");
        var summaryPath = Path.Combine(_paths.ReportsDirectory, $"tag-probe-summary-{stamp}.json");
        File.WriteAllText(
            reportPath,
            JsonSerializer.Serialize(report, TagProbeJsonContext.Default.TagProbeReport));

        var phrasesWithoutUnsync = report.Files
            .Where(file => file.Lyrics.Any(entry =>
                string.IsNullOrEmpty(entry.UnsynchronizedLyrics) && entry.SynchronizedPhrases.Count > 0))
            .Select(file => file.Path)
            .ToList();
        var unsyncPreserved = report.Files
            .Where(file => file.Lyrics.Any(entry => !string.IsNullOrEmpty(entry.UnsynchronizedLyrics)))
            .Select(file => file.Path)
            .ToList();
        var summary = new TagProbeSummary
        {
            GeneratedAtUtc = report.GeneratedAtUtc,
            SourceRoot = report.SourceRoot,
            Reader = report.Reader,
            FullReportPath = reportPath,
            SummaryPath = summaryPath,
            FileCount = report.Files.Count,
            ErrorCount = report.Errors.Count,
            FilesOpenedReadOnly = report.Files.Count(file => file.OpenedReadOnly),
            FilesWithUnsynchronizedLyrics = unsyncPreserved.Count,
            FilesWithPhrasesWithoutUnsync = phrasesWithoutUnsync.Count,
            FilesWithSlashInArtist = report.Files.Count(file =>
                file.ArtistRaw?.Contains('/', StringComparison.Ordinal) == true),
            FilesWithMultipleLyricsEntries = report.Files.Count(file => file.Lyrics.Count > 1),
            DistinctWarnings = report.Files.SelectMany(file => file.Warnings).Distinct(StringComparer.Ordinal).ToList(),
            SamplePhrasesWithoutUnsync = phrasesWithoutUnsync.Take(12).ToList(),
            SampleUnsyncPreserved = unsyncPreserved.Take(12).ToList()
        };
        File.WriteAllText(
            summaryPath,
            JsonSerializer.Serialize(summary, TagProbeJsonContext.Default.TagProbeSummary));
        report.Errors.Insert(0, $"Wrote {reportPath}");
        report.Errors.Insert(1, $"Wrote {summaryPath}");
        report.Errors.Insert(
            2,
            $"Unsync={summary.FilesWithUnsynchronizedLyrics}; phrases-without-unsync={summary.FilesWithPhrasesWithoutUnsync}; slash-artist={summary.FilesWithSlashInArtist}");
        return report;
    }
}
