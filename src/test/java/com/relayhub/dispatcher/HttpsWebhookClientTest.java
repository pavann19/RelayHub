package com.relayhub.dispatcher;

import com.relayhub.security.WebhookTargetPolicy;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** A real CA-signed HTTPS receiver, with no JVM-global truststore changes. */
class HttpsWebhookClientTest {
    @TempDir static Path directory;
    static HttpsServer server;
    static SSLContext trustedClient;
    static final char[] PASSWORD = "ephemeral-test-only".toCharArray();
    static final AtomicReference<String> sni = new AtomicReference<>();

    @BeforeAll static void start() throws Exception {
        String ca=directory.resolve("ca.p12").toString(), leaf=directory.resolve("leaf.p12").toString();
        String certificate=directory.resolve("ca.pem").toString(), csr=directory.resolve("leaf.csr").toString();
        String signed=directory.resolve("leaf.pem").toString();
        keytool("-genkeypair","-alias","ca","-keystore",ca,"-dname","CN=RelayHub Ephemeral Test CA",
                "-keyalg","RSA","-keysize","2048","-ext","bc=ca:true","-validity","2");
        keytool("-exportcert","-rfc","-alias","ca","-keystore",ca,"-file",certificate);
        keytool("-genkeypair","-alias","leaf","-keystore",leaf,"-dname","CN=webhook.test",
                "-keyalg","RSA","-keysize","2048","-ext","SAN=dns:webhook.test","-validity","2");
        keytool("-certreq","-alias","leaf","-keystore",leaf,"-file",csr);
        keytool("-gencert","-rfc","-alias","ca","-keystore",ca,"-infile",csr,"-outfile",signed,
                "-ext","SAN=dns:webhook.test","-ext","EKU=serverAuth","-validity","2");
        keytool("-importcert","-noprompt","-alias","ca","-keystore",leaf,"-file",certificate);
        keytool("-importcert","-alias","leaf","-keystore",leaf,"-file",signed);
        KeyStore serverKeys=load(Path.of(leaf));
        var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(serverKeys,PASSWORD);
        SSLContext serverTls=SSLContext.getInstance("TLS");serverTls.init(keys.getKeyManagers(),null,null);
        KeyStore trust=KeyStore.getInstance("PKCS12");trust.load(null,null);
        trust.setCertificateEntry("ca",load(Path.of(ca)).getCertificate("ca"));
        var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(trust);
        trustedClient=SSLContext.getInstance("TLS");trustedClient.init(null,managers.getTrustManagers(),null);
        server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        server.createContext("/",exchange->{
            if(exchange instanceof HttpsExchange https && https.getSSLSession() instanceof ExtendedSSLSession session) {
                sni.set(session.getRequestedServerNames().stream().filter(n->n instanceof SNIHostName)
                        .map(n->((SNIHostName)n).getAsciiName()).findFirst().orElse("missing"));
            }
            exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,2);
            exchange.getResponseBody().write("ok".getBytes());exchange.close();
        });server.start();
    }

    @AfterAll static void stop() { if(server!=null) server.stop(0); }

    @Test void trustedCaAndCorrectHostnameSucceedWithOriginalSni() {
        try(var client=client(trustedClient,"webhook.test")) {
            assertEquals(200,send(client,"webhook.test").statusCode());
            assertEquals("webhook.test",sni.get());
        }
    }
    @Test void defaultTrustRejectsUnknownCa() {
        try(var client=client(null,"webhook.test")) { assertEquals(0,send(client,"webhook.test").statusCode()); }
    }
    @Test void trustedCaDoesNotDisableHostnameVerification() {
        try(var client=client(trustedClient,"wrong.test")) { assertEquals(0,send(client,"wrong.test").statusCode()); }
    }
    @Test void wrongTrustAnchorRejectsServer() throws Exception {
        KeyStore empty=KeyStore.getInstance("PKCS12");empty.load(null,null);
        var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(empty);
        SSLContext untrusted=SSLContext.getInstance("TLS");untrusted.init(null,managers.getTrustManagers(),null);
        try(var client=client(untrusted,"webhook.test")) { assertEquals(0,send(client,"webhook.test").statusCode()); }
    }
    static HttpWebhookClient client(SSLContext tls,String host) {
        var client=new HttpWebhookClient(tls);
        client.setTargetPolicy(new WebhookTargetPolicy(host,true) {
            @Override protected InetAddress[] resolveHost(String value) throws UnknownHostException {
                return new InetAddress[]{InetAddress.getByAddress(new byte[]{127,0,0,1})};
            }
        });return client;
    }
    static HttpResponseSummary send(HttpWebhookClient client,String host) {
        return client.sendWebhook("https://"+host+":"+server.getAddress().getPort()+"/","{}","signature","tls.test","tls-delivery",3000);
    }
    static KeyStore load(Path path) throws Exception {
        KeyStore keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(path)){keys.load(input,PASSWORD);}return keys;
    }
    static void keytool(String... args) throws Exception {
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        var command=new ArrayList<String>();command.add(executable);command.addAll(List.of(args));
        command.addAll(List.of("-storepass",new String(PASSWORD),"-keypass",new String(PASSWORD)));
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes());
        assertEquals(0,process.waitFor(),output);
    }
}
