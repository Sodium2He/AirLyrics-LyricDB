using System.Net.Http.Headers;
using System.Text;

namespace AirLyrics.Maintainer.Publish;

public sealed class WebDavRequest
{
    public required string Method { get; init; }
    public required Uri Uri { get; init; }
    public byte[]? Body { get; init; }
}

public sealed class WebDavResponse
{
    public required int Status { get; init; }
}

public interface IWebDavTransport
{
    WebDavResponse Send(WebDavRequest request);
}

public sealed class WebDavPublishResult
{
    public required bool Success { get; init; }
    public string? Error { get; init; }
    public int UploadedFiles { get; init; }

    public static WebDavPublishResult Fail(string error) => new() { Success = false, Error = error };
}

public sealed class WebDavPublisher
{
    private readonly IWebDavTransport _transport;

    public WebDavPublisher(IWebDavTransport transport)
    {
        _transport = transport;
    }

    public static WebDavPublisher Create(Uri folderUrl, string? username, string? password)
    {
        return new WebDavPublisher(new HttpWebDavTransport(folderUrl, username, password));
    }

    public WebDavPublishResult Upload(string publishDirectory)
    {
        var manifestPath = Path.Combine(publishDirectory, "manifest.json");
        if (!File.Exists(manifestPath))
        {
            return WebDavPublishResult.Fail("manifest.json is missing in the publish folder.");
        }

        PublishManifest manifest;
        try
        {
            manifest = ManifestIO.Read(manifestPath);
        }
        catch (Exception ex)
        {
            return WebDavPublishResult.Fail(ex.Message);
        }

        var uploaded = 0;
        foreach (var shard in manifest.Shards)
        {
            var local = ResolveLocal(publishDirectory, shard.RelativeUrl);
            if (!File.Exists(local))
            {
                return WebDavPublishResult.Fail($"Missing shard {shard.RelativeUrl}");
            }

            EnsureCollections(shard.RelativeUrl);
            var put = Put(shard.RelativeUrl, File.ReadAllBytes(local));
            if (put.Status is < 200 or > 299)
            {
                return WebDavPublishResult.Fail($"PUT {shard.RelativeUrl} failed ({put.Status}). Manifest was not uploaded.");
            }

            var head = Send("HEAD", shard.RelativeUrl, body: null);
            if (head.Status is < 200 or > 299)
            {
                return WebDavPublishResult.Fail($"HEAD {shard.RelativeUrl} failed ({head.Status}). Manifest was not uploaded.");
            }

            uploaded += 1;
        }

        var manifestPut = Put("manifest.json", File.ReadAllBytes(manifestPath));
        if (manifestPut.Status is < 200 or > 299)
        {
            return WebDavPublishResult.Fail($"PUT manifest.json failed ({manifestPut.Status}). Previous remote generation should be kept.");
        }

        return new WebDavPublishResult { Success = true, UploadedFiles = uploaded + 1 };
    }

    private void EnsureCollections(string relativeUrl)
    {
        var parts = relativeUrl.Split('/', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length <= 1)
        {
            return;
        }

        var prefix = new StringBuilder();
        for (var i = 0; i < parts.Length - 1; i++)
        {
            if (prefix.Length > 0)
            {
                prefix.Append('/');
            }

            prefix.Append(parts[i]);
            var status = Send("MKCOL", prefix.ToString(), body: null).Status;
            if (status is >= 200 and <= 299 or 405 or 409 or 301)
            {
                continue;
            }
        }
    }

    private WebDavResponse Put(string relativeUrl, byte[] body)
    {
        return Send("PUT", relativeUrl, body);
    }

    private WebDavResponse Send(string method, string relativeUrl, byte[]? body)
    {
        return _transport.Send(new WebDavRequest
        {
            Method = method,
            Uri = new Uri(relativeUrl, UriKind.Relative),
            Body = body
        });
    }

    private static string ResolveLocal(string publishDirectory, string relativeUrl)
    {
        var decoded = relativeUrl.Split('/', StringSplitOptions.RemoveEmptyEntries)
            .Select(Uri.UnescapeDataString);
        return Path.Combine(new[] { publishDirectory }.Concat(decoded).ToArray());
    }
}

internal sealed class HttpWebDavTransport : IWebDavTransport
{
    private readonly HttpClient _client;
    private readonly Uri _folder;

    public HttpWebDavTransport(Uri folderUrl, string? username, string? password)
    {
        _folder = folderUrl;
        var handler = new HttpClientHandler { AllowAutoRedirect = false };
        _client = new HttpClient(handler) { Timeout = TimeSpan.FromMinutes(5) };
        if (!string.IsNullOrWhiteSpace(username))
        {
            var token = Convert.ToBase64String(Encoding.UTF8.GetBytes($"{username}:{password}"));
            _client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic", token);
        }
    }

    public WebDavResponse Send(WebDavRequest request)
    {
        var uri = request.Uri.IsAbsoluteUri ? request.Uri : Combine(_folder, request.Uri.ToString());
        using var message = new HttpRequestMessage(new HttpMethod(request.Method), uri);
        message.Headers.TryAddWithoutValidation("Translate", "f");
        if (request.Body != null)
        {
            message.Content = new ByteArrayContent(request.Body);
        }

        using var response = _client.Send(message);
        return new WebDavResponse { Status = (int)response.StatusCode };
    }

    private static Uri Combine(Uri folder, string relative)
    {
        var baseUrl = folder.ToString().TrimEnd('/') + "/";
        return new Uri(baseUrl + relative.TrimStart('/'), UriKind.Absolute);
    }
}
