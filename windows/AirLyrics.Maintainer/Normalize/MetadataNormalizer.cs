using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace AirLyrics.Maintainer.Normalize;

/// <summary>
/// Must match the Kotlin MetadataNormalizer against shared/fixtures/unicode-normalization.json.
/// </summary>
public static class MetadataNormalizer
{
    private static readonly Regex WhitespaceRegex = new("[\\t\\n\\r\\u00A0\\u3000 ]+", RegexOptions.Compiled);
    private static readonly Regex PunctuationRegex = new("[\\p{P}、。，．！？：；「」『』（）\\[\\]{}]+", RegexOptions.Compiled);
    private static readonly Regex FeatRegex = new(
        @"(?i)(?<![A-Za-z0-9])(?:featuring|feat\.?|ft\.?)(?![A-Za-z0-9])",
        RegexOptions.Compiled);

    public static string Primary(string raw)
    {
        var nfc = raw.Normalize(NormalizationForm.FormC);
        return CollapseWhitespace(nfc.ToLower(CultureInfo.InvariantCulture));
    }

    public static string Secondary(string raw)
    {
        var folded = FoldFullwidthAscii(Primary(raw));
        var featCanonical = FeatRegex.Replace(folded, "feat");
        return CollapseWhitespace(PunctuationRegex.Replace(featCanonical, " "));
    }

    public static long ApplyFileOffset(long timeMs, long fileOffsetMs) => timeMs + fileOffsetMs;

    private static string CollapseWhitespace(string text)
    {
        return WhitespaceRegex.Replace(text.Trim(), " ");
    }

    private static string FoldFullwidthAscii(string text)
    {
        var builder = new StringBuilder(text.Length);
        foreach (var ch in text)
        {
            builder.Append(ch is >= '\uFF01' and <= '\uFF5E' ? (char)(ch - 0xFEE0) : ch);
        }

        return builder.ToString();
    }
}
