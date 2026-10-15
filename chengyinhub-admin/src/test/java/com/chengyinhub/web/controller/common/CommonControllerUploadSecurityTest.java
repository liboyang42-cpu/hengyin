package com.chengyinhub.web.controller.common;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.Arrays;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;
import com.chengyinhub.common.config.RuoYiConfig;
import com.chengyinhub.framework.config.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CommonControllerUploadSecurityTest
{
    private String originalProfile;

    @BeforeEach
    void rememberProfile()
    {
        originalProfile = RuoYiConfig.getProfile();
    }

    @AfterEach
    void restoreProfile()
    {
        new RuoYiConfig().setProfile(originalProfile);
    }

    @Test
    void localUploadRejectsHtmlWithoutWriting(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();

        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "notice.html", "text/html",
                        "<html><script>alert(1)</script></html>".getBytes("UTF-8"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
        assertFalse(Files.exists(tempDir.resolve("upload")));
    }

    @Test
    void localUploadRejectsHtmlDisguisedAsPngWithoutWriting(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();

        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "notice.png", "image/png",
                        "<html><script>alert(1)</script></html>".getBytes("UTF-8"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
        assertFalse(Files.exists(tempDir.resolve("upload")));
    }

    @Test
    void localUploadRejectsHtmlDisguisedAsPdfWithoutWriting(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();

        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "notice.pdf", "application/pdf",
                        "<html><script>alert(1)</script></html>".getBytes("UTF-8"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void localUploadRejectsGenericZipDisguisedAsDocxWithoutWriting(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();

        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "notice.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        zipEntry("payload.txt", "not an office document"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void localUploadRejectsPdfDisguisedAsLegacyWordWithoutWriting(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();

        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "notice.doc", "application/msword",
                        "%PDF-1.4\n%%EOF".getBytes("ISO-8859-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void localUploadRejectsActiveMarkupDisguisedAsTextWithoutWriting(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();

        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "notice.txt", "text/plain",
                        "<svg onload=alert(1)>".getBytes("UTF-8"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void batchValidatesEveryFileBeforeWritingAnyFile(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();

        mvc.perform(multipart("/common/uploads")
                        .file(new MockMultipartFile("files", "cover.png", "image/png", image("png")))
                        .file(new MockMultipartFile("files", "notice.html", "text/html", "<html>unsafe</html>".getBytes("UTF-8"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
        assertFalse(Files.exists(tempDir.resolve("upload")));
    }

    @Test
    void ossUploadRejectsEmptyFileWithHttp400() throws Exception
    {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();

        mvc.perform(multipart("/common/uploadOSS")
                        .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void localUploadRejectsTruncatedImageDoubleExtensionAndActivePdf(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();

        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "truncated.png", "image/png",
                        new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
        // avatar.html.png：末段 png 合法，但中间夹了 html 主动内容段=真正双扩展伪装，必须拒绝。
        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "avatar.html.png", "image/png",
                        image("png"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "active.pdf", "application/pdf",
                        "%PDF-1.4\n/OpenAction 1 0 R\n%%EOF".getBytes("ISO-8859-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "escaped.pdf", "application/pdf",
                        "%PDF-1.4\n/J#61vaScript 1 0 R\n%%EOF".getBytes("ISO-8859-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void localUploadRejectsPdfWithCompressedObjectStreamWithoutWriting(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        // 压缩对象流（/ObjStm）里的主动结构无法在原始字节上安全检查，一律 fail-closed。
        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "objstm.pdf", "application/pdf",
                        "%PDF-1.5\n5 0 obj\n<</Type/ObjStm/N 1>>\nstream\nendstream\nendobj\n%%EOF".getBytes("ISO-8859-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void localUploadAcceptsLegitimateMultiDotFileName(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        // report.v2.pdf：合法多点名，末段 pdf、中间无主动内容段，应被接受。
        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "report.v2.pdf", "application/pdf",
                        "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes("ISO-8859-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.originalFilename").value("report.v2.pdf"));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(1L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void localUploadRejectsDoubleExtensionTrailingSpaceAndAdsWithoutWriting(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        MockMultipartFile[] rejected = {
                new MockMultipartFile("file", "avatar.svg.png", "image/png", image("png")),
                new MockMultipartFile("file", "report.pdf ", "application/pdf",
                        "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes("ISO-8859-1")),
                new MockMultipartFile("file", "avatar.png:evil.exe", "image/png", image("png")),
                new MockMultipartFile("file", "sub/dir/avatar.png", "image/png", image("png"))
        };

        for (MockMultipartFile file : rejected)
        {
            mvc.perform(multipart("/common/upload").file(file))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400));
        }

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void rejectionUsesHttp200SoVueSuccessHandlerReceivesCodeAndMessage() throws Exception
    {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        // element-ui 仅在 HTTP 2xx 触发 on-success；被拒也必须是 200 且体内带 code!=200 与 msg，
        // 否则走 on-error 不会 number--，Vue FileUpload/ImageUpload 会计数错位卡死。
        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "notice.html", "text/html",
                        "<html><script>alert(1)</script></html>".getBytes("UTF-8"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").isString());
    }

    @Test
    void localBatchAcceptsEightAndNineFilesRejectsTen(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();

        performBatch(mvc, 10).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
        performBatch(mvc, 8).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        performBatch(mvc, 9).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    private org.springframework.test.web.servlet.ResultActions performBatch(MockMvc mvc, int count) throws Exception
    {
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder request =
                multipart("/common/uploads");
        for (int index = 0; index < count; index++)
        {
            request.file(new MockMultipartFile("files", "cover" + index + ".png", "image/png", image("png")));
        }
        return mvc.perform(request);
    }

    @Test
    void localUploadRejectsExecutableBytesDisguisedAsText(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();

        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "program.txt", "text/plain",
                        "MZThis program cannot be run in DOS mode".getBytes("US-ASCII"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void localUploadExplicitlyRejectsActiveAndExecutableExtensions(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        MockMultipartFile[] rejected = {
                new MockMultipartFile("file", "page.htm", "text/html", "<html/>".getBytes("UTF-8")),
                new MockMultipartFile("file", "vector.svg", "image/svg+xml", "<svg/>".getBytes("UTF-8")),
                new MockMultipartFile("file", "script.js", "application/javascript", "alert(1)".getBytes("UTF-8")),
                new MockMultipartFile("file", "program.exe", "application/octet-stream", "MZpayload".getBytes("US-ASCII"))
        };

        for (MockMultipartFile file : rejected)
        {
            mvc.perform(multipart("/common/upload").file(file))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400));
        }

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void batchStorageFailureRemovesFilesWrittenByCurrentRequest(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMultipartFile first = new MockMultipartFile("file", "first.txt", "text/plain", "first".getBytes("UTF-8"));
        MockMultipartFile failing = new MockMultipartFile("file", "second.txt", "text/plain", "second".getBytes("UTF-8"))
        {
            @Override
            public void transferTo(Path destination) throws IOException
            {
                throw new IOException("simulated write failure /private/tmp/secret");
            }
        };

        ResponseEntity<AjaxResult> response = controller().uploadFiles(Arrays.asList(first, failing));

        // 存储失败也走 HTTP 200 承载 AjaxResult（code=500），避免 element-ui 走 on-error 卡死计数。
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(500, response.getBody().get(AjaxResult.CODE_TAG));
        assertEquals("文件上传失败", response.getBody().get(AjaxResult.MSG_TAG));
        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
        assertFalse(Files.exists(tempDir.resolve("upload")));
    }

    @Test
    void localUploadKeepsExistingVueImageAndDocumentFormats(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        MockMultipartFile[] files = {
                new MockMultipartFile("file", "image.jpg", "image/jpeg", image("jpg")),
                new MockMultipartFile("file", "image.jpeg", "image/jpeg", image("jpg")),
                new MockMultipartFile("file", "image.png", "image/png", image("png")),
                new MockMultipartFile("file", "image.gif", "image/gif", image("gif")),
                new MockMultipartFile("file", "manual.pdf", "application/pdf", "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes("ISO-8859-1")),
                new MockMultipartFile("file", "manual.doc", "application/msword", legacyOffice("WordDocument")),
                new MockMultipartFile("file", "manual.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", openXml("word/document.xml")),
                new MockMultipartFile("file", "table.xls", "application/vnd.ms-excel", legacyOffice("Workbook")),
                new MockMultipartFile("file", "table.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", openXml("xl/workbook.xml")),
                new MockMultipartFile("file", "slides.ppt", "application/vnd.ms-powerpoint", legacyOffice("PowerPoint Document")),
                new MockMultipartFile("file", "slides.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", openXml("ppt/presentation.xml")),
                new MockMultipartFile("file", "notes.txt", "text/plain", "合法 UTF-8 文本".getBytes("UTF-8"))
        };

        for (MockMultipartFile file : files)
        {
            mvc.perform(multipart("/common/upload").file(file))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.url").isString())
                    .andExpect(jsonPath("$.fileName").isString())
                    .andExpect(jsonPath("$.originalFilename").value(file.getOriginalFilename()));
        }

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(files.length, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void localUploadRejectsOpenXmlExternalRelationshipWithoutWriting(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        byte[] activeDocx = openXml("word/document.xml", "word/_rels/document.xml.rels",
                "<Relationship TargetMode = \"External\" Target=\"https://attacker.example\"/>");

        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "active.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", activeDocx)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void localUploadRejectsOpenXmlWithDoctypeEntityWithoutWriting(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        // document.xml 结构合法，但 settings.xml 携带 DOCTYPE/ENTITY：XML 语义解析器 fail-closed 拒绝。
        byte[] entityDocx = openXml("word/document.xml", "word/settings.xml",
                "<!DOCTYPE settings [<!ENTITY payload \"x\">]><settings/>");

        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "entity.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", entityDocx)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void localUploadRejectsOpenXmlWithExcessiveDecompressionRatioWithoutWriting(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        StringBuilder filler = new StringBuilder();
        for (int index = 0; index < 4 * 1024 * 1024; index++)
        {
            filler.append('A');
        }
        // 高度可压缩但绝对量不大的解压比 fixture（非攻击 payload），解压比超阈值即拒。
        byte[] ratioDocx = openXml("word/document.xml", "word/media/filler.bin", filler.toString());

        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "ratio.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ratioDocx)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void localUploadRejectsLegacyOleEncodedStreamWithoutWriting(@TempDir Path tempDir) throws Exception
    {
        new RuoYiConfig().setProfile(tempDir.toString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        // 旧 OLE 携带无法安全检查的加密/编码流（EncryptedPackage），必须 fail-closed。
        byte[] encodedDoc = legacyOfficeWithEntry("WordDocument", "EncryptedPackage");

        mvc.perform(multipart("/common/upload").file(new MockMultipartFile("file", "encoded.doc", "application/msword",
                        encodedDoc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        try (Stream<Path> paths = Files.walk(tempDir))
        {
            assertEquals(0L, paths.filter(Files::isRegularFile).count());
        }
    }

    private byte[] legacyOfficeWithEntry(String rootName, String extraName) throws Exception
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (POIFSFileSystem filesystem = new POIFSFileSystem())
        {
            filesystem.getRoot().createDocument(rootName, new ByteArrayInputStream(new byte[4096]));
            filesystem.getRoot().createDocument(extraName, new ByteArrayInputStream(new byte[64]));
            filesystem.writeFilesystem(bytes);
        }
        return bytes.toByteArray();
    }

    private byte[] zipEntry(String name, String content) throws Exception
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes))
        {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes("UTF-8"));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private byte[] openXml(String rootName) throws Exception
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (rootName.startsWith("word/"))
        {
            try (XWPFDocument document = new XWPFDocument())
            {
                document.createParagraph().createRun().setText("合法 Word 文档");
                document.write(bytes);
            }
        }
        else if (rootName.startsWith("xl/"))
        {
            try (XSSFWorkbook workbook = new XSSFWorkbook())
            {
                workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("合法 Excel 文档");
                workbook.write(bytes);
            }
        }
        else
        {
            try (XMLSlideShow presentation = new XMLSlideShow())
            {
                presentation.createSlide();
                presentation.write(bytes);
            }
        }
        return bytes.toByteArray();
    }

    private byte[] openXml(String rootName, String extraName, String extraContent) throws Exception
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes))
        {
            String mainContentType = rootName.startsWith("word/")
                    ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                    : rootName.startsWith("xl/")
                        ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
                        : "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
            writeZipEntry(zip, "[Content_Types].xml", "<Types><Override PartName=\"/" + rootName
                    + "\" ContentType=\"" + mainContentType + "\"/></Types>");
            writeZipEntry(zip, rootName, "<document/>");
            if (extraName != null)
            {
                writeZipEntry(zip, extraName, extraContent);
            }
        }
        return bytes.toByteArray();
    }

    private void writeZipEntry(ZipOutputStream zip, String name, String content) throws Exception
    {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes("UTF-8"));
        zip.closeEntry();
    }

    private byte[] legacyOffice(String rootName) throws Exception
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (POIFSFileSystem filesystem = new POIFSFileSystem())
        {
            filesystem.getRoot().createDocument(rootName, new ByteArrayInputStream(new byte[4096]));
            filesystem.writeFilesystem(bytes);
        }
        return bytes.toByteArray();
    }

    private byte[] image(String format) throws Exception
    {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        return bytes.toByteArray();
    }

    private CommonController controller()
    {
        CommonController controller = new CommonController() { @Override protected Long currentSystemUserId() {return 99L;} };
        com.chengyinhub.business.service.impl.AppMediaAssetService registry=org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.AppMediaAssetService.class);
        org.mockito.Mockito.when(registry.registerApprovedSystemImage(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call->call.getArgument(2));
        ReflectionTestUtils.setField(controller,"appMediaAssetService",registry);
        ReflectionTestUtils.setField(controller, "serverConfig", new ServerConfig());
        com.chengyinhub.web.controller.api.AdminFileUploadService upload=new com.chengyinhub.web.controller.api.AdminFileUploadService();
        ReflectionTestUtils.setField(upload,"imageSecurity",org.mockito.Mockito.mock(com.chengyinhub.web.controller.api.AppUploadService.class));
        ReflectionTestUtils.setField(controller, "adminFileUploadService", upload);
        return controller;
    }
}
