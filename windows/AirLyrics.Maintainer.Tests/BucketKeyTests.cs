using AirLyrics.Maintainer.Publish;
using AirLyrics.Maintainer.Scanning;
using Xunit;

namespace AirLyrics.Maintainer.Tests;

public class BucketKeyTests
{
    [Theory]
    [InlineData("Album/Touhou/同人/社團/專輯/01.m4a", BucketKind.Depth3, "Album/Touhou/同人")]
    [InlineData("Album/Instrumental+/# アニメ/專輯/01.mp3", BucketKind.Depth3, "Album/Instrumental+/# アニメ")]
    [InlineData("Main/J-POP, EN, CN+/J-POP/歌手/曲.mp3", BucketKind.Depth3, "Main/J-POP, EN, CN+/J-POP")]
    [InlineData("Album/Touhou/同人/01.m4a", BucketKind.Depth3, "Album/Touhou/同人")]
    [InlineData("Album/01.m4a", BucketKind.Direct, "Album")]
    [InlineData("Album/Touhou/01.m4a", BucketKind.Direct, "Album/Touhou")]
    [InlineData("01.m4a", BucketKind.Root, "")]
    public void FromRelativeFile_matchesContractExamples(string relative, BucketKind kind, string bucketPath)
    {
        var key = BucketKey.FromRelativeFile(relative);
        Assert.Equal(kind, key.Kind);
        Assert.Equal(bucketPath, key.RelativePath);
    }

    [Fact]
    public void DirectAndDepth3WithSameFolderNameDoNotShareIdentity()
    {
        var direct = BucketKey.FromRelativeFile("Album/song.mp3");
        var depth3 = BucketKey.FromRelativeFile("Album/Touhou/同人/song.mp3");
        Assert.NotEqual(direct, depth3);
        Assert.NotEqual(direct.Serialized, depth3.Serialized);
        Assert.NotEqual(direct.ShardId, depth3.ShardId);
    }

    [Fact]
    public void FolderNamedLikeKindDoesNotCollideWithRootOrDepth3()
    {
        var named = BucketKey.FromRelativeFile("depth3/song.mp3");
        Assert.Equal(BucketKind.Direct, named.Kind);
        Assert.Equal("direct:depth3", named.Serialized);
        Assert.NotEqual(BucketKey.Root().Serialized, named.Serialized);
    }

    [Fact]
    public void UrlEncoder_encodesHashAndPlusPerSegment()
    {
        var key = BucketKey.FromRelativeFile("Album/Instrumental+/# アニメ/專輯/01.mp3");
        var url = UrlPathEncoder.ShardRelativeUrl(key, "rev.sqlite");
        Assert.Contains("Instrumental%2B", url);
        Assert.Contains("%23%20", url);
        Assert.DoesNotContain("/#", url);
        Assert.StartsWith("shards/depth3/", url);
    }
}
