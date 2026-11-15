package com.chengyinhub.business.push;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JDK HttpClient，强制 HTTP/2（APNs 只接受 HTTP/2）。 */
public class JdkApnsTransport implements ApnsTransport
{
    private static final Pattern REASON = Pattern.compile("\"reason\"\\s*:\\s*\"([A-Za-z]+)\"");

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public ApnsResponse post(String host, String path, Map<String, String> headers, byte[] body) throws Exception
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://" + host + path))
                .version(HttpClient.Version.HTTP_2)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new ApnsResponse(response.statusCode(), reason(response.body()));
    }

    static String reason(String body)
    {
        if (body == null) return null;
        Matcher m = REASON.matcher(body);
        return m.find() ? m.group(1) : null;
    }
}
