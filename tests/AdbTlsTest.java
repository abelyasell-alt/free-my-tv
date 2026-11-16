package io.freemytv;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public final class AdbTlsTest {
    private static int assertions;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair clientKeys = kpg.generateKeyPair();
        KeyPair serverKeys = kpg.generateKeyPair();

        // 1. Verify self-signed X.509 certificate structure, dates, and cryptographic signature.
        X509Certificate clientCert = Adb.selfSignedCert(clientKeys);
        check(clientCert.getVersion() == 3, "X.509 certificate version should be 3");
        check(clientCert.getSubjectX500Principal().getName().contains("FreeMyTV"), "subject CN missing");
        check(Arrays.equals(clientCert.getPublicKey().getEncoded(), clientKeys.getPublic().getEncoded()),
                "certificate public key mismatch");
        clientCert.verify(clientKeys.getPublic());
        assertions++;
        clientCert.checkValidity(new Date(0L));
        clientCert.checkValidity(new Date());
        clientCert.checkValidity(new Date(2524521599_000L)); // 2049-12-31
        assertions += 3;

        // 2. Verify STLS upgrade: plaintext exchange -> TLSv1.3 mutual auth over the same TCP socket.
        X509Certificate serverCert = Adb.selfSignedCert(serverKeys);
        char[] pass = "test".toCharArray();
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, pass);
        ks.setKeyEntry("server", serverKeys.getPrivate(), pass, new Certificate[]{ serverCert });
        ks.setCertificateEntry("trusted-client", clientCert);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext serverCtx = SSLContext.getInstance("TLSv1.3");
        serverCtx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        final SSLSocketFactory serverSf = serverCtx.getSocketFactory();

        final Throwable[] serverErr = new Throwable[1];
        final byte[][] serverSeenPeerPub = new byte[1][];
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = listener.getLocalPort();
            Thread server = new Thread(() -> {
                try (Socket raw = listener.accept()) {
                    InputStream in = raw.getInputStream();
                    OutputStream out = raw.getOutputStream();
                    byte[] pre = new byte[4];
                    if (in.read(pre) != 4 || !"CNXN".equals(new String(pre, StandardCharsets.US_ASCII))) {
                        throw new AssertionError("missing pre-TLS CNXN");
                    }
                    out.write("STLS".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    byte[] ack = new byte[4];
                    if (in.read(ack) != 4 || !"STLS".equals(new String(ack, StandardCharsets.US_ASCII))) {
                        throw new AssertionError("missing client STLS");
                    }
                    SSLSocket ssl = (SSLSocket) serverSf.createSocket(raw, null, true);
                    ssl.setUseClientMode(false);
                    ssl.setNeedClientAuth(true);
                    ssl.setEnabledProtocols(new String[]{ "TLSv1.3" });
                    ssl.startHandshake();
                    SSLSession sess = ssl.getSession();
                    serverSeenPeerPub[0] = sess.getPeerCertificates()[0].getPublicKey().getEncoded();
                    ssl.getOutputStream().write("OKAY".getBytes(StandardCharsets.US_ASCII));
                    ssl.getOutputStream().flush();
                } catch (Throwable t) {
                    serverErr[0] = t;
                }
            });
            server.start();

            try (Socket raw = new Socket(InetAddress.getLoopbackAddress(), port)) {
                raw.setSoTimeout(5000);
                OutputStream out = raw.getOutputStream();
                InputStream in = raw.getInputStream();
                out.write("CNXN".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                byte[] stls = new byte[4];
                check(in.read(stls) == 4 && "STLS".equals(new String(stls, StandardCharsets.US_ASCII)),
                        "expected STLS from server");
                out.write("STLS".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                SSLSocketFactory clientSf = Adb.tlsSocketFactory(clientKeys);
                try (SSLSocket ssl = (SSLSocket) clientSf.createSocket(raw, "127.0.0.1", port, true)) {
                    ssl.setUseClientMode(true);
                    ssl.setSoTimeout(5000);
                    ssl.startHandshake();
                    check("TLSv1.3".equals(ssl.getSession().getProtocol()),
                            "expected TLSv1.3, got " + ssl.getSession().getProtocol());
                    byte[] ok = new byte[4];
                    check(ssl.getInputStream().read(ok) == 4 && "OKAY".equals(new String(ok, StandardCharsets.US_ASCII)),
                            "expected OKAY over TLS socket");
                }
            }
            server.join(5000);
        }
        check(serverErr[0] == null, "server error: " + serverErr[0]);
        check(Arrays.equals(serverSeenPeerPub[0], clientKeys.getPublic().getEncoded()),
                "server should see client's exact RSA public key");

        // 3. Verify untrusted client key is rejected during TLSv1.3 mutual auth.
        KeyPair untrustedKeys = kpg.generateKeyPair();
        final boolean[] serverRejected = new boolean[1];
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = listener.getLocalPort();
            Thread server = new Thread(() -> {
                try (Socket raw = listener.accept()) {
                    SSLSocket ssl = (SSLSocket) serverSf.createSocket(raw, null, true);
                    ssl.setUseClientMode(false);
                    ssl.setNeedClientAuth(true);
                    ssl.setEnabledProtocols(new String[]{ "TLSv1.3" });
                    ssl.startHandshake();
                    ssl.getInputStream().read();
                } catch (Throwable expected) {
                    serverRejected[0] = true;
                }
            });
            server.start();
            boolean clientFailed = false;
            try (Socket raw = new Socket(InetAddress.getLoopbackAddress(), port)) {
                raw.setSoTimeout(5000);
                SSLSocketFactory untrustedSf = Adb.tlsSocketFactory(untrustedKeys);
                try (SSLSocket ssl = (SSLSocket) untrustedSf.createSocket(raw, "127.0.0.1", port, true)) {
                    ssl.setUseClientMode(true);
                    ssl.setSoTimeout(5000);
                    ssl.startHandshake();
                    int b = ssl.getInputStream().read();
                    if (b < 0) clientFailed = true;
                }
            } catch (Exception expected) {
                clientFailed = true;
            }
            server.join(5000);
            check(serverRejected[0], "server should reject untrusted client certificate");
            check(clientFailed, "client should fail after TLS certificate rejection");
        }

        System.out.println("AdbTls: " + assertions + " assertions passed");
    }
}
