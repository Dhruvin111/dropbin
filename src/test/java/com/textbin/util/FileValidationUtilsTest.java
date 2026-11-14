package com.textbin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FileValidationUtilsTest {

    // ─── 1. Category Resolution ──────────────────────────────────────────────

    @Test
    @DisplayName("determineCategory should accurately map valid extensions and reject others")
    void testDetermineCategory() {
        assertThat(FileValidationUtils.determineCategory("photo.png")).isEqualTo("IMAGE");
        assertThat(FileValidationUtils.determineCategory("photo.jpg")).isEqualTo("IMAGE");
        assertThat(FileValidationUtils.determineCategory("photo.jpeg")).isEqualTo("IMAGE");
        assertThat(FileValidationUtils.determineCategory("photo.gif")).isEqualTo("IMAGE");
        assertThat(FileValidationUtils.determineCategory("photo.webp")).isEqualTo("IMAGE");
        assertThat(FileValidationUtils.determineCategory("photo.bmp")).isEqualTo("IMAGE");
        assertThat(FileValidationUtils.determineCategory("vector.svg")).isEqualTo("IMAGE");
        assertThat(FileValidationUtils.determineCategory("doc.pdf")).isEqualTo("PDF");
        assertThat(FileValidationUtils.determineCategory("doc.docx")).isEqualTo("WORD");
        assertThat(FileValidationUtils.determineCategory("doc.doc")).isEqualTo("WORD");

        // Disallowed extensions must return null
        assertThat(FileValidationUtils.determineCategory("script.sh")).isNull();
        assertThat(FileValidationUtils.determineCategory("page.html")).isNull();
        assertThat(FileValidationUtils.determineCategory("app.exe")).isNull();
        assertThat(FileValidationUtils.determineCategory("no_ext")).isNull();
        assertThat(FileValidationUtils.determineCategory(null)).isNull();
    }

    // ─── 2. Safe Content-Type Resolution ─────────────────────────────────────

    @Test
    @DisplayName("resolveSafeContentType should map known extensions and fallback safely")
    void testResolveSafeContentType() {
        assertThat(FileValidationUtils.resolveSafeContentType("test.png")).isEqualTo("image/png");
        assertThat(FileValidationUtils.resolveSafeContentType("test.jpg")).isEqualTo("image/jpeg");
        assertThat(FileValidationUtils.resolveSafeContentType("test.jpeg")).isEqualTo("image/jpeg");
        assertThat(FileValidationUtils.resolveSafeContentType("test.gif")).isEqualTo("image/gif");
        assertThat(FileValidationUtils.resolveSafeContentType("test.webp")).isEqualTo("image/webp");
        assertThat(FileValidationUtils.resolveSafeContentType("test.bmp")).isEqualTo("image/bmp");
        assertThat(FileValidationUtils.resolveSafeContentType("test.svg")).isEqualTo("image/svg+xml");
        assertThat(FileValidationUtils.resolveSafeContentType("test.pdf")).isEqualTo("application/pdf");
        assertThat(FileValidationUtils.resolveSafeContentType("test.doc")).isEqualTo("application/msword");
        assertThat(FileValidationUtils.resolveSafeContentType("test.docx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        // Fallback for unknown extensions
        assertThat(FileValidationUtils.resolveSafeContentType("exploit.php")).isEqualTo("application/octet-stream");
        assertThat(FileValidationUtils.resolveSafeContentType(null)).isEqualTo("application/octet-stream");
    }

    // ─── 3. Magic Byte Signature Verification (Images) ────────────────────────

    @Test
    @DisplayName("PNG: Should accept valid magic bytes and reject text")
    void testPngValidation() {
        byte[] validPng = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        MockMultipartFile validFile = new MockMultipartFile("file", "image.png", "image/png", validPng);
        assertThat(FileValidationUtils.isValidFileSignature("image.png", validFile)).isTrue();

        MockMultipartFile fakeFile = new MockMultipartFile("file", "fake.png", "image/png", "not a real png".getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("fake.png", fakeFile)).isFalse();
    }

    @Test
    @DisplayName("JPEG: Should accept FF D8 FF and reject invalid header")
    void testJpegValidation() {
        byte[] validJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        MockMultipartFile validFile = new MockMultipartFile("file", "photo.jpg", "image/jpeg", validJpeg);
        assertThat(FileValidationUtils.isValidFileSignature("photo.jpg", validFile)).isTrue();

        MockMultipartFile fakeFile = new MockMultipartFile("file", "photo.jpg", "image/jpeg", "fake jpg content".getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("photo.jpg", fakeFile)).isFalse();
    }

    @Test
    @DisplayName("GIF: Should accept GIF87a and GIF89a, reject corrupted header")
    void testGifValidation() {
        byte[] gif87 = "GIF87a...data".getBytes(StandardCharsets.US_ASCII);
        byte[] gif89 = "GIF89a...data".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile f87 = new MockMultipartFile("file", "anim.gif", "image/gif", gif87);
        MockMultipartFile f89 = new MockMultipartFile("file", "anim.gif", "image/gif", gif89);
        assertThat(FileValidationUtils.isValidFileSignature("anim.gif", f87)).isTrue();
        assertThat(FileValidationUtils.isValidFileSignature("anim.gif", f89)).isTrue();

        MockMultipartFile fake = new MockMultipartFile("file", "anim.gif", "image/gif", "GIF99a...bad".getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("anim.gif", fake)).isFalse();
    }

    @Test
    @DisplayName("WebP: Should accept RIFF....WEBP and reject invalid header")
    void testWebpValidation() {
        byte[] validWebp = new byte[]{
                'R', 'I', 'F', 'F',
                0, 0, 0, 0, // length
                'W', 'E', 'B', 'P'
        };
        MockMultipartFile valid = new MockMultipartFile("file", "image.webp", "image/webp", validWebp);
        assertThat(FileValidationUtils.isValidFileSignature("image.webp", valid)).isTrue();

        MockMultipartFile fake = new MockMultipartFile("file", "image.webp", "image/webp", "RIFF....TEXT".getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("image.webp", fake)).isFalse();
    }

    @Test
    @DisplayName("BMP: Should accept BM signature and reject text")
    void testBmpValidation() {
        byte[] validBmp = new byte[]{0x42, 0x4D, 0, 0, 0, 0};
        MockMultipartFile valid = new MockMultipartFile("file", "image.bmp", "image/bmp", validBmp);
        assertThat(FileValidationUtils.isValidFileSignature("image.bmp", valid)).isTrue();

        MockMultipartFile fake = new MockMultipartFile("file", "image.bmp", "image/bmp", "XX...bad".getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("image.bmp", fake)).isFalse();
    }

    // ─── 4. Magic Byte Signature Verification (Documents) ────────────────────

    @Test
    @DisplayName("PDF: Should accept standard %PDF / %PDF- and reject text")
    void testPdfValidation() {
        byte[] validPdf = "%PDF-1.7\n%obj...".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile valid = new MockMultipartFile("file", "document.pdf", "application/pdf", validPdf);
        assertThat(FileValidationUtils.isValidFileSignature("document.pdf", valid)).isTrue();

        MockMultipartFile fake = new MockMultipartFile("file", "document.pdf", "application/pdf", "Plain text disguised as PDF".getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("document.pdf", fake)).isFalse();
    }

    @Test
    @DisplayName("DOC: Should accept OLE2 compound file header and reject text")
    void testDocValidation() {
        byte[] oleHeader = new byte[]{
                (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
        };
        MockMultipartFile valid = new MockMultipartFile("file", "resume.doc", "application/msword", oleHeader);
        assertThat(FileValidationUtils.isValidFileSignature("resume.doc", valid)).isTrue();

        MockMultipartFile fake = new MockMultipartFile("file", "resume.doc", "application/msword", "This is plain text".getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("resume.doc", fake)).isFalse();
    }

    @Test
    @DisplayName("DOCX: Should accept valid ZIP with OpenXML content and reject arbitrary ZIP or text")
    void testDocxValidation() throws IOException {
        // Valid DOCX: ZIP containing [Content_Types].xml and word/document.xml
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write("<Types></Types>".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write("<w:document></w:document>".getBytes());
            zos.closeEntry();
        }
        MockMultipartFile valid = new MockMultipartFile("file", "notes.docx", "application/vnd.openxmlformats", baos.toByteArray());
        assertThat(FileValidationUtils.isValidFileSignature("notes.docx", valid)).isTrue();

        // Arbitrary ZIP without OpenXML structure
        ByteArrayOutputStream badZip = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(badZip)) {
            zos.putNextEntry(new ZipEntry("malware.exe"));
            zos.write("payload".getBytes());
            zos.closeEntry();
        }
        MockMultipartFile invalidDocx = new MockMultipartFile("file", "notes.docx", "application/vnd.openxmlformats", badZip.toByteArray());
        assertThat(FileValidationUtils.isValidFileSignature("notes.docx", invalidDocx)).isFalse();

        // Plain text disguised as DOCX
        MockMultipartFile textFile = new MockMultipartFile("file", "notes.docx", "application/vnd.openxmlformats", "plain text".getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("notes.docx", textFile)).isFalse();
    }

    // ─── 5. SVG Sanitization & Security Verification ─────────────────────────

    @Test
    @DisplayName("SVG: Should accept clean SVG and reject XSS or XXE vectors")
    void testSvgValidation() {
        // 1. Valid clean SVG
        String cleanSvg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\">" +
                "<circle cx=\"50\" cy=\"50\" r=\"40\" fill=\"red\" />" +
                "</svg>";
        MockMultipartFile validSvg = new MockMultipartFile("file", "graphic.svg", "image/svg+xml", cleanSvg.getBytes(StandardCharsets.UTF_8));
        assertThat(FileValidationUtils.isValidFileSignature("graphic.svg", validSvg)).isTrue();

        // 2. Reject SVG with <script> tag
        String xssScript = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>";
        MockMultipartFile f1 = new MockMultipartFile("file", "graphic.svg", "image/svg+xml", xssScript.getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("graphic.svg", f1)).isFalse();

        // 3. Reject SVG with event handler onload
        String xssOnload = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"></svg>";
        MockMultipartFile f2 = new MockMultipartFile("file", "graphic.svg", "image/svg+xml", xssOnload.getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("graphic.svg", f2)).isFalse();

        // 4. Reject SVG with javascript: pseudo-protocol in link
        String xssLink = "<svg xmlns=\"http://www.w3.org/2000/svg\"><a href=\"javascript:alert(1)\"><text>click</text></a></svg>";
        MockMultipartFile f3 = new MockMultipartFile("file", "graphic.svg", "image/svg+xml", xssLink.getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("graphic.svg", f3)).isFalse();

        // 5. Reject SVG with <foreignObject>
        String xssForeign = "<svg xmlns=\"http://www.w3.org/2000/svg\"><foreignObject width=\"100\" height=\"100\">" +
                "<body xmlns=\"http://www.w3.org/1999/xhtml\"><div>XSS</div></body></foreignObject></svg>";
        MockMultipartFile f4 = new MockMultipartFile("file", "graphic.svg", "image/svg+xml", xssForeign.getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("graphic.svg", f4)).isFalse();

        // 6. Reject SVG with DOCTYPE / XXE attempt
        String xxeSvg = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>&xxe;</text></svg>";
        MockMultipartFile f5 = new MockMultipartFile("file", "graphic.svg", "image/svg+xml", xxeSvg.getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("graphic.svg", f5)).isFalse();

        // 7. Reject plain text file named .svg
        MockMultipartFile textSvg = new MockMultipartFile("file", "graphic.svg", "image/svg+xml", "Just plain text".getBytes());
        assertThat(FileValidationUtils.isValidFileSignature("graphic.svg", textSvg)).isFalse();
    }
}
