package dev.lndf.logonmc.crypto;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Passwords players may not use, read from the .txt files of a directory (one password per line).
 *
 * <p>Entries are kept as sorted 64-bit FNV-1a hashes rather than strings, so even lists with
 * millions of entries take about 8 bytes each. A false match needs a 64-bit collision, which is
 * negligible at these sizes and would only refuse a password, never accept one.
 */
public final class UnsafePasswords {

    private record Snapshot(long[] hashes, boolean caseSensitive) {
    }

    private volatile Snapshot snapshot = new Snapshot(new long[0], false);
    private volatile boolean loaded;

    /**
     * Replaces the list with the contents of every .txt file in {@code directory}. Blank lines are
     * skipped and surrounding whitespace is ignored. Files need not be valid UTF-8.
     *
     * @return the number of distinct entries loaded
     */
    public int load(Path directory, boolean caseSensitive) throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing
                    .filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted()
                    .toList();
        }
        long[] hashes = new long[1024];
        int count = 0;
        for (Path file : files) {
            try (BufferedReader reader = reader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String password = line.strip();
                    if (password.isEmpty()) {
                        continue;
                    }
                    if (count == hashes.length) {
                        hashes = Arrays.copyOf(hashes, hashes.length * 2);
                    }
                    hashes[count++] = hash(normalize(password, caseSensitive));
                }
            }
        }
        long[] sorted = Arrays.copyOf(hashes, count);
        Arrays.sort(sorted);
        int distinct = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || sorted[i] != sorted[i - 1]) {
                sorted[distinct++] = sorted[i];
            }
        }
        snapshot = new Snapshot(Arrays.copyOf(sorted, distinct), caseSensitive);
        loaded = true;
        return distinct;
    }

    public boolean contains(String password) {
        Snapshot current = snapshot;
        return current.hashes().length > 0
                && Arrays.binarySearch(current.hashes(), hash(normalize(password, current.caseSensitive()))) >= 0;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public int size() {
        return snapshot.hashes().length;
    }

    private static BufferedReader reader(Path file) throws IOException {
        // Leaked-password lists often mix encodings; undecodable bytes just won't match anything.
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)));
    }

    private static String normalize(String password, boolean caseSensitive) {
        return caseSensitive ? password : password.toLowerCase(Locale.ROOT);
    }

    private static long hash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
