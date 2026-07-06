package org.evilproject.evilkaraoke.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Queue;

final class PacketAudioInputStream extends InputStream {
    private static final int MAX_QUEUED_BYTES = 128 * 1024 * 1024;

    private final Queue<byte[]> chunks = new ArrayDeque<>();
    private int chunkOffset;
    private int queuedBytes;
    private long totalReceivedBytes;
    private long totalReadBytes;
    private boolean closed;
    private IOException failure;

    synchronized void append(byte[] data) throws IOException {
        if (closed) {
            return;
        }
        if (data.length == 0) {
            return;
        }
        if ((long) queuedBytes + data.length > MAX_QUEUED_BYTES) {
            fail("Server audio stream buffered too far ahead");
            throw failure;
        }
        chunks.add(data);
        queuedBytes += data.length;
        totalReceivedBytes += data.length;
        notifyAll();
    }

    synchronized void finish() {
        closed = true;
        notifyAll();
    }

    synchronized void fail(String message) {
        failure = new IOException(message == null || message.isBlank() ? "Server audio stream failed" : message);
        closed = true;
        notifyAll();
    }

    @Override
    public synchronized int read() throws IOException {
        byte[] single = new byte[1];
        int read = read(single, 0, 1);
        return read == -1 ? -1 : single[0] & 0xFF;
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }
        while (chunks.isEmpty() && !closed && failure == null) {
            try {
                wait();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for server audio", ex);
            }
        }
        if (failure != null) {
            throw failure;
        }
        byte[] chunk = chunks.peek();
        if (chunk == null) {
            return -1;
        }
        int copied = Math.min(length, chunk.length - chunkOffset);
        System.arraycopy(chunk, chunkOffset, buffer, offset, copied);
        chunkOffset += copied;
        queuedBytes -= copied;
        totalReadBytes += copied;
        if (chunkOffset >= chunk.length) {
            chunks.remove();
            chunkOffset = 0;
        }
        return copied;
    }

    @Override
    public synchronized void close() {
        closed = true;
        chunks.clear();
        queuedBytes = 0;
        chunkOffset = 0;
        notifyAll();
    }

    synchronized StreamStats stats() {
        return new StreamStats(totalReceivedBytes, totalReadBytes, queuedBytes);
    }

    record StreamStats(long bytesReceived, long bytesRead, int queuedBytes) {
    }
}
