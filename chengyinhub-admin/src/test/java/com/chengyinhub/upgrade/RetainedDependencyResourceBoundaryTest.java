package com.chengyinhub.upgrade;

import com.chengyinhub.web.controller.business.MerchantLeadImportParser;
import okhttp3.internal.tls.OkHostnameVerifier;
import okio.Buffer;
import okio.GzipSource;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Small in-memory adversarial inputs. No network, global parser-limit mutation, or unbounded allocation. */
@Timeout(10)
class RetainedDependencyResourceBoundaryTest {
    @Test void duplicateOoxmlNamesAreRejectedAtTheActualImportBoundary() throws Exception {
        byte[] bytes = modifiedWorkbook(1, false, true);
        Exception failure = assertThrows(Exception.class, () -> parse(bytes));
        assertTrue(causes(failure).contains("more than 1 entry"), causes(failure));
    }

    @Test void compressedExpansionBombIsRejectedWithoutRelaxingPoiDefaults() throws Exception {
        byte[] bytes = modifiedWorkbook(1, true, false);
        assertTrue(bytes.length < 20_000);
        Exception failure = assertThrows(Exception.class, () -> parse(bytes));
        assertTrue(causes(failure).contains("Zip bomb detected"), causes(failure));
    }

    @Test void excessiveZipEntryCountIsRejectedAtTheActualImportBoundary() throws Exception {
        byte[] bytes = modifiedWorkbook(1001, false, false);
        Exception failure = assertThrows(Exception.class, () -> parse(bytes));
        assertTrue(causes(failure).contains("MAX_FILE_COUNT"), causes(failure));
    }

    @Test void importerClosesOwnedUploadStreamOnSuccess() throws Exception {
        TrackingStream stream = new TrackingStream(legacy());
        parseTracking(stream);
        assertTrue(stream.closed, "MultipartFile stream is owned by the parser");
    }

    @Test void importerClosesOwnedUploadStreamWhenWorkbookCreationFails() throws Exception {
        TrackingStream stream = new TrackingStream(new byte[]{1, 2, 3, 4});
        assertThrows(Exception.class, () -> parseTracking(stream));
        assertTrue(stream.closed, "Resource must close even before Workbook construction succeeds");
    }

    @Test void truncatedUnsignedGzipExtraFieldRaisesCheckedIoError() throws Exception {
        Buffer compressed = new Buffer().write(new byte[]{0x1f, (byte)0x8b, 8, 4, 0, 0, 0, 0, 0, 0, (byte)0xff, (byte)0xff});
        try (GzipSource gzip = new GzipSource(compressed)) {
            assertThrows(IOException.class, () -> gzip.read(new Buffer(), 1));
        }
    }

    @Test void jettisonSelfReferentialCollectionStopsAtItsRecursionBoundary() {
        List<Object> recursive = new ArrayList<>(); recursive.add(recursive);
        JSONException failure = assertThrows(JSONException.class, () -> new JSONArray(recursive));
        assertTrue(failure.getMessage().contains("recursion depth limit"));
    }

    @Test void nonAsciiCertificateNameCannotCaseFoldIntoAnAsciiHostname() throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames()).thenReturn(Collections.singletonList(Arrays.asList(2, "\u212a.example.invalid")));
        assertFalse(OkHostnameVerifier.INSTANCE.verify("k.example.invalid", certificate));
        when(certificate.getSubjectAlternativeNames()).thenReturn(Collections.singletonList(Arrays.asList(2, "K.example.invalid")));
        assertTrue(OkHostnameVerifier.INSTANCE.verify("k.example.invalid", certificate));
        assertFalse(OkHostnameVerifier.INSTANCE.verify("other.example.invalid", certificate));
    }

    private void parse(byte[] bytes) throws Exception {
        new MerchantLeadImportParser().parse(new MockMultipartFile("file", "boundary.xlsx", "application/octet-stream", bytes));
    }

    private void parseTracking(TrackingStream stream) throws Exception {
        MockMultipartFile upload = new MockMultipartFile("file", "boundary.xlsx", "application/octet-stream", new byte[]{1}) {
            @Override public InputStream getInputStream() { return stream; }
        };
        new MerchantLeadImportParser().parse(upload);
    }

    private byte[] legacy() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/upgrade/legacy-poi412.xlsx")) {
            assertNotNull(in); return in.readAllBytes();
        }
    }

    private byte[] modifiedWorkbook(int extraEntries, boolean compressible, boolean duplicate) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] firstSheet = null;
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes);
             ZipInputStream old = new ZipInputStream(new ByteArrayInputStream(legacy()))) {
            ZipEntry entry;
            while ((entry = old.getNextEntry()) != null) {
                byte[] content = old.readAllBytes();
                append(zip, entry.getName(), content);
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) firstSheet = content;
            }
            if (duplicate) {
                assertNotNull(firstSheet); append(zip, "xl/worksheets/sheet1.xml", firstSheet);
            } else {
                byte[] content = new byte[compressible ? 1_000_000 : 0];
                Arrays.fill(content, (byte)'a');
                for (int i = 0; i < extraEntries; i++) append(zip, "boundary/" + i + ".bin", content);
            }
        }
        return bytes.toByteArray();
    }

    private void append(ZipArchiveOutputStream zip, String name, byte[] data) throws IOException {
        zip.putArchiveEntry(new ZipArchiveEntry(name)); zip.write(data); zip.closeArchiveEntry();
    }

    private String causes(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) all.append(current.getMessage()).append('\n');
        return all.toString();
    }

    private static final class TrackingStream extends ByteArrayInputStream {
        boolean closed;
        TrackingStream(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
