package com.chengyinhub.web.controller.api;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.exception.file.FileNameLengthLimitExceededException;
import com.chengyinhub.common.exception.file.FileSizeLimitExceededException;
import com.chengyinhub.common.exception.file.InvalidExtensionException;
import com.chengyinhub.common.utils.file.FileOssUploadUtils;
import com.chengyinhub.common.utils.file.FileUploadUtils;
import com.chengyinhub.common.utils.file.FileUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 后台通用上传入口的本地与 OSS 存储编排。 */
@Service
public class AdminFileUploadService
{
    private final AdminFileUploadPolicy policy;
    private final OssUploader ossUploader;
    @org.springframework.beans.factory.annotation.Autowired
    private AppUploadService imageSecurity;
    private void reviewImage(MultipartFile file) {
        String name=file.getOriginalFilename();
        if(name==null || !name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(jpg|jpeg|png|gif)"))return;
        if(imageSecurity==null)throw new SecurityUnavailableException("图片安全检查暂不可用");
        try { imageSecurity.verifyImageForPublication(file); }
        catch(UploadSecurityUnavailableException e) {throw new SecurityUnavailableException(e.getMessage());}
        catch(UploadRejectedException e) {throw new RejectedException(e.getMessage(),e);}
    }

    public AdminFileUploadService()
    {
        this(new AdminFileUploadPolicy(), FileOssUploadUtils::upload);
    }

    AdminFileUploadService(OssUploader ossUploader)
    {
        this(new AdminFileUploadPolicy(), ossUploader);
    }

    private AdminFileUploadService(AdminFileUploadPolicy policy, OssUploader ossUploader)
    {
        this.policy = policy;
        this.ossUploader = ossUploader;
    }

    public JSONObject uploadOss(MultipartFile file)
    {
        MultipartFile prepared = policy.prepareOss(file);
        reviewImage(prepared);
        try
        {
            JSONObject result = ossUploader.upload(prepared);
            if (result == null || isBlank(result.getString("fileName")) || isBlank(result.getString("url")))
            {
                throw new IOException("对象存储未返回完整结果");
            }
            return result;
        }
        catch (IOException | RuntimeException e)
        {
            throw new StorageException("OSS文件上传失败", e);
        }
    }

    public String uploadLocal(String filePath, MultipartFile file)
    {
        MultipartFile prepared=policy.prepareLocal(file);reviewImage(prepared);
        return storeLocal(filePath,prepared,null);
    }

    public List<String> uploadLocals(String filePath, List<MultipartFile> files)
    {
        List<MultipartFile> preparedFiles = policy.prepareLocalBatch(files);
        for(MultipartFile prepared:preparedFiles)reviewImage(prepared);
        List<String> fileNames = new ArrayList<String>();
        FileUploadUtils.UploadDirectoryContext directoryContext = FileUploadUtils.newUploadDirectoryContext(filePath);
        try
        {
            for (MultipartFile preparedFile : preparedFiles)
            {
                fileNames.add(storeLocal(filePath, preparedFile, directoryContext));
            }
            return fileNames;
        }
        catch (RuntimeException e)
        {
            rollback(fileNames);
            directoryContext.deleteOwnedEmptyDirectories();
            throw e;
        }
    }

    public static class SecurityUnavailableException extends RuntimeException {
        public SecurityUnavailableException(String message) {super(message);}
    }

    private String storeLocal(String filePath, MultipartFile file, FileUploadUtils.UploadDirectoryContext directoryContext)
    {
        try
        {
            if (directoryContext == null)
            {
                return FileUploadUtils.upload(filePath, file, AdminFileUploadPolicy.LOCAL_EXTENSIONS, true);
            }
            return FileUploadUtils.upload(filePath, file, AdminFileUploadPolicy.LOCAL_EXTENSIONS, true, directoryContext);
        }
        catch (InvalidExtensionException | FileSizeLimitExceededException | FileNameLengthLimitExceededException e)
        {
            throw new RejectedException("不支持的文件类型或文件过大", e);
        }
        catch (IOException e)
        {
            throw new StorageException("本地文件上传失败", e);
        }
    }

    private void rollback(List<String> fileNames)
    {
        String prefix = Constants.RESOURCE_PREFIX + "/";
        int residue = 0;
        for (String fileName : fileNames)
        {
            if (fileName != null && fileName.startsWith(prefix))
            {
                String path = RuoYiConfig.getProfile() + "/" + fileName.substring(prefix.length());
                if (!FileUtils.deleteFile(path) && new java.io.File(path).exists())
                {
                    residue++;
                }
            }
        }
        if (residue > 0)
        {
            // 回滚未能删净：绝不把 deleteFile=false 当成功，抛出以保证请求整体失败。
            throw new StorageException("批量上传回滚清理失败", null);
        }
    }

    private boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    @FunctionalInterface
    interface OssUploader
    {
        JSONObject upload(MultipartFile file) throws IOException;
    }

    public static final class RejectedException extends RuntimeException
    {
        RejectedException(String message)
        {
            super(message);
        }

        RejectedException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    public static final class StorageException extends RuntimeException
    {
        private StorageException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
