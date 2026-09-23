using System.Text.Json;
using AirLyrics.Maintainer.Normalize;
using Xunit;

namespace AirLyrics.Maintainer.Tests;

public class MetadataNormalizerTests
{
    private static readonly JsonElement Fixture = JsonDocument.Parse(
        File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "fixtures", "unicode-normalization.json"))
    ).RootElement;

    [Fact]
    public void Primary_matchesSharedUnicodeFixture()
    {
        foreach (var caseJson in Fixture.GetProperty("primary").EnumerateArray())
        {
            Assert.Equal(
                caseJson.GetProperty("expected").GetString(),
                MetadataNormalizer.Primary(caseJson.GetProperty("input").GetString()!));
        }
    }

    [Fact]
    public void SamePrimary_matchesSharedUnicodeFixture()
    {
        foreach (var caseJson in Fixture.GetProperty("same_primary").EnumerateArray())
        {
            Assert.Equal(
                MetadataNormalizer.Primary(caseJson.GetProperty("left").GetString()!),
                MetadataNormalizer.Primary(caseJson.GetProperty("right").GetString()!));
        }
    }

    [Fact]
    public void DifferentPrimary_matchesSharedUnicodeFixture()
    {
        foreach (var caseJson in Fixture.GetProperty("different_primary").EnumerateArray())
        {
            Assert.NotEqual(
                MetadataNormalizer.Primary(caseJson.GetProperty("left").GetString()!),
                MetadataNormalizer.Primary(caseJson.GetProperty("right").GetString()!));
        }
    }

    [Fact]
    public void SameSecondary_matchesSharedUnicodeFixture()
    {
        foreach (var caseJson in Fixture.GetProperty("same_secondary").EnumerateArray())
        {
            Assert.Equal(
                MetadataNormalizer.Secondary(caseJson.GetProperty("left").GetString()!),
                MetadataNormalizer.Secondary(caseJson.GetProperty("right").GetString()!));
        }
    }

    [Fact]
    public void DifferentSecondary_keepsVersionWords()
    {
        foreach (var caseJson in Fixture.GetProperty("different_secondary").EnumerateArray())
        {
            Assert.NotEqual(
                MetadataNormalizer.Secondary(caseJson.GetProperty("left").GetString()!),
                MetadataNormalizer.Secondary(caseJson.GetProperty("right").GetString()!));
        }
    }
}
