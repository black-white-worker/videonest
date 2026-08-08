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

/**
 * 上传文件安全校验器
 * 核心目标：不能信任文件名、http的content‑type，读取二进制真实内容校验媒体文件；支持图片封面、MP4视频；支持病毒扫描
 * 流程：MinIO下载对象 -> 写入本地临时bin文件 -> 魔数识别真实mime -> 图片/视频专项校验 -> ClamAV病毒扫描 -> 返回检测结果 -> finally删除临时文件
 */
/**
 * UploadedFileSecurityValidator 类作为校验入口，统一执行魔数校验，把文件从 MinIO 下载到本地临时文件，
 * 区分图片和视频调用各自校验逻辑；图片使用 ImageIO 校验，
 * 视频调用 run 方法通过 ProcessBuilder 创建 ffprobe 子进程完成校验，
 * 内部封装 CommandResult 保存进程执行信息；校验完成清理临时文件资源
 * 最终统一返回 Inspection 记录类，携带识别的 MIME 和视频时长。
 * */
@Service
@Slf4j
public class UploadedFileSecurityValidator {

    /**
     * 白名单：允许的视频编码格式
     * h264、hevc(h265)、av1、vp9；不在集合内的编码直接拒绝上传
     */
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

    /**
     * 文件安全检测入口方法
     * @param objectName MinIO存储的对象文件名
     * @param type 文件类型：cover=封面图片；video=视频
     * @return Inspection record，包含真实检测mime、视频时长(图片为null)
     * @throws BusinessException 任意安全校验失败抛出业务异常
     */
    /**
     * 从 MinIO 下载已经上传好的文件，落地到本地临时 bin 文件，依次做全套安全校验，
     * 校验全部通过返回检测结果；无论成功失败，finally 一定会删除临时文件，防止磁盘占满。
     * ffprobe、ClamAV 杀毒软件，只能读取磁盘上的文件，不能直接读取 InputStream 流。
     * MinIO 拿到的是网络流，必须落地成本地磁盘文件
     * */
    public Inspection inspect(String objectName, String type) {
        Path temporaryFile = null;
        try {
            // 创建临时文件，前缀videonest‑upload‑scan‑，后缀.bin，存系统临时目录
            temporaryFile = Files.createTempFile("videonest-upload-scan-", ".bin");
            try (InputStream input = minioService.download(objectName)) {
                // 将oss流复制写入本地临时文件；REPLACE_EXISTING：如果文件存在直接覆盖
                Files.copy(input, temporaryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // 读取文件头部魔数字节，识别真实mime类型，防御修改后缀名伪装文件攻击
            String detectedMime = detectMagic(temporaryFile);
            // duration = 这个yield出来的值
            Integer duration = switch (type) {
                case "cover" -> {
                    // 魔数检测出来不是image/*，直接抛异常
                    if (!detectedMime.startsWith("image/")) {
                        throw new BusinessException(400, "封面文件内容不是受支持的图片");
                    }
                    // 图片深度校验：使用ImageIO解析图片，校验宽高像素上限，判断图片不是损坏二进制
                    validateImage(temporaryFile);
                    yield null;
                }
                case "video" -> {
                    // 魔数校验必须识别为video/mp4，拒绝伪装MP4
                    if (!"video/mp4".equals(detectedMime)) {
                        throw new BusinessException(400, "视频文件魔数不是 MP4");
                    }
                    // 调用ffprobe外部进程解析视频：编码、宽高、时长，返回视频秒数
                    yield probeVideo(temporaryFile);
                }
                default -> throw new BusinessException(400, "不支持的上传类型");
            };

            // 病毒扫描（ClamAV）；配置为空可跳过，如果配置强制扫描则不允许跳过
            scanVirus(temporaryFile);
            // 全部校验通过，返回检测结果记录对象
            return new Inspection(detectedMime, duration);
        } catch (BusinessException e) {
            // 业务异常直接往外抛出，不需要包装
            throw e;
        } catch (IOException e) {
            // IO异常（读文件失败、下载失败）统一封装为业务异常
            throw new BusinessException(400, "无法安全读取上传文件");
        } finally {
            // 无论正常、异常，都执行：删除临时文件，防止磁盘堆积垃圾文件
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException e) {
                    log.warn("删除上传扫描临时文件失败，path={}", temporaryFile, e);
                }
            }
        }
    }

    /**
     * 魔数检测，读取文件前16字节二进制，判断真实文件类型，不依赖文件名后缀
     * @param file 本地临时文件path
     * @return mime字符串 image/jpeg image/png image/webp video/mp4
     * @throws IOException IO读取失败
     */
    private String detectMagic(Path file) throws IOException {
        // 读取文件头部最多16个字节，魔数只看文件开头
        byte[] header = new byte[16];
        try (InputStream input = Files.newInputStream(file)) {
            if (input.read(header) < 12) {
                throw new BusinessException(400, "文件内容不完整");
            }
        }
        /**
         * 魔数校验读取文件头部字节到 byte 数组，Java 的 byte 是有符号，
         * 大于等于 128 的字节需&0xff清除符号扩展的虚假高位后再和十六进制比对；
         * ASCII 字符魔数直接对比字符常量。依据各文件格式的头部特征位置分别校验 JPEG、PNG、WebP、MP4
         * */
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

    /**
     * ImageIO解析图片，校验图片真实可解析，校验总像素上限，防止超大图片DoS攻击
     * @param file 本地临时图片文件
     * @throws IOException IO异常
     */
    private void validateImage(Path file) throws IOException {
        // ImageInputStream：图片IO流，try‑with‑resources自动关闭流
        // 流：读取硬盘二进制数据的通道。
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            // 获取可以解析该图片的Reader迭代器，把文件二进制交给 JDK 解码器去探测，不是只看头部魔数字节，会做格式合法性探测。
            // 合法的图片都有一套严格二进制结构规范
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            // 迭代器无元素：图片二进制损坏，不是合法图片，迭代器里面拿不出 ImageReader
            if (!readers.hasNext()) {
                throw new BusinessException(400, "图片编码损坏或不受支持");
            }
            ImageReader reader = readers.next();
            try {
                // 设置输入源；true忽略元数据，true不向前搜索；提升解析速度
                reader.setInput(input, true, true);
                // 获取第0帧图片宽高，计算总像素
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                // 像素<=0说明解析异常；大于配置最大像素拒绝上传，防御超大图片DoS
                if (pixels <= 0 || pixels > securityProperties.getMaxImagePixels()) {
                    throw new BusinessException(400, "图片像素尺寸超过安全限制");
                }
            } finally {
                // 必须释放ImageReader资源，避免内存泄漏
                reader.dispose();
            }
        }
    }

    /**
     * 调用ffprobe外部进程探测MP4视频信息：编码、宽高、时长，做安全校验
     * @param file 本地临时视频文件
     * @return 视频时长，单位秒，四舍五入整数
     * @throws IOException 进程调用IO异常
     */
    /**
     * run 方法执行 ffprobe 外部进程探测视频，进程非 0 退出码即可拦截伪造魔数、损坏的非法视频；
     * 解析输出后校验格式、编码、分辨率和时长，校验全部通过才返回视频时长。
     * */
    private int probeVideo(Path file) throws IOException {
        // 组装ffprobe命令参数列表
        List<String> command = List.of(
                videoProperties.getFfprobePath(), "-v", "error",    // ffprobe路径；只输出error级别日志
                "-select_streams", "v:0",                           // 只取第0号视频流
                "-show_entries", "stream=codec_name,width,height:format=format_name,duration",  // 需要输出字段
                "-of", "default=noprint_wrappers=1",                // 输出格式，不输出包装，key=value每行
                file.toString()                                     // 待探测本地视频文件路径
        );
        // 执行外部命令，带超时，拿到进程输出结果
        CommandResult result = run(command, videoProperties.getTimeoutSeconds());
        // exitCode不等于0，ffprobe执行失败，文件损坏或不是合法视频
        if (result.exitCode() != 0) {
            throw new BusinessException(400, "媒体探测失败，文件可能已损坏");
        }
        // 将ffprobe输出的key=value文本解析成Map
        Map<String, String> fields = parseFields(result.output());
        // 获取封装格式；获取视频编码名称，统一转小写
        String format = fields.getOrDefault("format_name", "");
        String codec = fields.getOrDefault("codec_name", "").toLowerCase(Locale.ROOT);
        // 解析宽高，校验必须是正数
        int width = parsePositiveInt(fields.get("width"), "视频宽度");
        int height = parsePositiveInt(fields.get("height"), "视频高度");
        double duration;
        try {
            // 解析视频时长浮点数
            duration = Double.parseDouble(fields.getOrDefault("duration", "0"));
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "无法识别视频时长");
        }
        // 校验封装格式必须包含mp4；视频编码必须在白名单集合
        if (!format.contains("mp4") || !VIDEO_CODECS.contains(codec)) {
            throw new BusinessException(400, "MP4 内的视频编码不受支持");
        }
        // 最大分辨率限制：总像素不能超过33177600；时长>0且小于等于86400秒(24小时)
        if ((long) width * height > 33_177_600L || duration <= 0 || duration > 86_400) {
            throw new BusinessException(400, "视频分辨率或时长超过安全限制");
        }
        return Math.max(1, (int) Math.round(duration));
    }

    /**
     * 解析字符串转为正整数；解析失败/<=0抛出业务异常
     * @param raw 待解析原始字符串
     * @param label 报错提示文字，例如“视频宽度”
     * @return int正数
     */
    private int parsePositiveInt(String raw, String label) {
        try {
            int value = Integer.parseInt(raw);
            if (value > 0) return value;
        } catch (RuntimeException ignored) {
            // 统一转换为业务错误。
        }
        throw new BusinessException(400, "无法识别" + label);
    }

    /**
     * 解析ffprobe输出文本，每行key=value转为map
     * @param output ffprobe标准输出字符串
     * @return key‑value map
     */
    private Map<String, String> parseFields(String output) {
        Map<String, String> fields = new HashMap<>();
        output.lines().forEach(line -> {
            // 查找等号分隔符下标
            int separator = line.indexOf('=');
            // separator>0，保证key不为空
            if (separator > 0) {
                fields.put(line.substring(0, separator), line.substring(separator + 1));
            }
        });
        return fields;
    }

    /**
     * ClamAV病毒扫描逻辑
     * @param file 待扫描本地临时文件
     * @throws IOException IO异常
     */
    private void scanVirus(Path file) throws IOException {
        // 读取配置中病毒扫描命令
        String scanner = securityProperties.getAntivirusCommand();
        if (scanner == null || scanner.isBlank()) {
            if (securityProperties.isAntivirusRequired()) {
                throw new BusinessException(503, "病毒扫描服务未配置，暂时不能上传");
            }
            log.debug("未配置 ClamAV 命令，跳过病毒签名扫描");
            return;
        }
        // 调用clamav扫描命令，--no-summary不输出汇总信息，传入待扫描文件路径
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

    /**
     * 通用执行外部shell命令工具；带超时控制，防止子进程卡死造成线程阻塞
     * @param command 命令参数list
     * @param timeoutSeconds 超时秒数
     * @return CommandResult 退出码+标准输出（stdout+stderr合并）
     * @throws IOException 进程启动IO异常
     */
    /**
     * ffprobe：每次上传校验视频，就临时生成一个进程；校验完成直接销毁，不占系统资源。
     * 请求操作系统新建一个独立 ffprobe 子进程，运行完就销毁。Java 通过流拿它的输出结果。
     * */
    private CommandResult run(List<String> command, long timeoutSeconds)
            throws IOException {
        Process process = new ProcessBuilder(command)
                // redirectErrorStream(true): 把 stderr 错误流合并进 stdout 流。，避免两个流缓冲区死锁问题
                .redirectErrorStream(true)
                .start();
        try {
            // 带超时等待进程结束；超时返回false；正常结束返回true
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(400, "媒体安全检测超时");
            }
            // 读取进程全部输出字节，转UTF‑8字符串
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

    /**
     * Java16 Record 不可变数据载体；inspect方法返回值，保存检测结果
     * @param detectedContentType 魔数识别真实mime类型
     * @param durationSeconds 视频时长，图片为null
     */
    public record Inspection(String detectedContentType, Integer durationSeconds) {
    }

    /**
     * Record；run()方法返回，封装外部进程执行结果
     * @param exitCode 进程退出码，0代表成功
     * @param output stdout+stderr合并输出文本
     */
    private record CommandResult(int exitCode, String output) {
    }
}
