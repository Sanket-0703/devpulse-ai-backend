package com.devpulse.backend.codeindex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds zip archives shaped like GitHub's zipball (everything under one top folder). */
public final class Zips {

    private Zips() {
    }

    public static byte[] zipball(Map<String, byte[]> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("owner-repo-1a2b3c/"));
            zip.closeEntry();
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry("owner-repo-1a2b3c/" + file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
