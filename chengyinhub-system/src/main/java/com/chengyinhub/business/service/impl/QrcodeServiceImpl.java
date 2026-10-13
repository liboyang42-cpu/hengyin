package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import com.chengyinhub.common.utils.file.FileUploadUtils;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.ReaderException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.net.URI;
import java.util.Date;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 二维码服务实现类
 */
@Service
public class QrcodeServiceImpl implements IQrcodeService {
    // ponytail: 本地存储依赖单实例磁盘；多实例使用默认 OSS。
    @Value("${chengyin.qrcode.storage:oss}")
    private String storage = "oss";
    @Value("${chengyin.qrcode.public-base-url:}")
    private String publicBaseUrl = "";

    // 二维码图片格式（PNG兼容性好，支持透明背景）
    private static final String QRCODE_FORMAT = "PNG";
    // 二维码颜色配置（黑底白块，符合常规视觉）
    private static final int COLOR_BLACK = 0xFF000000;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    // 二维码存储根目录（基于若依上传路径的子目录）
    private static final String QRCODE_ROOT_DIR = "qrcode";
    // 日期格式（用于按日期分目录，如20240905）
    private static final String DATE_FORMAT = "yyyyMMdd";
    // 二维码纠错等级（H级：可恢复约30%错误；前端中心 logo 挖空依赖此容错）
    private static final com.google.zxing.qrcode.decoder.ErrorCorrectionLevel ERROR_CORRECTION_LEVEL =
            com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H;



    /**
     * 生成二维码并上传至OSS返回访问URL
     * @param content 二维码内容（建议含报名编号+核销码，确保唯一，如"REG:20240905123456:VER890123"）
     * @param width 二维码宽度（像素，建议300-500，需与高度一致）
     * @param height 二维码高度（像素，需与宽度一致，保证正方形）
     * @return 二维码OSS访问URL
     */
    @Override
    public String generateQrcode(String content, int width, int height) {
        // 1. 基础参数校验
        if (StringUtils.isEmpty(content)) {
            throw new RuntimeException("二维码内容不能为空");
        }
        if (width <= 0 || height <= 0 || width != height) {
            throw new RuntimeException("二维码尺寸非法：宽高需为正整数且相等");
        }
        if (!"oss".equals(storage) && !"local".equals(storage)) {
            throw new IllegalArgumentException("二维码存储方式仅支持 oss 或 local");
        }
        String localBaseUrl = "";
        if ("local".equals(storage)) {
            localBaseUrl = publicBaseUrl.trim().replaceAll("/+$", "");
            URI base = URI.create(localBaseUrl);
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null
                    || base.getRawQuery() != null || base.getRawFragment() != null
                    || StringUtils.isBlank(RuoYiConfig.getProfile())) {
                throw new IllegalArgumentException("本地二维码存储需要有效访问地址和文件目录");
            }
        }

        try {
            BufferedImage qrImage = renderQrcode(content, width, height);

            // 5. 生成唯一文件名和OSS路径
            String currentDate = DateUtils.parseDateToStr(DATE_FORMAT, new Date()); // 获取当前日期（如20240905）
            String uniqueFileName = UUID.randomUUID().toString().replace("-", "") + "." + QRCODE_FORMAT; // 唯一文件名
            // OSS存储路径：qrcode/20240905/xxx.png（分目录存储，避免单目录文件过多）
            String ossFileName = "qrcode/" + currentDate + "/" + uniqueFileName;

            // 6. 将BufferedImage转换为MultipartFile
            MultipartFile multipartFile = createMultipartFile(qrImage, ossFileName);

            if ("local".equals(storage)) {
                return localBaseUrl + FileUploadUtils.upload(
                        RuoYiConfig.getProfile() + "/" + QRCODE_ROOT_DIR, multipartFile, new String[]{"png"});
            }

            // 7. 上传到OSS
            JSONObject uploadResult = FileOssUploadUtils.upload(multipartFile);

            if (uploadResult == null) {
                throw new RuntimeException("二维码上传OSS失败");
            }

            // 8. 返回OSS访问URL
            return (String) uploadResult.get("url");

        } catch (WriterException e) {
            throw new RuntimeException("二维码矩阵生成失败：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("二维码图片转换失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("二维码生成异常：" + e.getMessage(), e);
        }
    }

    @Override
    public String generateInlineQrcode(String content, int width, int height) {
        try {
            BufferedImage image = renderQrcode(content, width, height);
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(image, QRCODE_FORMAT, output)) {
                    throw new IOException("PNG 编码不可用");
                }
                return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
            }
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("二维码图片生成失败", e);
        }
    }

    private BufferedImage renderQrcode(String content, int width, int height) throws WriterException {
        // 1. 基础参数校验
        if (StringUtils.isEmpty(content)) {
            throw new RuntimeException("二维码内容不能为空");
        }
        if (width <= 0 || height <= 0 || width != height) {
            throw new RuntimeException("二维码尺寸非法：宽高需为正整数且相等");
        }

        // 2. 配置二维码生成参数
        Map<EncodeHintType, Object> qrParams = new HashMap<>(3);
        qrParams.put(EncodeHintType.CHARACTER_SET, "UTF-8"); // 解决中文乱码
        qrParams.put(EncodeHintType.MARGIN, 1); // 边距1像素，减少空白
        qrParams.put(EncodeHintType.ERROR_CORRECTION, ERROR_CORRECTION_LEVEL); // 纠错等级

        // 中心留白会破坏部分码字；H 级纠错并不保证每个掩码都能识别。
        // 先用默认掩码，再尝试其余标准掩码，只返回能读回原内容的实际图片。
        for (int mask = -1; mask < 8; mask++) {
            if (mask >= 0) qrParams.put(EncodeHintType.QR_MASK_PATTERN, mask);
            BitMatrix qrMatrix = new MultiFormatWriter().encode(
                    content, BarcodeFormat.QR_CODE, width, height, qrParams);
            BufferedImage qrImage = renderLogoHole(qrMatrix, width, height);
            if (decodesTo(qrImage, content)) return qrImage;
        }
        throw new WriterException("中心留白后的二维码不可识别");
    }

    private BufferedImage renderLogoHole(BitMatrix qrMatrix, int width, int height) {
        // 保留前端中心 logo 对应的约 25% 边长留白。
        int hole = (width / 4) | 1; // ≈25%，强制奇数使留白严格居中
        int holeFrom = (width - hole) / 2;
        int holeTo = holeFrom + hole;
        BufferedImage qrImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                boolean inLogoHole = x >= holeFrom && x < holeTo && y >= holeFrom && y < holeTo;
                // 矩阵true=黑色（二维码块），false=白色（背景）；中心留白一律画白
                qrImage.setRGB(x, y, !inLogoHole && qrMatrix.get(x, y) ? COLOR_BLACK : COLOR_WHITE);
            }
        }

        return qrImage;
    }

    private boolean decodesTo(BufferedImage image, String content) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(width, height, pixels)));
        try {
            return content.equals(new QRCodeReader().decode(bitmap).getText());
        } catch (ReaderException e) {
            return false;
        }
    }

    /**
     * 将BufferedImage转换为MultipartFile
     * @param image 图片对象
     * @param fileName 文件名（包含路径）
     * @return MultipartFile对象
     * @throws IOException
     */
    private MultipartFile createMultipartFile(BufferedImage image, String fileName) throws IOException {
        // 将BufferedImage转换为字节数组
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, QRCODE_FORMAT, outputStream);
            final byte[] imageBytes = outputStream.toByteArray();

            // 提取原始文件名（不包含路径）
            String originalFileName = fileName.substring(fileName.lastIndexOf("/") + 1);

            // 创建MultipartFile实现
            return new MultipartFile() {
                @Override
                public String getName() {
                    return "file";
                }

                @Override
                public String getOriginalFilename() {
                    return originalFileName;
                }

                @Override
                public String getContentType() {
                    return "image/png";
                }

                @Override
                public boolean isEmpty() {
                    return imageBytes.length == 0;
                }

                @Override
                public long getSize() {
                    return imageBytes.length;
                }

                @Override
                public byte[] getBytes() throws IOException {
                    return imageBytes;
                }

                @Override
                public InputStream getInputStream() throws IOException {
                    return new ByteArrayInputStream(imageBytes);
                }

                @Override
                public void transferTo(File dest) throws IOException, IllegalStateException {
                    Files.write(dest.toPath(), imageBytes);
                }
            };
        } finally {
            outputStream.close();
        }
    }

//    /**
//     * 生成二维码并返回访问URL
//     * @param content 二维码内容（建议含报名编号+核销码，确保唯一，如"REG:20240905123456:VER890123"）
//     * @param width 二维码宽度（像素，建议300-500，需与高度一致）
//     * @param height 二维码高度（像素，需与宽度一致，保证正方形）
//     * @return 二维码访问URL（如http://localhost:8080/profile/qrcode/20240905/xxx.png）
//     */
//    @Override
//    public String generateQrcode(String content, int width, int height) {
//        // 1. 基础参数校验
//        if (StringUtils.isEmpty(content)) {
//            throw new RuntimeException("二维码内容不能为空");
//        }
//        if (width <= 0 || height <= 0 || width != height) {
//            throw new RuntimeException("二维码尺寸非法：宽高需为正整数且相等");
//        }
//
//        try {
//            // 2. 配置二维码生成参数
//            Map<EncodeHintType, Object> qrParams = new HashMap<>(3);
//            qrParams.put(EncodeHintType.CHARACTER_SET, "UTF-8"); // 解决中文乱码
//            qrParams.put(EncodeHintType.MARGIN, 1); // 边距1像素，减少空白
//            qrParams.put(EncodeHintType.ERROR_CORRECTION, ERROR_CORRECTION_LEVEL); // 纠错等级
//
//            // 3. 生成二维码矩阵（核心：将内容转为像素矩阵）
//            BitMatrix qrMatrix = new MultiFormatWriter().encode(
//                    content,
//                    BarcodeFormat.QR_CODE,
//                    width,
//                    height,
//                    qrParams
//            );
//
//            // 4. 矩阵转BufferedImage（用于写入文件）
//            BufferedImage qrImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
//            for (int x = 0; x < width; x++) {
//                for (int y = 0; y < height; y++) {
//                    // 矩阵true=黑色（二维码块），false=白色（背景）
//                    qrImage.setRGB(x, y, qrMatrix.get(x, y) ? COLOR_BLACK : COLOR_WHITE);
//                }
//            }
//
//            // 5. 手动构建文件路径（替代FileUploadUtils.getPath()）
//            String currentDate = DateUtils.parseDateToStr(DATE_FORMAT, new Date()); // 获取当前日期（如20240905）
//            String uniqueFileName = UUID.randomUUID().toString().replace("-", "") + "." + QRCODE_FORMAT; // 唯一文件名
//            // 相对路径：qrcode/20240905/xxx.png（分目录存储，避免单目录文件过多）
//            String relativePath = QRCODE_ROOT_DIR + File.separator + currentDate + File.separator + uniqueFileName;
//            // 服务器绝对路径：上传根路径 + 相对路径（如D:/ruoyi/upload/qrcode/20240905/xxx.png）
//            String absolutePath = RuoYiConfig.getUploadPath() + File.separator + relativePath;
//
//            // 6. 创建存储目录（递归创建所有父目录，避免目录不存在）
//            File qrFile = new File(absolutePath);
//            File parentDir = qrFile.getParentFile();
//            if (!parentDir.exists() && !parentDir.mkdirs()) {
//                throw new RuntimeException("创建二维码目录失败：" + parentDir.getAbsolutePath());
//            }
//
//            // 7. 写入文件（try-with-resources自动关闭流，防止资源泄漏）
//            try (OutputStream outputStream = new FileOutputStream(qrFile)) {
//                ImageIO.write(qrImage, QRCODE_FORMAT, outputStream);
//                outputStream.flush(); // 强制刷新，确保数据完全写入
//            }
//
//            // 8. 生成访问URL（若依规范：profile前缀 + 相对路径，支持CDN）
//            // 替换File.separator为"/"（URL路径需用"/"，避免Windows下的"\"导致URL无效）
//            String urlPath = relativePath.replace(File.separator, "/");
//            return RuoYiConfig.getProfile() + "/" + urlPath;
//
//        } catch (WriterException e) {
//            throw new RuntimeException("二维码矩阵生成失败：" + e.getMessage(), e);
//        } catch (IOException e) {
//            throw new RuntimeException("二维码写入文件失败：" + e.getMessage(), e);
//        } catch (Exception e) {
//            throw new RuntimeException("二维码生成异常：" + e.getMessage(), e);
//        }
//    }
}
