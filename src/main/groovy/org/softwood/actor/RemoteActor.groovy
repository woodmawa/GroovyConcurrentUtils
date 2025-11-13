package org.softwood.actor

import groovy.transform.CompileStatic

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@CompileStatic
class RemoteActor extends ScopedValueActor<Object> {

    private final URI endpoint
    private final HttpClient client

    RemoteActor(String name, URI endpoint) {
        super(name, new ScopedValueActor.MessageHandler<Object>() {
            @Override
            Object handle(Object msg, ScopedValueActor.ActorContext ctx) {
                RemoteActor self = (RemoteActor) ctx.actor
                return self.sendRemote(msg)
            }
        })
        this.endpoint = endpoint
        this.client = HttpClient.newHttpClient()
    }

    private Object sendRemote(Object msg) {
        String payload = (msg instanceof String) ? (String) msg : msg.toString()

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .header("Content-Type", "text/plain")
                .build()

        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString())
            return response.body()
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new RuntimeException("RemoteActor '${getName()}' HTTP call failed", e)
        }
    }

    URI getEndpoint() { endpoint }
}
