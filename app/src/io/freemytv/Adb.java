package io.freemytv;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * ADB client that talks to the TV itself (127.0.0.1:5555, open while debugging is on). It's what lets the app switch
 * the home screen and the screensaver, and turn off apps, without a computer. The first time, the TV asks
 * "Allow USB debugging?" and you accept with "Always allow". Only HomeMode uses it, and only with its own fixed
 * commands (nothing from outside).
 * Protocol: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/protocol.txt
 */
final class Adb implements Closeable {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257, STLS = 0x534c5453;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** Start of the PKCS#1 block of a SHA-1 signature (adbd treats the token as if it were the digest). */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    /** Notified when the TV is showing the permission prompt (so the screen can explain what to do). */
    interface Approval { void waiting(); }

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private static final int STLS_VERSION = 0x01000000;
    /** DER AlgorithmIdentifier for sha256WithRSAEncryption (OID 1.2.840.113549.1.1.11 + NULL). */
    private static final byte[] SHA256_RSA_ALG = {
        0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b, 0x05, 0x00
    };

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private Context restoreWifiContext;
    private int nextLocalId = 1;

    private Adb(Socket socket) throws IOException {
        this.socket = socket;
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    /** Connects and authenticates. If the TV doesn't know our key yet, waits up to `approvalMs` for you to accept. */
    static Adb connect(Context c, int approvalMs, Approval approval) throws IOException {
        KeyPair keys = keys(c);
        Context restoreWifiOnClose = null;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        } catch (IOException e) {
            s.close();
            if (c == null || android.os.Build.VERSION.SDK_INT < 30) {
                throw new IOException(Ui.tr("debugging is off on the TV (port 5555 closed)"));
            }
            TlsSocket tls = connectTlsSocket(c);
            s = tls.socket;
            restoreWifiOnClose = tls.restoreWifiContext;
        }
        Adb adb;
        try {
            s.setSoTimeout(10_000);
            adb = new Adb(s);
            adb.restoreWifiContext = restoreWifiOnClose;
        } catch (IOException | RuntimeException e) {
            try { s.close(); } catch (IOException ignored) {}
            if (restoreWifiOnClose != null) setWifiAdb(restoreWifiOnClose, false);
            throw e;
        }
        try {
            adb.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            boolean signed = false, offeredKey = false;
            while (true) {
                Message m;
                try {
                    m = adb.read();
                } catch (EOFException | SocketTimeoutException e) {
                    adb.close();
                    throw new IOException(offeredKey ? Ui.tr("debugging permission wasn't accepted on the TV") : Ui.tr("the TV's ADB didn't respond"));
                }
                if (m.command == CNXN) {
                    adb.socket.setSoTimeout(20_000);
                    return adb;
                }
                if (m.command == STLS) {
                    return adb.completeTlsHandshake(keys);
                }
                if (m.command != AUTH || m.arg0 != AUTH_TOKEN) {
                    adb.close();
                    throw new IOException(Ui.tr("unexpected reply from ADB"));
                }
                if (!signed) {
                    adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                    signed = true;
                } else if (!offeredKey) {
                    // Unknown key: send the public one; the TV shows "Allow USB debugging?".
                    adb.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(keys.getPublic()));
                    offeredKey = true;
                    if (approval != null) approval.waiting();
                    if (Build.VERSION.SDK_INT >= 30) {
                        s.setSoTimeout(approvalMs);
                    } else {
                        // Android 8 to 10 hand the answer to the first connection that offered a key (on 10, or signed
                        // in) while it stays open, often the computer's adb (#221). "Always allow" saves our key, so a
                        // new connection that only signs gets in.
                        long until = SystemClock.elapsedRealtime() + approvalMs;
                        while (adb.in.available() == 0) {
                            if (SystemClock.elapsedRealtime() > until) {
                                throw new IOException(Ui.tr("debugging permission wasn't accepted on the TV"));
                            }
                            SystemClock.sleep(2000);
                            Adb fresh = signedIn(keys);
                            if (fresh != null) {
                                adb.close();
                                return fresh;
                            }
                        }
                    }
                } else {
                    throw new IOException(Ui.tr("the TV rejected the app's key"));
                }
            }
        } catch (IOException | RuntimeException e) {
            adb.close();
            throw e;
        }
    }

    /** A new connection that only signs (it never offers the key, so no second prompt); null while the TV doesn't know it. */
    private static Adb signedIn(KeyPair keys) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            s.setSoTimeout(5000);
            Adb adb = new Adb(s);
            adb.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Message m = adb.read();
            if (m.command == AUTH && m.arg0 == AUTH_TOKEN) {
                adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                m = adb.read();
            }
            if (m.command == CNXN) {
                s.setSoTimeout(20_000);
                return adb;
            }
        } catch (IOException | RuntimeException ignored) {
        }
        try { s.close(); } catch (IOException ignored) {}
        return null;
    }

    /** Runs a command in the TV's shell and returns its output (stdout and stderr together). */
    String shell(String command) throws IOException {
        int local = nextLocalId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = 0;
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == OKAY) {
                remote = m.arg0;
            } else if (m.command == WRTE) {
                result.write(m.data);
                send(OKAY, local, m.arg0, null);
            } else if (m.command == CLSE) {
                if (remote == 0 && m.arg0 == 0) throw new IOException(Ui.tr("ADB refused the command"));
                send(CLSE, local, m.arg0, null);
                return result.toString("UTF-8");
            }
        }
    }

    @Override public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        } finally {
            Context c = restoreWifiContext;
            restoreWifiContext = null;
            if (c != null) setWifiAdb(c, false);
        }
    }

    private Adb completeTlsHandshake(KeyPair keys) throws IOException {
        try {
            send(STLS, STLS_VERSION, 0, null);
            out.flush();
            javax.net.ssl.SSLSocketFactory sf = tlsSocketFactory(keys);
            java.net.InetAddress addr = socket.getInetAddress();
            String host = addr != null ? addr.getHostAddress() : "127.0.0.1";
            javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) sf.createSocket(socket, host, socket.getPort(), true);
            ssl.setUseClientMode(true);
            ssl.setSoTimeout(10_000);
            ssl.startHandshake();
            this.socket = ssl;
            this.in = new DataInputStream(ssl.getInputStream());
            this.out = ssl.getOutputStream();
            Message m = read();
            if (m.command != CNXN) throw new IOException(Ui.tr("unexpected reply from ADB"));
            ssl.setSoTimeout(20_000);
            return this;
        } catch (javax.net.ssl.SSLException | EOFException e) {
            close();
            throw new IOException(Ui.tr("the TV rejected the app's key"), e);
        } catch (SocketTimeoutException e) {
            close();
            throw new IOException(Ui.tr("the TV's ADB didn't respond"), e);
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    private static final class TlsSocket {
        final Socket socket;
        final Context restoreWifiContext;

        TlsSocket(Socket socket, Context restoreWifiContext) {
            this.socket = socket;
            this.restoreWifiContext = restoreWifiContext;
        }
    }

    private static TlsSocket connectTlsSocket(Context c) throws IOException {
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        boolean tempWifi = false;
        if (android.provider.Settings.Global.getInt(app.getContentResolver(), "adb_wifi_enabled", 0) == 0) {
            tempWifi = setWifiAdb(app, true);
            if (!tempWifi) throw new IOException(Ui.tr("debugging is off on the TV (port 5555 closed)"));
        }
        try {
            InetSocketAddress endpoint = discoverLocalTlsEndpoint(app, tempWifi ? 6_000 : 3_500);
            if (endpoint == null) throw new IOException(Ui.tr("debugging is off on the TV (port 5555 closed)"));
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", endpoint.getPort()), 3_000);
            } catch (IOException loopbackFailed) {
                try { s.close(); } catch (IOException ignored) {}
                s = new Socket();
                try {
                    s.connect(endpoint, 3_000);
                } catch (IOException directFailed) {
                    try { s.close(); } catch (IOException ignored) {}
                    throw directFailed;
                }
            }
            return new TlsSocket(s, tempWifi ? app : null);
        } catch (IOException | RuntimeException e) {
            if (tempWifi) setWifiAdb(app, false);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e);
        }
    }

    private static boolean setWifiAdb(Context c, boolean enabled) {
        if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            return android.provider.Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", enabled ? 1 : 0);
        } catch (SecurityException e) {
            return false;
        }
    }

    private static InetSocketAddress discoverLocalTlsEndpoint(Context c, long timeoutMs) {
        android.net.nsd.NsdManager nsd = c.getSystemService(android.net.nsd.NsdManager.class);
        if (nsd == null) return null;
        final TlsDiscovery state = new TlsDiscovery(nsd, localInterfaceAddresses());
        android.net.nsd.NsdManager.DiscoveryListener discovery = new android.net.nsd.NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) {}
            @Override public void onDiscoveryStopped(String serviceType) {}
            @Override public void onServiceLost(android.net.nsd.NsdServiceInfo info) {}
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) { state.abort(); }
            @Override public void onServiceFound(android.net.nsd.NsdServiceInfo info) { state.enqueue(info); }
        };
        try {
            nsd.discoverServices("_adb-tls-connect._tcp.", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, discovery);
            return state.await(timeoutMs);
        } catch (RuntimeException e) {
            return null;
        } finally {
            try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) {}
        }
    }

    private static final class TlsDiscovery {
        private final android.net.nsd.NsdManager nsd;
        private final java.util.List<byte[]> localAddrs;
        private final java.util.Deque<android.net.nsd.NsdServiceInfo> queue = new java.util.ArrayDeque<>();
        private boolean resolving;
        private boolean done;
        private InetSocketAddress found;

        TlsDiscovery(android.net.nsd.NsdManager nsd, java.util.List<byte[]> localAddrs) {
            this.nsd = nsd;
            this.localAddrs = localAddrs;
        }

        void abort() {
            synchronized (this) {
                done = true;
                notifyAll();
            }
        }

        void enqueue(android.net.nsd.NsdServiceInfo info) {
            android.net.nsd.NsdServiceInfo toResolve = null;
            synchronized (this) {
                if (done) return;
                queue.addLast(info);
                if (!resolving) {
                    toResolve = queue.pollFirst();
                    resolving = toResolve != null;
                }
            }
            if (toResolve != null) resolve(toResolve);
        }

        @SuppressWarnings("deprecation")
        private void resolve(android.net.nsd.NsdServiceInfo target) {
            try {
                nsd.resolveService(target, new android.net.nsd.NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(android.net.nsd.NsdServiceInfo info, int errorCode) {
                        advance(null);
                    }
                    @Override public void onServiceResolved(android.net.nsd.NsdServiceInfo info) {
                        java.net.InetAddress host = info.getHost();
                        int port = info.getPort();
                        InetSocketAddress match = (host != null && port > 0 && isLocalAddress(host, localAddrs))
                                ? new InetSocketAddress(host, port) : null;
                        advance(match);
                    }
                });
            } catch (RuntimeException e) {
                advance(null);
            }
        }

        private void advance(InetSocketAddress match) {
            android.net.nsd.NsdServiceInfo next = null;
            synchronized (this) {
                resolving = false;
                if (match != null && !done) {
                    found = match;
                    done = true;
                    notifyAll();
                    return;
                }
                if (!done && !queue.isEmpty()) {
                    next = queue.pollFirst();
                    resolving = next != null;
                }
            }
            if (next != null) resolve(next);
        }

        InetSocketAddress await(long timeoutMs) {
            long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
            synchronized (this) {
                while (!done) {
                    long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                    if (remaining <= 0) break;
                    try {
                        wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                done = true;
                return found;
            }
        }
    }

    private static java.util.List<byte[]> localInterfaceAddresses() {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nifs = java.net.NetworkInterface.getNetworkInterfaces();
            while (nifs != null && nifs.hasMoreElements()) {
                java.net.NetworkInterface ni = nifs.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) out.add(addrs.nextElement().getAddress());
            }
        } catch (IOException | RuntimeException ignored) {}
        return out;
    }

    private static boolean isLocalAddress(java.net.InetAddress host, java.util.List<byte[]> localAddrs) {
        if (host.isLoopbackAddress()) return true;
        byte[] target = host.getAddress();
        for (byte[] local : localAddrs) {
            if (java.util.Arrays.equals(target, local)) return true;
        }
        return false;
    }

    static javax.net.ssl.SSLSocketFactory tlsSocketFactory(KeyPair keys) throws IOException {
        try {
            final java.security.cert.X509Certificate cert = selfSignedCert(keys);
            final java.security.PrivateKey priv = keys.getPrivate();
            javax.net.ssl.KeyManager km = new javax.net.ssl.X509ExtendedKeyManager() {
                private final String[] aliases = { "adb" };
                @Override public String[] getClientAliases(String keyType, java.security.Principal[] issuers) {
                    return "RSA".equalsIgnoreCase(keyType) ? aliases : null;
                }
                @Override public String chooseClientAlias(String[] keyTypes, java.security.Principal[] issuers, Socket socket) {
                    return "adb";
                }
                @Override public String[] getServerAliases(String keyType, java.security.Principal[] issuers) { return null; }
                @Override public String chooseServerAlias(String keyType, java.security.Principal[] issuers, Socket socket) { return null; }
                @Override public java.security.cert.X509Certificate[] getCertificateChain(String alias) {
                    return "adb".equals(alias) ? new java.security.cert.X509Certificate[]{ cert } : null;
                }
                @Override public java.security.PrivateKey getPrivateKey(String alias) {
                    return "adb".equals(alias) ? priv : null;
                }
            };
            // adbd uses an ephemeral self-signed certificate; TLS mutual auth verifies our authorized RSA key
            // while connecting strictly to a verified local loopback / device interface.
            javax.net.ssl.TrustManager tm = new javax.net.ssl.X509TrustManager() {
                @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            };
            javax.net.ssl.SSLContext sslCtx;
            try {
                sslCtx = javax.net.ssl.SSLContext.getInstance("TLSv1.3");
            } catch (java.security.NoSuchAlgorithmException ignored) {
                sslCtx = javax.net.ssl.SSLContext.getInstance("TLS");
            }
            sslCtx.init(new javax.net.ssl.KeyManager[]{ km }, new javax.net.ssl.TrustManager[]{ tm }, new java.security.SecureRandom());
            return sslCtx.getSocketFactory();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    static java.security.cert.X509Certificate selfSignedCert(KeyPair keys) throws IOException {
        try {
            byte[] version = der(0xA0, new byte[]{ 0x02, 0x01, 0x02 });
            byte[] serial = new byte[]{ 0x02, 0x01, 0x01 };
            byte[] cnOid = new byte[]{ 0x06, 0x03, 0x55, 0x04, 0x03 };
            byte[] cnVal = der(0x0C, "FreeMyTV".getBytes(StandardCharsets.UTF_8));
            byte[] name = der(0x30, der(0x31, der(0x30, cnOid, cnVal)));
            byte[] notBefore = der(0x17, "700101000000Z".getBytes(StandardCharsets.US_ASCII));
            byte[] notAfter = der(0x17, "491231235959Z".getBytes(StandardCharsets.US_ASCII));
            byte[] validity = der(0x30, notBefore, notAfter);
            byte[] tbs = der(0x30, version, serial, SHA256_RSA_ALG, name, validity, name, keys.getPublic().getEncoded());

            java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
            sig.initSign(keys.getPrivate());
            sig.update(tbs);
            byte[] rawSig = sig.sign();
            byte[] sigBits = der(0x03, new byte[]{ 0x00 }, rawSig);
            byte[] certDer = der(0x30, tbs, SHA256_RSA_ALG, sigBits);

            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            return (java.security.cert.X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(certDer));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] der(int tag, byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(len + 6);
        bos.write(tag);
        if (len < 128) {
            bos.write(len);
        } else if (len <= 0xff) {
            bos.write(0x81);
            bos.write(len);
        } else if (len <= 0xffff) {
            bos.write(0x82);
            bos.write((len >> 8) & 0xff);
            bos.write(len & 0xff);
        } else {
            bos.write(0x83);
            bos.write((len >> 16) & 0xff);
            bos.write((len >> 8) & 0xff);
            bos.write(len & 0xff);
        }
        for (byte[] p : parts) bos.write(p, 0, p.length);
        return bos.toByteArray();
    }

    private Message read() throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int length = b.getInt();
        b.getInt(); // checksum (newer adbd doesn't check it)
        int magic = b.getInt();
        if ((m.command ^ 0xffffffff) != magic) throw new IOException(Ui.tr("invalid ADB message"));
        if (length < 0 || length > 1024 * 1024) throw new IOException(Ui.tr("ADB message too large"));
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int length = data == null ? 0 : data.length;
        int sum = 0;
        if (data != null) for (byte x : data) sum += x & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(length).putInt(sum).putInt(command ^ 0xffffffff);
        if (data != null) b.put(data);
        out.write(b.array());
        out.flush();
    }

    // MARK: - The app's key (kept in private storage; never goes to the log)

    private static synchronized KeyPair keys(Context c) throws IOException {
        File dir = c.getFilesDir();
        File privateFile = new File(dir, "adbkey"), publicFile = new File(dir, "adbkey.pub");
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            if (privateFile.exists() && publicFile.exists()) {
                try {
                    return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(readFile(publicFile))),
                            factory.generatePrivate(new PKCS8EncodedKeySpec(readFile(privateFile))));
                } catch (GeneralSecurityException | IOException e) {
                    // Corrupted file (the power went out mid-write): new key; the TV asks again.
                    AppLog.w("ADB key unreadable, generating a new one");
                }
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            writeFile(privateFile, pair.getPrivate().getEncoded());
            writeFile(publicFile, pair.getPublic().getEncoded());
            return pair;
        } catch (GeneralSecurityException e) {
            throw new IOException("ADB key: " + e.getMessage());
        }
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            byte[] block = new byte[256];
            block[1] = 1;
            int separator = block.length - SHA1_PREFIX.length - token.length - 1;
            for (int i = 2; i < separator; i++) block[i] = (byte) 0xff;
            System.arraycopy(SHA1_PREFIX, 0, block, separator + 1, SHA1_PREFIX.length);
            System.arraycopy(token, 0, block, separator + 1 + SHA1_PREFIX.length, token.length);
            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IOException("ADB signature: " + e.getMessage());
        }
    }

    /** Public key in adbd's format (RSAPublicKey struct in base64, plus a name). */
    private static byte[] publicKeyBlob(PublicKey key) {
        RSAPublicKey rsa = (RSAPublicKey) key;
        BigInteger n = rsa.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, 256));
        b.put(littleEndian(rr, 256));
        b.putInt(rsa.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " free-my-tv@tv\0";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] littleEndian(BigInteger value, int size) {
        byte[] big = value.toByteArray();
        byte[] little = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) little[i] = big[big.length - 1 - i];
        return little;
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    /** Writes all or nothing: to a file alongside, synced to disk, and only then renamed to the real name. */
    private static void writeFile(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("could not save " + f.getName());
    }
}
