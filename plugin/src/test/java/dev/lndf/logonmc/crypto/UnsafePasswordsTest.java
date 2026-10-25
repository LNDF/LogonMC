package dev.lndf.logonmc.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnsafePasswordsTest {

    @TempDir
    Path dir;

    private final UnsafePasswords list = new UnsafePasswords();

    @Test
    void readsEveryTxtFileAndSkipsBlankLines() throws IOException {
        Files.writeString(dir.resolve("a.txt"), "hunter2\n\n  dragon  \r\nhunter2\n");
        Files.writeString(dir.resolve("b.TXT"), "letmein");
        Files.writeString(dir.resolve("notes.md"), "ignored_password");

        assertEquals(3, list.load(dir, true));
        assertTrue(list.contains("hunter2"));
        assertTrue(list.contains("dragon"));
        assertTrue(list.contains("letmein"));
        assertFalse(list.contains("ignored_password"));
        assertFalse(list.contains(""));
    }

    @Test
    void caseInsensitiveByDefaultSetting() throws IOException {
        Files.writeString(dir.resolve("a.txt"), "Password1\n");

        list.load(dir, false);
        assertTrue(list.contains("password1"));
        assertTrue(list.contains("PASSWORD1"));

        list.load(dir, true);
        assertTrue(list.contains("Password1"));
        assertFalse(list.contains("password1"));
    }

    @Test
    void toleratesInvalidUtf8() throws IOException {
        Files.write(dir.resolve("latin1.txt"), new byte[] {'c', 'a', 'f', (byte) 0xE9, '\n', 'o', 'k', '1', '2', '3', '4'});
        assertEquals(2, list.load(dir, true));
        assertTrue(list.contains("ok1234"));
    }

    @Test
    void reloadReplacesTheList() throws IOException {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "first1\n");
        list.load(dir, true);
        Files.writeString(file, "second\n");
        list.load(dir, true);
        assertFalse(list.contains("first1"));
        assertTrue(list.contains("second"));
    }

    @Test
    void bundledListLoads() throws IOException {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("unsafe_lists/common.txt"))) {
            Files.copy(in, dir.resolve("common.txt"));
        }
        assertTrue(list.load(dir, false) > 50);
        assertTrue(list.contains("Minecraft123"));
        assertTrue(list.contains("contraseña"));
        assertFalse(list.contains("correct horse battery staple"));
    }

    @Test
    void bundledSecListsLoads() throws IOException {
        try (InputStream in = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("unsafe_lists/seclists-10k-most-common.txt"))) {
            Files.copy(in, dir.resolve("seclists.txt"));
        }
        // 10,000 entries; a handful collapse when capitalization is ignored.
        assertTrue(list.load(dir, false) > 9_900);
        assertTrue(list.contains("trustno1"));
        assertFalse(list.contains("correct horse battery staple"));
    }
}
