package dev.jumpwatch.serverfabric.host.instance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class InstanceLogBuffer {

    private final int maxLines;
    private final Deque<String> recentLines = new ArrayDeque<>();
    private final StringBuilder partialLine = new StringBuilder(1024);
    private final Object lock = new Object();

    public InstanceLogBuffer(int maxLines) {
        this.maxLines = Math.max(1, maxLines);
    }

    public void appendChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;

        synchronized (lock) {
            partialLine.append(chunk);

            while (true) {
                int nl = partialLine.indexOf("\n");
                if (nl < 0) break;

                String line = partialLine.substring(0, nl).replace("\r", "");
                addLineInternal(line);
                partialLine.delete(0, nl + 1);
            }

            // prevent unbounded growth if no newline ever appears
            if (partialLine.length() > 32_000) {
                partialLine.delete(0, partialLine.length() - 4_000);
            }
        }
    }

    public void clear() {
        synchronized (lock) {
            recentLines.clear();
            partialLine.setLength(0);
        }
    }

    public List<String> snapshot() {
        synchronized (lock) {
            List<String> out = new ArrayList<>(recentLines);
            if (partialLine.length() > 0) {
                out.add(partialLine.toString());
            }
            return out;
        }
    }

    private void addLineInternal(String line) {
        recentLines.addLast(line);
        while (recentLines.size() > maxLines) {
            recentLines.removeFirst();
        }
    }
}
