package dev.midnightbeam;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Minimal ADB client for the device's own ADB daemon (127.0.0.1). It only opens a shell and reads the output:
 * no pairing key, no host key, no port forwarding. When the daemon asks for authorisation the call fails, so it
 * is used only on devices whose ADB needs none (ro.adb.secure=0).
 */
final class AdbClient {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_WRTE = 0x45545257;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_AUTH = 0x48545541;
    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 256 * 1024;

    private AdbClient() {
    }

    static String shell(int port, String command) throws IOException {
        return shell("127.0.0.1", port, command);
    }

    static String shell(String host, int port, String command) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName(host), port), 3000);
            s.setSoTimeout(15000);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(out, A_CNXN, VERSION, MAX_DATA, bytes("host::\u0000"));
            while (true) {
                Packet p = read(in);
                if (p.command == A_CNXN) break;
                if (p.command == A_AUTH) throw new IOException("adb authorisation required");
                if (p.command == A_CLSE) throw new IOException("adb refused the connection");
            }
            send(out, A_OPEN, 1, 0, bytes("shell:" + command + "\u0000"));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            while (true) {
                Packet p;
                try {
                    p = read(in);
                } catch (EOFException e) {
                    break;
                }
                if (p.command == A_OKAY) {
                    // the device opened our stream: nothing to do until output arrives
                } else if (p.command == A_WRTE) {
                    result.write(p.data);
                    send(out, A_OKAY, 1, p.arg0, new byte[0]);
                } else if (p.command == A_CLSE) {
                    break;
                }
            }
            return new String(result.toByteArray(), "UTF-8");
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void send(DataOutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        writeInt(out, command);
        writeInt(out, arg0);
        writeInt(out, arg1);
        writeInt(out, data.length);
        writeInt(out, checksum(data));
        writeInt(out, command ^ 0xffffffff);
        out.write(data);
        out.flush();
    }

    private static Packet read(DataInputStream in) throws IOException {
        Packet p = new Packet();
        p.command = readInt(in);
        p.arg0 = readInt(in);
        p.arg1 = readInt(in);
        int length = readInt(in);
        readInt(in);
        readInt(in);
        if (length < 0 || length > MAX_DATA) throw new IOException("bad adb packet length " + length);
        p.data = new byte[length];
        in.readFully(p.data);
        return p;
    }

    /** The ADB wire format is little-endian, unlike DataOutputStream. */
    private static void writeInt(DataOutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    private static int readInt(DataInputStream in) throws IOException {
        return in.readUnsignedByte() | (in.readUnsignedByte() << 8)
                | (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 24);
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        return sum;
    }

    private static byte[] bytes(String s) throws IOException {
        return s.getBytes("UTF-8");
    }

    private static final class Packet {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }
}
