package com.example.demo.module.upload.service;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.infrastructure.oss.service.MinioService;
import com.example.demo.module.upload.config.UploadSecurityProperties;
import com.example.demo.module.video.config.VideoProcessProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class UploadedFileSecurityValidator {

    private static final Set<String> VIDEO_CODECS =
            Set.of("h264", "hevc", "av1", "vp9");

    private final MinioService minioService;
    private final VideoProcessProperties videoProperties;
    private final UploadSecurityProperties securityProperties;

    public UploadedFileSecurityValidator(
            MinioService minioService,
            VideoProcessProperties videoProperties,
            UploadSecurityProperties securityProperties
    ) {
        this.minioService = minioService;
        this.videoProperties = videoProperties;
        this.securityProperties = securityProperties;
    }

    public Inspection inspect(String objectName, String type) {
        Path temporaryFile = null;
        try {
            temporaryFile = Files.createTempFile("videonest-upload-scan-", ".bin");
            try (InputStream input = minioService.download(objectName)) {
                Files.copy(input, temporaryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            String detectedMime = detectMagic(temporaryFile);
            Integer duration = switch (type) {
                case "cover" -> {
                    if (!detectedMime.startsWith("image/")) {
                        throw new BusinessException(400, "封面文件内容不是受支持的图片");
                    }
                    validateImage(temporaryFile);
                    yield null;
                }
                case "video" -> {
                    if (!"video/mp4".equals(detectedMime)) {
                        throw new BusinessException(400, "视频文件魔数不是 MP4");
                    }
                    yield probeVideo(temporaryFile);
                }
                default -> throw new BusinessException(400, "不支持的上传类型");
            };

            scanVirus(temporaryFile);
            return new Inspection(detectedMime, duration);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(400, "无法安全读取上传文件");
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException e) {
                    log.warn("删除上传扫描临时文件失败，path={}", temporaryFile, e);
                }
            }
        }
    }

    private String detectMagic(Path file) throws IOException {
        byte[] header = new byte[16];
        try (InputStream input = Files.newInputStream(file)) {
            if (input.read(header) < 12) {
                throw new BusinessException(400, "文件内容不完整");
            }
        }
        if ((header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
                && (header[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if ((header[0] & 0xff) == 0x89 && header[1] == 'P'
                && header[2] == 'N' && header[3] == 'G') {
            return "image/png";
        }
        if (header[0] == 'R' && header[1] == 'I' && header[2] == 'F'
                && header[3] == 'F' && header[8] == 'W' && header[9] == 'E'
                && header[10] == 'B' && header[11] == 'P') {
            return "image/webp";
        }
        if (header[4] == 'f' && header[5] == 't' && header[6] == 'y'
                && header[7] == 'p') {
            return "video/mp4";
        }
        throw new BusinessException(400, "无法通过文件魔数识别媒体类型");
    }

    private void validateImage(Path file) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException(400, "图片编码损坏或不受支持");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > securityProperties.getMaxImagePixels()) {
                    throw new BusinessException(400, "图片像素尺寸超过安全限制");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private int probeVideo(Path file) throws IOException {
        List<String> command = List.of(
                videoProperties.getFfprobePath(), "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,width,height:format=format_name,duration",
                "-of", "default=noprint_wrappers=1",
                file.toString()
        );
        CommandResult result = run(command, videoProperties.getTimeoutSeconds());
        if (result.exitCode() != 0) {
            throw new BusinessException(400, "媒体探测失败，文件可能已损坏");
        }
        Map<String, String> fields = parseFields(result.output());
        String format = fields.getOrDefault("format_name", "");
        String codec = fields.getOrDefault("codec_name", "").toLowerCase(Locale.ROOT);
        int width = parsePositiveInt(fields.get("width"), "视频宽度");
        int height = parsePositiveInt(fields.get("height"), "视频高度");
        double duration;
        try {
            duration = Double.parseDouble(fields.getOrDefault("duration", "0"));
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "无法识别视频时长");
        }
        if (!format.contains("mp4") || !VIDEO_CODECS.contains(codec)) {
            throw new BusinessException(400, "MP4 内的视频编码不受支持");
        }
        if ((long) width * height > 33_177_600L || duration <= 0 || duration > 86_400) {
            throw new BusinessException(400, "视频分辨率或时长超过安全限制");
        }
        return Math.max(1, (int) Math.round(duration));
    }

    private int parsePositiveInt(String raw, String label) {
        try {
            int value = Integer.parseInt(raw);
            if (value > 0) return value;
        } catch (RuntimeException ignored) {
            // 统一转换为业务错误。
        }
        throw new BusinessException(400, "无法识别" + label);
    }

    private Map<String, String> parseFields(String output) {
        Map<String, String> fields = new HashMap<>();
        output.lines().forEach(line -> {
            int separator = line.indexOf('=');
            if (separator > 0) {
                fields.put(line.substring(0, separator), line.substring(separator + 1));
            }
        });
        return fields;
    }

    private void scanVirus(Path file) throws IOException {
        String scanner = securityProperties.getAntivirusCommand();
        if (scanner == null || scanner.isBlank()) {
            if (securityProperties.isAntivirusRequired()) {
                throw new BusinessException(503, "病毒扫描服务未配置，暂时不能上传");
            }
            log.debug("未配置 ClamAV 命令，跳过病毒签名扫描");
            return;
        }
        CommandResult result = run(
                List.of(scanner, "--no-summary", file.toString()),
                securityProperties.getScanTimeoutSeconds()
        );
        if (result.exitCode() == 1) {
            throw new BusinessException(400, "上传文件未通过病毒扫描");
        }
        if (result.exitCode() != 0) {
            throw new BusinessException(503, "病毒扫描服务暂时不可用");
        }
    }

    private CommandResult run(List<String> command, long timeoutSeconds)
            throws IOException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(400, "媒体安全检测超时");
            }
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8
            );
            return new CommandResult(process.exitValue(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "媒体安全检测被中断");
        }
    }

    public record Inspection(String detectedContentType, Integer durationSeconds) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
