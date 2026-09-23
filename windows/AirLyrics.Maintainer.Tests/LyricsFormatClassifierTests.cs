using AirLyrics.Maintainer.Lyrics;
using Xunit;

namespace AirLyrics.Maintainer.Tests;

public class LyricsFormatClassifierTests
{
    [Theory]
    [InlineData("[00:10.00]hello", LyricsFormatClassifier.Lrc)]
    [InlineData("[00:10.00]hello[00:10.80]", LyricsFormatClassifier.Lrc)]
    [InlineData("[00:01.00]你[00:01.20]好[00:01.40]嗎", LyricsFormatClassifier.Lrc)]
    [InlineData("[00:10.00]<00:10.00>あ<00:10.20>い<00:10.40>う", LyricsFormatClassifier.Elrc)]
    [InlineData("just words\nand more words", LyricsFormatClassifier.Plain)]
    [InlineData("", LyricsFormatClassifier.Plain)]
    public void Classify_usesOriginalTimeMarkers(string input, string expected)
    {
        Assert.Equal(expected, LyricsFormatClassifier.Classify(input));
    }

    [Fact]
    public void ElrcWinsWhenAngleTimesPresentEvenIfLineAlsoHasBrackets()
    {
        const string text = "[00:10.00]<00:10.00>hello <00:10.40>world";
        Assert.Equal(LyricsFormatClassifier.Elrc, LyricsFormatClassifier.Classify(text));
        Assert.False(LyricsFormatClassifier.LooksMixed(text));
    }
}
