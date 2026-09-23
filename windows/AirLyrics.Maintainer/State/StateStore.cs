using AirLyrics.Maintainer.Reading;
using AirLyrics.Maintainer.Scanning;
using Microsoft.Data.Sqlite;
using System.Text.Json;

namespace AirLyrics.Maintainer.State;

public sealed record LibraryRecord
{
    public required string LibraryId { get; init; }
    public required string SourceRoot { get; init; }
    public required long LastSuccessfulGeneration { get; init; }
}

public sealed class StoredFile
{
    public required string RelativePath { get; init; }
    public required string BucketKey { get; init; }
    public required string Filename { get; init; }
    public required long Size { get; init; }
    public required long MtimeUtcMs { get; init; }
    public uint? VolumeSerial { get; init; }
    public ulong? FileIndex { get; init; }
    public required ExtractedTrack Extract { get; init; }
    public List<StoredAlias> Aliases { get; init; } = [];
}

public sealed class StoredAlias
{
    public required string Field { get; init; }
    public required string OldValue { get; init; }
    public required string Source { get; init; }
}

public sealed class StoredBucket
{
    public required string BucketKey { get; init; }
    public required string Kind { get; init; }
    public required string RelativePath { get; init; }
    public required string ShardId { get; init; }
    public string? Revision { get; init; }
    public string? Sha256 { get; init; }
    public long? ByteSize { get; init; }
    public int? TrackCount { get; init; }
    public string? RelativeUrl { get; init; }
}

public sealed class StateStore : IDisposable
{
    public const int SchemaVersion = 1;

    private readonly SqliteConnection _connection;
    private bool _disposed;

    private StateStore(SqliteConnection connection)
    {
        _connection = connection;
    }

    public static StateStore Open(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var connection = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Pooling = false
        }.ToString());
        connection.Open();
        using (var pragma = connection.CreateCommand())
        {
            pragma.CommandText = "PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;";
            pragma.ExecuteNonQuery();
        }

        EnsureSchema(connection);
        return new StateStore(connection);
    }

    public LibraryRecord? GetLibrary()
    {
        using var cmd = _connection.CreateCommand();
        cmd.CommandText = """
            SELECT library_id, source_root, last_successful_generation
            FROM library LIMIT 1;
            """;
        using var reader = cmd.ExecuteReader();
        if (!reader.Read())
        {
            return null;
        }

        return new LibraryRecord
        {
            LibraryId = reader.GetString(0),
            SourceRoot = reader.GetString(1),
            LastSuccessfulGeneration = reader.GetInt64(2)
        };
    }

    public LibraryRecord CreateLibrary(string sourceRoot)
    {
        var record = new LibraryRecord
        {
            LibraryId = Guid.NewGuid().ToString("D"),
            SourceRoot = Path.GetFullPath(sourceRoot),
            LastSuccessfulGeneration = 0
        };
        using var cmd = _connection.CreateCommand();
        cmd.CommandText = """
            INSERT INTO library (library_id, source_root, created_at_utc, last_successful_generation, schema_version)
            VALUES ($id, $root, $now, 0, $schema);
            """;
        cmd.Parameters.AddWithValue("$id", record.LibraryId);
        cmd.Parameters.AddWithValue("$root", record.SourceRoot);
        cmd.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        cmd.Parameters.AddWithValue("$schema", SchemaVersion);
        cmd.ExecuteNonQuery();
        return record;
    }

    public void UpdateSourceRoot(string libraryId, string sourceRoot)
    {
        using var cmd = _connection.CreateCommand();
        cmd.CommandText = "UPDATE library SET source_root = $root WHERE library_id = $id;";
        cmd.Parameters.AddWithValue("$root", Path.GetFullPath(sourceRoot));
        cmd.Parameters.AddWithValue("$id", libraryId);
        cmd.ExecuteNonQuery();
    }

    public IReadOnlyList<StoredFile> LoadFiles()
    {
        var files = new Dictionary<string, StoredFile>(StringComparer.Ordinal);
        using (var cmd = _connection.CreateCommand())
        {
            cmd.CommandText = """
                SELECT relative_path, bucket_key, filename, size, mtime_utc, volume_serial, file_index, extract_json
                FROM file_state;
                """;
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
            {
                var relative = reader.GetString(0);
                files[relative] = new StoredFile
                {
                    RelativePath = relative,
                    BucketKey = reader.GetString(1),
                    Filename = reader.GetString(2),
                    Size = reader.GetInt64(3),
                    MtimeUtcMs = reader.GetInt64(4),
                    VolumeSerial = reader.IsDBNull(5) ? null : (uint)reader.GetInt64(5),
                    FileIndex = reader.IsDBNull(6) ? null : (ulong)reader.GetInt64(6),
                    Extract = JsonSerializer.Deserialize<ExtractedTrack>(reader.GetString(7))
                        ?? throw new InvalidOperationException("Corrupt extract_json in state.")
                };
            }
        }

        using (var cmd = _connection.CreateCommand())
        {
            cmd.CommandText = "SELECT relative_path, field, old_value, source FROM file_alias;";
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
            {
                var relative = reader.GetString(0);
                if (!files.TryGetValue(relative, out var file))
                {
                    continue;
                }

                file.Aliases.Add(new StoredAlias
                {
                    Field = reader.GetString(1),
                    OldValue = reader.GetString(2),
                    Source = reader.GetString(3)
                });
            }
        }

        return files.Values.ToList();
    }

    public IReadOnlyList<StoredBucket> LoadBuckets()
    {
        var list = new List<StoredBucket>();
        using var cmd = _connection.CreateCommand();
        cmd.CommandText = """
            SELECT bucket_key, kind, relative_path, shard_id, revision, sha256, byte_size, track_count, relative_url
            FROM bucket_state;
            """;
        using var reader = cmd.ExecuteReader();
        while (reader.Read())
        {
            list.Add(new StoredBucket
            {
                BucketKey = reader.GetString(0),
                Kind = reader.GetString(1),
                RelativePath = reader.GetString(2),
                ShardId = reader.GetString(3),
                Revision = reader.IsDBNull(4) ? null : reader.GetString(4),
                Sha256 = reader.IsDBNull(5) ? null : reader.GetString(5),
                ByteSize = reader.IsDBNull(6) ? null : reader.GetInt64(6),
                TrackCount = reader.IsDBNull(7) ? null : reader.GetInt32(7),
                RelativeUrl = reader.IsDBNull(8) ? null : reader.GetString(8)
            });
        }

        return list;
    }

    public void RecordRun(string runId, string status, string mode, long? generation, string? error)
    {
        using var cmd = _connection.CreateCommand();
        cmd.CommandText = """
            INSERT INTO scan_run (run_id, started_at_utc, finished_at_utc, status, mode, generation, error)
            VALUES ($id, $start, $end, $status, $mode, $generation, $error);
            """;
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        cmd.Parameters.AddWithValue("$id", runId);
        cmd.Parameters.AddWithValue("$start", now);
        cmd.Parameters.AddWithValue("$end", now);
        cmd.Parameters.AddWithValue("$status", status);
        cmd.Parameters.AddWithValue("$mode", mode);
        cmd.Parameters.AddWithValue("$generation", (object?)generation ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$error", (object?)error ?? DBNull.Value);
        cmd.ExecuteNonQuery();
    }

    public void ReplaceAfterSuccess(
        LibraryRecord library,
        long generation,
        IReadOnlyList<StoredFile> files,
        IReadOnlyList<StoredBucket> buckets)
    {
        using var tx = _connection.BeginTransaction();
        Execute(tx, "DELETE FROM file_alias;");
        Execute(tx, "DELETE FROM file_state;");
        Execute(tx, "DELETE FROM bucket_state;");

        foreach (var file in files)
        {
            using var cmd = _connection.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText = """
                INSERT INTO file_state (
                    relative_path, bucket_key, filename, size, mtime_utc, volume_serial, file_index, extract_json)
                VALUES ($rel, $bucket, $name, $size, $mtime, $vol, $idx, $json);
                """;
            cmd.Parameters.AddWithValue("$rel", file.RelativePath);
            cmd.Parameters.AddWithValue("$bucket", file.BucketKey);
            cmd.Parameters.AddWithValue("$name", file.Filename);
            cmd.Parameters.AddWithValue("$size", file.Size);
            cmd.Parameters.AddWithValue("$mtime", file.MtimeUtcMs);
            cmd.Parameters.AddWithValue("$vol", (object?)file.VolumeSerial ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$idx", file.FileIndex is null ? DBNull.Value : (long)file.FileIndex.Value);
            cmd.Parameters.AddWithValue("$json", JsonSerializer.Serialize(file.Extract));
            cmd.ExecuteNonQuery();

            foreach (var alias in file.Aliases)
            {
                using var aliasCmd = _connection.CreateCommand();
                aliasCmd.Transaction = tx;
                aliasCmd.CommandText = """
                    INSERT INTO file_alias (relative_path, field, old_value, source)
                    VALUES ($rel, $field, $old, $source);
                    """;
                aliasCmd.Parameters.AddWithValue("$rel", file.RelativePath);
                aliasCmd.Parameters.AddWithValue("$field", alias.Field);
                aliasCmd.Parameters.AddWithValue("$old", alias.OldValue);
                aliasCmd.Parameters.AddWithValue("$source", alias.Source);
                aliasCmd.ExecuteNonQuery();
            }
        }

        foreach (var bucket in buckets)
        {
            using var cmd = _connection.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText = """
                INSERT INTO bucket_state (
                    bucket_key, kind, relative_path, shard_id, revision, sha256, byte_size, track_count, relative_url)
                VALUES ($key, $kind, $path, $shard, $rev, $sha, $size, $count, $url);
                """;
            cmd.Parameters.AddWithValue("$key", bucket.BucketKey);
            cmd.Parameters.AddWithValue("$kind", bucket.Kind);
            cmd.Parameters.AddWithValue("$path", bucket.RelativePath);
            cmd.Parameters.AddWithValue("$shard", bucket.ShardId);
            cmd.Parameters.AddWithValue("$rev", (object?)bucket.Revision ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$sha", (object?)bucket.Sha256 ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$size", (object?)bucket.ByteSize ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$count", (object?)bucket.TrackCount ?? DBNull.Value);
            cmd.Parameters.AddWithValue("$url", (object?)bucket.RelativeUrl ?? DBNull.Value);
            cmd.ExecuteNonQuery();
        }

        using (var cmd = _connection.CreateCommand())
        {
            cmd.Transaction = tx;
            cmd.CommandText = """
                UPDATE library
                SET last_successful_generation = $gen,
                    last_success_at_utc = $now
                WHERE library_id = $id;
                """;
            cmd.Parameters.AddWithValue("$gen", generation);
            cmd.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            cmd.Parameters.AddWithValue("$id", library.LibraryId);
            cmd.ExecuteNonQuery();
        }

        tx.Commit();
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        SqliteConnection.ClearPool(_connection);
        _connection.Dispose();
        _disposed = true;
    }

    private static void Execute(SqliteTransaction tx, string sql)
    {
        using var cmd = tx.Connection!.CreateCommand();
        cmd.Transaction = tx;
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }

    private static void EnsureSchema(SqliteConnection connection)
    {
        using var cmd = connection.CreateCommand();
        cmd.CommandText = """
            CREATE TABLE IF NOT EXISTS library (
                library_id TEXT PRIMARY KEY NOT NULL,
                source_root TEXT NOT NULL,
                created_at_utc INTEGER NOT NULL,
                last_successful_generation INTEGER NOT NULL,
                last_success_at_utc INTEGER,
                schema_version INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS scan_run (
                run_id TEXT PRIMARY KEY NOT NULL,
                started_at_utc INTEGER NOT NULL,
                finished_at_utc INTEGER,
                status TEXT NOT NULL,
                mode TEXT NOT NULL,
                generation INTEGER,
                error TEXT
            );
            CREATE TABLE IF NOT EXISTS bucket_state (
                bucket_key TEXT PRIMARY KEY NOT NULL,
                kind TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                shard_id TEXT NOT NULL,
                revision TEXT,
                sha256 TEXT,
                byte_size INTEGER,
                track_count INTEGER,
                relative_url TEXT
            );
            CREATE TABLE IF NOT EXISTS file_state (
                relative_path TEXT PRIMARY KEY NOT NULL,
                bucket_key TEXT NOT NULL,
                filename TEXT NOT NULL,
                size INTEGER NOT NULL,
                mtime_utc INTEGER NOT NULL,
                volume_serial INTEGER,
                file_index INTEGER,
                extract_json TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS file_alias (
                relative_path TEXT NOT NULL,
                field TEXT NOT NULL,
                old_value TEXT NOT NULL,
                source TEXT NOT NULL
            );
            """;
        cmd.ExecuteNonQuery();
    }
}
