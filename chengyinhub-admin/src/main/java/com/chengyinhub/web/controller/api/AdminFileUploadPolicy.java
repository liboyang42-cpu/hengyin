package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.exception.file.FileNameLengthLimitExceededException;
import com.chengyinhub.common.exception.file.FileSizeLimitExceededException;
import com.chengyinhub.common.exception.file.InvalidExtensionException;
import com.chengyinhub.common.utils.file.FileUploadUtils;
import com.chengyinhub.common.utils.file.MimeTypeUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** 后台文件的用途白名单、签名、容器与主动内容合同。 */
final class AdminFileUploadPolicy
{
    static final String[] LOCAL_EXTENSIONS = {
            "jpg", "jpeg", "png", "gif", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt"
    };
    private static final String[] IMAGE_EXTENSIONS = { "jpg", "jpeg", "png", "gif" };
    private static final String[] OSS_EXTENSIONS = { "jpg", "jpeg", "png", "gif" };
    // 统一后台上传合同：单文件与批量总量同为 50MiB，批量最多 9 个文件。
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
    private static final int MAX_BATCH_FILES = 9;
    private static final long MAX_BATCH_TOTAL_SIZE = 50L * 1024 * 1024;
    private static final long MAX_UNCOMPRESSED_SIZE = 50L * 1024 * 1024;
    // 压缩包解压比上限，配合绝对解压量共同拦截压缩炸弹。
    private static final long MAX_COMPRESSION_RATIO = 200L;
    private static final int MAX_ARCHIVE_ENTRIES = 2048;
    private static final Map<String, String> OPEN_XML_ROOTS = new HashMap<String, String>();
    private static final Map<String, String> OPEN_XML_CONTENT_TYPES = new HashMap<String, String>();
    private static final Map<String, String> OPEN_XML_MAIN_CONTENT_TYPES = new HashMap<String, String>();
    private static final Map<String, String> LEGACY_OFFICE_ROOTS = new HashMap<String, String>();
    private static final Map<String, String> LEGACY_OFFICE_CONTENT_TYPES = new HashMap<String, String>();

    static
    {
        OPEN_XML_ROOTS.put("docx", "word/document.xml");
        OPEN_XML_ROOTS.put("xlsx", "xl/workbook.xml");
        OPEN_XML_ROOTS.put("pptx", "ppt/presentation.xml");
        OPEN_XML_CONTENT_TYPES.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        OPEN_XML_CONTENT_TYPES.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        OPEN_XML_CONTENT_TYPES.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        OPEN_XML_MAIN_CONTENT_TYPES.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml");
        OPEN_XML_MAIN_CONTENT_TYPES.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml");
        OPEN_XML_MAIN_CONTENT_TYPES.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml");
        LEGACY_OFFICE_ROOTS.put("doc", "WordDocument");
        LEGACY_OFFICE_ROOTS.put("xls", "Workbook");
        LEGACY_OFFICE_ROOTS.put("ppt", "PowerPoint Document");
        LEGACY_OFFICE_CONTENT_TYPES.put("doc", "application/msword");
        LEGACY_OFFICE_CONTENT_TYPES.put("xls", "application/vnd.ms-excel");
        LEGACY_OFFICE_CONTENT_TYPES.put("ppt", "application/vnd.ms-powerpoint");
    }

    MultipartFile prepareLocal(MultipartFile file)
    {
        assertPresent(file);
        try
        {
            FileUploadUtils.assertAllowed(file, LOCAL_EXTENSIONS);
            assertCommonContract(file);
            String extension = extension(file);
            if (FileUploadUtils.isAllowedExtension(extension, IMAGE_EXTENSIONS))
            {
                return prepareImage(file, IMAGE_EXTENSIONS);
            }
            if ("pdf".equals(extension))
            {
                validatePdf(file);
            }
            else if (OPEN_XML_ROOTS.containsKey(extension))
            {
                validateOpenXml(file, extension);
            }
            else if (LEGACY_OFFICE_ROOTS.containsKey(extension))
            {
                validateLegacyOffice(file, extension);
            }
            else if ("txt".equals(extension))
            {
                validateText(file);
            }
            return file;
        }
        catch (InvalidExtensionException | FileSizeLimitExceededException | FileNameLengthLimitExceededException e)
        {
            throw new AdminFileUploadService.RejectedException("不支持的文件类型或文件过大", e);
        }
        catch (IllegalArgumentException e)
        {
            throw new AdminFileUploadService.RejectedException(safePolicyMessage(e), e);
        }
    }

    List<MultipartFile> prepareLocalBatch(List<MultipartFile> files)
    {
        if (files == null || files.isEmpty() || files.size() > MAX_BATCH_FILES)
        {
            throw new AdminFileUploadService.RejectedException("上传文件数量超出限制");
        }
        // 先按真实读取的字节独立累计总量（不信 getSize），任一超限都在任何文件落盘前拒绝。
        long cumulativeSize = 0L;
        for (MultipartFile file : files)
        {
            if (file == null)
            {
                throw new AdminFileUploadService.RejectedException("上传文件不能为空");
            }
            cumulativeSize += assertReadableSizeWithinLimit(file, MAX_FILE_SIZE);
            if (cumulativeSize > MAX_BATCH_TOTAL_SIZE)
            {
                throw new AdminFileUploadService.RejectedException("上传文件总大小超出限制");
            }
        }
        List<MultipartFile> prepared = new ArrayList<MultipartFile>();
        for (MultipartFile file : files)
        {
            prepared.add(prepareLocal(file));
        }
        return prepared;
    }

    MultipartFile prepareOss(MultipartFile file)
    {
        assertPresent(file);
        try
        {
            assertCommonContract(file);
            return prepareImage(file, OSS_EXTENSIONS);
        }
        catch (InvalidExtensionException | FileSizeLimitExceededException | FileNameLengthLimitExceededException e)
        {
            throw new AdminFileUploadService.RejectedException("不支持的文件类型或文件过大", e);
        }
        catch (IllegalArgumentException e)
        {
            throw new AdminFileUploadService.RejectedException(safePolicyMessage(e), e);
        }
    }

    private MultipartFile prepareImage(MultipartFile file, String[] allowedExtensions)
            throws FileSizeLimitExceededException, InvalidExtensionException
    {
        FileUploadUtils.assertAllowedStrict(file, allowedExtensions);
        UploadImagePolicy.validate(file, null);
        MultipartFile sanitized = UploadImagePolicy.sanitizeMetadata(file, null);
        if (sanitized.getSize() > MAX_FILE_SIZE)
        {
            throw new AdminFileUploadService.RejectedException("上传文件大小超出限制");
        }
        return sanitized;
    }

    private void assertPresent(MultipartFile file)
    {
        if (file == null || file.isEmpty())
        {
            throw new AdminFileUploadService.RejectedException("上传文件不能为空");
        }
    }

    private void assertCommonContract(MultipartFile file)
    {
        assertReadableSizeWithinLimit(file, MAX_FILE_SIZE);
        assertSafeFileName(file.getOriginalFilename());
        assertNotExecutable(file);
    }

    /**
     * 独立读取真实字节流计数，绝不信任 MultipartFile.getSize：
     * 超过单文件上限或声称大小大于实际（截断）都在任何本地/OSS 落盘前拒绝，并返回真实字节数。
     */
    long assertReadableSizeWithinLimit(MultipartFile file, long limit)
    {
        long counted = 0L;
        try (InputStream input = file.getInputStream())
        {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0)
            {
                counted += read;
                if (counted > limit)
                {
                    throw new AdminFileUploadService.RejectedException("上传文件大小超出限制");
                }
            }
        }
        catch (AdminFileUploadService.RejectedException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            throw new AdminFileUploadService.RejectedException("文件内容无法读取", e);
        }
        if (file.getSize() > counted)
        {
            throw new AdminFileUploadService.RejectedException("上传文件不完整");
        }
        return counted;
    }

    private void assertSafeFileName(String fileName)
    {
        if (fileName == null || fileName.isEmpty() || !fileName.equals(fileName.trim())
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 || fileName.indexOf(':') >= 0
                || hasControlCharacter(fileName))
        {
            throw new AdminFileUploadService.RejectedException("文件名格式不安全");
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == fileName.length() - 1)
        {
            throw new AdminFileUploadService.RejectedException("文件名格式不安全");
        }
        // 合法多点名（report.v2.pdf）放行；任一非首段是主动内容扩展即视为双扩展伪装。
        if (MimeTypeUtils.hasActiveContentExtensionSegment(fileName))
        {
            throw new AdminFileUploadService.RejectedException("文件名格式不安全");
        }
    }

    private boolean hasControlCharacter(String value)
    {
        for (int index = 0; index < value.length(); index++)
        {
            char current = value.charAt(index);
            if (current < 0x20 || current == 0x7F)
            {
                return true;
            }
        }
        return false;
    }

    private void assertNotExecutable(MultipartFile file)
    {
        try (InputStream input = file.getInputStream())
        {
            byte[] header = new byte[8];
            int length = input.read(header);
            if ((length >= 2 && header[0] == 'M' && header[1] == 'Z')
                    || (length >= 4 && (header[0] & 0xFF) == 0x7F && header[1] == 'E' && header[2] == 'L' && header[3] == 'F')
                    || (length >= 2 && header[0] == '#' && header[1] == '!')
                    || (length >= 4 && (header[0] & 0xFF) == 0xCA && (header[1] & 0xFF) == 0xFE
                        && (header[2] & 0xFF) == 0xBA && (header[3] & 0xFF) == 0xBE))
            {
                throw new AdminFileUploadService.RejectedException("不允许上传可执行内容");
            }
        }
        catch (AdminFileUploadService.RejectedException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            throw new AdminFileUploadService.RejectedException("文件内容无法读取", e);
        }
    }

    private void validatePdf(MultipartFile file)
    {
        if (!"application/pdf".equals(normalizeContentType(file.getContentType())))
        {
            throw new AdminFileUploadService.RejectedException("文件内容与类型不匹配");
        }
        try
        {
            byte[] bytes = file.getBytes();
            String content = new String(bytes, StandardCharsets.ISO_8859_1);
            String lower = content.toLowerCase(Locale.ROOT);
            if (!content.startsWith("%PDF-") || content.lastIndexOf("%%EOF") < Math.max(0, content.length() - 1024)
                    || containsActivePdfContent(lower))
            {
                throw new AdminFileUploadService.RejectedException("文件内容与类型不匹配");
            }
        }
        catch (IOException e)
        {
            throw new AdminFileUploadService.RejectedException("文件内容无法读取", e);
        }
    }

    private void validateOpenXml(MultipartFile file, String extension)
    {
        String contentType = normalizeContentType(file.getContentType());
        if (!OPEN_XML_CONTENT_TYPES.get(extension).equals(contentType) && !"application/zip".equals(contentType)
                && !"application/octet-stream".equals(contentType))
        {
            throw new AdminFileUploadService.RejectedException("文件内容与类型不匹配");
        }
        boolean rootFound = false;
        boolean contentTypesMatch = false;
        int entries = 0;
        long totalSize = 0L;
        Set<String> entryNames = new HashSet<String>();
        long[] compressedBytes = { 0L };
        // 计数真实压缩字节，配合解压量算解压比；ZipInputStream 会一路读到尾部并对截断抛错。
        try (InputStream source = countingStream(file.getInputStream(), compressedBytes);
                ZipInputStream zip = new ZipInputStream(source))
        {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null)
            {
                entries++;
                String entryName = entry.getName();
                if (entries > MAX_ARCHIVE_ENTRIES || isUnsafeArchivePath(entryName) || !entryNames.add(entryName)
                        || isActiveOfficeEntry(entryName))
                {
                    throw new AdminFileUploadService.RejectedException("办公文件包含不安全内容");
                }
                if (OPEN_XML_ROOTS.get(extension).equals(entryName))
                {
                    rootFound = true;
                }
                ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) >= 0)
                {
                    totalSize += read;
                    if (totalSize > MAX_UNCOMPRESSED_SIZE)
                    {
                        throw new AdminFileUploadService.RejectedException("办公文件解压后内容过大");
                    }
                    entryBytes.write(buffer, 0, read);
                }
                byte[] data = entryBytes.toByteArray();
                String lower = new String(data, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                if ("[Content_Types].xml".equals(entryName))
                {
                    contentTypesMatch = lower.contains(OPEN_XML_MAIN_CONTENT_TYPES.get(extension))
                            && lower.contains("/" + OPEN_XML_ROOTS.get(extension));
                }
                assertSafeOfficeXml(entryName, data);
                if (containsActiveOfficeContent(lower))
                {
                    throw new AdminFileUploadService.RejectedException("办公文件包含不安全内容");
                }
                zip.closeEntry();
            }
        }
        catch (AdminFileUploadService.RejectedException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            throw new AdminFileUploadService.RejectedException("文件内容与类型不匹配", e);
        }
        if (compressedBytes[0] > 0 && totalSize > MAX_COMPRESSION_RATIO * compressedBytes[0])
        {
            throw new AdminFileUploadService.RejectedException("办公文件解压比异常");
        }
        if (!contentTypesMatch || !rootFound)
        {
            throw new AdminFileUploadService.RejectedException("文件内容与类型不匹配");
        }
    }

    private InputStream countingStream(InputStream source, long[] counter)
    {
        return new FilterInputStream(source)
        {
            @Override
            public int read() throws IOException
            {
                int value = super.read();
                if (value >= 0)
                {
                    counter[0]++;
                }
                return value;
            }

            @Override
            public int read(byte[] target, int off, int len) throws IOException
            {
                int count = super.read(target, off, len);
                if (count > 0)
                {
                    counter[0] += count;
                }
                return count;
            }
        };
    }

    /** 用 XML 语义（而非原始子串）识别外部关系与实体编码，遇到 DOCTYPE/实体声明或畸形 XML 一律 fail-closed。 */
    private void assertSafeOfficeXml(String entryName, byte[] data)
    {
        String lower = entryName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xml") && !lower.endsWith(".rels"))
        {
            return;
        }
        Document document;
        try
        {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(data));
        }
        catch (Exception e)
        {
            throw new AdminFileUploadService.RejectedException("办公文件包含不安全内容", e);
        }
        if (lower.endsWith(".rels"))
        {
            NodeList relationships = document.getElementsByTagNameNS("*", "Relationship");
            for (int index = 0; index < relationships.getLength(); index++)
            {
                Node mode = relationships.item(index).getAttributes().getNamedItem("TargetMode");
                if (mode != null && "external".equalsIgnoreCase(mode.getNodeValue().trim()))
                {
                    throw new AdminFileUploadService.RejectedException("办公文件包含不安全内容");
                }
            }
        }
    }

    private void validateLegacyOffice(MultipartFile file, String extension)
    {
        String contentType = normalizeContentType(file.getContentType());
        if (!LEGACY_OFFICE_CONTENT_TYPES.get(extension).equals(contentType)
                && !"application/octet-stream".equals(contentType) && !"application/x-ole-storage".equals(contentType))
        {
            throw new AdminFileUploadService.RejectedException("文件内容与类型不匹配");
        }
        try (InputStream source = file.getInputStream(); POIFSFileSystem filesystem = new POIFSFileSystem(source))
        {
            DirectoryEntry root = filesystem.getRoot();
            if (!hasRequiredLegacyRoot(root, extension) || hasActiveOleEntry(root))
            {
                throw new AdminFileUploadService.RejectedException("办公文件包含不安全内容");
            }
            String lower = new String(file.getBytes(), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            if (lower.contains("ddeauto") || lower.contains("cmd.exe") || lower.contains("powershell")
                    || lower.contains("javascript:"))
            {
                throw new AdminFileUploadService.RejectedException("办公文件包含不安全内容");
            }
        }
        catch (AdminFileUploadService.RejectedException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new AdminFileUploadService.RejectedException("文件内容与类型不匹配", e);
        }
    }

    private boolean hasRequiredLegacyRoot(DirectoryEntry root, String extension)
    {
        return root.hasEntry(LEGACY_OFFICE_ROOTS.get(extension)) || ("xls".equals(extension) && root.hasEntry("Book"));
    }

    private void validateText(MultipartFile file)
    {
        if (!"text/plain".equals(normalizeContentType(file.getContentType())))
        {
            throw new AdminFileUploadService.RejectedException("文件内容与类型不匹配");
        }
        try
        {
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.getBytes())).toString();
            String normalized = content.startsWith("\uFEFF") ? content.substring(1) : content;
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (containsUnsafeTextControl(normalized) || lower.contains("<html") || lower.contains("<!doctype html")
                    || lower.contains("<svg") || lower.contains("<script") || lower.contains("javascript:"))
            {
                throw new AdminFileUploadService.RejectedException("文本文件包含不安全内容");
            }
        }
        catch (CharacterCodingException e)
        {
            throw new AdminFileUploadService.RejectedException("文本文件编码不支持", e);
        }
        catch (IOException e)
        {
            throw new AdminFileUploadService.RejectedException("文件内容无法读取", e);
        }
    }

    private boolean isUnsafeArchivePath(String name)
    {
        return name == null || name.startsWith("/") || name.contains("\\") || name.contains("../");
    }

    private boolean isActiveOfficeEntry(String name)
    {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("vbaproject.bin") || lower.contains("/activex/") || lower.contains("/embeddings/")
                || lower.contains("oleobject") || lower.contains("macrosheets") || lower.contains("externallinks")
                || lower.startsWith("_xmlsignatures/") || lower.endsWith(".class")
                || "meta-inf/manifest.mf".equals(lower);
    }

    private boolean containsActiveOfficeContent(String lower)
    {
        String compact = compactMarkup(lower);
        return compact.contains("<script") || compact.contains("javascript:") || compact.contains("ddeauto")
                || compact.contains("cmd.exe") || compact.contains("powershell");
    }

    private boolean hasActiveOleEntry(DirectoryEntry directory)
    {
        for (Entry entry : directory)
        {
            String lower = entry.getName().toLowerCase(Locale.ROOT);
            // 主动内容与无法安全检查的编码/加密流（DataSpaces/EncryptedPackage/Ole10Native）一律 fail-closed。
            if (lower.contains("vba") || lower.contains("macro") || lower.contains("objectpool")
                    || lower.contains("dataspaces") || lower.contains("encryptedpackage")
                    || lower.contains("encryptioninfo") || lower.contains("ole10native"))
            {
                return true;
            }
            if (entry.isDirectoryEntry() && hasActiveOleEntry((DirectoryEntry) entry))
            {
                return true;
            }
        }
        return false;
    }

    private boolean containsUnsafeTextControl(String content)
    {
        for (int index = 0; index < content.length(); index++)
        {
            char value = content.charAt(index);
            if (value == 0 || value == 0x7F || (value < 0x20 && value != '\r' && value != '\n' && value != '\t'))
            {
                return true;
            }
        }
        return false;
    }

    private boolean containsActivePdfContent(String lower)
    {
        String normalized = decodePdfEscapes(lower);
        String compact = compactMarkup(normalized);
        // /objstm 是压缩对象流：主动结构可藏于其中且无法在原始字节上安全检查，一律 fail-closed。
        return compact.contains("/javascript") || compact.contains("/js") || compact.contains("/openaction")
                || compact.contains("/aa") || compact.contains("/launch") || compact.contains("/embeddedfile")
                || compact.contains("/richmedia") || compact.contains("/xfa") || compact.contains("/objstm");
    }

    private String compactMarkup(String value)
    {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String decodePdfEscapes(String value)
    {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++)
        {
            if (value.charAt(index) == '#' && index + 2 < value.length())
            {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high >= 0 && low >= 0)
                {
                    decoded.append((char) ((high << 4) + low));
                    index += 2;
                    continue;
                }
            }
            decoded.append(value.charAt(index));
        }
        return decoded.toString();
    }

    private String extension(MultipartFile file)
    {
        return FileUploadUtils.getExtension(file).toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String contentType)
    {
        if (contentType == null)
        {
            return "";
        }
        int separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator)).trim().toLowerCase(Locale.ROOT);
    }

    private String safePolicyMessage(IllegalArgumentException exception)
    {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? "文件内容不符合上传要求" : message;
    }
}
