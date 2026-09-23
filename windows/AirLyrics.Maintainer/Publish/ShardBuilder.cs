using AirLyrics.Maintainer.Scanning;
using AirLyrics.Maintainer.State;
using Microsoft.Data.Sqlite;

namespace AirLyrics.Maintainer.Publish;

public sealed class ShardTrackInput
{
    public required SourceFileRecord File { get; init; }
    public required Reading.ExtractedTrack Extract { get; init; }
    public required IReadOnlyList<StoredAlias> Aliases { get; init; }
}

public sealed class BuiltShard
{
    public required string StagingPath { get; init; }
    public required string Revision { get; init; }
    public required string Sha256 { get; init; }
    public required long ByteSize { get; init; }
    public required int TrackCount { get; init; }
}

public static class ShardBuilder
{
    public const int SchemaVersion = 1;
    public const int NormalizationVersion = 1;

    public static BuiltShard Build(
        string stagingPath,
        string libraryId,
        BucketKey bucket,
        IReadOnlyList<ShardTrackInput> tracks)
    {
        var revision = Guid.NewGuid().ToString("N");
        if (File.Exists(stagingPath))
        {
            File.Delete(stagingPath);
        }

        Directory.CreateDirectory(Path.GetDirectoryName(stagingPath)!);

        var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = stagingPath,
            Mode = SqliteOpenMode.ReadWriteCreate
        }.ToString());
        connection.Open();
        try
        {
            Exec(connection, "PRAGMA journal_mode=DELETE;");
            Exec(connection, "PRAGMA synchronous=FULL;");
            Exec(connection, "PRAGMA foreign_keys=ON;");
            CreateSchema(connection);
            InsertAll(connection, libraryId, bucket, revision, tracks);
            CheckIntegrity(connection, tracks.Count);
        }
        finally
        {
            connection.Close();
            SqliteConnection.ClearPool(connection);
            connection.Dispose();
        }

        DeleteSidecar(stagingPath, "-wal");
        DeleteSidecar(stagingPath, "-shm");
        DeleteSidecar(stagingPath, "-journal");

        var hash = Sha256Hex.OfFile(stagingPath);
        return new BuiltShard
        {
            StagingPath = stagingPath,
            Revision = revision,
            Sha256 = hash,
            ByteSize = new FileInfo(stagingPath).Length,
            TrackCount = tracks.Count
        };
    }

    private static void CreateSchema(SqliteConnection connection)
    {
        Exec(connection, """
            CREATE TABLE db_info (
                schema_version INTEGER NOT NULL,
                normalization_version INTEGER NOT NULL,
                library_id TEXT NOT NULL,
                shard_id TEXT NOT NULL,
                revision TEXT NOT NULL,
                bucket_path TEXT NOT NULL,
                bucket_kind TEXT NOT NULL,
                built_at_utc INTEGER NOT NULL
            );
            CREATE TABLE track (
                track_id INTEGER PRIMARY KEY NOT NULL,
                relative_path TEXT NOT NULL,
                filename TEXT NOT NULL,
                title TEXT,
                album TEXT,
                album_artist TEXT,
                duration_ms INTEGER,
                disc INTEGER,
                track_number INTEGER,
                genre TEXT,
                mbid TEXT,
                isrc TEXT,
                lyrics_status TEXT NOT NULL
            );
            CREATE TABLE track_artist (
                track_id INTEGER NOT NULL,
                ordinal INTEGER NOT NULL,
                artist TEXT NOT NULL,
                PRIMARY KEY (track_id, ordinal),
                FOREIGN KEY (track_id) REFERENCES track(track_id)
            );
            CREATE TABLE match_alias (
                track_id INTEGER NOT NULL,
                field TEXT NOT NULL,
                old_value TEXT NOT NULL,
                source TEXT NOT NULL,
                FOREIGN KEY (track_id) REFERENCES track(track_id)
            );
            CREATE TABLE lyrics (
                lyric_id INTEGER PRIMARY KEY NOT NULL,
                track_id INTEGER NOT NULL,
                tag_kind TEXT NOT NULL,
                language TEXT,
                description TEXT,
                variant_order INTEGER NOT NULL,
                format TEXT,
                raw_text TEXT,
                text_hash TEXT,
                diagnostic TEXT,
                FOREIGN KEY (track_id) REFERENCES track(track_id)
            );
            """);
    }

    private static void InsertAll(
        SqliteConnection connection,
        string libraryId,
        BucketKey bucket,
        string revision,
        IReadOnlyList<ShardTrackInput> tracks)
    {
        using var tx = connection.BeginTransaction();
        using (var info = connection.CreateCommand())
        {
            info.Transaction = tx;
            info.CommandText = """
                INSERT INTO db_info (
                    schema_version, normalization_version, library_id, shard_id, revision,
                    bucket_path, bucket_kind, built_at_utc)
                VALUES ($schema, $norm, $lib, $shard, $rev, $path, $kind, $built);
                """;
            info.Parameters.AddWithValue("$schema", SchemaVersion);
            info.Parameters.AddWithValue("$norm", NormalizationVersion);
            info.Parameters.AddWithValue("$lib", libraryId);
            info.Parameters.AddWithValue("$shard", bucket.ShardId);
            info.Parameters.AddWithValue("$rev", revision);
            info.Parameters.AddWithValue("$path", bucket.RelativePath);
            info.Parameters.AddWithValue("$kind", bucket.KindName);
            info.Parameters.AddWithValue("$built", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            info.ExecuteNonQuery();
        }

        var trackId = 0;
        foreach (var item in tracks)
        {
            trackId++;
            using (var cmd = connection.CreateCommand())
            {
                cmd.Transaction = tx;
                cmd.CommandText = """
                    INSERT INTO track (
                        track_id, relative_path, filename, title, album, album_artist, duration_ms,
                        disc, track_number, genre, mbid, isrc, lyrics_status)
                    VALUES ($id, $rel, $name, $title, $album, $aa, $dur, $disc, $tn, $genre, $mbid, $isrc, $status);
                    """;
                cmd.Parameters.AddWithValue("$id", trackId);
                cmd.Parameters.AddWithValue("$rel", item.File.RelativePath);
                cmd.Parameters.AddWithValue("$name", item.File.Filename);
                cmd.Parameters.AddWithValue("$title", (object?)item.Extract.Title ?? DBNull.Value);
                cmd.Parameters.AddWithValue("$album", (object?)item.Extract.Album ?? DBNull.Value);
                cmd.Parameters.AddWithValue("$aa", (object?)item.Extract.AlbumArtist ?? DBNull.Value);
                cmd.Parameters.AddWithValue("$dur", (object?)item.Extract.DurationMs ?? DBNull.Value);
                cmd.Parameters.AddWithValue("$disc", (object?)item.Extract.DiscNumber ?? DBNull.Value);
                cmd.Parameters.AddWithValue("$tn", (object?)item.Extract.TrackNumber ?? DBNull.Value);
                cmd.Parameters.AddWithValue("$genre", (object?)item.Extract.Genre ?? DBNull.Value);
                cmd.Parameters.AddWithValue("$mbid", (object?)item.Extract.Mbid ?? DBNull.Value);
                cmd.Parameters.AddWithValue("$isrc", (object?)item.Extract.Isrc ?? DBNull.Value);
                cmd.Parameters.AddWithValue("$status", item.Extract.LyricsStatus);
                cmd.ExecuteNonQuery();
            }

            if (!string.IsNullOrEmpty(item.Extract.ArtistRaw))
            {
                using var artist = connection.CreateCommand();
                artist.Transaction = tx;
                artist.CommandText = """
                    INSERT INTO track_artist (track_id, ordinal, artist)
                    VALUES ($id, 0, $artist);
                    """;
                artist.Parameters.AddWithValue("$id", trackId);
                artist.Parameters.AddWithValue("$artist", item.Extract.ArtistRaw);
                artist.ExecuteNonQuery();
            }

            foreach (var alias in item.Aliases)
            {
                using var aliasCmd = connection.CreateCommand();
                aliasCmd.Transaction = tx;
                aliasCmd.CommandText = """
                    INSERT INTO match_alias (track_id, field, old_value, source)
                    VALUES ($id, $field, $old, $source);
                    """;
                aliasCmd.Parameters.AddWithValue("$id", trackId);
                aliasCmd.Parameters.AddWithValue("$field", alias.Field);
                aliasCmd.Parameters.AddWithValue("$old", alias.OldValue);
                aliasCmd.Parameters.AddWithValue("$source", alias.Source);
                aliasCmd.ExecuteNonQuery();
            }

            var lyricIdBase = trackId * 1000;
            var lyricOffset = 0;
            foreach (var lyric in item.Extract.Lyrics)
            {
                lyricOffset++;
                using var lyricCmd = connection.CreateCommand();
                lyricCmd.Transaction = tx;
                lyricCmd.CommandText = """
                    INSERT INTO lyrics (
                        lyric_id, track_id, tag_kind, language, description, variant_order,
                        format, raw_text, text_hash, diagnostic)
                    VALUES ($lid, $tid, $kind, $lang, $desc, $ord, $fmt, $raw, $hash, $diag);
                    """;
                lyricCmd.Parameters.AddWithValue("$lid", lyricIdBase + lyricOffset);
                lyricCmd.Parameters.AddWithValue("$tid", trackId);
                lyricCmd.Parameters.AddWithValue("$kind", lyric.TagKind);
                lyricCmd.Parameters.AddWithValue("$lang", (object?)lyric.Language ?? DBNull.Value);
                lyricCmd.Parameters.AddWithValue("$desc", (object?)lyric.Description ?? DBNull.Value);
                lyricCmd.Parameters.AddWithValue("$ord", lyric.VariantOrder);
                lyricCmd.Parameters.AddWithValue("$fmt", (object?)lyric.Format ?? DBNull.Value);
                lyricCmd.Parameters.AddWithValue("$raw", (object?)lyric.RawText ?? DBNull.Value);
                lyricCmd.Parameters.AddWithValue("$hash", (object?)lyric.TextHash ?? DBNull.Value);
                lyricCmd.Parameters.AddWithValue("$diag", (object?)lyric.Diagnostic ?? DBNull.Value);
                lyricCmd.ExecuteNonQuery();
            }
        }

        tx.Commit();
    }

    private static void CheckIntegrity(SqliteConnection connection, int expectedTracks)
    {
        using (var check = connection.CreateCommand())
        {
            check.CommandText = "PRAGMA quick_check;";
            var result = check.ExecuteScalar()?.ToString();
            if (!string.Equals(result, "ok", StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException($"Shard quick_check failed: {result}");
            }
        }

        using var cmd = connection.CreateCommand();
        cmd.CommandText = """
            SELECT
                (SELECT COUNT(*) FROM db_info),
                (SELECT COUNT(*) FROM track),
                (SELECT COUNT(*) FROM track_artist WHERE track_id NOT IN (SELECT track_id FROM track)),
                (SELECT COUNT(*) FROM lyrics WHERE track_id NOT IN (SELECT track_id FROM track)),
                (SELECT COUNT(*) FROM match_alias WHERE track_id NOT IN (SELECT track_id FROM track)),
                (SELECT COUNT(*) FROM track WHERE lyrics_status NOT IN ('present','absent','error')),
                (SELECT COUNT(*) FROM track WHERE lyrics_status = 'present'
                    AND track_id NOT IN (SELECT track_id FROM lyrics WHERE raw_text IS NOT NULL AND raw_text <> '')),
                (SELECT COUNT(*) FROM track WHERE lyrics_status = 'absent'
                    AND track_id IN (SELECT track_id FROM lyrics)),
                (SELECT COUNT(*) FROM track WHERE lyrics_status = 'error'
                    AND track_id NOT IN (SELECT track_id FROM lyrics WHERE diagnostic IS NOT NULL));
            """;
        using var reader = cmd.ExecuteReader();
        reader.Read();
        if (reader.GetInt32(0) != 1)
        {
            throw new InvalidOperationException("Shard must contain exactly one db_info row.");
        }

        if (reader.GetInt32(1) != expectedTracks)
        {
            throw new InvalidOperationException("Shard track count mismatch.");
        }

        if (reader.GetInt32(2) != 0 || reader.GetInt32(3) != 0 || reader.GetInt32(4) != 0)
        {
            throw new InvalidOperationException("Shard has orphaned relation rows.");
        }

        if (reader.GetInt32(5) != 0)
        {
            throw new InvalidOperationException("Shard has invalid lyrics_status.");
        }

        if (reader.GetInt32(6) != 0)
        {
            throw new InvalidOperationException("present tracks must keep original lyrics text.");
        }

        if (reader.GetInt32(7) != 0)
        {
            throw new InvalidOperationException("absent tracks must not store lyrics rows.");
        }

        if (reader.GetInt32(8) != 0)
        {
            throw new InvalidOperationException("error tracks must store a diagnostic.");
        }
    }

    private static void Exec(SqliteConnection connection, string sql)
    {
        using var cmd = connection.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }

    private static void DeleteSidecar(string sqlitePath, string suffix)
    {
        var sidecar = sqlitePath + suffix;
        if (File.Exists(sidecar))
        {
            File.Delete(sidecar);
        }
    }
}
