package com.android.mycamera.utils;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YuvDumpParser {
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "(?:(?:\\d+_\\d+m\\d+_\\d+)_)?"
                    + "(?<stream>inResized(?:_\\d+)?|record|display)_(?<width>\\d+)x(?<height>\\d+)"
                    + "-PW(?<physicalWidth>\\d+)-PH(?<physicalHeight>\\d+)_(?<stride>\\d+)_.*"
                    + "\\.(?<format>[A-Za-z0-9_]+)$",
            Pattern.CASE_INSENSITIVE);

    private YuvDumpParser() {
    }

    public static List<DumpFile> listYv12Dumps(File directory, int limit) throws IOException {
        if (!directory.exists()) {
            throw new IOException("Directory does not exist or is not readable: " + directory);
        }
        if (!directory.isDirectory()) {
            throw new IOException("Path is not a directory: " + directory);
        }

        File[] files;
        try {
            files = directory.listFiles();
        } catch (SecurityException exception) {
            throw new IOException("Permission denied: " + directory, exception);
        }
        if (files == null) {
            throw new IOException("Unable to read directory: " + directory);
        }

        List<DumpFile> dumps = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.US).endsWith(".yv12")) {
                continue;
            }
            DumpFile dump = parseDumpFile(file);
            if (dump != null) {
                dumps.add(dump);
            }
        }
        Collections.sort(dumps, new Comparator<DumpFile>() {
            @Override
            public int compare(DumpFile left, DumpFile right) {
                int byTime = Long.compare(right.getLastModified(), left.getLastModified());
                return byTime != 0 ? byTime : right.getFile().getName().compareTo(left.getFile().getName());
            }
        });
        if (limit > 0 && dumps.size() > limit) {
            return new ArrayList<>(dumps.subList(0, limit));
        }
        return dumps;
    }

    public static DumpFile parseDumpFile(File file) {
        Matcher matcher = NAME_PATTERN.matcher(file.getName());
        if (!matcher.matches()) {
            return null;
        }
        String format = matcher.group("format");
        if (!"yv12".equalsIgnoreCase(format)) {
            return null;
        }
        String pixelFormat = format.toLowerCase(Locale.US);

        int stride = parseInt(matcher.group("stride"));
        int physicalHeight = parseInt(matcher.group("physicalHeight"));
        if (stride <= 0 || physicalHeight <= 0) {
            return null;
        }
        return new DumpFile(
                file,
                matcher.group("stream"),
                parseInt(matcher.group("width")),
                parseInt(matcher.group("height")),
                parseInt(matcher.group("physicalWidth")),
                physicalHeight,
                stride,
                pixelFormat,
                file.length(),
                expectedYv12Size(stride, physicalHeight));
    }

    public static Bitmap decodeToBitmap(DumpFile dump, int maxWidth) throws IOException {
        if (dump == null || !dump.isYv12()) {
            throw new IOException("Only YV12 dump files are supported.");
        }
        if (dump.getSize() != dump.getExpectedSize()) {
            throw new IOException("File size mismatch: got " + dump.getSize()
                    + ", expected " + dump.getExpectedSize());
        }
        if (dump.getWidth() <= 0 || dump.getHeight() <= 0
                || dump.getWidth() > dump.getStride()
                || dump.getHeight() > dump.getPhysicalHeight()) {
            throw new IOException("Invalid dimensions or stride in dump filename.");
        }
        if (dump.getExpectedSize() > Integer.MAX_VALUE) {
            throw new IOException("Dump file is too large for an Android byte buffer.");
        }

        byte[] payload = new byte[(int) dump.getExpectedSize()];
        try (FileInputStream input = new FileInputStream(dump.getFile())) {
            int offset = 0;
            while (offset < payload.length) {
                int count = input.read(payload, offset, payload.length - offset);
                if (count < 0) {
                    throw new IOException("Dump file ended before the expected size.");
                }
                if (count == 0) {
                    continue;
                }
                offset += count;
            }
        } catch (SecurityException exception) {
            throw new IOException("Permission denied: " + dump.getFile(), exception);
        }

        int targetWidth = dump.getWidth();
        if (maxWidth > 0 && targetWidth > maxWidth) {
            targetWidth = maxWidth;
        }
        int targetHeight = Math.max(1, (int) Math.round(
                (double) dump.getHeight() * targetWidth / dump.getWidth()));
        long pixelCount = (long) targetWidth * targetHeight;
        if (pixelCount > Integer.MAX_VALUE) {
            throw new IOException("Preview bitmap is too large.");
        }

        int strideC = (int) align((dump.getStride() + 1L) / 2L, 16);
        int yPlaneSize = multiplyToInt(dump.getStride(), dump.getPhysicalHeight());
        int chromaHeight = (int) ((dump.getPhysicalHeight() + 1L) / 2L);
        int chromaSize = multiplyToInt(strideC, chromaHeight);
        int[] pixels = new int[(int) pixelCount];

        for (int y = 0; y < targetHeight; y++) {
            int sourceY = Math.min(dump.getHeight() - 1,
                    y * dump.getHeight() / targetHeight);
            int sourceYOffset = sourceY * dump.getStride();
            int sourceCOffset = (sourceY / 2) * strideC;
            int targetOffset = y * targetWidth;
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = Math.min(dump.getWidth() - 1,
                        x * dump.getWidth() / targetWidth);
                int chromaOffset = sourceX / 2;
                int yValue = Math.max((payload[sourceYOffset + sourceX] & 0xff) - 16, 0);
                int vValue = (payload[yPlaneSize + sourceCOffset + chromaOffset] & 0xff) - 128;
                int uValue = (payload[yPlaneSize + chromaSize + sourceCOffset + chromaOffset]
                        & 0xff) - 128;
                int red = clamp((298 * yValue + 459 * vValue + 128) >> 8);
                int green = clamp((298 * yValue - 55 * uValue - 136 * vValue + 128) >> 8);
                int blue = clamp((298 * yValue + 541 * uValue + 128) >> 8);
                pixels[targetOffset + x] = Color.rgb(red, green, blue);
            }
        }
        return Bitmap.createBitmap(pixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static long expectedYv12Size(int stride, int physicalHeight) {
        long strideC = align((stride + 1L) / 2L, 16);
        long chromaHeight = (physicalHeight + 1L) / 2L;
        return stride * (long) physicalHeight + 2L * strideC * chromaHeight;
    }

    private static long align(long value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    private static int multiplyToInt(long left, long right) throws IOException {
        long value = left * right;
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException("Dump plane is too large.");
        }
        return (int) value;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public static final class DumpFile {
        private final File file;
        private final String stream;
        private final int width;
        private final int height;
        private final int physicalWidth;
        private final int physicalHeight;
        private final int stride;
        private final String pixelFormat;
        private final long size;
        private final long expectedSize;

        private DumpFile(File file, String stream, int width, int height,
                int physicalWidth, int physicalHeight, int stride,
                String pixelFormat, long size, long expectedSize) {
            this.file = file;
            this.stream = stream;
            this.width = width;
            this.height = height;
            this.physicalWidth = physicalWidth;
            this.physicalHeight = physicalHeight;
            this.stride = stride;
            this.pixelFormat = pixelFormat;
            this.size = size;
            this.expectedSize = expectedSize;
        }

        public File getFile() {
            return file;
        }

        public String getStream() {
            return stream;
        }

        public String getPixelFormat() {
            return pixelFormat;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getPhysicalWidth() {
            return physicalWidth;
        }

        public int getPhysicalHeight() {
            return physicalHeight;
        }

        public int getStride() {
            return stride;
        }

        public int getChromaStride() {
            return (int) align((stride + 1L) / 2L, 16);
        }

        public long getSize() {
            return size;
        }

        public long getExpectedSize() {
            return expectedSize;
        }

        public long getLastModified() {
            return file.lastModified();
        }

        public boolean isYv12() {
            return true;
        }

        public String getDisplayName() {
            return file.getName();
        }

        public String getSummary() {
            return String.format(Locale.US,
                    "%s | %dx%d logical, %dx%d physical, stride Y=%d, size=%d/%d",
                    file.getName(), width, height, physicalWidth, physicalHeight,
                    stride, size, expectedSize);
        }
    }
}
