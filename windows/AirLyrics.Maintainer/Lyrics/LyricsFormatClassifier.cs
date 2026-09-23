using System.Text.RegularExpressions;

namespace AirLyrics.Maintainer.Lyrics;

/// <summary>
/// Classifies original lyrics text by its own time markers.
/// ELRC uses angle-bracket word times; any <c>[mm:ss</c> form is LRC
/// (line, end-time, or bracket word-by-word). Absence of both is plain.
/// </summary>
public static class LyricsFormatClassifier
{
    public const string Elrc = "elrc";
    public const string Lrc = "lrc";
    public const string Plain = "plain";
    public const string MixedAmbiguous = "mixed_ambiguous";

    private static readonly Regex BracketTime = new(
        @"\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]",
        RegexOptions.Compiled);

    private static readonly Regex AngleTime = new(
        @"<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>",
        RegexOptions.Compiled);

    public static string Classify(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return Plain;
        }

        var sawElrc = false;
        var sawLrc = false;

        foreach (var rawLine in text.Split('\n'))
        {
            var line = rawLine.Trim();
            if (line.Length == 0)
            {
                continue;
            }

            if (AngleTime.IsMatch(line))
            {
                sawElrc = true;
            }

            if (BracketTime.IsMatch(line))
            {
                sawLrc = true;
            }
        }

        if (sawElrc)
        {
            return Elrc;
        }

        return sawLrc ? Lrc : Plain;
    }

    public static bool LooksMixed(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return false;
        }

        var sawElrcLine = false;
        var sawLrcOnlyLine = false;
        foreach (var rawLine in text.Split('\n'))
        {
            var line = rawLine.Trim();
            if (line.Length == 0)
            {
                continue;
            }

            var elrc = AngleTime.IsMatch(line);
            var lrc = BracketTime.IsMatch(line);
            if (elrc)
            {
                sawElrcLine = true;
            }
            else if (lrc)
            {
                sawLrcOnlyLine = true;
            }
        }

        return sawElrcLine && sawLrcOnlyLine;
    }
}
