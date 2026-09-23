using System.Buffers.Binary;
using System.Text;

namespace AirLyrics.Maintainer.Reading;

public sealed class RawLyricText
{
    public required string Source { get; init; }
    public required string Text { get; init; }
}

/// <summary>
/// Reads original lyrics atoms/frames without ATL's synchronised-phrase parser.
/// Source streams must be readable; this type never writes.
/// </summary>
public static class RawContainerLyricsReader
{
    public static IReadOnlyList<RawLyricText> Read(Stream stream)
    {
        if (stream.CanWrite)
        {
            throw new InvalidOperationException("Refusing writable stream for raw lyrics extraction.");
        }

        if (!stream.CanSeek)
        {
            return [];
        }

        var start = stream.Position;
        var header = new byte[12];
        var read = stream.Read(header, 0, header.Length);
        stream.Position = start;
        if (read < 8)
        {
            return [];
        }

        if (header[4] == (byte)'f' && header[5] == (byte)'t' && header[6] == (byte)'y' && header[7] == (byte)'p')
        {
            return ReadMp4(stream, start, stream.Length);
        }

        if (header[0] == (byte)'I' && header[1] == (byte)'D' && header[2] == (byte)'3')
        {
            return ReadId3(stream, start);
        }

        if (header[0] == (byte)'O' && header[1] == (byte)'g' && header[2] == (byte)'g' && header[3] == (byte)'S')
        {
            return ReadOggComments(stream, start, stream.Length);
        }

        if (header.AsSpan(0, 4).SequenceEqual("fLaC"u8))
        {
            return ReadFlacComments(stream, start);
        }

        return [];
    }

    private static List<RawLyricText> ReadFlacComments(Stream stream, long start)
    {
        var texts = new List<RawLyricText>();
        stream.Position = start + 4;
        var header = new byte[4];
        bool last;
        do
        {
            stream.ReadExactly(header);
            last = (header[0] & 0x80) != 0;
            var length = (header[1] << 16) | (header[2] << 8) | header[3];
            if (length > stream.Length - stream.Position) throw new InvalidDataException("Truncated FLAC metadata.");
            if ((header[0] & 0x7f) == 4)
            {
                var data = new byte[length];
                stream.ReadExactly(data);
                ParseVorbisComments(data, texts, "flac-vorbis");
            }
            else stream.Position += length;
        } while (!last);
        return texts;
    }

    private static List<RawLyricText> ReadMp4(Stream stream, long start, long end)
    {
        var texts = new List<RawLyricText>();
        WalkMp4(stream, start, end, texts);
        return texts;
    }

    private static void WalkMp4(Stream stream, long start, long end, List<RawLyricText> texts)
    {
        var position = start;
        var header = new byte[16];
        while (position + 8 <= end)
        {
            stream.Position = position;
            if (stream.Read(header, 0, 8) < 8)
            {
                return;
            }

            var size = BinaryPrimitives.ReadUInt32BigEndian(header);
            var type = Encoding.Latin1.GetString(header, 4, 4);
            var headerSize = 8;
            ulong boxSize = size;
            if (size == 1)
            {
                if (stream.Read(header, 8, 8) < 8)
                {
                    return;
                }

                boxSize = BinaryPrimitives.ReadUInt64BigEndian(header.AsSpan(8, 8));
                headerSize = 16;
            }
            else if (size == 0)
            {
                boxSize = (ulong)(end - position);
            }

            if (boxSize < (uint)headerSize)
            {
                return;
            }

            var payloadStart = position + headerSize;
            var payloadEnd = position + (long)boxSize;
            if (payloadEnd > end || payloadEnd < payloadStart)
            {
                return;
            }

            if (type is "moov" or "udta" or "ilst" or "trak" or "mdia" or "minf" or "stbl")
            {
                WalkMp4(stream, payloadStart, payloadEnd, texts);
            }
            else if (type == "meta")
            {
                WalkMp4(stream, payloadStart + 4, payloadEnd, texts);
            }
            else if (type == "\u00a9lyr")
            {
                foreach (var text in ReadMp4DataTexts(stream, payloadStart, payloadEnd))
                {
                    texts.Add(new RawLyricText { Source = "mp4-©lyr", Text = text });
                }
            }
            else if (type == "----")
            {
                ReadMp4Freeform(stream, payloadStart, payloadEnd, texts);
            }

            position = payloadEnd;
        }
    }

    private static void ReadMp4Freeform(Stream stream, long start, long end, List<RawLyricText> texts)
    {
        string? mean = null;
        string? name = null;
        var dataTexts = new List<string>();
        var position = start;
        var header = new byte[8];
        while (position + 8 <= end)
        {
            stream.Position = position;
            if (stream.Read(header, 0, 8) < 8)
            {
                break;
            }

            var size = BinaryPrimitives.ReadUInt32BigEndian(header);
            var type = Encoding.Latin1.GetString(header, 4, 4);
            if (size < 8)
            {
                break;
            }

            var payloadStart = position + 8;
            var payloadEnd = position + size;
            if (payloadEnd > end)
            {
                break;
            }

            stream.Position = payloadStart;
            var payload = new byte[payloadEnd - payloadStart];
            _ = stream.Read(payload, 0, payload.Length);
            switch (type)
            {
                case "mean":
                    mean = Encoding.UTF8.GetString(payload, 4, Math.Max(0, payload.Length - 4));
                    break;
                case "name":
                    name = Encoding.UTF8.GetString(payload, 4, Math.Max(0, payload.Length - 4));
                    break;
                case "data" when payload.Length >= 8:
                    dataTexts.Add(Encoding.UTF8.GetString(payload, 8, payload.Length - 8));
                    break;
            }

            position = payloadEnd;
        }

        if (!string.Equals(name, "LYRICS", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(name, "UNSYNCEDLYRICS", StringComparison.OrdinalIgnoreCase))
        {
            return;
        }

        foreach (var text in dataTexts.Where(item => !string.IsNullOrEmpty(item)))
        {
            texts.Add(new RawLyricText
            {
                Source = $"mp4-freeform:{mean}:{name}",
                Text = text
            });
        }
    }

    private static List<string> ReadMp4DataTexts(Stream stream, long start, long end)
    {
        var texts = new List<string>();
        var position = start;
        var header = new byte[8];
        while (position + 8 <= end)
        {
            stream.Position = position;
            if (stream.Read(header, 0, 8) < 8)
            {
                break;
            }

            var size = BinaryPrimitives.ReadUInt32BigEndian(header);
            var type = Encoding.Latin1.GetString(header, 4, 4);
            if (size < 8)
            {
                break;
            }

            var payloadEnd = position + size;
            if (payloadEnd > end)
            {
                break;
            }

            if (type == "data" && size >= 16)
            {
                stream.Position = position + 16;
                var textBytes = new byte[size - 16];
                var read = stream.Read(textBytes, 0, textBytes.Length);
                if (read > 0)
                {
                    var text = Encoding.UTF8.GetString(textBytes, 0, read);
                    if (!string.IsNullOrEmpty(text))
                    {
                        texts.Add(text);
                    }
                }
            }

            position = payloadEnd;
        }

        return texts;
    }

    private static List<RawLyricText> ReadId3(Stream stream, long start)
    {
        stream.Position = start;
        var header = new byte[10];
        if (stream.Read(header, 0, 10) < 10)
        {
            return [];
        }

        var version = header[3];
        var size = SynchSafe(header.AsSpan(6, 4));
        var body = new byte[size];
        var read = stream.Read(body, 0, body.Length);
        if (read <= 0)
        {
            return [];
        }

        var texts = new List<RawLyricText>();
        var offset = 0;
        while (offset + 10 <= read)
        {
            var frameId = Encoding.ASCII.GetString(body, offset, 4);
            if (frameId[0] == '\0')
            {
                break;
            }

            int frameSize;
            if (version >= 4)
            {
                frameSize = SynchSafe(body.AsSpan(offset + 4, 4));
            }
            else
            {
                frameSize = (int)BinaryPrimitives.ReadUInt32BigEndian(body.AsSpan(offset + 4, 4));
            }

            if (frameSize < 0 || offset + 10 + frameSize > read)
            {
                break;
            }

            if (frameId == "USLT")
            {
                var text = DecodeUslt(body.AsSpan(offset + 10, frameSize));
                if (!string.IsNullOrEmpty(text))
                {
                    texts.Add(new RawLyricText { Source = "id3-USLT", Text = text });
                }
            }

            offset += 10 + frameSize;
        }

        return texts;
    }

    private static string? DecodeUslt(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < 5)
        {
            return null;
        }

        var encoding = payload[0];
        var rest = payload[4..];
        return encoding switch
        {
            1 => SplitUtf16(rest, bigEndian: false),
            2 => SplitUtf16(rest, bigEndian: true),
            3 => SplitUtf8(rest),
            _ => SplitLatin1(rest)
        };
    }

    private static string? SplitUtf8(ReadOnlySpan<byte> rest)
    {
        var zero = rest.IndexOf((byte)0);
        var text = zero >= 0 ? rest[(zero + 1)..] : rest;
        return Encoding.UTF8.GetString(text);
    }

    private static string? SplitLatin1(ReadOnlySpan<byte> rest)
    {
        var zero = rest.IndexOf((byte)0);
        var text = zero >= 0 ? rest[(zero + 1)..] : rest;
        return Encoding.Latin1.GetString(text);
    }

    private static string? SplitUtf16(ReadOnlySpan<byte> rest, bool bigEndian)
    {
        var encoding = bigEndian ? Encoding.BigEndianUnicode : Encoding.Unicode;
        for (var i = 0; i + 1 < rest.Length; i += 2)
        {
            if (rest[i] == 0 && rest[i + 1] == 0)
            {
                return encoding.GetString(rest[(i + 2)..]);
            }
        }

        return encoding.GetString(rest);
    }

    private static int SynchSafe(ReadOnlySpan<byte> bytes)
    {
        return (bytes[0] << 21) | (bytes[1] << 14) | (bytes[2] << 7) | bytes[3];
    }

    private static List<RawLyricText> ReadOggComments(Stream stream, long start, long end)
    {
        var texts = new List<RawLyricText>();
        foreach (var packet in ReadOggPackets(stream, start, end, maxPackets: 8))
        {
            ReadCommentBlock(packet, texts);
        }

        return texts;
    }

    private static void ReadCommentBlock(byte[] packet, List<RawLyricText> texts)
    {
        var span = packet.AsSpan();
        if (span.Length >= 8 && span[0] == 3 && Encoding.ASCII.GetString(span.Slice(1, 6)) == "vorbis")
        {
            ParseVorbisComments(span[7..], texts, "ogg-vorbis");
            return;
        }

        if (span.Length >= 8 && Encoding.ASCII.GetString(span[..8]) == "OpusTags")
        {
            ParseVorbisComments(span[8..], texts, "ogg-opus");
        }
    }

    private static void ParseVorbisComments(ReadOnlySpan<byte> data, List<RawLyricText> texts, string source)
    {
        if (data.Length < 8)
        {
            return;
        }

        var vendorLen = BinaryPrimitives.ReadInt32LittleEndian(data);
        if (vendorLen < 0 || 4 + vendorLen + 4 > data.Length)
        {
            return;
        }

        var offset = 4 + vendorLen;
        var count = BinaryPrimitives.ReadInt32LittleEndian(data[offset..]);
        offset += 4;
        for (var i = 0; i < count && offset + 4 <= data.Length; i++)
        {
            var len = BinaryPrimitives.ReadInt32LittleEndian(data[offset..]);
            offset += 4;
            if (len < 0 || offset + len > data.Length)
            {
                return;
            }

            var entry = Encoding.UTF8.GetString(data.Slice(offset, len));
            offset += len;
            var eq = entry.IndexOf('=');
            if (eq <= 0)
            {
                continue;
            }

            var key = entry[..eq];
            if (key.Equals("LYRICS", StringComparison.OrdinalIgnoreCase) ||
                key.Equals("UNSYNCEDLYRICS", StringComparison.OrdinalIgnoreCase) ||
                key.Equals("UNSYNCED LYRICS", StringComparison.OrdinalIgnoreCase))
            {
                var value = entry[(eq + 1)..];
                if (!string.IsNullOrEmpty(value))
                {
                    texts.Add(new RawLyricText { Source = source + ":" + key, Text = value });
                }
            }
        }
    }

    private static List<byte[]> ReadOggPackets(Stream stream, long start, long end, int maxPackets)
    {
        var packets = new List<byte[]>();
        var current = new MemoryStream();
        var position = start;
        while (position + 27 <= end && packets.Count < maxPackets)
        {
            stream.Position = position;
            var header = new byte[27];
            if (stream.Read(header, 0, 27) < 27)
            {
                break;
            }

            if (header[0] != (byte)'O' || header[1] != (byte)'g' || header[2] != (byte)'g' || header[3] != (byte)'S')
            {
                break;
            }

            var nsegs = header[26];
            var table = new byte[nsegs];
            if (stream.Read(table, 0, nsegs) < nsegs)
            {
                break;
            }

            var payloadSize = table.Sum(seg => (int)seg);
            var payload = new byte[payloadSize];
            var read = stream.Read(payload, 0, payload.Length);
            var offset = 0;
            foreach (var seg in table)
            {
                current.Write(payload, offset, seg);
                offset += seg;
                if (seg < 255)
                {
                    packets.Add(current.ToArray());
                    current.SetLength(0);
                    if (packets.Count >= maxPackets)
                    {
                        return packets;
                    }
                }
            }

            position = stream.Position;
        }

        return packets;
    }
}
