package com.chengyinhub.common.utils.file;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.apache.commons.io.FilenameUtils;
import org.springframework.web.multipart.MultipartFile;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.exception.file.FileNameLengthLimitExceededException;
import com.chengyinhub.common.exception.file.FileSizeLimitExceededException;
import com.chengyinhub.common.exception.file.InvalidExtensionException;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.uuid.IdUtils;
import com.chengyinhub.common.utils.uuid.Seq;

/**
 * 文件上传工具类
 * 
 * @author ruoyi
 */
public class FileUploadUtils
{
    /**
     * 默认大小 50M
     */
    public static final long DEFAULT_MAX_SIZE = 50 * 1024 * 1024L;

    /**
     * 默认的文件名最大长度 100
     */
    public static final int DEFAULT_FILE_NAME_LENGTH = 100;

    /**
     * 默认上传的地址
     */
    private static String defaultBaseDir = RuoYiConfig.getProfile();

    public static void setDefaultBaseDir(String defaultBaseDir)
    {
        FileUploadUtils.defaultBaseDir = defaultBaseDir;
    }

    public static String getDefaultBaseDir()
    {
        return defaultBaseDir;
    }

    /**
     * 以默认配置进行文件上传
     *
     * @param file 上传的文件
     * @return 文件名称
     * @throws Exception
     */
    public static final String upload(MultipartFile file) throws IOException
    {
        try
        {
            return upload(getDefaultBaseDir(), file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION);
        }
        catch (Exception e)
        {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 根据文件路径上传
     *
     * @param baseDir 相对应用的基目录
     * @param file 上传的文件
     * @return 文件名称
     * @throws IOException
     */
    public static final String upload(String baseDir, MultipartFile file) throws IOException
    {
        try
        {
            return upload(baseDir, file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION);
        }
        catch (Exception e)
        {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 文件上传
     *
     * @param baseDir 相对应用的基目录
     * @param file 上传的文件
     * @param allowedExtension 上传文件类型
     * @return 返回上传成功的文件名
     * @throws FileSizeLimitExceededException 如果超出最大大小
     * @throws FileNameLengthLimitExceededException 文件名太长
     * @throws IOException 比如读写文件出错时
     * @throws InvalidExtensionException 文件校验异常
     */
    public static final String upload(String baseDir, MultipartFile file, String[] allowedExtension)
            throws FileSizeLimitExceededException, IOException, FileNameLengthLimitExceededException,
            InvalidExtensionException
    {
        return upload(baseDir, file, allowedExtension, false);
    }
    
    /**
     * 文件上传
     *
     * @param baseDir 相对应用的基目录
     * @param file 上传的文件
     * @param useCustomNaming 系统自定义文件名
     * @param allowedExtension 上传文件类型
     * @return 返回上传成功的文件名
     * @throws FileSizeLimitExceededException 如果超出最大大小
     * @throws FileNameLengthLimitExceededException 文件名太长
     * @throws IOException 比如读写文件出错时
     * @throws InvalidExtensionException 文件校验异常
     */
    public static final String upload(String baseDir, MultipartFile file, String[] allowedExtension, boolean useCustomNaming)
            throws FileSizeLimitExceededException, IOException, FileNameLengthLimitExceededException,
            InvalidExtensionException
    {
        UploadDirectoryContext directoryContext = new UploadDirectoryContext(baseDir);
        try
        {
            return upload(baseDir, file, allowedExtension, useCustomNaming, directoryContext);
        }
        catch (IOException | RuntimeException e)
        {
            directoryContext.deleteOwnedEmptyDirectories();
            throw e;
        }
    }

    public static UploadDirectoryContext newUploadDirectoryContext(String baseDir)
    {
        return new UploadDirectoryContext(baseDir);
    }

    public static final String upload(String baseDir, MultipartFile file, String[] allowedExtension, boolean useCustomNaming,
            UploadDirectoryContext directoryContext)
            throws FileSizeLimitExceededException, IOException, FileNameLengthLimitExceededException,
            InvalidExtensionException
    {
        int fileNameLength = Objects.requireNonNull(file.getOriginalFilename()).length();
        if (fileNameLength > FileUploadUtils.DEFAULT_FILE_NAME_LENGTH)
        {
            throw new FileNameLengthLimitExceededException(FileUploadUtils.DEFAULT_FILE_NAME_LENGTH);
        }

        assertAllowed(file, allowedExtension);
        String fileName = useCustomNaming ? uuidFilename(file) : extractFilename(file);
        File target = new File(baseDir + File.separator + fileName);
        java.nio.file.Path targetPath = target.toPath();
        directoryContext.createMissingDirectories(targetPath.getParent());
        java.nio.file.Path temporaryPath = Files.createTempFile(targetPath.getParent(), ".upload-", ".tmp");
        try
        {
            file.transferTo(temporaryPath);
            try
            {
                Files.move(temporaryPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(temporaryPath, targetPath);
            }
        }
        catch (IOException | RuntimeException e)
        {
            try
            {
                Files.deleteIfExists(temporaryPath);
            }
            catch (IOException cleanupException)
            {
                e.addSuppressed(cleanupException);
            }
            directoryContext.deleteOwnedEmptyDirectories();
            throw e;
        }
        return getPathFileName(baseDir, fileName);
    }

    public static final class UploadDirectoryContext
    {
        private final Path baseDirectory;
        private final List<Path> createdDirectories = new ArrayList<Path>();

        private UploadDirectoryContext(String baseDir)
        {
            baseDirectory = new File(baseDir).toPath().toAbsolutePath().normalize();
        }

        private void createMissingDirectories(Path directory) throws IOException
        {
            Path normalizedDirectory = directory.toAbsolutePath().normalize();
            if (!normalizedDirectory.startsWith(baseDirectory))
            {
                throw new IOException("上传目录越界");
            }
            List<Path> missing = new ArrayList<Path>();
            for (Path current = normalizedDirectory; current != null && current.startsWith(baseDirectory); current = current.getParent())
            {
                if (!Files.exists(current))
                {
                    missing.add(current);
                }
            }
            Collections.reverse(missing);
            for (Path current : missing)
            {
                try
                {
                    Files.createDirectory(current);
                    createdDirectories.add(current);
                }
                catch (FileAlreadyExistsException ignored)
                {
                    // 并发上传先创建时，该目录不属于本次请求。
                }
            }
        }

        public void deleteOwnedEmptyDirectories()
        {
            for (int index = createdDirectories.size() - 1; index >= 0; index--)
            {
                try
                {
                    Files.delete(createdDirectories.get(index));
                }
                catch (DirectoryNotEmptyException ignored)
                {
                    // 其他上传已写入时，保留目录。
                }
                catch (IOException ignored)
                {
                    // 清理失败不覆盖原始上传异常。
                }
            }
        }
    }

    /**
     * 编码文件名(日期格式目录 + 原文件名 + 序列值 + 后缀)
     */
    public static final String extractFilename(MultipartFile file)
    {
        return StringUtils.format("{}/{}_{}.{}", DateUtils.datePath(), FilenameUtils.getBaseName(file.getOriginalFilename()), Seq.getId(Seq.uploadSeqType), getExtension(file));
    }

    /**
     * 编编码文件名(日期格式目录 + UUID + 后缀)
     */
    public static final String uuidFilename(MultipartFile file)
    {
        return StringUtils.format("{}/{}.{}", DateUtils.datePath(), IdUtils.fastSimpleUUID(), getExtension(file));
    }

    public static final File getAbsoluteFile(String uploadDir, String fileName) throws IOException
    {
        File desc = new File(uploadDir + File.separator + fileName);

        if (!desc.exists())
        {
            if (!desc.getParentFile().exists())
            {
                desc.getParentFile().mkdirs();
            }
        }
        return desc;
    }

    public static final String getPathFileName(String uploadDir, String fileName) throws IOException
    {
        int dirLastIndex = RuoYiConfig.getProfile().length() + 1;
        String currentDir = StringUtils.substring(uploadDir, dirLastIndex);
        return Constants.RESOURCE_PREFIX + "/" + currentDir + "/" + fileName;
    }

    /**
     * 文件大小校验
     *
     * @param file 上传的文件
     * @return
     * @throws FileSizeLimitExceededException 如果超出最大大小
     * @throws InvalidExtensionException
     */
    public static final void assertAllowed(MultipartFile file, String[] allowedExtension)
            throws FileSizeLimitExceededException, InvalidExtensionException
    {
        long size = file.getSize();
        if (size > DEFAULT_MAX_SIZE)
        {
            throw new FileSizeLimitExceededException(DEFAULT_MAX_SIZE / 1024 / 1024);
        }

        String fileName = file.getOriginalFilename();
        String extension = getExtension(file);
        if (allowedExtension != null && !isAllowedExtension(extension, allowedExtension))
        {
            if (allowedExtension == MimeTypeUtils.IMAGE_EXTENSION)
            {
                throw new InvalidExtensionException.InvalidImageExtensionException(allowedExtension, extension,
                        fileName);
            }
            else if (allowedExtension == MimeTypeUtils.FLASH_EXTENSION)
            {
                throw new InvalidExtensionException.InvalidFlashExtensionException(allowedExtension, extension,
                        fileName);
            }
            else if (allowedExtension == MimeTypeUtils.MEDIA_EXTENSION)
            {
                throw new InvalidExtensionException.InvalidMediaExtensionException(allowedExtension, extension,
                        fileName);
            }
            else if (allowedExtension == MimeTypeUtils.VIDEO_EXTENSION)
            {
                throw new InvalidExtensionException.InvalidVideoExtensionException(allowedExtension, extension,
                        fileName);
            }
            else
            {
                throw new InvalidExtensionException(allowedExtension, extension, fileName);
            }
        }
    }

    /**
     * App 上传入口的严格内容校验。通用后台上传仍保留既有的后缀/大小兼容语义。
     */
    public static final void assertAllowedStrict(MultipartFile file, String[] allowedExtension)
            throws FileSizeLimitExceededException, InvalidExtensionException
    {
        assertAllowed(file, allowedExtension);

        String fileName = file.getOriginalFilename();
        String extension = getExtension(file);
        if (MimeTypeUtils.hasActiveContentExtensionSegment(fileName)
                || !MimeTypeUtils.isAllowedContentType(extension, file.getContentType()))
        {
            throw new InvalidExtensionException(allowedExtension, extension, fileName);
        }
        try (InputStream input = file.getInputStream())
        {
            if (!MimeTypeUtils.hasExpectedContent(extension, input))
            {
                throw new InvalidExtensionException(allowedExtension, extension, fileName);
            }
        }
        catch (IOException e)
        {
            throw new InvalidExtensionException(allowedExtension, extension, fileName);
        }
    }

    /**
     * 判断MIME类型是否是允许的MIME类型
     *
     * @param extension
     * @param allowedExtension
     * @return
     */
    public static final boolean isAllowedExtension(String extension, String[] allowedExtension)
    {
        for (String str : allowedExtension)
        {
            if (str.equalsIgnoreCase(extension))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取文件名的后缀
     * 
     * @param file 表单文件
     * @return 后缀名
     */
    public static final String getExtension(MultipartFile file)
    {
        String extension = FilenameUtils.getExtension(file.getOriginalFilename());
        if (StringUtils.isEmpty(extension))
        {
            extension = MimeTypeUtils.getExtension(Objects.requireNonNull(file.getContentType()));
        }
        return extension;
    }
}
