package com.chengyinhub.web.controller.common;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.file.FileUtils;
import com.chengyinhub.framework.config.ServerConfig;
import com.chengyinhub.web.controller.api.AdminFileUploadService;

/**
 * 通用请求处理
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/common")
public class CommonController
{
    private static final Logger log = LoggerFactory.getLogger(CommonController.class);

    @Autowired
    private ServerConfig serverConfig;

    @Autowired
    private AdminFileUploadService adminFileUploadService;

    private static final String FILE_DELIMETER = ",";

    /**
     * 通用下载请求（已停用）。
     *
     * <p>旧 {@code fileName/delete} 合同曾让任意已认证用户按已知文件名读取或删除 download
     * 目录下的文件。该合同已废止：不解析参数、不触碰文件系统，避免泄露文件是否存在。
     *
     * <p>如未来确需磁盘下载，必须由生成链路签发绑定 requester 的不可猜测短凭证并按凭证消费，
     * 严禁恢复按 {@code fileName} 取文件的接口。
     *
     * @param fileName 已忽略（仅保留兼容旧 URL 形态）
     * @param delete 已忽略
     */
    @GetMapping("/download")
    public void fileDownload(String fileName, Boolean delete, HttpServletResponse response, HttpServletRequest request)
            throws IOException
    {
        log.warn("拒绝已废止的通用 fileName 下载合同");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":403,\"msg\":\"该下载接口已停用\"}");
    }

    /**
     * 通用上传请求（单个）
     */
    @PostMapping("/upload")
    public ResponseEntity<AjaxResult> uploadFile(MultipartFile file)
    {
        try
        {
            // 上传文件路径
            String filePath = RuoYiConfig.getUploadPath();
            // 上传并返回新文件名称
            String fileName = adminFileUploadService.uploadLocal(filePath, file);
            String url = serverConfig.getUrl() + fileName;
            AjaxResult ajax = AjaxResult.success();
            ajax.put("url", url);
            ajax.put("fileName", fileName);
            ajax.put("newFileName", FileUtils.getName(fileName));
            ajax.put("originalFilename", file.getOriginalFilename());
            return ResponseEntity.ok(ajax);
        }
        catch (AdminFileUploadService.RejectedException e)
        {
            return badUploadRequest(e.getMessage());
        }
        catch (AdminFileUploadService.StorageException e)
        {
            log.error("本地文件上传失败，类别=storage，文件数=1");
            return uploadFailed();
        }
    }


    @Operation(summary = "上传文件到OSS")
    @PostMapping("/uploadOSS")
    public ResponseEntity<AjaxResult> uploadOSS(MultipartFile file)
    {
        try
        {
            JSONObject jsonObject = adminFileUploadService.uploadOss(file);
            AjaxResult ajax = AjaxResult.success();
            ajax.put("fileName", jsonObject.get("fileName"));
            ajax.put("url", jsonObject.get("url"));
            return ResponseEntity.ok(ajax);
        }
        catch (AdminFileUploadService.RejectedException e)
        {
            return badUploadRequest(e.getMessage());
        }
        catch (AdminFileUploadService.StorageException e)
        {
            log.error("OSS文件上传失败，类别=storage，文件数=1");
            return uploadFailed();
        }
    }



    /**
     * 通用上传请求（多个）
     */
    @PostMapping("/uploads")
    public ResponseEntity<AjaxResult> uploadFiles(List<MultipartFile> files)
    {
        try
        {
            // 上传文件路径
            String filePath = RuoYiConfig.getUploadPath();
            List<String> urls = new ArrayList<String>();
            List<String> fileNames = adminFileUploadService.uploadLocals(filePath, files);
            List<String> newFileNames = new ArrayList<String>();
            List<String> originalFilenames = new ArrayList<String>();
            for (int index = 0; index < files.size(); index++)
            {
                MultipartFile file = files.get(index);
                String fileName = fileNames.get(index);
                String url = serverConfig.getUrl() + fileName;
                urls.add(url);
                newFileNames.add(FileUtils.getName(fileName));
                originalFilenames.add(file.getOriginalFilename());
            }
            AjaxResult ajax = AjaxResult.success();
            ajax.put("urls", StringUtils.join(urls, FILE_DELIMETER));
            ajax.put("fileNames", StringUtils.join(fileNames, FILE_DELIMETER));
            ajax.put("newFileNames", StringUtils.join(newFileNames, FILE_DELIMETER));
            ajax.put("originalFilenames", StringUtils.join(originalFilenames, FILE_DELIMETER));
            return ResponseEntity.ok(ajax);
        }
        catch (AdminFileUploadService.RejectedException e)
        {
            return badUploadRequest(e.getMessage());
        }
        catch (AdminFileUploadService.StorageException e)
        {
            log.error("批量本地文件上传失败，类别=storage，文件数={}", files == null ? 0 : files.size());
            return uploadFailed();
        }
    }

    // 错误也走 HTTP 200 承载 AjaxResult：element-ui 仅在 2xx 触发 on-success，
    // 由此 Vue FileUpload/ImageUpload 的 handleUploadSuccess 才会执行 number-- 与移除，
    // 避免走 on-error 导致 number/fileList 计数错位卡死；错误码/文案仍在响应体里清晰可读。
    private ResponseEntity<AjaxResult> badUploadRequest(String message)
    {
        return ResponseEntity.ok(AjaxResult.error(HttpStatus.BAD_REQUEST.value(), message));
    }

    private ResponseEntity<AjaxResult> uploadFailed()
    {
        return ResponseEntity.ok(AjaxResult.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "文件上传失败"));
    }

    /**
     * 本地资源通用下载
     *
     * <p>错误合同(负控见 CommonControllerResourceDownloadContractTest):参数非法返回稳定
     * 400 JSON、文件不存在返回稳定 404 JSON、其他失败返回稳定 500 JSON;错误体只含稳定错误码
     * 与人话文案,绝不回显资源路径、本地绝对路径或堆栈。不再 catch 后静默返回空/部分 200。
     */
    @GetMapping("/download/resource")
    public void resourceDownload(String resource, HttpServletRequest request, HttpServletResponse response)
    {
        try
        {
            if (StringUtils.isEmpty(resource) || !FileUtils.checkAllowDownload(resource))
            {
                writeDownloadError(response, HttpStatus.BAD_REQUEST.value(), "资源文件参数非法");
                return;
            }
            // 本地资源路径
            String localPath = RuoYiConfig.getProfile();
            // 数据库资源地址
            String downloadPath = localPath + FileUtils.stripPrefix(resource);
            // 下载名称
            String downloadName = StringUtils.substringAfterLast(downloadPath, "/");
            if (!new java.io.File(downloadPath).isFile())
            {
                writeDownloadError(response, HttpStatus.NOT_FOUND.value(), "资源文件不存在");
                return;
            }
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            FileUtils.setAttachmentResponseHeader(response, downloadName);
            FileUtils.writeBytes(downloadPath, response.getOutputStream());
        }
        catch (Exception e)
        {
            log.error("下载文件失败", e);
            // 仅在响应尚未提交时才能改写为稳定 500 JSON;若流式写出已部分提交(大文件中途失败),
            // 无法安全重置状态码,只能保留已输出内容并靠上面的 error 日志留痕——绝不伪装成完整成功。
            if (!response.isCommitted())
            {
                try
                {
                    writeDownloadError(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), "资源文件下载失败");
                }
                catch (IOException ignore)
                {
                    // 写错误体失败时不再抛给容器;根因已由上面的 error 日志记录
                }
            }
        }
    }

    /**
     * 写出稳定的 JSON 错误响应:只含 code + msg,不携带任何路径/堆栈/异常文本。
     */
    private void writeDownloadError(HttpServletResponse response, int code, String message)
            throws java.io.IOException
    {
        response.reset();
        response.setStatus(code);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.toJSONString(AjaxResult.error(code, message)));
    }
}
