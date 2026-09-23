namespace AirLyrics.Maintainer.Scanning;

public sealed class SourceScanException : Exception
{
    public SourceScanException(string message) : base(message)
    {
    }

    public SourceScanException(string message, Exception inner) : base(message, inner)
    {
    }
}
