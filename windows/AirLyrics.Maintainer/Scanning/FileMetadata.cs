namespace AirLyrics.Maintainer.Scanning;

public sealed class FileMetadata
{
    public required long Size { get; init; }
    public required long MtimeUtcMs { get; init; }
    public uint? VolumeSerial { get; init; }
    public ulong? FileIndex { get; init; }
    public required bool IsReparsePoint { get; init; }
}
