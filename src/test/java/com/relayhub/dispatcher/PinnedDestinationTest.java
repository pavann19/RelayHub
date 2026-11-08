package com.relayhub.dispatcher;

import com.relayhub.security.WebhookTargetPolicy;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PinnedDestinationTest {
    @Test void usesOnlyPolicyResolvedAddressAndRetainsHostname() throws Exception {
        var hits = new AtomicInteger();
        var lookups = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", request -> {
            assertTrue(request.getRequestHeaders().getFirst("Host").startsWith("pinned.invalid:"));
            request.getRequestBody().readAllBytes(); hits.incrementAndGet();
            request.sendResponseHeaders(200, 2); request.getResponseBody().write("ok".getBytes()); request.close();
        });
        server.start();
        try (var client = new HttpWebhookClient()) {
            client.setTargetPolicy(new WebhookTargetPolicy("pinned.invalid", true) {
                @Override protected InetAddress[] resolveHost(String host) throws UnknownHostException {
                    if (lookups.incrementAndGet() > 1) throw new UnknownHostException("Second DNS lookup forbidden");
                    return new InetAddress[]{InetAddress.getByAddress(new byte[]{127,0,0,1})};
                }
            });
            var response = client.sendWebhook("http://pinned.invalid:" + server.getAddress().getPort() + "/", "{}", "sig", "test", "delivery", 2000);
            assertEquals(200, response.statusCode(), response.errorMessage());
            assertEquals(1, hits.get()); assertEquals(1, lookups.get());
        } finally { server.stop(0); }
    }
    @Test void rejectsReboundPrivateAddressBeforeConnecting() throws Exception {
        try (var client = new HttpWebhookClient()) {
            client.setTargetPolicy(new WebhookTargetPolicy("pinned.invalid", false) {
                @Override protected InetAddress[] resolveHost(String host) throws UnknownHostException {
                    return new InetAddress[]{InetAddress.getByAddress(new byte[]{127,0,0,1})};
                }
            });
            assertEquals(0, client.sendWebhook("http://pinned.invalid:8080/", "{}", "sig", "test", "delivery", 500).statusCode());
        }
    }
    @Test void boundsSlowResponseWithAttemptDeadline() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",request->{
            request.getRequestBody().readAllBytes();request.sendResponseHeaders(200,2);
            try { Thread.sleep(2000); request.getResponseBody().write("ok".getBytes()); }
            catch(InterruptedException error){Thread.currentThread().interrupt();} catch(java.io.IOException ignored){}
            finally{request.close();}
        });server.start();
        try(var client=new HttpWebhookClient()){
            client.setTargetPolicy(new WebhookTargetPolicy("127.0.0.1",true));
            long start=System.nanoTime();
            var result=client.sendWebhook("http://127.0.0.1:"+server.getAddress().getPort()+"/","{}","sig","test","deadline",500);
            assertEquals(0,result.statusCode());
            assertTrue((System.nanoTime()-start)/1_000_000<1800);
        }finally{server.stop(0);}
    }

}
