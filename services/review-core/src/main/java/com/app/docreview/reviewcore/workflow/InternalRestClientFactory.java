package com.app.docreview.reviewcore.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Builds authenticated, timeout-bounded clients for worker calls. Architecture §9.2, §10.5.
 *
 * <p>Centralised so no call site can construct a client that lacks the internal token or has no
 * timeouts. An unbounded client is the failure that takes the whole service down: dispatch threads
 * block on a hung worker until the pool is exhausted, and then the public API stops answering too.
 *
 * <p>Built on the JDK's {@link HttpClient} via {@code spring-web}'s factory rather than Boot's
 * {@code ClientHttpRequestFactoryBuilder}: the latter lives in {@code spring-boot-http-client}, which
 * the web starter does not pull in, and adding a dependency to configure two timeouts is not worth
 * the extra surface. The JDK client is already present and does exactly this.
 *
 * <p>The two timeouts are set in different places because they mean different things.
 * {@code connectTimeout} belongs to the JDK client — it bounds establishing the TCP connection.
 * {@code readTimeout} belongs to the Spring factory — it bounds waiting for the response once
 * connected. Setting only one leaves the other unbounded, which is the hung-worker case above.
 *
 * <p>The token goes on a dedicated header rather than {@code Authorization}. Workers use it for
 * service identity only, and keeping it off the standard header avoids any chance of it reaching a
 * logger that redacts {@code Authorization} but nothing else.
 */
@Component
@RequiredArgsConstructor
public class InternalRestClientFactory {

    /** Service-identity header presented to workers. */
    public static final String TOKEN_HEADER = "X-Internal-Service-Token";

    private final JobDispatchProperties properties;

    public RestClient create(String baseUrl) {
        var httpClient = HttpClient.newBuilder()
                // HTTP/1.1 pinned deliberately, matching HttpEvidenceSpanReader. The JDK client
                // defaults to HTTP_2, which over cleartext sends an h2c upgrade preamble
                // (Connection: Upgrade / Upgrade: h2c). A server that does not negotiate it can
                // mis-parse the request and drop the BODY — producing a transport error or a
                // validation complaint about a field that was in fact sent, which sends debugging
                // at the payload instead of the protocol. Every service here speaks HTTP/1.1, so
                // nothing is given up.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                // Never follow redirects on an internal call. A redirect could send the internal
                // token to an address the dispatcher never chose.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(TOKEN_HEADER, properties.internalServiceToken())
                .build();
    }
}
